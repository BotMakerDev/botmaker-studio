package com.botmaker.studio.project.vcs;

import com.botmaker.studio.parser.helpers.SourceParser;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.ASTMatcher;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.LabeledStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.SynchronizedStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.WhileStatement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Two versions of one Java file compared the way the canvas draws them — function by function, block by block
 * ({@code docs/refactor/39-versions.md} §5). Pure: no JavaFX, no bindings, no project; two strings in, a
 * {@link FileDiff} out.
 *
 * <ul>
 *   <li><b>Functions</b> are matched by signature — the enclosing type, the name and the parameter types as
 *       written. One only on one side is added or removed; a rename is therefore one of each.</li>
 *   <li><b>Statements</b> are aligned by longest common subsequence over each body, equal when JDT's
 *       {@link ASTMatcher} says the subtrees match (so formatting and comments do not count). An unmatched
 *       pair at the same place is <i>changed</i> — unless both are the same compound statement with the same
 *       header, which is then compared <b>inside</b>, so {@code wait 500 → 200} in a loop marks one block and
 *       not the loop.</li>
 *   <li><b>Fields</b> — a {@code @Param}, a picture constant — compare as rows: name, was, now.</li>
 * </ul>
 *
 * <p>Every mark is a source range ({@link Span}) of the side it belongs to. The canvas parses the same text,
 * so a span finds its block by position there, and nothing here has to know a block exists.
 */
public final class BlockDiff {

    private BlockDiff() {}

    private static final ASTMatcher MATCHER = new ASTMatcher(false);

    /** How one block differs. */
    public enum Mark { ADDED, REMOVED, CHANGED }

    /** A marked statement: its range in its own side's source. */
    public record Span(int start, int length, Mark mark) {}

    /**
     * One function that differs.
     *
     * @param signature   {@code Type#name(int, String)} — the key both sides are matched on
     * @param beforeStart where the declaration starts in the old source, or -1 when it has none
     * @param afterStart  the same in the new source
     * @param header      whether anything but the body changed (modifiers, return type, parameters, Javadoc)
     */
    public record MethodChange(String signature, String name, Mark kind, int beforeStart, int afterStart,
                               List<Span> before, List<Span> after, boolean header) {

        public MethodChange {
            before = List.copyOf(before);
            after = List.copyOf(after);
        }
    }

    /**
     * One field that differs; {@code was} is null when it is new, {@code now} when it is gone.
     *
     * @param parameter whether it is a {@code @Param} — the Parameters window's row rather than a constant
     */
    public record FieldChange(String name, String was, String now, boolean parameter) {}

    /**
     * The whole comparison.
     *
     * @param unchanged how many functions both sides have alike — the "4 functions unchanged" fold
     * @param problem   why the file could not be compared as blocks, or null; the caller then shows its text
     */
    public record FileDiff(List<MethodChange> methods, List<FieldChange> fields, int unchanged, String problem) {

        public FileDiff {
            methods = List.copyOf(methods);
            fields = List.copyOf(fields);
        }

        public boolean readable() {
            return problem == null;
        }

        static FileDiff unreadable(String why) {
            return new FileDiff(List.of(), List.of(), 0, why);
        }
    }

    /** Compares {@code before} with {@code after}; either is null when the file does not exist on that side. */
    public static FileDiff of(String before, String after) {
        CompilationUnit a = before == null ? null : SourceParser.parse(before);
        CompilationUnit b = after == null ? null : SourceParser.parse(after);
        String why = unparseable(a, "before");
        if (why == null) why = unparseable(b, "after");
        if (why != null) return FileDiff.unreadable(why);

        Map<String, MethodDeclaration> olds = a == null ? Map.of() : methods(a);
        Map<String, MethodDeclaration> news = b == null ? Map.of() : methods(b);
        List<MethodChange> changes = new ArrayList<>();
        int unchanged = 0;
        for (var e : olds.entrySet()) {
            MethodDeclaration old = e.getValue();
            MethodDeclaration now = news.get(e.getKey());
            if (now == null) {
                changes.add(new MethodChange(e.getKey(), name(old), Mark.REMOVED, old.getStartPosition(), -1,
                        List.of(), List.of(), false));
            } else if (old.subtreeMatch(MATCHER, now)) {
                unchanged++;
            } else {
                changes.add(changed(e.getKey(), old, now));
            }
        }
        for (var e : news.entrySet()) {
            if (olds.containsKey(e.getKey())) continue;
            MethodDeclaration now = e.getValue();
            changes.add(new MethodChange(e.getKey(), name(now), Mark.ADDED, -1, now.getStartPosition(),
                    List.of(), List.of(), false));
        }
        return new FileDiff(changes, fields(before, a, after, b), unchanged, null);
    }

