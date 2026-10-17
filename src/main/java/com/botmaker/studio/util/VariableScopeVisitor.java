package com.botmaker.studio.util;

import org.eclipse.jdt.core.dom.*;

import java.util.*;

public class VariableScopeVisitor extends ASTVisitor {

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private final Deque<Map<String, IVariableBinding>> variableStack = new ArrayDeque<>();
    private final Deque<Map<String, IMethodBinding>>   methodStack   = new ArrayDeque<>();
    private final Deque<Map<String, ITypeBinding>>     typeStack     = new ArrayDeque<>();

    private final Map<ASTNode, List<IVariableBinding>> variableResult = new LinkedHashMap<>();
    private final Map<ASTNode, List<IMethodBinding>>   methodResult   = new LinkedHashMap<>();
    private final Map<ASTNode, List<ITypeBinding>>     typeResult     = new LinkedHashMap<>();

    /**
     * What the three stacks hold now, shared by every node visited until a scope opens, closes or declares
     * something; null once one has. Nodes outnumber scope changes many times over, so a walk copies the
     * stacks once per change rather than once per node.
     */
    private NodeScope current;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Full-file analysis — returns a holder with all three maps. */
    public static ScopeResult analyze(CompilationUnit cu) {
        VariableScopeVisitor v = new VariableScopeVisitor();
        cu.accept(v);
        return new ScopeResult(
                Collections.unmodifiableMap(v.variableResult),
                Collections.unmodifiableMap(v.methodResult),
                Collections.unmodifiableMap(v.typeResult)
        );
    }

    /** Variables in scope at the given node. */
    public static List<IVariableBinding> getAvailableVariables(ASTNode node) {
        return getScopeAt(node).variables();
    }

    /** Methods in scope at the given node. */
    public static List<IMethodBinding> getAvailableMethods(ASTNode node) {
        return getScopeAt(node).methods();
    }

    /** Types in scope at the given node. */
    public static List<ITypeBinding> getAvailableTypes(ASTNode node) {
        return getScopeAt(node).types();
    }

    /**
     * Variables in scope at the end of {@code block} — where a statement appended to it lands, so it sees
     * every local the block declares. {@link #getAvailableVariables(ASTNode)} on the block answers for its
     * start, which is right for nothing inserted into it.
     */
    public static List<IVariableBinding> getAvailableVariablesAtEnd(Block block) {
        if (block.statements().isEmpty() || !(block.getRoot() instanceof CompilationUnit cu)) {
            return getAvailableVariables(block);
        }
        NodeScope atEnd = Index.of(cu).atEnd.get(block);
        return atEnd != null ? atEnd.variables() : List.of();
    }

    /** All three scopes at {@code node}, from one lookup — what the three getters answer one at a time. */
    public static NodeScope getAvailableScopes(ASTNode node) {
        return getScopeAt(node);
    }

    public static List<IVariableBinding> getAvailableVariables(ASTNode node, ITypeBinding type) {
        return getAvailableVariables(node).stream()
                .filter(b -> b.getType().isAssignmentCompatible(type))
                .toList();
    }

    public static List<IMethodBinding> getAvailableMethods(ASTNode node, ITypeBinding returnType) {
        return getAvailableMethods(node).stream()
                .filter(b -> b.getReturnType().isAssignmentCompatible(returnType))
                .toList();
    }

    /** Result holder — all three scopes for a given node. */
    public record NodeScope(
            List<IVariableBinding> variables,
            List<IMethodBinding>   methods,
            List<ITypeBinding>     types
    ) {}

    /** All three maps from a full-file analysis. */
    public record ScopeResult(
            Map<ASTNode, List<IVariableBinding>> variables,
            Map<ASTNode, List<IMethodBinding>>   methods,
            Map<ASTNode, List<ITypeBinding>>     types
    ) {
        public NodeScope at(ASTNode node) {
            return new NodeScope(
                    variables.getOrDefault(node, List.of()),
                    methods.getOrDefault(node, List.of()),
                    types.getOrDefault(node, List.of())
            );
        }
    }

    // -----------------------------------------------------------------------
    // Scope boundaries — push on enter, pop on leave
    // -----------------------------------------------------------------------
    @Override
    public boolean visit(CompilationUnit node) {
        // Imports live above all type scopes — push a permanent bottom-level scope
        pushAll();
        return true;
    }

