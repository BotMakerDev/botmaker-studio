package com.botmaker.studio.assist;

import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.parser.BlockId;
import com.botmaker.studio.parser.CodeEditor;
import com.botmaker.studio.parser.ExpressionChoice;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.util.MethodSignature;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One assistant turn over one file: the tools a model calls, each run as a transaction against a private working
 * copy, and a single {@link #commit} that hands the result to the editor as one undo step.
 *
 * <p><b>The three checks, and where each lives.</b> A model can only name what the palette offers
 * ({@link #insert} takes a {@link PaletteEntry#id}, never Java); a slot takes a value the host's
 * {@code ValueGrammar} can read as the slot's type and then writes itself ({@link #setSlot}); and every edit
 * is compiled with bindings before it is kept, and dropped if it introduced an error ({@link #apply}). The
 * first two refuse what is not in the vocabulary; the third refuses what is in it but does not fit here —
 * deleting a variable something still reads, a call whose arguments do not type.
 *
 * <p><b>Only a new error refuses.</b> A file that already has a problem — a half-finished call the user left —
 * would otherwise refuse every edit including the one that fixes it. Errors are compared as a multiset of
 * problem id plus arguments, not by position, since an insertion moves every offset below it.
 *
 * <p><b>Why a working copy and not the live editor.</b> See {@link AssistWorkspace}. It also makes a turn
 * atomic: a model that gives up half-way leaves nothing behind, and a turn that lands is one ↶, not twelve.
 *
 * <p>Not thread-confined: nothing here reads {@code ProjectState}'s live instance, so a turn may run on the
 * thread that talks to the model. {@link #commit} returns the event and the caller publishes it on the FX
 * thread.
 */
public final class AssistTurn {

    private static final int TEXT_LIMIT = 160;

    private final AssistWorkspace workspace;
    private final String original;
    private String source;
    private int accepted;

    public AssistTurn(AssistWorkspace workspace, String source) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.original = Objects.requireNonNull(source, "source");
        this.source = source;
    }

    /** The working copy as it stands. */
    public String source() {
        return source;
    }

    /** How many edits this turn has kept. */
    public int acceptedEdits() {
        return accepted;
    }

    // ---- reading -----------------------------------------------------------------------------------------

    /**
     * What may be inserted: every statement block the insert menu offers, then every static call of every
     * facade the project's plugins put in a menu, curated exactly as the menu is.
     */
    public List<PaletteEntry> palette() {
        List<PaletteEntry> entries = new ArrayList<>();
        for (BlockType type : BlockCatalog.all()) {
            if (!type.isStatement()) continue;
            entries.add(new PaletteEntry(PaletteEntry.Kind.STATEMENT.idOf(type.id()), PaletteEntry.Kind.STATEMENT,
                    type.displayName(), type.category().getLabel(), List.of(), ""));
        }
        Stage stage = stage();
        for (FacadeEntry facade : facades()) {
            String owner = facade.simpleName();
            for (MethodSignature sig : offered(stage.analyzer, owner)) {
                entries.add(new PaletteEntry(PaletteEntry.Kind.CALL.idOf(callKey(owner, sig)), PaletteEntry.Kind.CALL,
                        owner + "." + sig.name(), facade.displayLabel(),
                        sig.paramTypes().stream().map(t -> t.simpleName()).toList(),
                        sig.returnType() == null ? "void" : sig.returnType().simpleName()));
            }
        }
        return entries;
    }

    /** The file's methods, bodies, statements and slots — the ids every edit tool takes. */
    public List<BlockView.Method> tree() {
        Stage stage = stage();
        List<BlockView.Method> methods = new ArrayList<>();
        if (stage.cu == null) return methods;
        stage.cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (method.getBody() != null && stage.registry.get(method.getBody()) instanceof BodyBlock body) {
                    String owner = method.getParent() instanceof AbstractTypeDeclaration type
                            ? type.getName().getIdentifier() : "";
                    methods.add(new BlockView.Method(method.getName().getIdentifier(), owner, view(body)));
                }
                return true;
            }
        });
        return methods;
    }

    /** The errors the working copy compiles with, one sentence each — what a model reads before it edits. */
    public List<String> errors() {
        return describe(errorsOf(source), source);
    }

    // ---- editing -----------------------------------------------------------------------------------------

    /** Inserts the palette entry {@code paletteId} into body {@code bodyId} at statement index {@code index}. */
    public Outcome insert(String bodyId, int index, String paletteId) {
        Stage stage = stage();
        Optional<BodyBlock> body = stage.find(bodyId, BodyBlock.class);
        if (body.isEmpty()) return Outcome.Refused.because("No body has the id " + bodyId + ". Read the tree again.");
        int at = Math.max(0, Math.min(index, body.get().getStatements().size()));

        String blockId = PaletteEntry.Kind.STATEMENT.restOf(paletteId);
        if (blockId != null) {
            Optional<BlockType> type = BlockCatalog.byId(blockId).filter(BlockType::isStatement);
            if (type.isEmpty()) return Outcome.Refused.because("The palette has no statement " + paletteId + ".");
            return apply(stage, "inserted " + type.get().displayName(),
                    editor -> editor.addStatement(body.get(), type.get(), at));
        }
        String callKey = PaletteEntry.Kind.CALL.restOf(paletteId);
        if (callKey != null) {
            Optional<ExpressionChoice.Method> call = call(stage.analyzer, callKey);
            if (call.isEmpty()) return Outcome.Refused.because("The palette has no call " + paletteId + ".");
            return apply(stage, "inserted " + call.get().scope() + "." + call.get().methodName(),
                    editor -> editor.addMethodCallStatement(body.get(), call.get(), at));
        }
        return Outcome.Refused.because(paletteId + " is not a palette id. Ids start with block: or call:.");
    }

    /**
     * Writes {@code value} into slot {@code slotId}. The value is read by the grammar as the slot's type and
     * written back by it, so what reaches the file is the host's spelling of a value, never the text given.
     */
    public Outcome setSlot(String slotId, String value) {
        Stage stage = stage();
        Optional<Expression> slot = stage.findNode(slotId, Expression.class);
        if (slot.isEmpty()) return Outcome.Refused.because("No slot has the id " + slotId + ". Read the tree again.");
        ITypeBinding expected = expectedType(slot.get());
        Optional<Type> type = valueType(expected);
        if (type.isEmpty()) {
            return Outcome.Refused.because("No loaded plugin declares " + nameOf(expected)
                    + ", so a value of it cannot be written here.");
        }
        Optional<Object> read = workspace.grammar().valueOf(type.get(), value == null ? "" : value);
        if (read.isEmpty()) {
            return Outcome.Refused.because("`" + value + "` is not a " + nameOf(expected)
                    + " value. Write a literal, or a constant or factory call the type declares.");
        }
        Optional<JavaValue> written = workspace.grammar().spell(type.get(), read.get());
        if (written.isEmpty()) return Outcome.Refused.because("That value cannot be written as " + nameOf(expected) + ".");
        return apply(stage, "set " + slotId + " to " + written.get().source(),
                editor -> editor.replaceWithValue(slot.get(), written.get()));
    }

    /** Deletes the statement {@code blockId}. */
    public Outcome delete(String blockId) {
        Stage stage = stage();
        Optional<Statement> statement = stage.findNode(blockId, Statement.class)
                .filter(s -> !(s instanceof Block));
        if (statement.isEmpty()) return Outcome.Refused.because("No statement has the id " + blockId + ".");
        return apply(stage, "deleted " + oneLine(statement.get(), source),
                editor -> editor.deleteStatement(statement.get()));
    }

    /**
     * The turn as one edit of the live file, or empty when nothing was kept — or when {@code liveSource} is
     * no longer the text the turn started from, because the user edited while the model was working and
     * publishing now would silently undo them.
     */
    public Optional<CoreApplicationEvents.CodeUpdatedEvent> commit(String liveSource, String label) {
        if (accepted == 0 || source.equals(original)) return Optional.empty();
        if (!original.equals(liveSource)) return Optional.empty();
        return Optional.of(new CoreApplicationEvents.CodeUpdatedEvent(source, original, false,
                label == null || label.isBlank() ? "the assistant's change" : label, List.of()));
    }

    // ---- the transaction ---------------------------------------------------------------------------------

    /**
     * Runs one {@link CodeEditor} call over {@code stage} and keeps its output only when it compiles no worse
     * than the working copy did. The editor's own guards still apply — the lock, and its refusal of source
     * that does not parse — and their status lines become the refusal's reasons.
     */
    private Outcome apply(Stage stage, String summary, Consumer<CodeEditor> edit) {
        edit.accept(stage.editor);
        if (stage.published == null) {
            return new Outcome.Refused(stage.status.isEmpty() ? List.of("That edit changed nothing.") : stage.status);
        }
        List<Problem> introduced = new ArrayList<>(errorsOf(stage.published));
        for (Problem existing : errorsOf(source)) introduced.remove(existing);
        if (!introduced.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            reasons.add("That edit would not compile, so nothing was changed:");
            reasons.addAll(describe(introduced, stage.published));
            return new Outcome.Refused(reasons);
        }
        source = stage.published;
        accepted++;
        return new Outcome.Accepted(summary);
    }

    /** One compile error, identified the way two versions of a file can agree on: id plus arguments. */
    private record Problem(int id, List<String> arguments, String message, int offset) {
        @Override public boolean equals(Object o) {
            return o instanceof Problem p && p.id == id && p.arguments.equals(arguments);
        }
        @Override public int hashCode() {
            return Objects.hash(id, arguments);
        }
    }

    private List<Problem> errorsOf(String code) {
        CompilationUnit cu = new BotParser(workspace.classpath(), workspace.sourceRoot()).parse(workspace.file(), code);
        List<Problem> problems = new ArrayList<>();
        for (IProblem problem : cu.getProblems()) {
            if (!problem.isError()) continue;
            problems.add(new Problem(problem.getID(), List.of(problem.getArguments()), problem.getMessage(),
                    problem.getSourceStart()));
        }
        return problems;
    }

    private static List<String> describe(List<Problem> problems, String code) {
        return problems.stream().map(p -> "line " + lineOf(code, p.offset) + ": " + p.message).toList();
    }

    private static int lineOf(String code, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, code.length()); i++) {
            if (code.charAt(i) == '\n') line++;
        }
        return line;
    }

    // ---- one step's private editor -----------------------------------------------------------------------

    /**
     * The working copy parsed to blocks, and a {@link CodeEditor} over it whose bus goes nowhere: the source
     * it would publish is captured instead, for {@link #apply} to judge.
     */
    private final class Stage {
        final ProjectState state;
        final CompilationUnit cu;
        final Map<ASTNode, CodeBlock> registry = new HashMap<>();
        final ProjectAnalyzer analyzer;
        final CodeEditor editor;
        final List<String> status = new ArrayList<>();
        String published;

        Stage() {
            state = workspace.stage(source);
            EventBus bus = new EventBus(false);
            bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> published = e.newCode());
            bus.subscribe(CoreApplicationEvents.StatusMessageEvent.class, e -> status.add(e.message()));
            BlockConverter.ConvertResult result = new BlockConverter(workspace.config(), state)
                    .convert(source, registry, new BlockDragAndDropManager(bus), false, false);
            cu = result.cu();
            state.setCompilationUnit(cu);
            state.setNodeToBlockMap(registry);
            analyzer = workspace.analyzer(state);
            editor = workspace.surface() != null
                    ? new CodeEditor(workspace.config(), state, bus, analyzer, workspace.surface())
                    : workspace.journal() != null
                    ? new CodeEditor(workspace.config(), state, bus, analyzer, workspace.journal())
                    : new CodeEditor(workspace.config(), state, bus, analyzer);
        }

        <T extends CodeBlock> Optional<T> find(String id, Class<T> kind) {
            for (CodeBlock block : registry.values()) {
                if (kind.isInstance(block) && BlockId.of(block.getAstNode()).equals(id)) return Optional.of(kind.cast(block));
            }
            return Optional.empty();
        }

        <T extends ASTNode> Optional<T> findNode(String id, Class<T> kind) {
            if (cu == null || id == null) return Optional.empty();
            List<T> found = new ArrayList<>(1);
            cu.accept(new ASTVisitor() {
                @Override
                public void preVisit(ASTNode node) {
                    if (found.isEmpty() && kind.isInstance(node) && BlockId.of(node).equals(id)) found.add(kind.cast(node));
                }
            });
            return found.stream().findFirst();
        }
    }

    private Stage stage() {
        return new Stage();
    }

    // ---- the palette's calls -----------------------------------------------------------------------------

    private List<FacadeEntry> facades() {
        return workspace.surface() == null ? PluginHost.menuFacades() : workspace.surface().menuFacades();
    }

    /** The static overloads of {@code owner} the menu offers, in the menu's order. */
    private List<MethodSignature> offered(ProjectAnalyzer analyzer, String owner) {
        List<MethodSignature> out = new ArrayList<>();
        Map<String, List<MethodSignature>> byName = new java.util.TreeMap<>();
        for (MethodSignature sig : analyzer.getMethods(owner, true)) {
            byName.computeIfAbsent(sig.name(), k -> new ArrayList<>()).add(sig);
        }
        List<String> names = new ArrayList<>(byName.keySet());
        if (workspace.surface() != null) names = workspace.surface().retainOfferedNames(owner, names, null);
        for (String name : names) {
            List<MethodSignature> sigs = byName.get(name);
            if (workspace.surface() != null) sigs = workspace.surface().retainOffered(owner, name, sigs, null);
            out.addAll(sigs);
        }
        return out;
    }

    private static String callKey(String owner, MethodSignature sig) {
        return owner + "." + sig.name() + "(" + sig.signatureKey() + ")";
    }

    private Optional<ExpressionChoice.Method> call(ProjectAnalyzer analyzer, String key) {
        for (FacadeEntry facade : facades()) {
            String owner = facade.simpleName();
            for (MethodSignature sig : offered(analyzer, owner)) {
                if (callKey(owner, sig).equals(key)) {
                    return Optional.of(new ExpressionChoice.Method(owner, sig.name(), sig.paramTypes(), true));
                }
            }
        }
        return Optional.empty();
    }

    // ---- views -------------------------------------------------------------------------------------------

    private BlockView.Body view(BodyBlock body) {
        List<BlockView.Statement> statements = new ArrayList<>();
        for (StatementBlock statement : body.getStatements()) {
            ASTNode node = statement.getAstNode();
            List<BlockView.Body> nested = new ArrayList<>();
            collectBodies(statement, nested);
            statements.add(new BlockView.Statement(BlockId.of(node), kindOf(statement), oneLine(node, source),
                    slots(node), nested));
        }
        return new BlockView.Body(BlockId.of(body.getAstNode()), statements);
    }

    /** The bodies directly inside {@code block} — not their statements' bodies, which those statements list. */
    private void collectBodies(CodeBlock block, List<BlockView.Body> out) {
        if (!(block instanceof BlockWithChildren parent)) return;
        for (CodeBlock child : parent.getChildren()) {
            if (child instanceof BodyBlock body) out.add(view(body));
            else collectBodies(child, out);
        }
    }

    private static String kindOf(StatementBlock block) {
        String name = block.getClass().getSimpleName();
        return name.endsWith("Block") ? name.substring(0, name.length() - "Block".length()) : name;
    }

    /** The positions on {@code statement} a value may be written into. */
    private List<BlockView.Slot> slots(ASTNode statement) {
        List<Expression> positions = new ArrayList<>();
        switch (statement) {
            case ExpressionStatement s -> arguments(s.getExpression(), positions);
            case VariableDeclarationStatement s -> {
                for (Object fragment : s.fragments()) {
                    Expression initializer = ((VariableDeclarationFragment) fragment).getInitializer();
                    if (initializer == null) continue;
                    positions.add(initializer);
                    arguments(initializer, positions);
                }
            }
            case IfStatement s -> positions.add(s.getExpression());
            case WhileStatement s -> positions.add(s.getExpression());
            case DoStatement s -> positions.add(s.getExpression());
            case ReturnStatement s -> { if (s.getExpression() != null) positions.add(s.getExpression()); }
            default -> { }
        }
        List<BlockView.Slot> slots = new ArrayList<>();
        for (Expression position : positions) {
            ITypeBinding expected = expectedType(position);
            slots.add(new BlockView.Slot(BlockId.of(position), nameOf(expected), oneLine(position, source),
                    valueType(expected).isPresent()));
        }
        return slots;
    }

    private static void arguments(Expression expression, List<Expression> out) {
        if (expression instanceof MethodInvocation call) {
            for (Object argument : call.arguments()) out.add((Expression) argument);
        } else if (expression instanceof ClassInstanceCreation creation) {
            for (Object argument : creation.arguments()) out.add((Expression) argument);
        }
    }

    // ---- types -------------------------------------------------------------------------------------------

    /**
     * The type {@code expression}'s position expects: the declared parameter of the call it is an argument
     * of (the varargs element past the last one), the declared type of the variable it initialises, and the
     * expression's own type anywhere else.
     */
    static ITypeBinding expectedType(Expression expression) {
        ASTNode parent = expression.getParent();
        IMethodBinding call = switch (parent) {
            case MethodInvocation m when m.arguments().contains(expression) -> m.resolveMethodBinding();
            case ClassInstanceCreation c when c.arguments().contains(expression) -> c.resolveConstructorBinding();
            default -> null;
        };
        if (call != null) {
            List<?> arguments = parent instanceof MethodInvocation m ? m.arguments() : ((ClassInstanceCreation) parent).arguments();
            int index = arguments.indexOf(expression);
            ITypeBinding[] params = call.getParameterTypes();
            if (index < params.length) {
                ITypeBinding param = params[index];
                return call.isVarargs() && index == params.length - 1 && param.isArray()
                        && !(expression.resolveTypeBinding() != null && expression.resolveTypeBinding().isArray())
                        ? param.getElementType() : param;
            }
            if (call.isVarargs() && params.length > 0) return params[params.length - 1].getElementType();
        }
        if (parent instanceof VariableDeclarationFragment fragment
                && fragment.getParent() instanceof VariableDeclarationStatement declaration) {
            ITypeBinding declared = declaration.getType().resolveBinding();
            if (declared != null) return declared;
        }
        return expression.resolveTypeBinding();
    }

    /** The grammar's type for {@code binding}, by its exact erased name, or empty when no plugin declares it. */
    private Optional<Type> valueType(ITypeBinding binding) {
        if (binding == null || binding.isArray() || binding.isNullType()) return Optional.empty();
        String name = binding.isPrimitive() ? binding.getName() : binding.getErasure().getQualifiedName();
        return workspace.grammar().named(name).<Type>map(cls -> cls);
    }

    private static String nameOf(ITypeBinding binding) {
        if (binding == null) return "an unresolved type";
        return binding.isPrimitive() ? binding.getName() : binding.getErasure().getQualifiedName();
    }

    private static String oneLine(ASTNode node, String code) {
        int start = node.getStartPosition();
        int end = start + node.getLength();
        String text = start >= 0 && end <= code.length() ? code.substring(start, end) : node.toString();
        text = text.replaceAll("\\s+", " ").strip();
        return text.length() <= TEXT_LIMIT ? text : text.substring(0, TEXT_LIMIT - 1) + "…";
    }
}
