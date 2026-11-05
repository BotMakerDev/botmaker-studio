package com.botmaker.studio.assist;

import com.botmaker.core.edit.DeclarableTypes;
import com.botmaker.core.edit.EditSession;
import com.botmaker.core.edit.EditStage;
import com.botmaker.core.edit.EditWorkspace;
import com.botmaker.core.edit.Outcome;
import com.botmaker.core.edit.PaletteEntry;
import com.botmaker.core.parser.StatementBodies;
import com.botmaker.core.parser.StatementKind;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.project.params.BotRecords;
import com.botmaker.java.edit.BlockId;
import com.botmaker.java.edit.BodyChildren;
import com.botmaker.java.edit.helpers.MethodSignatures;
import com.botmaker.java.edit.palette.FunctionDraft;
import com.botmaker.java.edit.refactor.MethodReferences;
import com.botmaker.java.edit.refactor.SignatureMigration;
import com.botmaker.java.types.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.*;

import javax.lang.model.SourceVersion;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One assistant turn over one file: the core's {@link EditSession}, which does its edits, with what the dialogs offer
 * around it. {@link #tree} reads the file as blocks are drawn, through the core's {@link StatementKind} and
 * {@link StatementBodies}, which Studio's block converter draws by too (2026-11-05); {@link #editVariable} retypes to
 * one of the {@link DeclarableTypes} it was given, {@link #addMethod} and {@link #editSignature} to a type the
 * signature dialog offers.
 *
 * <p>Not thread-confined: nothing here reads {@code ProjectState}'s live instance, so a turn may run on the thread that
 * talks to the model. The turn lands through {@code CoreEditor.commit}, on the FX thread.
 */
public final class AssistTurn {

    /** What a turn's one undo step is called when the model names it nothing. */
    private static final String DEFAULT_LABEL = "the assistant's change";

    private final EditWorkspace workspace;
    private final EditSession session;
    private final DeclarableTypes types;

    /** A turn over {@code source}, whose {@link #editVariable} retypes to one of {@code types}. */
    public AssistTurn(EditWorkspace workspace, String source, DeclarableTypes types) {
        this.workspace = workspace;
        this.session = new EditSession(workspace, source);
        this.types = types;
    }

    /** The session the core lands. */
    public EditSession session() {
        return session;
    }

    /** The working copy as it stands. */
    public String source() {
        return session.source();
    }

    /** The file the turn edits. */
    public Path file() {
        return session.file();
    }

    /** How many edits this turn has kept. */
    public int acceptedEdits() {
        return session.acceptedEdits();
    }

    /** {@code label}, or what the undo step is called when it is blank. */
    public static String labelOf(String label) {
        return label == null || label.isBlank() ? DEFAULT_LABEL : label;
    }

    // ---- reading -----------------------------------------------------------------------------------------

    /** What may be inserted. */
    public List<PaletteEntry> palette() {
        return session.palette();
    }

    /** The errors the working copy compiles with, one sentence each: what a model reads before it edits. */
    public List<String> errors() {
        return session.errors();
    }

    /** The operators a slot takes, as edit_code's set spells them. */
    static List<String> operatorIds() {
        return EditSession.operatorIds();
    }

    /** The file's methods, bodies, statements and slots: the ids every edit tool takes. */
    public List<BlockView.Method> tree() {
        String source = session.source();
        Path file = workspace.file();
        CompilationUnit cu = ProjectAnalyzer.createCompilationUnit(workspace.level(), workspace.classpath(), source,
                workspace.sourceRoot(), file == null ? null : file.toAbsolutePath().toString());
        List<BlockView.Method> methods = new ArrayList<>();
        // The methods the canvas draws: the file's first type's, and its nested classes' and records', in order.
        if (cu.types().isEmpty()) return methods;
        methodsOf((AbstractTypeDeclaration) cu.types().getFirst(), BodyChildren.nonJavadoc(cu), source, methods);
        return methods;
    }

    private void methodsOf(AbstractTypeDeclaration type, List<Comment> comments, String source,
                           List<BlockView.Method> out) {
        if (!(type instanceof TypeDeclaration || type instanceof RecordDeclaration)) return;
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method && method.getBody() != null) {
                out.add(new BlockView.Method(method.getName().getIdentifier(), type.getName().getIdentifier(),
                        view(StatementBodies.of(method.getBody(), comments), comments, source)));
            } else if (member instanceof AbstractTypeDeclaration nested) {
                methodsOf(nested, comments, source, out);
            }
        }
    }

    // ---- editing -----------------------------------------------------------------------------------------

    /** Inserts the palette entry {@code paletteId} into body {@code bodyId} at statement index {@code index}. */
    public Outcome insert(String bodyId, int index, String paletteId) {
        return session.insert(bodyId, index, paletteId);
    }

    /** Writes {@code value} into slot {@code slotId}. */
    public Outcome setSlot(String slotId, String value) {
        return session.setSlot(slotId, value);
    }

    /** Adds an argument to the varargs call {@code callId}: a call statement's id, or a slot holding a call. */
    public Outcome addArgument(String callId) {
        return session.addArgument(callId);
    }

    /** Deletes the statement {@code blockId}. */
    public Outcome delete(String blockId) {
        return session.delete(blockId);
    }

    /** Moves the statement {@code blockId} into body {@code bodyId}, before the statement now at {@code index}. */
    public Outcome moveBlock(String blockId, String bodyId, int index) {
        return session.moveBlock(blockId, bodyId, index);
    }

    /**
     * Retypes and renames the variable {@code blockId} declares, as the Edit Variable dialog does: {@code type}
     * one the dialog's chooser offers (its initialiser reset to the type's fresh value), {@code name} carried to
     * its uses. A blank one is kept.
     */
    public Outcome editVariable(String blockId, String name, String type) {
        EditStage stage = session.stage();
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
        int keptBefore = session.acceptedEdits();
        if (!newType.isEmpty()) {
            List<DeclarableTypes.Offered> offered =
                    types.offered(BotRecords.scan(workspace.config(), stage.state, workspace.grammar()));
            Optional<Type> chosen = offered.stream()
                    .filter(e -> e.label().equals(newType) || simpleName(e.type()).equals(newType))
                    .map(DeclarableTypes.Offered::type).findFirst();
            if (chosen.isEmpty()) {
                return Outcome.Refused.because("A variable cannot be declared as " + newType + ". The types are "
                        + offered.stream().map(e -> simpleName(e.type())).distinct().toList() + ".");
            }
            Outcome typed = session.apply(stage, "retyped to " + newType,
                    editor -> editor.replaceVariableType(declared.get(), chosen.get()));
            if (!(typed instanceof Outcome.Accepted accepted)) return typed;
            done.add(accepted.summary());
        }
        if (!newName.isEmpty()) {
            EditStage again = newType.isEmpty() ? stage : session.stage();
            Optional<VariableDeclarationStatement> fresh = again.statement(blockId)
                    .filter(VariableDeclarationStatement.class::isInstance).map(VariableDeclarationStatement.class::cast);
            if (fresh.isEmpty()) {
                session.rollBackTo(keptBefore);
                return Outcome.Refused.because("Statement " + blockId + " moved. Read the tree again.");
            }
            SimpleName declaredName = ((VariableDeclarationFragment) fresh.get().fragments().getFirst()).getName();
            Outcome renamed = session.apply(again, "renamed to " + newName,
                    editor -> editor.renameLocal(declaredName, newName));
            if (!(renamed instanceof Outcome.Accepted kept)) {
                session.rollBackTo(keptBefore);
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

    /**
     * Adds a method to the class {@code className} (the file's first class when blank), after its other members,
     * as the Add Function dialog does: {@code returns} and each parameter's type a type the dialog offers.
     */
    public Outcome addMethod(String className, String name, String returns, List<SignatureNames.Param> params) {
        EditStage stage = session.stage();
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
        Set<String> taken = new HashSet<>();
        for (MethodDeclaration method : type.get().getMethods()) {
            MethodSignatures.draftOf(method, PluginHost.grammar()).ifPresent(d -> taken.add(d.signatureKey()));
        }
        Optional<String> problem = draft.problem(taken);
        if (problem.isPresent()) return Outcome.Refused.because(problem.get());
        int end = type.get().bodyDeclarations().size();
        return session.apply(stage, "added " + draft.name() + "()",
                editor -> editor.addFunctionToClass(type.get(), draft, end));
    }

    /**
     * Changes the method {@code method} ({@code name} or {@code Owner.name}) to the given name, return type and
     * parameters; a null one is kept. Its calls in this file follow; a parameter keeps its place in them by name.
     * Calls in other files are the caller's to check ({@code LiveBot.breaksElsewhere}).
     */
    public Outcome editSignature(String method, String name, String returns, List<SignatureNames.Param> params) {
        EditStage stage = session.stage();
        List<MethodDeclaration> found = methodsNamed(stage, method);
        if (found.isEmpty()) return Outcome.Refused.because("This file has no method " + method + ".");
        if (found.size() > 1) {
            return Outcome.Refused.because(method + " has " + found.size() + " overloads here; Studio cannot tell "
                    + "which. Rename one first.");
        }
        MethodDeclaration declaration = found.getFirst();
        Optional<FunctionDraft> current = MethodSignatures.draftOf(declaration, PluginHost.grammar());
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
        Set<String> taken = new HashSet<>();
        if (declaration.getParent() instanceof TypeDeclaration owner) {
            for (MethodDeclaration other : owner.getMethods()) {
                if (other != declaration) MethodSignatures.draftOf(other, PluginHost.grammar()).ifPresent(d -> taken.add(d.signatureKey()));
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
        return session.apply(stage, "changed " + current.get().name() + "() to " + draft.name() + "("
                        + String.join(", ", draft.parameters().stream()
                        .map(p -> p.type().sourceName() + " " + p.name()).toList()) + ")",
                editor -> editor.applyFunctionSignature(declaration, draft, plan));
    }

    private static Optional<TypeDeclaration> typeNamed(EditStage stage, String className) {
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

    private static List<MethodDeclaration> methodsNamed(EditStage stage, String spelled) {
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

    // ---- views -------------------------------------------------------------------------------------------

    /** {@code body} as the canvas draws it: each line's kind and the bodies inside it, by the core's classifier. */
    private BlockView.Body view(StatementBodies.Body body, List<Comment> comments, String source) {
        List<BlockView.Statement> statements = new ArrayList<>();
        for (ASTNode line : body.lines()) {
            List<BlockView.Body> nested = new ArrayList<>();
            if (line instanceof Statement statement) {
                for (StatementBodies.Body inner : StatementBodies.inside(statement, comments)) {
                    nested.add(view(inner, comments, source));
                }
            }
            ASTNode at = StatementBodies.addressOf(line);
            statements.add(new BlockView.Statement(BlockId.of(at), StatementKind.of(line).id(),
                    EditSession.oneLine(at, source), slots(at, source), nested));
        }
        return new BlockView.Body(BlockId.of(body.anchor()), statements);
    }

    /** The positions on {@code statement} a value may be written into. */
    private List<BlockView.Slot> slots(ASTNode statement, String source) {
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
            ITypeBinding expected = EditSession.expectedType(position);
            slots.add(new BlockView.Slot(BlockId.of(position), EditSession.nameOf(expected),
                    EditSession.oneLine(position, source),
                    session.valueType(expected).isPresent() || expected != null && expected.isEnum()));
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
}