    @Override
    public void endVisit(CompilationUnit node) { popAll(); }

    @Override
    public boolean visit(ImportDeclaration node) {
        current = null;
        if (node.isStatic()) {
            IBinding b = node.resolveBinding();
            if (b instanceof IMethodBinding mb && !methodStack.isEmpty())
                methodStack.peek().put(methodKey(mb), mb);
        } else {
            IBinding b = node.resolveBinding();
            if (b instanceof ITypeBinding tb && !typeStack.isEmpty())
                typeStack.peek().put(tb.getName(), tb);
        }
        return false;
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        pushAll();

        // Register the type itself first — so it's visible to everything inside it
        ITypeBinding self = node.resolveBinding();
        if (self != null) typeStack.peek().put(self.getName(), self);

        // Inner types
        for (Object body : node.bodyDeclarations())
            if (body instanceof AbstractTypeDeclaration atd) {
                ITypeBinding tb = atd.resolveBinding();
                if (tb != null) typeStack.peek().put(tb.getName(), tb);
            }

        // Methods declared here (visible regardless of order)
        for (MethodDeclaration m : node.getMethods()) {
            IMethodBinding mb = m.resolveBinding();
            if (mb != null) methodStack.peek().put(methodKey(mb), mb);
        }

        // Fields (visible throughout the class regardless of declaration order)
        for (FieldDeclaration f : node.getFields())
            for (Object frag : f.fragments())
                if (frag instanceof VariableDeclarationFragment vdf) {
                    IVariableBinding vb = (IVariableBinding) vdf.getName().resolveBinding();
                    if (vb != null) variableStack.peek().put(vdf.getName().getIdentifier(), vb);
                }

        // Inherited members
        ITypeBinding typeBinding = node.resolveBinding();
        if (typeBinding != null) {
            collectInheritedMethods(typeBinding.getSuperclass());
            for (ITypeBinding iface : typeBinding.getInterfaces())
                collectInheritedMethods(iface);
        }

        return true;
    }



    @Override public void endVisit(TypeDeclaration node) { popAll(); }

    @Override
    public boolean visit(AnonymousClassDeclaration node) {
        pushAll();
        for (Object decl : node.bodyDeclarations()) {
            if (decl instanceof MethodDeclaration m) {
                IMethodBinding mb = m.resolveBinding();
                if (mb != null) methodStack.peek().put(methodKey(mb), mb);
            }
        }
        ITypeBinding tb = node.resolveBinding();
        if (tb != null && tb.getSuperclass() != null)
            collectInheritedMethods(tb.getSuperclass());
        return true;
    }

    @Override public void endVisit(AnonymousClassDeclaration node) { popAll(); }

    @Override public boolean visit(MethodDeclaration node) { pushAll(); return true; }
    @Override public void endVisit(MethodDeclaration node) { popAll(); }

    @Override public boolean visit(LambdaExpression node)  { pushAll(); return true; }
    @Override public void endVisit(LambdaExpression node)  { popAll(); }

    @Override public boolean visit(Block node)             { pushAll(); return true; }
    @Override public void endVisit(Block node)             { popAll(); }

    @Override public boolean visit(SwitchStatement node)   { pushAll(); return true; }
    @Override public void endVisit(SwitchStatement node)   { popAll(); }

    @Override public boolean visit(CatchClause node)       { pushAll(); return true; }
    @Override public void endVisit(CatchClause node)       { popAll(); }

    // A loop's own variable and a try's resources end with the statement, not with the enclosing block: without
    // these, `for (int i …)` left `i` offered after the loop, and a pick of it did not compile.
    @Override public boolean visit(ForStatement node)          { pushAll(); return true; }
    @Override public void endVisit(ForStatement node)          { popAll(); }

    @Override public boolean visit(EnhancedForStatement node)  { pushAll(); return true; }
    @Override public void endVisit(EnhancedForStatement node)  { popAll(); }

    @Override public boolean visit(TryStatement node)          { pushAll(); return true; }
    @Override public void endVisit(TryStatement node)          { popAll(); }

    // -----------------------------------------------------------------------
    // Declarations — endVisit so RHS doesn't see its own symbol
    // -----------------------------------------------------------------------

    @Override
    public void endVisit(VariableDeclarationFragment node) {
        declareVariable(node.getName());
    }

    @Override
    public void endVisit(SingleVariableDeclaration node) {
        declareVariable(node.getName());
    }