    private static String unparseable(CompilationUnit cu, String side) {
        if (cu == null) return null;
        IProblem problem = SourceParser.firstSyntaxError(cu);
        return problem == null ? null
                : "The version " + side + " does not parse (line " + problem.getSourceLineNumber() + ": "
                        + problem.getMessage() + "), so it is compared as text.";
    }

    private static MethodChange changed(String signature, MethodDeclaration old, MethodDeclaration now) {
        List<Span> before = new ArrayList<>();
        List<Span> after = new ArrayList<>();
        boolean header = !sameHeader(old, now);
        if (old.getBody() != null && now.getBody() != null) {
            statements(old.getBody().statements(), now.getBody().statements(), before, after);
        } else if (old.getBody() != now.getBody()) {
            header = true;
        }
        // Bodies alike and nothing a statement shows: what changed is the header (or its Javadoc).
        if (before.isEmpty() && after.isEmpty()) header = true;
        return new MethodChange(signature, name(now), Mark.CHANGED, old.getStartPosition(), now.getStartPosition(),
                before, after, header);
    }

    private static boolean sameHeader(MethodDeclaration a, MethodDeclaration b) {
        return MATCHER.safeSubtreeListMatch(a.modifiers(), b.modifiers())
                && MATCHER.safeSubtreeMatch(a.getReturnType2(), b.getReturnType2())
                && MATCHER.safeSubtreeListMatch(a.parameters(), b.parameters())
                && MATCHER.safeSubtreeListMatch(a.thrownExceptionTypes(), b.thrownExceptionTypes())
                && MATCHER.safeSubtreeListMatch(a.typeParameters(), b.typeParameters())
                && MATCHER.safeSubtreeMatch(a.getJavadoc(), b.getJavadoc());
    }

    // -------------------------------------------------------------------------
    // Statements
    // -------------------------------------------------------------------------

    private enum Op { SAME, DEL, INS }

