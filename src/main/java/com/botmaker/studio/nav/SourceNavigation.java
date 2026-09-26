package com.botmaker.studio.nav;

import com.botmaker.studio.core.CodeBlock;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What the Navigate menu reads off a file's syntax tree (2026-09-26): the node on a line, a file's structure,
 * what a block names and where that is declared, and the sentence Quick Documentation shows. Pure — no FX,
 * no project services — so the popups that use it stay thin and every answer here is testable headless.
 */
public final class SourceNavigation {

    private SourceNavigation() {}

    // --- lines -------------------------------------------------------------------------------------------------

    /**
     * The statement or declaration the canvas draws for {@code line} (1-based): the outermost one that starts
     * there, else the innermost one that spans it; empty past the file or on a line nothing covers.
     */
    public static Optional<ASTNode> nodeAtLine(CompilationUnit cu, int line) {
        if (cu == null || line < 1) return Optional.empty();
        ASTNode[] starting = {null};
        ASTNode[] spanning = {null};
        cu.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (!(node instanceof Statement) && !(node instanceof BodyDeclaration)) return;
                int first = cu.getLineNumber(node.getStartPosition());
                int last = cu.getLineNumber(node.getStartPosition() + Math.max(0, node.getLength() - 1));
                if (first == line && starting[0] == null) starting[0] = node;
                if (first <= line && line <= last) spanning[0] = node;   // pre-order: the last hit is innermost
            }
        });
        return Optional.ofNullable(starting[0] != null ? starting[0] : spanning[0]);
    }

    /** The block drawn for {@code node} or, failing that, for the nearest ancestor that has one. */
    public static Optional<CodeBlock> blockFor(ASTNode node, Map<ASTNode, CodeBlock> nodeToBlock) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            CodeBlock block = nodeToBlock.get(n);
            if (block != null) return Optional.of(block);
        }
        return Optional.empty();
    }

    // --- structure ---------------------------------------------------------------------------------------------

    /** One row of File Structure. */
    public record Entry(StructureKind kind, String name, String detail, int depth, ASTNode node) {
        /** What the list shows and what typing filters on. */
        public String label() {
            return detail.isEmpty() ? name : name + detail;
        }
    }

    /** Every type, field, constructor, method and enum constant in {@code cu}, in source order, nested by depth. */
    public static List<Entry> structure(CompilationUnit cu) {
        List<Entry> out = new ArrayList<>();
        if (cu == null) return out;
        for (Object type : cu.types()) addType((AbstractTypeDeclaration) type, 0, out);
        return out;
    }

    private static void addType(AbstractTypeDeclaration type, int depth, List<Entry> out) {
        out.add(new Entry(StructureKind.TYPE, type.getName().getIdentifier(), "", depth, type));
        if (type instanceof org.eclipse.jdt.core.dom.EnumDeclaration e) {
            for (Object c : e.enumConstants()) {
                EnumConstantDeclaration constant = (EnumConstantDeclaration) c;
                out.add(new Entry(StructureKind.ENUM_CONSTANT, constant.getName().getIdentifier(), "", depth + 1,
                        constant));
            }
        }
        for (Object d : type.bodyDeclarations()) {
            switch (d) {
                case AbstractTypeDeclaration inner -> addType(inner, depth + 1, out);
                case FieldDeclaration field -> {
                    for (Object f : field.fragments()) {
                        VariableDeclarationFragment fragment = (VariableDeclarationFragment) f;
                        out.add(new Entry(StructureKind.FIELD, fragment.getName().getIdentifier(),
                                ": " + field.getType(), depth + 1, fragment));
                    }
                }
                case MethodDeclaration method -> out.add(new Entry(
                        method.isConstructor() ? StructureKind.CONSTRUCTOR : StructureKind.METHOD,
                        method.getName().getIdentifier(), signatureDetail(method), depth + 1, method));
                default -> { }
            }
        }
    }

    /** {@code (int, String): boolean} — the parameter types and, for a method, the return type. */
    private static String signatureDetail(MethodDeclaration method) {
        List<String> types = new ArrayList<>();
        for (Object p : method.parameters()) types.add(((SingleVariableDeclaration) p).getType().toString());
        Type returned = method.getReturnType2();
        return "(" + String.join(", ", types) + ")"
                + (method.isConstructor() || returned == null ? "" : ": " + returned);
    }

    // --- what a block names ------------------------------------------------------------------------------------

    /**
     * The binding the node is about: a call's method, a name's variable/method/type, a declaration's own. For a
     * statement, what its first expression names — an expression statement's call, a declaration's variable.
     */
    public static Optional<IBinding> bindingOf(ASTNode node) {
        if (node == null) return Optional.empty();
        IBinding binding = switch (node) {
            case ExpressionStatement s -> bindingOf(s.getExpression()).orElse(null);
            case VariableDeclarationStatement s -> s.fragments().isEmpty() ? null
                    : ((VariableDeclarationFragment) s.fragments().getFirst()).resolveBinding();
            case VariableDeclarationFragment f -> f.resolveBinding();
            case SingleVariableDeclaration v -> v.resolveBinding();
            case MethodInvocation m -> m.resolveMethodBinding();
            case SuperMethodInvocation m -> m.resolveMethodBinding();
            case ClassInstanceCreation c -> c.resolveConstructorBinding();
            case MethodDeclaration m -> m.resolveBinding();
            case AbstractTypeDeclaration t -> t.resolveBinding();
            case Name n -> n.resolveBinding();
            case Expression e -> e.resolveTypeBinding();
            default -> null;
        };
        return Optional.ofNullable(binding);
    }

    /** Where a binding is declared: in this file (a node), in another of the bot's sources (a path), or neither. */
    public sealed interface Declaration {
        /** Declared in the file being read. */
        record Here(ASTNode node) implements Declaration {}

        /** Declared in another source of the bot; find it there with the binding's {@code key}. */
        record Elsewhere(Path file, String key) implements Declaration {}
    }

    /**
     * Where {@code binding} is declared, when that is the bot's own source. A library's member answers empty —
     * there is nothing of the user's to go to.
     */
    public static Optional<Declaration> declarationOf(IBinding binding, CompilationUnit cu, Path sourceRoot) {
        if (binding == null) return Optional.empty();
        ASTNode here = cu == null ? null : cu.findDeclaringNode(binding.getKey());
        if (here != null) return Optional.of(new Declaration.Here(here));
        ITypeBinding owner = topLevel(declaringType(binding));
        if (owner == null || sourceRoot == null) return Optional.empty();
        Path file = sourceRoot.resolve(owner.getErasure().getQualifiedName().replace('.', '/') + ".java");
        return Files.isRegularFile(file) ? Optional.of(new Declaration.Elsewhere(file, binding.getKey()))
                : Optional.empty();
    }

    private static ITypeBinding declaringType(IBinding binding) {
        return switch (binding) {
            case IMethodBinding m -> m.getDeclaringClass();
            case IVariableBinding v -> v.isField() ? v.getDeclaringClass() : null;
            case ITypeBinding t -> t;
            default -> null;
        };
    }

    private static ITypeBinding topLevel(ITypeBinding type) {
        ITypeBinding t = type;
        while (t != null && t.getDeclaringClass() != null) t = t.getDeclaringClass();
        return t;
    }

    // --- quick documentation -----------------------------------------------------------------------------------

    /** What Quick Documentation shows: one heading line, and prose under it (possibly empty). */
    public record Doc(String heading, String body) {}

    /**
     * The heading for {@code binding} — {@code int count} for a variable, {@code boolean has(String name)} for a
     * method, the qualified name for a type — plus where it lives, and the Javadoc when the declaration is
     * in {@code cu}. {@code libraryDoc} fills the body for a member the bot does not declare (the SDK's
     * sources-jar summary); null or blank means none.
     */
    public static Doc docOf(IBinding binding, CompilationUnit cu, String libraryDoc) {
        String heading = switch (binding) {
            case IMethodBinding m -> methodHeading(m);
            case IVariableBinding v -> simple(v.getType()) + " " + v.getName() + "  —  "
                    + (v.isField() ? "field of " + simple(v.getDeclaringClass())
                    : v.isParameter() ? "parameter" : "local variable");
            case ITypeBinding t -> (t.isEnum() ? "enum " : t.isInterface() ? "interface " : t.isRecord() ? "record "
                    : "class ") + t.getQualifiedName();
            default -> binding.getName();
        };
        String body = "";
        ASTNode declaration = cu == null ? null : cu.findDeclaringNode(binding.getKey());
        if (declaration instanceof BodyDeclaration d && d.getJavadoc() != null) body = javadocText(d.getJavadoc());
        else if (declaration instanceof VariableDeclarationFragment f && f.getParent() instanceof FieldDeclaration fd
                && fd.getJavadoc() != null) body = javadocText(fd.getJavadoc());
        if (body.isBlank() && libraryDoc != null) body = libraryDoc.trim();
        if (declaration != null && cu != null) {
            String at = "Declared on line " + cu.getLineNumber(namePosition(declaration)) + ".";
            body = body.isBlank() ? at : body + "\n\n" + at;
        }
        return new Doc(heading, body);
    }

    private static String methodHeading(IMethodBinding m) {
        List<String> params = new ArrayList<>();
        ITypeBinding[] types = m.getParameterTypes();
        for (int i = 0; i < types.length; i++) params.add(simple(types[i]));
        String owner = m.getDeclaringClass() == null ? "" : "  —  in " + simple(m.getDeclaringClass());
        return (m.isConstructor() ? "new " : simple(m.getReturnType()) + " ") + m.getName()
                + "(" + String.join(", ", params) + ")" + owner;
    }

    private static String simple(ITypeBinding type) {
        return type == null ? "?" : type.getName();
    }

    /**
     * The comment's prose: the description with inline tags reduced to their text and each {@code <p>} a new
     * line, then one line per block tag ({@code count — the rounds} for a {@code @param}).
     */
    static String javadocText(Javadoc doc) {
        StringBuilder out = new StringBuilder();
        for (Object t : doc.tags()) {
            TagElement tag = (TagElement) t;
            String text = fragments(tag.fragments()).trim();
            if (tag.getTagName() == null) {
                out.append(text);
            } else {
                if (!out.isEmpty()) out.append('\n');
                out.append(tag.getTagName().substring(1)).append(": ").append(text);
            }
        }
        return out.toString().replaceAll("[ \\t]+\n", "\n").replaceAll("[ \\t]{2,}", " ").trim();
    }

    private static String fragments(List<?> fragments) {
        StringBuilder out = new StringBuilder();
        for (Object f : fragments) {
            switch (f) {
                case TextElement text -> {
                    String s = text.getText();
                    boolean paragraph = s.trim().toLowerCase(Locale.ROOT).startsWith("<p>");
                    s = s.replaceAll("</?\\w+[^>]*>", "").trim();
                    if (paragraph && !out.isEmpty()) out.append('\n');
                    else if (!out.isEmpty()) out.append(' ');
                    out.append(s);
                }
                case TagElement inline -> out.append(out.isEmpty() ? "" : " ").append(fragments(inline.fragments()).trim());
                case ASTNode other -> out.append(out.isEmpty() ? "" : " ").append(other);
                default -> { }
            }
        }
        return out.toString();
    }

    /** Where a declaration's name is, which is the line a reader means — not its Javadoc's first line. */
    private static int namePosition(ASTNode declaration) {
        return switch (declaration) {
            case MethodDeclaration m -> m.getName().getStartPosition();
            case AbstractTypeDeclaration t -> t.getName().getStartPosition();
            case VariableDeclarationFragment f -> f.getName().getStartPosition();
            case SingleVariableDeclaration v -> v.getName().getStartPosition();
            case EnumConstantDeclaration c -> c.getName().getStartPosition();
            default -> declaration.getStartPosition();
        };
    }

    // --- filtering ---------------------------------------------------------------------------------------------

    /**
     * How well {@code query} matches {@code candidate}, higher is better, negative for no match. Case-insensitive;
     * a prefix beats a substring beats letters in order ({@code "gb"} finds {@code GameBot}), and a shorter
     * candidate beats a longer one at the same kind of match.
     */
    public static int match(String query, String candidate) {
        if (query == null || query.isBlank()) return 0;
        String q = query.toLowerCase(Locale.ROOT).trim();
        String c = candidate.toLowerCase(Locale.ROOT);
        int penalty = Math.min(candidate.length(), 99);
        if (c.startsWith(q)) return 3000 - penalty;
        int at = c.indexOf(q);
        if (at >= 0) return 2000 - penalty - Math.min(at, 99);
        int from = 0;
        for (char ch : q.toCharArray()) {
            from = c.indexOf(ch, from);
            if (from < 0) return -1;
            from++;
        }
        return 1000 - penalty;
    }
}