    @Override
    public void endVisit(TypeDeclarationStatement node) {
        // Local class declared inside a block
        current = null;
        ITypeBinding tb = node.getDeclaration().resolveBinding();
        if (tb != null && !typeStack.isEmpty())
            typeStack.peek().put(tb.getName(), tb);
    }

    // -----------------------------------------------------------------------
    // Expressions of interest — snapshot all three stacks
    // -----------------------------------------------------------------------

    @Override
    public boolean visit(SimpleName node) {
        if (node.resolveBinding() instanceof IVariableBinding) record(node);
        return true;
    }

    @Override public boolean visit(MethodInvocation node)      { record(node); return true; }
    @Override public boolean visit(Assignment node)            { record(node); return true; }
    @Override public boolean visit(ClassInstanceCreation node) { record(node); return true; }
    @Override public boolean visit(ConditionalExpression node) { record(node); return true; }

    // -----------------------------------------------------------------------
    // Stack helpers
    // -----------------------------------------------------------------------

    // Every put into a stack follows a pushAll in the same visit, or goes through declareVariable or one of the
    // two other declaring visits, which mark the snapshot stale themselves.
    private void pushAll() {
        current = null;
        variableStack.push(new LinkedHashMap<>());
        methodStack.push(new LinkedHashMap<>());
        typeStack.push(new LinkedHashMap<>());
    }

    private void popAll() {
        current = null;
        if (!variableStack.isEmpty()) variableStack.pop();
        if (!methodStack.isEmpty())   methodStack.pop();
        if (!typeStack.isEmpty())     typeStack.pop();
    }


    @Override
    public boolean visit(RecordDeclaration node) {
        pushAll();
        ITypeBinding self = node.resolveBinding();
        if (self != null) {
            assert typeStack.peek() != null;
            typeStack.peek().put(self.getName(), self);
        }

        for (MethodDeclaration m : node.getMethods()) {
            IMethodBinding mb = m.resolveBinding();
            if (mb != null) {
                assert methodStack.peek() != null;
                methodStack.peek().put(methodKey(mb), mb);
            }
        }
        // record components are the constructor params — treat as fields
        for (Object comp : node.recordComponents())
            if (comp instanceof SingleVariableDeclaration svd) {
                IVariableBinding vb = (IVariableBinding) svd.getName().resolveBinding();
                if (vb != null) {
                    assert variableStack.peek() != null;
                    variableStack.peek().put(svd.getName().getIdentifier(), vb);
                }
            }

        ITypeBinding tb = node.resolveBinding();
        if (tb != null) collectInheritedMethods(tb.getSuperclass());

        return true;
    }

    @Override public void endVisit(RecordDeclaration node) { popAll(); }

    private void declareVariable(SimpleName name) {
        if (variableStack.isEmpty()) return;
        current = null;
        IBinding b = name.resolveBinding();
        if (b instanceof IVariableBinding vb)
            variableStack.peek().put(name.getIdentifier(), vb);
    }

    /**
     * A supertype's methods, stopping at {@code Object}: {@code clone}, {@code finalize} and {@code wait} are
     * nothing a bot calls, and being first in the list made them what "Call Function" wrote into {@code main}.
     */
    private void collectInheritedMethods(ITypeBinding type) {
        if (type == null || methodStack.isEmpty()) return;
        if ("java.lang.Object".equals(type.getQualifiedName())) return;
        for (IMethodBinding m : type.getDeclaredMethods())
            methodStack.peek().put(methodKey(m), m);
        collectInheritedMethods(type.getSuperclass());
    }

    protected void record(ASTNode node) {
        NodeScope scope = scope();
        variableResult.put(node, scope.variables());
        methodResult.put(node, scope.methods());
        typeResult.put(node, scope.types());
    }

    /** What is in scope now; rebuilt only after the stacks changed. */
    protected NodeScope scope() {
        if (current == null) current = new NodeScope(snapshotVariables(), snapshotMethods(), snapshotTypes());
        return current;
    }

    protected List<IVariableBinding> snapshotVariables() {
        return variableStack.stream()
                .flatMap(s -> s.values().stream())
                .filter(Objects::nonNull)
                .toList();
    }

    protected List<IMethodBinding> snapshotMethods() {
        return methodStack.stream()
                .flatMap(s -> s.values().stream())
                .filter(Objects::nonNull)
                .toList();
    }