    /** Aligns two statement lists and marks what differs, recursing into a compound pair with one header. */
    private static void statements(List<?> olds, List<?> news, List<Span> before, List<Span> after) {
        int n = olds.size();
        int m = news.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = equal(olds.get(i), news.get(j))
                        ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Statement> dels = new ArrayList<>();
        List<Statement> ins = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            Op op;
            if (i < n && j < m && equal(olds.get(i), news.get(j))) op = Op.SAME;
            else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) op = Op.INS;
            else op = Op.DEL;
            switch (op) {
                case SAME -> {
                    flush(dels, ins, before, after);
                    i++;
                    j++;
                }
                case DEL -> dels.add((Statement) olds.get(i++));
                case INS -> ins.add((Statement) news.get(j++));
            }
        }
        flush(dels, ins, before, after);
    }

    /** One run of unmatched statements between two matched ones: paired by position, the rest added/removed. */
    private static void flush(List<Statement> dels, List<Statement> ins, List<Span> before, List<Span> after) {
        int paired = Math.min(dels.size(), ins.size());
        for (int k = 0; k < paired; k++) pair(dels.get(k), ins.get(k), before, after);
        for (int k = paired; k < dels.size(); k++) before.add(span(dels.get(k), Mark.REMOVED));
        for (int k = paired; k < ins.size(); k++) after.add(span(ins.get(k), Mark.ADDED));
        dels.clear();
        ins.clear();
    }

    private static void pair(Statement old, Statement now, List<Span> before, List<Span> after) {
        List<List<?>> oldBodies = bodies(old, now);
        List<List<?>> newBodies = oldBodies == null ? null : bodies(now, old);
        if (oldBodies != null && newBodies != null && oldBodies.size() == newBodies.size()) {
            for (int k = 0; k < oldBodies.size(); k++) {
                statements(oldBodies.get(k), newBodies.get(k), before, after);
            }
            return;
        }
        before.add(span(old, Mark.CHANGED));
        after.add(span(now, Mark.CHANGED));
    }

    /**
     * The bodies of {@code s}, each as its statement list, when it and {@code other} are the same compound
     * statement with the same header — everything but the bodies alike — else null. A missing {@code else} or
     * {@code finally} is a body the other side lacks, so the sizes differ and the pair reads as changed. A
     * switch is one body: its {@code case} labels are statements in JDT, and align as such.
     */
    private static List<List<?>> bodies(Statement s, Statement other) {
        if (s.getNodeType() != other.getNodeType()) return null;
        List<List<?>> out = new ArrayList<>();
        switch (s) {
            case Block b -> out.add(b.statements());
            case IfStatement x when MATCHER.safeSubtreeMatch(x.getExpression(), ((IfStatement) other).getExpression()) -> {
                out.add(list(x.getThenStatement()));
                if (x.getElseStatement() != null) out.add(list(x.getElseStatement()));
            }
            case WhileStatement x when MATCHER.safeSubtreeMatch(x.getExpression(), ((WhileStatement) other).getExpression()) ->
                    out.add(list(x.getBody()));
            case DoStatement x when MATCHER.safeSubtreeMatch(x.getExpression(), ((DoStatement) other).getExpression()) ->
                    out.add(list(x.getBody()));
            case ForStatement x when forHeader(x, (ForStatement) other) -> out.add(list(x.getBody()));
            case EnhancedForStatement x when MATCHER.safeSubtreeMatch(x.getParameter(), ((EnhancedForStatement) other).getParameter())
                    && MATCHER.safeSubtreeMatch(x.getExpression(), ((EnhancedForStatement) other).getExpression()) ->
                    out.add(list(x.getBody()));
            case SynchronizedStatement x when MATCHER.safeSubtreeMatch(x.getExpression(), ((SynchronizedStatement) other).getExpression()) ->
                    out.add(list(x.getBody()));
            case LabeledStatement x when MATCHER.safeSubtreeMatch(x.getLabel(), ((LabeledStatement) other).getLabel()) ->
                    out.add(list(x.getBody()));
            case SwitchStatement x when MATCHER.safeSubtreeMatch(x.getExpression(), ((SwitchStatement) other).getExpression()) ->
                    out.add(x.statements());
            case TryStatement x when tryHeader(x, (TryStatement) other) -> {
                out.add(list(x.getBody()));
                for (Object c : x.catchClauses()) out.add(list(((CatchClause) c).getBody()));
                if (x.getFinally() != null) out.add(list(x.getFinally()));
            }
            default -> {
                return null;
            }
        }
        return out;
    }

    private static boolean forHeader(ForStatement a, ForStatement b) {
        return MATCHER.safeSubtreeListMatch(a.initializers(), b.initializers())
                && MATCHER.safeSubtreeMatch(a.getExpression(), b.getExpression())
                && MATCHER.safeSubtreeListMatch(a.updaters(), b.updaters());
    }

    private static boolean tryHeader(TryStatement a, TryStatement b) {
        if (!MATCHER.safeSubtreeListMatch(a.resources(), b.resources())) return false;
        if (a.catchClauses().size() != b.catchClauses().size()) return false;
        for (int k = 0; k < a.catchClauses().size(); k++) {
            if (!MATCHER.safeSubtreeMatch(((CatchClause) a.catchClauses().get(k)).getException(),
                    ((CatchClause) b.catchClauses().get(k)).getException())) return false;
        }
        return (a.getFinally() == null) == (b.getFinally() == null);
    }

    private static List<?> list(Statement s) {
        return s instanceof Block b ? b.statements() : List.of(s);
    }

    private static boolean equal(Object a, Object b) {
        return ((ASTNode) a).subtreeMatch(MATCHER, b);
    }

    private static Span span(ASTNode node, Mark mark) {
        return new Span(node.getStartPosition(), node.getLength(), mark);
    }

    // -------------------------------------------------------------------------
    // Members
    // -------------------------------------------------------------------------

    /**
     * Every method and constructor of {@code cu}, in source order, keyed by {@link #signature} — nested types
     * included, anonymous classes not. Public because the restore that puts one back matches on the same key.
     */
    public static Map<String, MethodDeclaration> methods(CompilationUnit cu) {
        Map<String, MethodDeclaration> out = new LinkedHashMap<>();
        for (Object t : cu.types()) collect((AbstractTypeDeclaration) t, "", out);
        return out;
    }

    private static void collect(AbstractTypeDeclaration type, String outer, Map<String, MethodDeclaration> out) {
        String path = outer.isEmpty() ? type.getName().getIdentifier() : outer + "." + type.getName().getIdentifier();
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof MethodDeclaration m) out.putIfAbsent(signature(path, m), m);
            else if (member instanceof AbstractTypeDeclaration nested) collect(nested, path, out);
        }
    }

    /** {@code Outer.Inner#name(int, String...)}: what makes two declarations the same function. */
    public static String signature(MethodDeclaration m) {
        StringBuilder path = new StringBuilder();
        for (ASTNode n = m.getParent(); n instanceof AbstractTypeDeclaration t; n = n.getParent()) {
            path.insert(0, path.isEmpty() ? t.getName().getIdentifier() : t.getName().getIdentifier() + ".");
        }
        return signature(path.toString(), m);
    }

    private static String signature(String typePath, MethodDeclaration m) {
        List<String> params = new ArrayList<>();
        for (Object p : m.parameters()) {
            SingleVariableDeclaration v = (SingleVariableDeclaration) p;
            params.add(v.getType() + (v.isVarargs() ? "..." : ""));
        }
        return typePath + "#" + m.getName().getIdentifier() + "(" + String.join(", ", params) + ")";
    }

    private static String name(MethodDeclaration m) {
        return m.getName().getIdentifier();
    }

    private record Field(String type, String initializer, boolean parameter) {}

    private static List<FieldChange> fields(String before, CompilationUnit a, String after, CompilationUnit b) {
        Map<String, Field> olds = a == null ? Map.of() : fields(before, a);
        Map<String, Field> news = b == null ? Map.of() : fields(after, b);
        List<FieldChange> out = new ArrayList<>();
        for (var e : olds.entrySet()) {
            Field now = news.get(e.getKey());
            if (!e.getValue().equals(now)) {
                out.add(new FieldChange(e.getKey(), shown(e.getValue(), now), now == null ? null : shown(now, e.getValue()),
                        e.getValue().parameter() || (now != null && now.parameter())));
            }
        }
        for (var e : news.entrySet()) {
            if (!olds.containsKey(e.getKey())) {
                out.add(new FieldChange(e.getKey(), null, shown(e.getValue(), null), e.getValue().parameter()));
            }
        }
        return out;
    }

    /** The initializer as written, with the type in front only when the other side's differs. */
    private static String shown(Field f, Field other) {
        String value = f.initializer() == null ? "(no value)" : f.initializer();
        return other == null || Objects.equals(f.type(), other.type()) ? value : f.type() + " " + value;
    }

    private static Map<String, Field> fields(String source, CompilationUnit cu) {
        Map<String, Field> out = new LinkedHashMap<>();
        for (Object t : cu.types()) fields(source, (AbstractTypeDeclaration) t, "", out);
        return out;
    }

    private static void fields(String source, AbstractTypeDeclaration type, String outer, Map<String, Field> out) {
        String path = outer.isEmpty() ? type.getName().getIdentifier() : outer + "." + type.getName().getIdentifier();
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof FieldDeclaration f) {
                boolean param = f.modifiers().stream().anyMatch(mod -> mod instanceof Annotation an
                        && an.getTypeName().getFullyQualifiedName().endsWith("Param"));
                for (Object o : f.fragments()) {
                    VariableDeclarationFragment v = (VariableDeclarationFragment) o;
                    String init = v.getInitializer() == null ? null : text(source, v.getInitializer());
                    out.put(path + "." + v.getName().getIdentifier(), new Field(text(source, f.getType()), init, param));
                }
            } else if (member instanceof AbstractTypeDeclaration nested) {
                fields(source, nested, path, out);
            }
        }
    }

    private static String text(String source, ASTNode node) {
        return source.substring(node.getStartPosition(), node.getStartPosition() + node.getLength())
                .replaceAll("\\s+", " ");
    }
}
