package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.nav.Usages;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeMethodReference;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every call to one function, across the whole project — the thing that had to exist before a signature could
 * be changed safely.
 *
 * <p>Editing a signature rewrote the declaration and nothing else, so renaming {@code clickAt} left four calls
 * to a name that no longer exists, in files the user was not even looking at. There was no helper anywhere
 * that answered "where is this called from"; this is it.
 *
 * <h2>By binding (2026-09-27)</h2>
 *
 * <p>It judged every call from source alone until then — same name, same number of arguments, a receiver
 * whose declared type could be read off the file — and refused whatever that could not settle, because
 * "bindings are not available". They are: the whole bot is parsed as one ({@link BotIndex}), and a call is
 * this method exactly when javac would say so ({@link Usages#keyOf}). The finder that guessed also never saw
 * a <b>method reference</b>: {@code Collect::body} in the flow is how an activity is named, and renaming
 * {@code body} left the flow pointing at nothing. A reference is found like a call now, and carried along by a
 * rename; a change of shape it cannot follow is refused by {@code SignatureEdits}, naming it.
 *
 * <p>What is left of "cannot tell" is a call whose binding javac could not resolve at all — a receiver of an
 * unknown type — with this method's name and arity. It still refuses, for the old reason: silently migrating
 * three of four call sites is strictly worse than migrating none and saying which file could not be read. A
 * file that does not parse is the same answer.
 *
 * <h2>Constructors count as calls</h2>
 *
 * <p>A constructor's calls are {@code new GoHome(…)} and {@code GoHome::new}. A {@code this(…)} or
 * {@code super(…)} call is a statement no call change can describe, so one refuses.
 */
public final class MethodReferences {

    private MethodReferences() {}

    /**
     * One call to the method, and the file and parse it was found in.
     *
     * <p>{@code node} is a {@link MethodInvocation} for {@code goHome(…)}, a {@link ClassInstanceCreation} for
     * {@code new GoHome(…)}, and a {@link MethodReference} for {@code GoHome::go}. They are one type here
     * because everything downstream — {@link SignatureMigration}'s per-argument plan, {@link CallMigrator}'s
     * rewrite — asks a call the same questions regardless of which it is: what are your arguments, which
     * property holds them, and is anything consuming what you produce. A reference answers "no arguments",
     * and {@link #isReference()} says why.
     *
     * <p>Since 2026-08 a site may also be a <b>field reference</b> — {@code Key.ENTER} (a
     * {@link org.eclipse.jdt.core.dom.QualifiedName}) or the bare {@link SimpleName} of a statically-imported
     * constant or a {@code case} label. A constant is API too, so an SDK migration has to be able to rename or
     * move one. A field simply answers "no arguments" to the argument questions, which is true.
     */
    public record CallSite(ProjectFile file, CompilationUnit unit, Expression node) {

        /** The arguments as written, in order — empty for a field or a method reference. */
        public List<?> arguments() {
            return switch (node) {
                case ClassInstanceCreation creation -> creation.arguments();
                case MethodInvocation call -> call.arguments();
                case SuperMethodInvocation call -> call.arguments();
                default -> List.of();
            };
        }

        /**
         * Which child list {@link #arguments()} is, for a {@code ListRewrite} over it — null for a field or a
         * method reference, whose argument list is never rewritten.
         */
        public ChildListPropertyDescriptor argumentsProperty() {
            return switch (node) {
                case ClassInstanceCreation ignored -> ClassInstanceCreation.ARGUMENTS_PROPERTY;
                case MethodInvocation ignored -> MethodInvocation.ARGUMENTS_PROPERTY;
                case SuperMethodInvocation ignored -> SuperMethodInvocation.ARGUMENTS_PROPERTY;
                default -> null;
            };
        }

        /**
         * The name that a rename would rewrite, or null when there is none to rewrite. A constructor's name is
         * its class's, which is not this dialog's to change — renaming the class is a different edit.
         */
        public SimpleName nameNode() {
            return switch (node) {
                case MethodInvocation call -> call.getName();
                case SuperMethodInvocation call -> call.getName();
                case ExpressionMethodReference reference -> reference.getName();
                case TypeMethodReference reference -> reference.getName();
                case SuperMethodReference reference -> reference.getName();
                case QualifiedName qualified -> qualified.getName();
                case SimpleName bare -> bare;
                default -> null;
            };
        }

        /**
         * The name of the type this member is reached <em>through</em> — the {@code Key} of {@code Key.ENTER},
         * the receiver of a static call, the class of a {@code new} — or null when the source names none, as
         * it does for a bare statically-imported constant, a {@code case} label, or a call on {@code this}.
         *
         * <p>Null is what makes a move refusable rather than guessable: with no type written at the site there
         * is nothing to retarget, and {@link CallMigrator} declines the whole migration instead of inventing a
         * qualifier.
         */
        public SimpleName ownerNode() {
            return switch (node) {
                case QualifiedName qualified ->
                        qualified.getQualifier() instanceof SimpleName owner ? owner : null;
                case MethodInvocation call ->
                        call.getExpression() instanceof SimpleName receiver ? receiver : null;
                case ClassInstanceCreation creation ->
                        creation.getType() instanceof SimpleType simple
                                && simple.getName() instanceof SimpleName name ? name : null;
                default -> null;
            };
        }

        public int argumentCount() {
            return arguments().size();
        }

        /** The file's class name — {@code Bot}, {@code GoHome} — which is how the preview names it. */
        public String className() {
            return file.getClassName();
        }

        /** True when the call stands as a line of its own, so nothing consumes what it gives back. */
        public boolean isStatement() {
            return node.getParent() instanceof ExpressionStatement;
        }

        /**
         * True for {@code Collect::body}: the method is named, not called, so there are no arguments to carry
         * — only its name follows a rename, and any other change of shape is one a reference cannot follow.
         */
        public boolean isReference() {
            return node instanceof MethodReference;
        }

        /** Where it is, for a list the user can click through. */
        public Usages.Usage usage() {
            int start = node.getStartPosition();
            int line = unit.getLineNumber(start);
            String source = file.getContent() == null ? "" : file.getContent();
            String[] lines = source.split("\n", -1);
            String text = line >= 1 && line <= lines.length ? lines[line - 1].trim() : "";
            return new Usages.Usage(file.getPath(), start, line, enclosingMethodName(node), text, false);
        }
    }

    /**
     * What the scan found. {@code calls} is only usable when {@link #isRefusal()} is false — a result with
     * anything in {@code unreadable} or {@code uncertain} is an answer about the project, not a partial list.
     */
    public record Result(List<CallSite> calls, List<String> unreadable, List<String> uncertain) {

        public boolean isRefusal() {
            return !unreadable.isEmpty() || !uncertain.isEmpty();
        }

        /** Why the change cannot be made, naming the file — or null when it can. */
        public String refusal() {
            if (!unreadable.isEmpty()) {
                return "\"" + unreadable.getFirst() + "\" doesn't currently parse, so the calls in it can't be "
                        + "found. Fix that file first, and nothing here will have changed.";
            }
            if (!uncertain.isEmpty()) {
                return "\"" + uncertain.getFirst() + "\" has a call this editor can't be sure about, and "
                        + "changing some call sites but not others would break the project. Nothing has "
                        + "changed.";
            }
            return null;
        }

        /** The files the calls are spread across, in the order they were found. */
        public List<String> fileNames() {
            Set<String> names = new LinkedHashSet<>();
            for (CallSite site : calls) names.add(site.className());
            return List.copyOf(names);
        }

        /** Every call as a place to go, in the order they were found. */
        public List<Usages.Usage> usages() {
            return calls.stream().map(CallSite::usage).toList();
        }
    }

    /**
     * Every call to {@code declaration} in the project.
     *
     * <p>The declaring file is read from the live AST the declaration itself belongs to, never re-parsed: the
     * caller goes on to rewrite that file through the editor's own guarded write, which holds the same tree,
     * and two parses of one file are two sets of nodes that only look alike. Every other file is the
     * {@link BotIndex}'s unit for it.
     */
    public static Result find(ProjectState state, MethodDeclaration declaration) {
        List<CallSite> calls = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        List<String> uncertain = new ArrayList<>();
        if (state == null || declaration == null) return new Result(calls, unreadable, uncertain);
        String owner = declaringClassOf(declaration);
        if (owner == null) return new Result(calls, unreadable, uncertain);
        IMethodBinding binding = declaration.resolveBinding();
        String key = binding == null ? null : Usages.keyOf(binding);
        if (key == null) {
            uncertain.add(owner);
            return new Result(calls, unreadable, uncertain);
        }

        CompilationUnit live = (CompilationUnit) declaration.getRoot();
        BotIndex index = BotIndex.of(state);
        Shape shape = new Shape(key, Usages.keyOf(binding.getDeclaringClass()), declaration.getName().getIdentifier(),
                declaration.parameters().size(), declaration.isConstructor());
        index.read(units -> {
            for (ProjectFile file : state.getAllFiles()) {
                CompilationUnit unit;
                if (file == state.getActiveFile()) {
                    unit = live;
                } else {
                    if (file.getContent() == null || file.getPath() == null) continue;
                    unit = units.get(file.getPath().toAbsolutePath().normalize());
                    if (unit == null) continue;
                    if (SourceParser.doesNotParse(unit)) {
                        unreadable.add(file.getClassName());
                        continue;
                    }
                }
                scan(file, unit, shape, calls, uncertain);
            }
            return null;
        });
        return new Result(calls, unreadable, uncertain);
    }

    // --- one file ------------------------------------------------------------------------------------------

    /**
     * What a call to the method is: its binding key, the key of the class declaring it, and the name and arity
     * a call javac could not bind has.
     */
    private record Shape(String key, String ownerKey, String name, int arity, boolean constructor) {

        boolean is(IMethodBinding binding) {
            return binding != null && key.equals(Usages.keyOf(binding));
        }

        /**
         * Whether a receiver of type {@code type} could be the declaring class — it is, or it extends or
         * implements it. A receiver whose type javac could only recover (an unresolved name) extends nothing
         * the bot declares, so a call on it is somebody else's method.
         */
        boolean couldOwn(ITypeBinding type) {
            if (type == null) return true;
            Set<String> seen = new LinkedHashSet<>();
            List<ITypeBinding> todo = new ArrayList<>(List.of(type));
            while (!todo.isEmpty()) {
                ITypeBinding next = todo.removeLast();
                String k = Usages.keyOf(next);
                if (k == null || !seen.add(k)) continue;
                if (k.equals(ownerKey)) return true;
                if (next.getSuperclass() != null) todo.add(next.getSuperclass());
                todo.addAll(List.of(next.getInterfaces()));
            }
            return false;
        }
    }

    private static void scan(ProjectFile file, CompilationUnit unit, Shape shape, List<CallSite> calls,
                             List<String> uncertain) {
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation call) {
                if (shape.constructor()) return true;
                ITypeBinding receiver = call.getExpression() == null ? null
                        : call.getExpression().resolveTypeBinding();
                judge(call, call.resolveMethodBinding(), call.getName().getIdentifier(), call.arguments().size(),
                        receiver);
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation call) {
                if (shape.constructor()) return true;
                judge(call, call.resolveMethodBinding(), call.getName().getIdentifier(), call.arguments().size(),
                        null);
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation creation) {
                if (!shape.constructor()) return true;
                judge(creation, creation.resolveConstructorBinding(), null, creation.arguments().size(),
                        creation.getType().resolveBinding());
                return true;
            }

            @Override
            public boolean visit(ExpressionMethodReference reference) {
                return reference(reference, reference.getName().getIdentifier());
            }

            @Override
            public boolean visit(TypeMethodReference reference) {
                return reference(reference, reference.getName().getIdentifier());
            }

            @Override
            public boolean visit(SuperMethodReference reference) {
                return reference(reference, reference.getName().getIdentifier());
            }

            @Override
            public boolean visit(CreationReference reference) {
                if (shape.constructor() && shape.is(reference.resolveMethodBinding())) {
                    calls.add(new CallSite(file, unit, reference));
                }
                return true;
            }

            @Override
            public boolean visit(ConstructorInvocation call) {
                if (shape.constructor() && shape.is(call.resolveConstructorBinding())) unsure();
                return true;
            }

            @Override
            public boolean visit(SuperConstructorInvocation call) {
                if (shape.constructor() && shape.is(call.resolveConstructorBinding())) unsure();
                return true;
            }

            private boolean reference(MethodReference reference, String name) {
                if (shape.constructor()) return true;
                IMethodBinding bound = reference.resolveMethodBinding();
                if (shape.is(bound)) {
                    calls.add(new CallSite(file, unit, reference));
                } else if (bound == null && name.equals(shape.name())) {
                    unsure();
                }
                return true;
            }

            /**
             * This method when javac binds it here; "cannot tell" when javac bound nothing, the call has this
             * method's name and arity, and its receiver could be the declaring class; anything else is another
             * method.
             */
            private void judge(Expression call, IMethodBinding bound, String name, int arity,
                               ITypeBinding receiver) {
                // A recovered binding names the nearest method even for a call of the wrong arity, which does
                // not compile against this one and is not one of its calls.
                if (shape.is(bound) && (arity == shape.arity() || bound.isVarargs())) {
                    calls.add(new CallSite(file, unit, call));
                } else if (bound == null && arity == shape.arity()
                        && (name == null || name.equals(shape.name())) && shape.couldOwn(receiver)) {
                    unsure();
                }
            }

            private void unsure() {
                if (!uncertain.contains(file.getClassName())) uncertain.add(file.getClassName());
            }
        });
    }

    /** The name of the class {@code declaration} belongs to. */
    public static String declaringClassOf(MethodDeclaration declaration) {
        for (ASTNode n = declaration; n != null; n = n.getParent()) {
            if (n instanceof AbstractTypeDeclaration type) return type.getName().getIdentifier();
        }
        return null;
    }

    private static String enclosingMethodName(ASTNode node) {
        for (ASTNode n = node.getParent(); n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration method) return method.getName().getIdentifier();
            if (n instanceof AbstractTypeDeclaration type) return type.getName().getIdentifier();
        }
        return "";
    }

    /** The statement a call stands in, for a caller that has to remove or replace the whole line. */
    public static Statement statementOf(MethodInvocation call) {
        for (ASTNode n = call; n != null; n = n.getParent()) {
            if (n instanceof Statement statement) return statement;
            if (n instanceof BodyDeclaration) return null;
        }
        return null;
    }
}