    protected List<ITypeBinding> snapshotTypes() {
        return typeStack.stream()
                .flatMap(s -> s.values().stream())
                .filter(Objects::nonNull)
                .toList();
    }

    /** Unique key for a method: name + param types (handles overloads). */
    private static String methodKey(IMethodBinding m) {
        return m.getName() + Arrays.stream(m.getParameterTypes())
                .map(ITypeBinding::getName)
                .reduce("(", (a, b) -> a + (a.equals("(") ? "" : ",") + b) + ")";
    }

    // -----------------------------------------------------------------------
    // Per-node lookup — one walk per parse, kept on the parse
    // -----------------------------------------------------------------------

    private static final NodeScope EMPTY_SCOPE = new NodeScope(List.of(), List.of(), List.of());

    /**
     * The scope at every node of one parse. A block asks for its scope several times while it is drawn, and a
     * file draws hundreds of blocks; walking the whole file for each question was most of the FX thread's time
     * on a big constants file (profiled 2026-10-18).
     *
     * <p>Kept as a property of the {@link CompilationUnit} rather than in a weak map: the index holds the
     * tree's nodes, which hold their root, so a weak map's value would keep its own key alive. It answers only
     * while the tree's {@code modificationCount} is the one it was built at — a tree edited in place is walked
     * again rather than read stale. {@code setProperty} does not move that count.
     */
    private static final class Index extends VariableScopeVisitor {

        private static final String PROPERTY = Index.class.getName();

        private final long modifications;
        private final Map<ASTNode, NodeScope> at = new IdentityHashMap<>();
        /** A block → what is in scope after its last statement: its end, where an appended statement lands. */
        private final Map<ASTNode, NodeScope> atEnd = new IdentityHashMap<>();

        private Index(long modifications) {
            this.modifications = modifications;
        }

        static Index of(CompilationUnit cu) {
            synchronized (cu) {
                long modifications = cu.getAST().modificationCount();
                if (cu.getProperty(PROPERTY) instanceof Index kept && kept.modifications == modifications) return kept;
                Index built = new Index(modifications);
                cu.accept(built);
                cu.setProperty(PROPERTY, built);
                return built;
            }
        }

        // preVisit(node) fires after every ancestor scope has been pushed and after the endVisit of any
        // preceding-sibling declaration, so the snapshot is exactly what is in scope at the node (and excludes a
        // variable from its own initializer) — for every node kind, literals, placeholders and statements too.
        @Override
        public void preVisit(ASTNode node) {
            at.put(node, scope());
        }

        // postVisit runs after the statement's endVisit has declared its locals, and before the block's endVisit
        // pops them.
        @Override
        public void postVisit(ASTNode node) {
            if (node.getParent() instanceof Block block && block.statements().getLast() == node) {
                atEnd.put(block, scope());
            }
        }

        // The per-kind maps are analyze()'s; the index answers for every node already.
        @Override
        protected void record(ASTNode node) {}
    }

    private static NodeScope getScopeAt(ASTNode node) {
        if (!(node.getRoot() instanceof CompilationUnit cu)) return EMPTY_SCOPE;
        NodeScope scope = Index.of(cu).at.getOrDefault(node, EMPTY_SCOPE);

        // A declaration's own variable is not in scope inside its initializer. Locals are already excluded
        // (declared on endVisit), but fields are pre-registered for the whole type, so drop the enclosing
        // declaration's own name explicitly.
        String selfName = enclosingDeclaredName(node);
        if (selfName != null && !scope.variables().isEmpty()) {
            List<IVariableBinding> filtered = scope.variables().stream()
                    .filter(v -> !selfName.equals(v.getName()))
                    .toList();
            scope = new NodeScope(filtered, scope.methods(), scope.types());
        }
        return scope;
    }

    /** Name declared by the nearest enclosing variable/field declaration (or {@code null} if none on the path). */
    private static String enclosingDeclaredName(ASTNode node) {
        ASTNode cur = node;
        while (cur != null) {
            if (cur instanceof VariableDeclarationFragment f) return f.getName().getIdentifier();
            if (cur instanceof SingleVariableDeclaration s) return s.getName().getIdentifier();
            if (cur instanceof Statement || cur instanceof BodyDeclaration) break;
            cur = cur.getParent();
        }
        return null;
    }
}
