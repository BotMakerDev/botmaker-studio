package com.botmaker.studio.assist;

import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.ExpressionCatalog;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.parser.helpers.MethodSignatures;
import com.botmaker.studio.parser.refactor.MethodReferences;
import com.botmaker.studio.parser.refactor.SignatureMigration;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.parser.BlockId;
import com.botmaker.studio.parser.CodeEditor;
import com.botmaker.studio.parser.ExpressionChoice;
import com.botmaker.studio.parser.StatementPlacement;
import com.botmaker.studio.parser.guard.CompileGuard;
import com.botmaker.studio.plugin.PaletteCuration;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.managed.ManagedConstants;
import com.botmaker.studio.project.managed.ManagedIds;
import com.botmaker.studio.project.params.BotRecords;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.render.components.types.TypeCatalog;
import com.botmaker.studio.util.MethodSignature;
import org.eclipse.jdt.core.dom.*;

import javax.lang.model.SourceVersion;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    /** The file the turn edits. */
    public java.nio.file.Path file() {
        return workspace.file();
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
        return CompileGuard.describe(guard().errorsOf(source), source);
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
            Optional<PaletteCall> found = paletteCall(stage.analyzer, callKey);
            if (found.isEmpty()) return Outcome.Refused.because("The palette has no call " + paletteId + ".");
            ExpressionChoice.Method call = found.get().choice();
            if (found.get().facade().entryPoint(call.methodName())) {
                if (!StatementPlacement.inMain(body.get().getAstNode())) {
                    return Outcome.Refused.because(call.scope() + "." + call.methodName()
                            + " starts the bot: it goes in main only.");
                }
                // An entry point is written as the statement menu writes it: Bot.runFlow(Sdk.class), each
                // holder of the bot's values handed over, never a (Class) null no slot can take a class into.
                BlockType entry = new BlockType.LibraryCall("SDK_" + call.scope() + "_" + call.methodName(),
                        call.methodName(), BlockCategory.INPUT, found.get().facade().type(), call.methodName(),
                        List.of());
                return apply(stage, "inserted " + call.scope() + "." + call.methodName(),
                        editor -> editor.addStatement(body.get(), entry, at));
            }
            return apply(stage, "inserted " + call.scope() + "." + call.methodName(),
                    editor -> editor.addMethodCallStatement(body.get(), call, at));
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
        String given = value == null ? "" : value.strip();
        String callKey = PaletteEntry.Kind.CALL.restOf(given);
        if ((callKey != null || given.startsWith(OPERATOR) || given.startsWith(VARIABLE)) && inManagedValue(slot.get())) {
            return Outcome.Refused.because("Slot " + slotId + " is a plugin's managed value: it takes a value, "
                    + "never a call, an operator or a variable.");
        }
        if (callKey != null) return setCall(stage, slotId, slot.get(), expected, callKey);
        if (given.startsWith(VARIABLE)) return setVariable(stage, slotId, slot.get(), given.substring(VARIABLE.length()));
        if (given.startsWith(OPERATOR)) {
            return setOperator(stage, slotId, slot.get(), expected, given.substring(OPERATOR.length()));
        }
        Optional<Type> type = valueType(expected);
        if (type.isEmpty() && expected != null && expected.isEnum()) {
            Optional<JavaValue> constant = enumConstant(expected, given);
            if (constant.isEmpty()) {
                return Outcome.Refused.because("`" + given + "` is none of " + nameOf(expected) + "'s constants: "
                        + enumConstants(expected) + ".");
            }
            return apply(stage, "set " + slotId + " to " + constant.get().source(),
                    editor -> editor.replaceWithValue(slot.get(), constant.get()));
        }
        if (type.isEmpty()) {
            return Outcome.Refused.because("No loaded plugin declares " + nameOf(expected)
                    + ", so a value of it cannot be written here.");
        }
        String text = given;
        List<ManagedConstants.Constant> named = constantsNamed(text);
        if (named.size() > 1) {
            return Outcome.Refused.because("`" + text + "` names " + named.size() + " of the bot's constants: "
                    + named.stream().map(c -> c.owner() + "." + c.field()).toList() + ". Give its owner's full name.");
        }
        if (named.size() == 1) {
            ManagedConstants.Constant constant = named.getFirst();
            // An enum's constant has no initialiser: what it holds is spelled through the grammar instead.
            String held = constant.initializer() != null ? constant.initializer()
                    : workspace.grammar().initializerOfAny(constant.member()).map(JavaValue::source).orElse(null);
            // Read with the bot's own names: an initialiser may name another constant (Way.of(Screens.HOME, …)).
            if (held == null || SourceNode.parse(held)
                    .flatMap(node -> workspace.grammar().read(type.get(), node, workspace.lookup())).isEmpty()) {
                return Outcome.Refused.because("`" + text + "` is not a " + nameOf(expected) + ".");
            }
            JavaValue reference = reference(constant);
            return apply(stage, "set " + slotId + " to " + reference.source(),
                    editor -> editor.replaceWithValue(slot.get(), reference));
        }
        // The bot's constants are read wherever the value holds one: ImageTemplateGroup.of(Pictures.A, Pictures.B).
        Optional<Object> read = SourceNode.parse(text)
                .flatMap(node -> workspace.grammar().read(type.get(), node, workspace.lookup()));
        if (read.isEmpty()) {
            // The type's own spelling, so a near miss (Duration.ofSeconds for Duration.ofMillis) is fixed in one go.
            String like = workspace.grammar().freshSpelling(type.get())
                    .map(fresh -> " It is written like `" + fresh.source() + "`.").orElse("");
            return Outcome.Refused.because("`" + value + "` is not a " + nameOf(expected)
                    + " value. Write a literal, one of the bot's constants (Pictures.ORE), or a constant or "
                    + "factory call the type declares." + like);
        }
        // A value one of the bot's constants holds is written as that constant, as the slot editors write it,
        // and so is each part of it.
        Optional<JavaValue> written = workspace.lookup().spell(read.get())
                .or(() -> workspace.grammar().write(type.get(), read.get(), workspace.lookup()));
        if (written.isEmpty()) return Outcome.Refused.because("That value cannot be written as " + nameOf(expected) + ".");
        return apply(stage, "set " + slotId + " to " + written.get().source(),
                editor -> editor.replaceWithValue(slot.get(), written.get()));
    }

    /**
     * Writes the palette's call {@code key} into a slot its result fits, as the slot's menu does: its arguments
     * are the call's defaults, each a slot of its own to set next.
     */
    private Outcome setCall(Stage stage, String slotId, Expression slot, ITypeBinding expected, String key) {
        Optional<PaletteCall> call = paletteCall(stage.analyzer, key);
        if (call.isEmpty()) return Outcome.Refused.because("The palette has no call " + PaletteEntry.Kind.CALL.idOf(key) + ".");
        ResolvedType returns = call.get().signature().returnType();
        if (expected == null) return Outcome.Refused.because("Slot " + slotId + " has no type Studio can read.");
        if (returns == null || returns.isVoid() || !returns.isAssignmentCompatible(ResolvedType.of(expected))) {
            return Outcome.Refused.because(key + " gives " + (returns == null ? "nothing" : returns.simpleName())
                    + ", not a " + nameOf(expected) + ".");
        }
        ExpressionChoice.Method choice = call.get().choice();
        return apply(stage, "set " + slotId + " to " + choice.scope() + "." + choice.methodName()
                + "(…); read the tree for its arguments' slots", editor -> editor.replaceWithMethodCall(slot, choice));
    }

    /**
     * Writes an operator ({@code op:NOT}, {@code op:AND}, {@code op:LESS}…) into a slot its result fits, as the
     * slot's menu does: its operands are placeholders, each a slot of its own to set next.
     */
    private Outcome setOperator(Stage stage, String slotId, Expression slot, ITypeBinding expected, String id) {
        Optional<ExpressionType> operator = ExpressionCatalog.byId(id).filter(AssistTurn::isOperator);
        if (operator.isEmpty()) {
            return Outcome.Refused.because("`" + OPERATOR + id + "` is not an operator. Operators: "
                    + String.join(", ", operatorIds()) + ".");
        }
        if (expected == null) return Outcome.Refused.because("Slot " + slotId + " has no type Studio can read.");
        if (!ExpressionCatalog.isCompatibleWith(operator.get(), ResolvedType.of(expected), stage.state)) {
            return Outcome.Refused.because(operator.get().displayName() + " does not give a " + nameOf(expected) + ".");
        }
        return apply(stage, "set " + slotId + " to " + operator.get().displayName()
                + "; read the tree for its operands' slots", editor -> editor.replaceExpression(slot, operator.get()));
    }

    /**
     * Whether {@code node} is inside a member a plugin's marker annotates: a managed value, whose expression only
     * the grammar writes and the host reads back (umbrella CLAUDE.md, a plugin's values).
     */
    private static boolean inManagedValue(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (!(at instanceof BodyDeclaration declaration)) continue;
            for (Object modifier : declaration.modifiers()) {
                if (modifier instanceof Annotation annotation && ManagedIds.isMarker(annotation.resolveTypeBinding())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Writes the variable {@code name} into a slot: a local, a parameter, a lambda's parameter or a field in
     * scope there. Only a name is written; the compile check refuses one out of scope or of another type.
     */
    private Outcome setVariable(Stage stage, String slotId, Expression slot, String name) {
        String wanted = name.strip();
        if (!SourceVersion.isIdentifier(wanted) || SourceVersion.isKeyword(wanted)) {
            return Outcome.Refused.because("`" + wanted + "` is not a variable's name.");
        }
        return apply(stage, "set " + slotId + " to " + wanted, editor -> editor.replaceWithVariable(slot, wanted));
    }

    /**
     * Retypes and renames the variable {@code blockId} declares, as the Edit Variable dialog does: {@code type}
     * one the dialog's chooser offers (its initialiser reset to the type's fresh value), {@code name} carried to
     * its uses. A blank one is kept.
     */
    public Outcome editVariable(String blockId, String name, String type) {
        Stage stage = stage();
        Optional<VariableDeclarationStatement> declared = stage.statement(blockId)
                .filter(VariableDeclarationStatement.class::isInstance).map(VariableDeclarationStatement.class::cast);
        if (declared.isEmpty() || declared.get().fragments().size() != 1) {
            return Outcome.Refused.because("Statement " + blockId + " declares no single variable.");
        }
        String newName = name == null ? "" : name.strip();
        String newType = type == null ? "" : type.strip();
        if (newName.isEmpty() && newType.isEmpty()) return Outcome.Refused.because("Give a name, a type or both.");
        if (!newName.isEmpty() && (!SourceVersion.isIdentifier(newName) || SourceVersion.isKeyword(newName))) {
            return Outcome.Refused.because("`" + newName + "` is not a variable's name.");
        }
        List<String> done = new ArrayList<>();
        // Both or neither: a rename refused after the retype must not leave the retype for the turn to commit.
        String before = source;
        int acceptedBefore = accepted;
        if (!newType.isEmpty()) {
            List<TypeCatalog.Entry> offered = TypeCatalog.current(TypeCatalog.Purpose.DECLARATION,
                    BotRecords.scan(workspace.config(), stage.state, workspace.grammar())).groups().stream()
                    .flatMap(g -> g.entries().stream()).toList();
            Optional<Type> chosen = offered.stream()
                    .filter(e -> e.label().equals(newType) || simpleName(e.type()).equals(newType))
                    .map(TypeCatalog.Entry::type).findFirst();
            if (chosen.isEmpty()) {
                return Outcome.Refused.because("A variable cannot be declared as " + newType + ". The types are "
                        + offered.stream().map(e -> simpleName(e.type())).distinct().toList() + ".");
            }
            Outcome typed = apply(stage, "retyped to " + newType,
                    editor -> editor.replaceVariableType(declared.get(), chosen.get()));
            if (!(typed instanceof Outcome.Accepted accepted)) return typed;
            done.add(accepted.summary());
        }
        if (!newName.isEmpty()) {
            Stage again = newType.isEmpty() ? stage : stage();
            Optional<VariableDeclarationStatement> fresh = again.statement(blockId)
                    .filter(VariableDeclarationStatement.class::isInstance).map(VariableDeclarationStatement.class::cast);
            if (fresh.isEmpty()) {
                source = before;
                accepted = acceptedBefore;
                return Outcome.Refused.because("Statement " + blockId + " moved. Read the tree again.");
            }
            SimpleName declaredName = ((VariableDeclarationFragment) fresh.get().fragments().getFirst()).getName();
            Outcome renamed = apply(again, "renamed to " + newName, editor -> editor.renameLocal(declaredName, newName));
            if (!(renamed instanceof Outcome.Accepted kept)) {
                source = before;
                accepted = acceptedBefore;
                return renamed;
            }
            done.add(kept.summary());
        }
        return new Outcome.Accepted(String.join("; ", done));
    }

    private static String simpleName(Type type) {
        String name = type.getTypeName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** Adds an argument to the varargs call {@code callId} — a call statement's id, or a slot holding a call. */
    public Outcome addArgument(String callId) {
        Stage stage = stage();
        Optional<MethodInvocation> call = stage.findNode(callId, MethodInvocation.class);
        if (call.isEmpty()) return Outcome.Refused.because("No call has the id " + callId + ". Read the tree again.");
        IMethodBinding binding = call.get().resolveMethodBinding();
        if (binding == null || !binding.isVarargs()) {
            return Outcome.Refused.because(call.get().getName().getIdentifier() + " takes no more arguments.");
        }
        ITypeBinding[] params = binding.getParameterTypes();
        ResolvedType element = ResolvedType.of(params[params.length - 1].getElementType());
        return apply(stage, "added an argument to " + call.get().getName().getIdentifier()
                + "; read the tree for its slot", editor -> editor.addVarargsArgument(call.get(), element));
    }

    /** What edit_code's set takes before a variable's name: {@code var:value}. */
    static final String VARIABLE = "var:";

    /** What edit_code's set takes before an operator's id: {@code op:NOT}. */
    static final String OPERATOR = "op:";

    /** The operators a slot takes, as edit_code's set spells them, in the menu's order. */
    static List<String> operatorIds() {
        return ExpressionCatalog.all().stream().filter(AssistTurn::isOperator).map(t -> OPERATOR + t.id()).toList();
    }

    /** An operator, or a lambda — the code a call such as {@code ImageFinder.whileFindAny} runs, its body a body. */
    private static boolean isOperator(ExpressionType type) {
        return type instanceof ExpressionType.InfixOp || type instanceof ExpressionType.PrefixOp
                || type == ExpressionCatalog.LAMBDA || type == ExpressionCatalog.NEGATE || type == ExpressionCatalog.CHOOSE;
    }

    /** The bot's managed constants {@code text} names as {@code Owner.FIELD}, by simple or full owner. */
    private List<ManagedConstants.Constant> constantsNamed(String text) {
        int dot = text.lastIndexOf('.');
        if (dot <= 0 || dot == text.length() - 1) return List.of();
        String owner = text.substring(0, dot);
        String field = text.substring(dot + 1);
        return workspace.constants().stream()
                .filter(c -> field.equals(c.field()) && (c.owner().equals(owner) || c.simpleOwner().equals(owner)))
                .toList();
    }

    /** {@code Owner.FIELD} — {@code Activities.Collect.WON} for a nested enum's — importing its top-level class. */
    /**
     * One of the enum {@code type}'s constants, named bare ({@code NEXT}) or after any part of the type's name
     * ({@code Research.NEXT}, {@code Activities.Research.NEXT}), written as a file names it with the top-level
     * type imported: an activity's outcome, an enum no plugin declares (2026-10-13).
     */
    static Optional<JavaValue> enumConstant(ITypeBinding type, String given) {
        int dot = given.lastIndexOf('.');
        String name = given.substring(dot + 1);
        String qualifier = dot < 0 ? "" : given.substring(0, dot);
        List<String> chain = new ArrayList<>();
        ITypeBinding top = type.getErasure();
        for (ITypeBinding at = top; at != null; at = at.getDeclaringClass()) {
            chain.addFirst(at.getName());
            top = at;
        }
        String written = String.join(".", chain);
        if (!qualifier.isEmpty() && !(written.equals(qualifier) || written.endsWith("." + qualifier)
                || type.getErasure().getQualifiedName().equals(qualifier))) {
            return Optional.empty();
        }
        if (!enumConstants(type).contains(name)) return Optional.empty();
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        QualifiedName constant = ast.newQualifiedName(ast.newName(written), ast.newSimpleName(name));
        // A local enum has no name to import; it is in scope where it is used.
        String imported = top.getQualifiedName();
        return Optional.of(JavaValue.built(constant, imported.isEmpty() || top.isLocal() ? List.of() : List.of(imported)));
    }

    private static List<String> enumConstants(ITypeBinding type) {
        return Arrays.stream(type.getDeclaredFields()).filter(IVariableBinding::isEnumConstant)
                .map(IVariableBinding::getName).toList();
    }

    private static JavaValue reference(ManagedConstants.Constant constant) {
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        QualifiedName name = ast.newQualifiedName(ast.newName(constant.simpleOwner()),
                ast.newSimpleName(constant.field()));
        return JavaValue.built(name, List.of(constant.topLevel()));
    }

    /** Deletes the statement {@code blockId}. */
    public Outcome delete(String blockId) {
        Stage stage = stage();
        Optional<Statement> statement = stage.statement(blockId);
        if (statement.isEmpty()) return Outcome.Refused.because("No statement has the id " + blockId + ".");
        return apply(stage, "deleted " + oneLine(statement.get(), source),
                editor -> editor.deleteStatement(statement.get()));
    }

    /**
     * Adds a method to the class {@code className} (the file's first class when blank), after its other members,
     * as the Add Function dialog does: {@code returns} and each parameter's type a type the dialog offers.
     */
    public Outcome addMethod(String className, String name, String returns, List<SignatureNames.Param> params) {
        Stage stage = stage();
        Optional<TypeDeclaration> type = typeNamed(stage, className);
        if (type.isEmpty()) {
            return Outcome.Refused.because(className == null || className.isBlank() ? "This file declares no class."
                    : "This file declares no class " + className + ".");
        }
        FunctionDraft draft;
        Supplier<List<String>> botTypes = () -> SignatureNames.botTypes(workspace.config(), stage.state);
        try {
            draft = new FunctionDraft(name == null ? "" : name.strip(), SignatureNames.type(returns, true, botTypes),
                    SignatureNames.parameters(params == null ? List.of() : params, List.of(), botTypes));
        } catch (IllegalArgumentException e) {
            return Outcome.Refused.because(e.getMessage());
        }
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (MethodDeclaration method : type.get().getMethods()) {
            MethodSignatures.draftOf(method).ifPresent(d -> taken.add(d.signatureKey()));
        }
        Optional<String> problem = draft.problem(taken);
        if (problem.isPresent()) return Outcome.Refused.because(problem.get());
        int end = type.get().bodyDeclarations().size();
        return apply(stage, "added " + draft.name() + "()",
                editor -> editor.addFunctionToClass(type.get(), draft, end));
    }

    /** Moves the statement {@code blockId} into body {@code bodyId}, before the statement now at {@code index}. */
    public Outcome moveBlock(String blockId, String bodyId, int index) {
        Stage stage = stage();
        Optional<Statement> statement = stage.statement(blockId);
        if (statement.isEmpty()) return Outcome.Refused.because("No statement has the id " + blockId + ".");
        Optional<BodyBlock> target = stage.find(bodyId, BodyBlock.class);
        if (target.isEmpty()) return Outcome.Refused.because("No body has the id " + bodyId + ". Read the tree again.");
        // Into itself would remove it and insert into what was removed: the statement would vanish, and a
        // deletion compiles, so the compile check would not catch it.
        for (ASTNode at = target.get().getAstNode(); at != null; at = at.getParent()) {
            if (at == statement.get()) {
                return Outcome.Refused.because("A statement cannot move into a body inside itself.");
            }
        }
        StatementBlock moved = null;
        BodyBlock from = null;
        for (CodeBlock block : stage.registry.values()) {
            if (!(block instanceof BodyBlock body)) continue;
            for (StatementBlock each : body.getStatements()) {
                if (each.enclosingStatement() == statement.get()) {
                    moved = each;
                    from = body;
                }
            }
        }
        if (moved == null) return Outcome.Refused.because("Statement " + blockId + " is not in a body it can leave.");
        StatementBlock block = moved;
        BodyBlock source = from;
        int at = Math.max(0, Math.min(index, target.get().getStatements().size()));
        return apply(stage, "moved " + oneLine(statement.get(), this.source),
                editor -> editor.moveStatement(block, source, target.get(), at));
    }

    /**
     * Changes the method {@code method} ({@code name} or {@code Owner.name}) to the given name, return type and
     * parameters; a null one is kept. Its calls in this file follow; a parameter keeps its place in them by name.
     * Calls in other files are the caller's to check ({@code LiveBot.breaksElsewhere}).
     */
    public Outcome editSignature(String method, String name, String returns, List<SignatureNames.Param> params) {
        Stage stage = stage();
        List<MethodDeclaration> found = methodsNamed(stage, method);
        if (found.isEmpty()) return Outcome.Refused.because("This file has no method " + method + ".");
        if (found.size() > 1) {
            return Outcome.Refused.because(method + " has " + found.size() + " overloads here; Studio cannot tell "
                    + "which. Rename one first.");
        }
        MethodDeclaration declaration = found.getFirst();
        Optional<FunctionDraft> current = MethodSignatures.draftOf(declaration);
        if (current.isEmpty()) return Outcome.Refused.because(method + "'s signature is not one Studio can edit.");
        List<String> names = current.get().parameters().stream().map(FunctionDraft.Parameter::name).toList();
        FunctionDraft draft;
        Supplier<List<String>> botTypes = () -> SignatureNames.botTypes(workspace.config(), stage.state);
        try {
            draft = new FunctionDraft(name == null || name.isBlank() ? current.get().name() : name.strip(),
                    returns == null || returns.isBlank() ? current.get().returnType()
                            : SignatureNames.type(returns, true, botTypes),
                    params == null ? current.get().parameters() : SignatureNames.parameters(params, names, botTypes));
        } catch (IllegalArgumentException e) {
            return Outcome.Refused.because(e.getMessage());
        }
        java.util.Set<String> taken = new java.util.HashSet<>();
        if (declaration.getParent() instanceof TypeDeclaration owner) {
            for (MethodDeclaration other : owner.getMethods()) {
                if (other != declaration) MethodSignatures.draftOf(other).ifPresent(d -> taken.add(d.signatureKey()));
            }
        }
        Optional<String> problem = draft.problem(taken);
        if (problem.isPresent()) return Outcome.Refused.because(problem.get());
        // The calls as the signature dialog finds them — over this stage, which holds this file alone, so the
        // plan carries this file's calls and no other; the others are the caller's whole-bot check.
        MethodReferences.Result references = MethodReferences.find(stage.state, declaration);
        if (references.isRefusal()) return Outcome.Refused.because(references.refusal());
        if (references.calls().stream().anyMatch(MethodReferences.CallSite::isReference)
                && !SignatureMigration.sameShape(current.get(), draft)) {
            return Outcome.Refused.because(method + " is passed by reference, which has no arguments to change with "
                    + "it. Renaming it is fine; to change its inputs, change the reference first.");
        }
        SignatureMigration.Plan plan = SignatureMigration.of(current.get(), draft, declaration, references.calls());
        return apply(stage, "changed " + current.get().name() + "() to " + draft.name() + "("
                        + String.join(", ", draft.parameters().stream()
                        .map(p -> p.type().sourceName() + " " + p.name()).toList()) + ")",
                editor -> editor.applyFunctionSignature(declaration, draft, plan));
    }

    private static Optional<TypeDeclaration> typeNamed(Stage stage, String className) {
        if (stage.cu == null) return Optional.empty();
        String wanted = className == null ? "" : className.strip();
        List<TypeDeclaration> types = new ArrayList<>();
        stage.cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration type) {
                if (wanted.isEmpty() ? types.isEmpty() : type.getName().getIdentifier().equals(wanted)) types.add(type);
                return true;
            }
        });
        return types.stream().findFirst();
    }

    private static List<MethodDeclaration> methodsNamed(Stage stage, String spelled) {
        String asked = spelled == null ? "" : spelled.strip().replaceFirst("\\(\\)$", "");
        int dot = asked.lastIndexOf('.');
        String owner = dot < 0 ? "" : asked.substring(0, dot);
        String name = asked.substring(dot + 1);
        List<MethodDeclaration> out = new ArrayList<>();
        if (stage.cu == null || name.isEmpty()) return out;
        stage.cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (method.getName().getIdentifier().equals(name) && !method.isConstructor()
                        && (owner.isEmpty() || method.getParent() instanceof AbstractTypeDeclaration t
                        && t.getName().getIdentifier().equals(owner))) {
                    out.add(method);
                }
                return true;
            }
        });
        return out;
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
        List<CompileGuard.Problem> introduced = guard().introduced(source, stage.published);
        if (!introduced.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            reasons.add("That edit would not compile, so nothing was changed:");
            reasons.addAll(CompileGuard.describe(introduced, stage.published));
            return new Outcome.Refused(reasons);
        }
        source = stage.published;
        accepted++;
        return new Outcome.Accepted(summary);
    }

    private CompileGuard guard() {
        return new CompileGuard(new BotParser(workspace.classpath(), workspace.sourceRoot()), workspace.file());
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
            editor = workspace.journal() != null
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

        /**
         * The statement {@code id} names: by its own id, or — as read_tree names a call statement, by its call —
         * by the id of an expression statement's expression.
         */
        Optional<Statement> statement(String id) {
            return findNode(id, Statement.class).filter(s -> !(s instanceof Block))
                    .or(() -> findNode(id, Expression.class).map(ASTNode::getParent)
                            .filter(ExpressionStatement.class::isInstance).map(Statement.class::cast));
        }
    }

    private Stage stage() {
        return new Stage();
    }

    // ---- the palette's calls -----------------------------------------------------------------------------

    private List<FacadeEntry> facades() {
        return PluginHost.menuFacades();
    }

    /** The static overloads of {@code owner} the menu offers, in the menu's order. */
    private List<MethodSignature> offered(ProjectAnalyzer analyzer, String owner) {
        List<MethodSignature> out = new ArrayList<>();
        Map<String, List<MethodSignature>> byName = new java.util.TreeMap<>();
        for (MethodSignature sig : analyzer.getMethods(owner, true)) {
            byName.computeIfAbsent(sig.name(), k -> new ArrayList<>()).add(sig);
        }
        PaletteCuration curation = PaletteCuration.current();
        List<String> names = curation.retainOfferedNames(owner, new ArrayList<>(byName.keySet()), null);
        for (String name : names) {
            List<MethodSignature> sigs = curation.retainOffered(owner, name, byName.get(name), null);
            out.addAll(sigs);
        }
        return out;
    }

    private static String callKey(String owner, MethodSignature sig) {
        return owner + "." + sig.name() + "(" + sig.signatureKey() + ")";
    }

    /** A call the palette offers: its facade, its owner's simple name and its signature. */
    private record PaletteCall(FacadeEntry facade, String owner, MethodSignature signature) {
        ExpressionChoice.Method choice() {
            return new ExpressionChoice.Method(owner, signature.name(), signature.paramTypes(), true);
        }
    }

    private Optional<PaletteCall> paletteCall(ProjectAnalyzer analyzer, String key) {
        for (FacadeEntry facade : facades()) {
            String owner = facade.simpleName();
            for (MethodSignature sig : offered(analyzer, owner)) {
                if (callKey(owner, sig).equals(key)) return Optional.of(new PaletteCall(facade, owner, sig));
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
            // A plugin's call statement is its block by the call itself, not the statement around it.
            case Expression call -> arguments(call, positions);
            case VariableDeclarationStatement s -> {
                for (Object fragment : s.fragments()) {
                    Expression initializer = ((VariableDeclarationFragment) fragment).getInitializer();
                    if (initializer != null) within(initializer, positions);
                }
            }
            case IfStatement s -> within(s.getExpression(), positions);
            case WhileStatement s -> within(s.getExpression(), positions);
            case DoStatement s -> within(s.getExpression(), positions);
            case ReturnStatement s -> { if (s.getExpression() != null) within(s.getExpression(), positions); }
            default -> { }
        }
        List<BlockView.Slot> slots = new ArrayList<>();
        for (Expression position : positions) {
            ITypeBinding expected = expectedType(position);
            slots.add(new BlockView.Slot(BlockId.of(position), nameOf(expected), oneLine(position, source),
                    valueType(expected).isPresent() || expected != null && expected.isEnum()));
        }
        return slots;
    }

    /**
     * {@code expression} and the positions inside it a value may be written into: through a {@code !}, an
     * operator's operands or brackets, down to each call's arguments — so a condition built with edit_code's
     * set ({@code !ImageFinder.find(…)}) can have its parts set too.
     */
    private static void within(Expression expression, List<Expression> out) {
        out.add(expression);
        switch (expression) {
            case PrefixExpression p when p.getOperator() == PrefixExpression.Operator.NOT
                    || p.getOperator() == PrefixExpression.Operator.MINUS -> within(p.getOperand(), out);
            case InfixExpression i -> {
                within(i.getLeftOperand(), out);
                within(i.getRightOperand(), out);
                for (Object more : i.extendedOperands()) within((Expression) more, out);
            }
            case ParenthesizedExpression p -> within(p.getExpression(), out);
            // A seeded `(T) null` is one slot, the cast: its null is no place of its own.
            case CastExpression c when !(c.getExpression() instanceof NullLiteral) -> within(c.getExpression(), out);
            case ConditionalExpression c -> {
                within(c.getExpression(), out);
                within(c.getThenExpression(), out);
                within(c.getElseExpression(), out);
            }
            default -> arguments(expression, out);
        }
    }

    /** Each argument of the call {@code expression} is, and the positions inside each, a call's in a call too. */
    private static void arguments(Expression expression, List<Expression> out) {
        List<?> arguments = switch (expression) {
            case MethodInvocation call -> call.arguments();
            case ClassInstanceCreation creation -> creation.arguments();
            default -> List.of();
        };
        for (Object argument : arguments) within((Expression) argument, out);
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
        // A return gives what its method declares: a seeded `return null;` is typed null otherwise (2026-10-13).
        if (parent instanceof ReturnStatement) {
            ASTNode at = parent;
            while (at != null && !(at instanceof MethodDeclaration) && !(at instanceof LambdaExpression)) {
                at = at.getParent();
            }
            ITypeBinding declared = switch (at) {
                case MethodDeclaration method when method.getReturnType2() != null ->
                        method.getReturnType2().resolveBinding();
                case LambdaExpression lambda when lambda.resolveMethodBinding() != null ->
                        lambda.resolveMethodBinding().getReturnType();
                case null, default -> null;
            };
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
