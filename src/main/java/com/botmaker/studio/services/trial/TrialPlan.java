package com.botmaker.studio.services.trial;

import com.botmaker.studio.plugin.grammar.JavaValue;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.LabeledStatement;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What ▶ Try needs to run one statement of a bot on its own: the earlier locals the statement reads, where each
 * one's value can come from, and the statement as Java a class beside the bot's can compile.
 *
 * <p><b>The locals</b> are a backward slice inside the statement's method, read off the compiler's bindings: every
 * local or parameter the statement reads that is declared before it, and for a local that is computed, the
 * locals its initializer reads in turn. Each gets a {@link Source}, defaulted in the order the user chose
 * (2026-10-05):
 * <ol>
 *   <li>{@link Source#LAST_RUN} — the value the last debug pause showed for it, when that value can be read back;</li>
 *   <li>{@link Source#COMPUTE} — its initializer, run again, when that initializer only reads: literals,
 *       constants, values the grammar reads, other locals, and calls a plugin probes read-only. A local assigned
 *       anywhere after its declaration is not computed — the value it has at the statement is the run's;</li>
 *   <li>{@link Source#ASK} — the user gives it.</li>
 * </ol>
 *
 * <p><b>The statement</b> is copied as written, with the enclosing class's own static members qualified, since the
 * caller is another class. What a caller outside the class cannot reach is refused with a sentence: {@code this},
 * an instance member, a private member, a {@code break} or {@code continue} whose loop is outside the statement.
 * So is a statement inside a lambda, whose {@code return} would mean something else.
 */
public final class TrialPlan {

    /** Where one local's value comes from. */
    public enum Source {
        LAST_RUN("last-run", "From the last run"),
        COMPUTE("compute", "Compute it"),
        ASK("ask", "Ask me");

        private final String id;
        private final String displayName;

        Source(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * One earlier local the statement reads.
     *
     * @param name        its name
     * @param type        the Java type to declare it with, qualified
     * @param typeName    its erasure's canonical name, which the grammar knows a type by
     * @param initializer its initializer, qualified for the caller, when it can be computed; else null
     * @param reads       the locals that initializer reads, when computed
     * @param lastRun     its value at the last debug pause, written as Java; null when there is none
     */
    public record Local(String name, String type, String typeName, String initializer, List<String> reads,
                        JavaValue lastRun) {

        public Local {
            reads = List.copyOf(reads);
        }

        /** The sources this local can take, in default order; {@link Source#ASK} always. */
        public List<Source> sources() {
            List<Source> out = new ArrayList<>();
            if (lastRun != null) out.add(Source.LAST_RUN);
            if (initializer != null) out.add(Source.COMPUTE);
            out.add(Source.ASK);
            return List.copyOf(out);
        }

        public Source defaultSource() {
            return sources().getFirst();
        }
    }

    /**
     * A statement ready to try.
     *
     * @param packageName  the package the caller is written in: the statement's own, so package members are reachable
     * @param imports      the statement's file's imports, as written
     * @param body         the statement, qualified for the caller
     * @param valued       whether its method returns a value, so a {@code return} in it reports one
     * @param locals       every local it may need, in declaration order
     * @param reads        the locals the statement itself reads
     * @param label        what the trial is called in the console
     */
    public record Plan(String packageName, List<String> imports, String body, boolean valued, List<Local> locals,
                       List<String> reads, String label) {

        public Plan {
            imports = List.copyOf(imports);
            locals = List.copyOf(locals);
            reads = List.copyOf(reads);
        }

        /**
         * The locals a trial with {@code chosen} declares, in declaration order: what the statement reads, and what
         * each computed local's initializer reads. A local not chosen counts as its default.
         */
        public List<Local> needed(Map<String, Source> chosen) {
            Map<String, Local> byName = new LinkedHashMap<>();
            for (Local local : locals) byName.put(local.name(), local);
            Set<String> need = new LinkedHashSet<>();
            List<String> work = new ArrayList<>(reads);
            while (!work.isEmpty()) {
                String name = work.removeLast();
                Local local = byName.get(name);
                if (local == null || !need.add(name)) continue;
                Source source = chosen.getOrDefault(name, local.defaultSource());
                if (source == Source.COMPUTE) work.addAll(local.reads());
            }
            return locals.stream().filter(l -> need.contains(l.name())).toList();
        }
    }

    /** What {@link #plan} decided. */
    public sealed interface Result permits Planned, Refused {}

    public record Planned(Plan plan) implements Result {}

    /** @param reason a sentence that says why, to show as it is */
    public record Refused(String reason) implements Result {}

    /** A local's value at the last debug pause, written as Java, or empty. */
    @FunctionalInterface
    public interface LastRun {
        LastRun NONE = (className, method, local, typeName) -> Optional.empty();

        Optional<JavaValue> value(String className, String method, String local, String typeName);
    }

    /** Why a statement cannot be tried; thrown inside the walk and turned into {@link Refused}. */
    private static final class Refusal extends RuntimeException {
        Refusal(String reason) {
            super(reason, null, false, false);
        }
    }

    private TrialPlan() {}

    /**
     * Plans {@code statement}, from a tree with bindings.
     *
     * @param source   the text of the statement's file, which the tree was parsed from
     * @param readOnly whether a call only looks: a plugin probes it read-only
     * @param isValue  whether an expression's text is a value the grammar reads
     * @param lastRun  the last debug pause's values
     */
    public static Result plan(Statement statement, String source, Predicate<IMethodBinding> readOnly,
                              Predicate<String> isValue, LastRun lastRun) {
        if (statement == null) return new Refused("There is no statement here to try.");
        try {
            return new Planned(planned(statement, source, readOnly, isValue, lastRun));
        } catch (Refusal refused) {
            return new Refused(refused.getMessage());
        }
    }

    /**
     * Plans a call of {@code className.method()} with no arguments — ▶ Run this activity.
     *
     * @param packageName the class's package, given apart: a nested class's name does not say where it ends
     * @param className   the class, qualified, a nested one through its outer classes
     * @param valued      whether the method returns a value, reported when it does
     */
    public static Plan call(String packageName, String className, String method, boolean valued) {
        String call = className + "." + method + "()";
        String simple = className.substring(className.lastIndexOf('.') + 1);
        return new Plan(packageName, List.of(), valued ? "return " + call + ";" : call + ";", valued, List.of(),
                List.of(), simple + "." + method + "()");
    }

    private static Plan planned(Statement statement, String source, Predicate<IMethodBinding> readOnly,
                                Predicate<String> isValue, LastRun lastRun) {
        MethodDeclaration method = enclosingMethod(statement);
        IMethodBinding methodBinding = method.resolveBinding();
        if (methodBinding == null) {
            throw new Refusal("Studio can't read this file's types yet. Wait for it to finish loading, then try again.");
        }
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new Refusal(method.getName() + "() is not static, and Try runs a statement without an instance "
                    + "of its class.");
        }
        CompilationUnit unit = (CompilationUnit) statement.getRoot();
        ITypeBinding owner = methodBinding.getDeclaringClass();
        checkJumps(statement);

        List<IVariableBinding> statementReads = localsRead(statement, statement, unit);
        Map<IVariableBinding, Local> found = new LinkedHashMap<>();
        Map<IVariableBinding, ASTNode> declarations = new LinkedHashMap<>();
        List<IVariableBinding> work = new ArrayList<>(statementReads);
        while (!work.isEmpty()) {
            IVariableBinding local = work.removeFirst();
            if (found.containsKey(local)) continue;
            ASTNode declaration = unit.findDeclaringNode(local);
            if (!(declaration instanceof VariableDeclaration declared)) {
                throw new Refusal("Studio can't find where " + local.getName() + " is declared.");
            }
            List<IVariableBinding> reads = new ArrayList<>();
            String initializer = computable(declared, method, unit, source, readOnly, isValue, reads);
            JavaValue last = lastRun.value(owner.getErasure().getBinaryName(), method.getName().getIdentifier(),
                    local.getName(), typeName(local.getType())).orElse(null);
            found.put(local, new Local(local.getName(), declaredType(local), typeName(local.getType()), initializer,
                    reads.stream().map(IVariableBinding::getName).toList(), last));
            declarations.put(local, declaration);
            if (initializer != null) work.addAll(reads);
        }
        List<Local> locals = found.keySet().stream()
                .sorted(Comparator.comparingInt(v -> declarations.get(v).getStartPosition()))
                .map(found::get).toList();

        String body = reported(statement, qualified(statement, source));
        String pkg = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName();
        List<String> imports = new ArrayList<>();
        for (Object each : unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) each;
            imports.add(text(declaration, source).strip());
        }
        boolean valued = !"void".equals(methodBinding.getReturnType().getName());
        return new Plan(pkg, imports, body, valued, locals,
                statementReads.stream().map(IVariableBinding::getName).distinct().toList(),
                firstLine(text(statement, source)));
    }

    /** The method {@code statement} is in, directly: a statement in a lambda or a local class is refused. */
    private static MethodDeclaration enclosingMethod(Statement statement) {
        for (ASTNode at = statement.getParent(); at != null; at = at.getParent()) {
            switch (at) {
                case MethodDeclaration method -> {
                    if (method.getBody() == null) break;
                    return method;
                }
                case LambdaExpression ignored ->
                        throw new Refusal("This statement is inside a lambda. Try the statement holding the lambda.");
                case AnonymousClassDeclaration ignored ->
                        throw new Refusal("This statement is inside an anonymous class, which Try can't run on its own.");
                case AbstractTypeDeclaration ignored -> throw new Refusal("This statement is not in a method.");
                default -> { }
            }
        }
        throw new Refusal("This statement is not in a method.");
    }

    /** Refuses a {@code break} or {@code continue} whose target is outside {@code statement}. */
    private static void checkJumps(Statement statement) {
        statement.accept(new ASTVisitor() {
            @Override
            public boolean visit(BreakStatement jump) {
                check(jump, jump.getLabel(), true);
                return false;
            }

            @Override
            public boolean visit(ContinueStatement jump) {
                check(jump, jump.getLabel(), false);
                return false;
            }

            @Override
            public boolean visit(LambdaExpression lambda) {
                return false;
            }

            @Override
            public boolean visit(AnonymousClassDeclaration body) {
                return false;
            }

            private void check(Statement jump, SimpleName label, boolean isBreak) {
                // Only the statement and what is inside it count: the walk stops once it has left the statement.
                for (ASTNode child = jump, at = jump.getParent(); child != statement && at != null;
                     child = at, at = at.getParent()) {
                    boolean target = label != null
                            ? at instanceof LabeledStatement l && l.getLabel().getIdentifier().equals(label.getIdentifier())
                            : at instanceof ForStatement || at instanceof EnhancedForStatement
                              || at instanceof WhileStatement || at instanceof DoStatement
                              || (isBreak && at instanceof SwitchStatement);
                    if (target) return;
                }
                throw new Refusal("This statement " + (isBreak ? "breaks out of" : "continues") + " a loop around it, "
                        + "which Try doesn't run. Try the loop instead.");
            }
        });
    }

    /** The earlier locals {@code node} reads: declared in the method, outside {@code statement}. */
    private static List<IVariableBinding> localsRead(ASTNode node, Statement statement, CompilationUnit unit) {
        List<IVariableBinding> out = new ArrayList<>();
        int from = statement.getStartPosition();
        int to = from + statement.getLength();
        node.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (name.getParent() instanceof VariableDeclaration d && d.getName() == name) return false;
                if (name.resolveBinding() instanceof IVariableBinding v && !v.isField()) {
                    ASTNode declaration = unit.findDeclaringNode(v);
                    int at = declaration == null ? -1 : declaration.getStartPosition();
                    if (declaration != null && (at < from || at >= to) && !out.contains(v)) out.add(v);
                }
                return false;
            }
        });
        return out;
    }

    /**
     * {@code declared}'s initializer, qualified for the caller, when running it again gives the value it has at
     * the statement; null otherwise. The locals it reads are added to {@code reads}.
     */
    private static String computable(VariableDeclaration declared, MethodDeclaration method, CompilationUnit unit,
                                     String source, Predicate<IMethodBinding> readOnly, Predicate<String> isValue,
                                     List<IVariableBinding> reads) {
        if (!(declared instanceof VariableDeclarationFragment fragment) || fragment.getInitializer() == null) return null;
        IVariableBinding binding = fragment.resolveBinding();
        if (binding == null || assignedAgain(binding, method)) return null;
        Expression initializer = fragment.getInitializer();
        List<IVariableBinding> found = new ArrayList<>();
        if (!readsOnly(initializer, source, readOnly, isValue, found, unit)) return null;
        String qualified;
        try {
            qualified = qualified(initializer, source);
        } catch (Refusal unreachable) {
            return null;   // asked for, or taken from the last run, instead
        }
        reads.addAll(found);
        return qualified;
    }

    /** Whether {@code local} is assigned anywhere in {@code method} after its declaration. */
    private static boolean assignedAgain(IVariableBinding local, MethodDeclaration method) {
        boolean[] assigned = {false};
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment assignment) {
                if (isLocal(assignment.getLeftHandSide())) assigned[0] = true;
                return true;
            }

            @Override
            public boolean visit(PostfixExpression change) {
                if (isLocal(change.getOperand())) assigned[0] = true;
                return true;
            }

            @Override
            public boolean visit(PrefixExpression change) {
                if ((change.getOperator() == PrefixExpression.Operator.INCREMENT
                        || change.getOperator() == PrefixExpression.Operator.DECREMENT) && isLocal(change.getOperand())) {
                    assigned[0] = true;
                }
                return true;
            }

            private boolean isLocal(Expression target) {
                return target instanceof SimpleName name && local.isEqualTo(name.resolveBinding());
            }
        });
        return assigned[0];
    }

    /** Whether evaluating {@code e} only reads: no call a plugin does not probe read-only, no assignment. */
    private static boolean readsOnly(Expression e, String source, Predicate<IMethodBinding> readOnly,
                                     Predicate<String> isValue, List<IVariableBinding> reads, CompilationUnit unit) {
        // A name is a local or a field, never a value to read: a local read as one would go undeclared.
        if (!(e instanceof Name) && isValue.test(text(e, source))) return true;
        return switch (e) {
            case NumberLiteral ignored -> true;
            case StringLiteral ignored -> true;
            case CharacterLiteral ignored -> true;
            case BooleanLiteral ignored -> true;
            case NullLiteral ignored -> true;
            case TextBlock ignored -> true;
            case TypeLiteral ignored -> true;
            case Name name -> switch (name.resolveBinding()) {
                case IVariableBinding v when v.isField() ->
                        Modifier.isStatic(v.getModifiers()) && Modifier.isFinal(v.getModifiers());
                case IVariableBinding v -> {
                    if (!reads.contains(v)) reads.add(v);
                    yield true;
                }
                case null, default -> false;
            };
            case ParenthesizedExpression p -> readsOnly(p.getExpression(), source, readOnly, isValue, reads, unit);
            case CastExpression c -> readsOnly(c.getExpression(), source, readOnly, isValue, reads, unit);
            case InstanceofExpression i -> readsOnly(i.getLeftOperand(), source, readOnly, isValue, reads, unit);
            case PrefixExpression p -> p.getOperator() != PrefixExpression.Operator.INCREMENT
                    && p.getOperator() != PrefixExpression.Operator.DECREMENT
                    && readsOnly(p.getOperand(), source, readOnly, isValue, reads, unit);
            case ConditionalExpression c -> readsOnly(c.getExpression(), source, readOnly, isValue, reads, unit)
                    && readsOnly(c.getThenExpression(), source, readOnly, isValue, reads, unit)
                    && readsOnly(c.getElseExpression(), source, readOnly, isValue, reads, unit);
            case InfixExpression i -> {
                List<Expression> operands = new ArrayList<>(List.of(i.getLeftOperand(), i.getRightOperand()));
                for (Object extra : i.extendedOperands()) operands.add((Expression) extra);
                yield operands.stream().allMatch(o -> readsOnly(o, source, readOnly, isValue, reads, unit));
            }
            case MethodInvocation call -> {
                if (!readOnly.test(call.resolveMethodBinding())) yield false;
                Expression target = call.getExpression();
                if (target != null && !(target instanceof Name n && n.resolveBinding() instanceof ITypeBinding)
                        && !readsOnly(target, source, readOnly, isValue, reads, unit)) {
                    yield false;
                }
                for (Object argument : call.arguments()) {
                    if (!readsOnly((Expression) argument, source, readOnly, isValue, reads, unit)) yield false;
                }
                yield true;
            }
            default -> false;
        };
    }

    /**
     * {@code node}'s text with every unqualified static member of a class qualified by that class, so it compiles
     * in another class of the package; refused when it reaches what such a class cannot.
     */
    static String qualified(ASTNode node, String source) {
        Map<Integer, String> inserts = new java.util.TreeMap<>(Comparator.reverseOrder());
        node.accept(new ASTVisitor() {
            @Override
            public boolean visit(ThisExpression self) {
                throw new Refusal("This statement uses this, and Try runs it without an instance of its class.");
            }

            @Override
            public boolean visit(SuperMethodInvocation call) {
                throw new Refusal("This statement calls super, which Try can't run outside its class.");
            }

            @Override
            public boolean visit(SuperFieldAccess access) {
                throw new Refusal("This statement reads super, which Try can't run outside its class.");
            }

            @Override
            public boolean visit(SimpleName name) {
                IBinding binding = name.resolveBinding();
                switch (binding) {
                    case IVariableBinding v when v.isField() -> {
                        refusePrivate(v.getModifiers(), v.getDeclaringClass(), v.getName());
                        if (!Modifier.isStatic(v.getModifiers())) {
                            if (unqualified(name)) {
                                throw new Refusal("This statement reads the field " + v.getName()
                                        + " of an instance, and Try runs it without one.");
                            }
                        } else if (unqualified(name) && !(name.getParent() instanceof SwitchCase)
                                && v.getDeclaringClass() != null) {
                            inserts.put(name.getStartPosition(), canonical(v.getDeclaringClass()) + ".");
                        }
                    }
                    case IMethodBinding m when name.getParent() instanceof MethodInvocation call
                            && call.getName() == name -> {
                        refusePrivate(m.getModifiers(), m.getDeclaringClass(), m.getName() + "()");
                        if (call.getExpression() == null) {
                            if (!Modifier.isStatic(m.getModifiers())) {
                                throw new Refusal("This statement calls " + m.getName() + "(), an instance method, "
                                        + "and Try runs it without an instance.");
                            }
                            inserts.put(name.getStartPosition(), canonical(m.getDeclaringClass()) + ".");
                        }
                    }
                    case ITypeBinding t when name.getParent() instanceof SimpleType || isTypeQualifier(name) -> {
                        if (t.isLocal()) {
                            throw new Refusal("This statement uses " + t.getName()
                                    + ", a class declared inside the method.");
                        }
                        refusePrivate(t.getModifiers(), t.getDeclaringClass(), t.getName());
                        if (t.isMember() && t.getDeclaringClass() != null) {
                            inserts.put(name.getStartPosition(), canonical(t.getDeclaringClass()) + ".");
                        }
                    }
                    case null, default -> { }
                }
                return false;
            }
        });
        String text = text(node, source);
        StringBuilder out = new StringBuilder(text);
        int base = node.getStartPosition();
        inserts.forEach((at, prefix) -> out.insert(at - base, prefix));
        return out.toString();
    }

    /** Whether {@code name} stands alone: not the right half of {@code a.b}, nor a field access's name. */
    private static boolean unqualified(SimpleName name) {
        ASTNode parent = name.getParent();
        if (parent instanceof QualifiedName q && q.getName() == name) return false;
        return !(parent instanceof FieldAccess f && f.getName() == name);
    }

    /** Whether {@code name} is the left-most part of a qualified name: {@code Inner} in {@code Inner.CONSTANT}. */
    private static boolean isTypeQualifier(SimpleName name) {
        return name.getParent() instanceof QualifiedName q && q.getQualifier() == name
                || name.getParent() instanceof MethodInvocation call && call.getExpression() == name;
    }

    private static void refusePrivate(int modifiers, ITypeBinding owner, String what) {
        if (Modifier.isPrivate(modifiers)) {
            throw new Refusal("This statement uses " + what + ", which is private to "
                    + (owner == null ? "its class" : owner.getName()) + ". Try runs from another class of the package, "
                    + "so make it package-private to try it.");
        }
    }

    /**
     * The statement as the caller runs it: a declaration or a call that answers something says what it answered,
     * so a try shows what it found as well as what it did.
     */
    private static String reported(Statement statement, String qualified) {
        if (statement instanceof VariableDeclarationStatement declared && declared.fragments().size() == 1) {
            String name = ((VariableDeclarationFragment) declared.fragments().getFirst()).getName().getIdentifier();
            return qualified + "\nreport(\"" + name + "\", " + name + ");";
        }
        if (statement instanceof ExpressionStatement s && s.getExpression() instanceof MethodInvocation call) {
            IMethodBinding binding = call.resolveMethodBinding();
            if (binding != null && !"void".equals(binding.getReturnType().getName())) {
                String expression = qualified.strip();
                if (expression.endsWith(";")) expression = expression.substring(0, expression.length() - 1);
                return "report(\"" + call.getName().getIdentifier() + "(…)\", " + expression + ");";
            }
        }
        return qualified;
    }

    /** The type a local is declared with in the caller: qualified, and refused when no other class can name it. */
    private static String declaredType(IVariableBinding local) {
        ITypeBinding type = local.getType();
        if (type == null || type.isLocal() || type.isAnonymous()) {
            throw new Refusal("This statement reads " + local.getName() + ", whose type can't be named outside "
                    + "the method.");
        }
        if (type.isCapture() || type.isWildcardType()) type = type.getErasure();
        String name = type.getQualifiedName();
        return name.contains("capture#") ? type.getErasure().getQualifiedName() : name;
    }

    /** The canonical name the grammar knows {@code type} by: its erasure's, a primitive's keyword. */
    private static String typeName(ITypeBinding type) {
        if (type == null) return "";
        ITypeBinding erased = type.getErasure();
        return erased.isPrimitive() ? erased.getName() : erased.getQualifiedName();
    }

    private static String canonical(ITypeBinding type) {
        return type.getErasure().getQualifiedName();
    }

    private static String text(ASTNode node, String source) {
        return source.substring(node.getStartPosition(), node.getStartPosition() + node.getLength());
    }

    private static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() > 60 ? line.substring(0, 59) + "…" : line;
    }
}
