package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.plugin.api.value.Visibility;
import com.botmaker.studio.plugin.grammar.JavaExpressions;
import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.source.BotAnnotation;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.project.source.ValueTypeResolver;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads {@code @Param} fields out of <b>one</b> Java source, with no project, no filesystem and no host.
 *
 * <p><b>Pure on purpose.</b> Everything about walking a project — buffers before files, which files are the
 * bot's, writing both copies back — belongs to {@link JavaParameters} and to {@code BotSources}, and none
 * of it is what goes wrong when a field is read incorrectly. Splitting the parse out is what lets the
 * reading be tested over source text, headlessly, without a project on disk.
 *
 * <p><b>Bindings when the caller has a project</b> ({@link BotParser}, 2026-09-24): {@code @Param} and its
 * constants are identified by class ({@link BotAnnotation#PARAM}), through the unit's imports when there is
 * no classpath, never by a name that ends in {@code Param}. A bot whose dependencies do not resolve still
 * shows its parameters: binding recovery keeps the tree, and the imports still say which annotation it is.
 * A field's type is resolved the same way, once, by {@link ValueTypeResolver}.
 */
public final class JavaParameterSource {

    private JavaParameterSource() {}

    /** Parses {@code source} without bindings — the one parser configuration this package uses. */
    public static CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion());
        parser.setCompilerOptions(options);
        return (CompilationUnit) parser.createAST(null);
    }

    /**
     * One expression parsed on its own, or {@code null} when the text is not one.
     *
     * <p>A real parser rather than a bracket-matching split, because reading a value means reading Java this
     * host did not necessarily write: {@code new Point(x + 1, f(2, 3))} is two arguments, and a comma count
     * says three. Public for the same reason {@link #parse} is — it is the one parser configuration for an
     * expression, and a second copy of it somewhere else is a second set of compiler options to keep in
     * step.
     */
    public static Expression expression(String source) {
        return JavaExpressions.parse(source);
    }

    /**
     * Every {@code @Param} field in {@code source}, in the order they are written.
     *
     * @param file    the path the rows are attributed to; may be {@code null} in a test
     * @param grammar the bound plugins' value grammar, which decides what a field's Java type <em>is</em>
     */
    public static List<JavaParameter> read(Path file, String source, ValueGrammar grammar) {
        return read(file, source, grammar, BotRecords.none());
    }

    /**
     * The same, told what the bot declares itself — the form a window reads, since a field may be typed with
     * one of the bot's own records.
     */
    public static List<JavaParameter> read(Path file, String source, ValueGrammar grammar,
                                           BotRecords records) {
        return read(file, source, grammar, records, BotParser.SYNTAX);
    }

    /**
     * The same, parsed by {@code parser} — the project's, so the annotation and its constants are the ones the
     * classpath declares.
     */
    public static List<JavaParameter> read(Path file, String source, ValueGrammar grammar,
                                           BotRecords records, BotParser parser) {
        List<JavaParameter> out = new ArrayList<>();
        parser.parse(file, source).accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration field) {
                Annotation annotation = paramAnnotation(field);
                if (annotation == null) return false;
                String className = enclosingTypeName(field);
                if (className == null) return false;
                for (Object each : field.fragments()) {
                    VariableDeclarationFragment fragment = (VariableDeclarationFragment) each;
                    out.add(read(file, source, grammar, records, className, field, annotation, fragment));
                }
                return false;
            }
        });
        return List.copyOf(out);
    }

    /** One fragment of one field — {@code @Param public static int a = 1, b = 2;} declares two. */
    private static JavaParameter read(Path file, String source, ValueGrammar grammar, BotRecords records,
                                      String className, FieldDeclaration field, Annotation annotation,
                                      VariableDeclarationFragment fragment) {
        Map<String, Object> members = BotAnnotation.PARAM.members(annotation);
        String name = fragment.getName().getIdentifier();
        String initializer = fragment.getInitializer() == null ? ""
                : text(source, fragment.getInitializer());

        java.lang.reflect.Type form = ValueTypeResolver.of(grammar, field.getType(), fragment.getExtraDimensions(),
                records.declaredNames());
        // The grammar declines a bot's own class by design — only the host's view of the bot's own source can
        // read one — so the second reader is asked for exactly that case and for no other.
        boolean readable = form instanceof ValueTypes.BotClass declared
                ? records.partsOf(declared, initializer).isPresent()
                : fragment.getInitializer() != null
                        && grammar.valueOf(form, new SourceNode(fragment.getInitializer(), source)).isPresent();

        String note = whyNotEditable(grammar, field, form, records, readable, initializer);
        // The row's value is the initialiser *as the author wrote it*, readable or not. A cell that cannot
        // be edited still has to show what the field holds, and the reading above decides only whether it
        // may be replaced.
        ParameterRow row = ParameterRow.named(name, ValueTypes.sourceName(form))
                .value(initializer)
                .description(string(members, "description"))
                .category(string(members, "category"))
                .visibility(Visibility.fromId(string(members, "visibility")))
                .options(strings(members, "options"))
                .bounds(number(members, "min", Double.NEGATIVE_INFINITY),
                        number(members, "max", Double.POSITIVE_INFINITY))
                .build();
        return new JavaParameter(file, className, row, form, initializer, note.isEmpty(), note);
    }

    /**
     * Why this field's value cell is read-only, or {@code ""} when it is not.
     *
     * <p>Each answer is a sentence a person can act on, because the alternative — a grey cell with no
     * reason — is the failure this whole design exists to avoid. A field is never <em>rejected</em>: it is
     * still listed, still shows what it holds, and still tells the author what to change.
     */
    private static String whyNotEditable(ValueGrammar grammar, FieldDeclaration field, java.lang.reflect.Type form,
                                         BotRecords records, boolean readable, String initializer) {
        int modifiers = field.getModifiers();
        if (!Modifier.isPublic(modifiers)) {
            return "not public: the bot can read it, the window cannot show it being changed";
        }
        if (!Modifier.isStatic(modifiers)) {
            return "not static: a parameter belongs to the bot, not to an instance of a class";
        }
        if (Modifier.isFinal(modifiers)) {
            return "final: its value is fixed at compile time";
        }
        if (form instanceof ValueTypes.BotClass declared) {
            // 32-generic-values.md §A bot's own generic class. A record's canonical constructor is what makes
            // `new Point(1, 2)` writable without guessing; anything else keeps whatever its author wrote, and
            // no placeholder is ever invented for it.
            String why = records.whyNotEditable(declared);
            if (why != null) return why;
        }
        String unknown = grammar.firstUnknown(form);
        if (unknown != null) return unknownReason(form, unknown);
        if (initializer.isBlank()) {
            return "no initialiser: there is no value to show or replace";
        }
        if (!readable) {
            return "written by hand as " + initializer + ", which is kept rather than replaced";
        }
        return "";
    }

    /** The sentence for a type with a leaf nothing declares: the type itself, or one of its arguments. */
    public static String unknownReason(java.lang.reflect.Type type, String unknown) {
        return ValueTypes.isLeaf(type)
                ? "no installed plugin declares " + unknown + " as a value type"
                : "type argument " + unknown + " is not a known value type";
    }

    /**
     * The {@code @Param} annotation on this field, or {@code null}. Public for {@code LockResolver}: what makes
     * a field a parameter is stated here once, and the canvas refusing to edit one asks the same question.
     */
    public static Annotation paramAnnotation(FieldDeclaration field) {
        return BotAnnotation.PARAM.on(field);
    }

    private static String string(Map<String, Object> members, String name) {
        Object value = members.get(name);
        return value instanceof String text ? text : "";
    }

    /**
     * A bound. A string is read too: {@code @Param}'s bounds were text until 2026-09-22, and a bot written
     * against plugin-basics' annotation says {@code min = "1"} — the same bound, which it still is.
     */
    private static double number(Map<String, Object> members, String name, double absent) {
        Object value = members.get(name);
        if (value instanceof Double number) return number;
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.strip());
            } catch (NumberFormatException notANumber) {
                return absent;
            }
        }
        return absent;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> members, String name) {
        Object value = members.get(name);
        if (value instanceof List<?> list) return List.copyOf((List<String>) list);
        return value instanceof String single ? List.of(single) : List.of();
    }

    /** The source text a node occupies, exactly as written — whitespace, comments and all. */
    public static String text(String source, ASTNode node) {
        int start = node.getStartPosition();
        if (start < 0 || start + node.getLength() > source.length()) return "";
        return source.substring(start, start + node.getLength());
    }

    /** The name of the type a node is declared in, or {@code null} for one declared nowhere. */
    public static String enclosingTypeName(ASTNode node) {
        for (ASTNode parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof TypeDeclaration type) return type.getName().getIdentifier();
        }
        return null;
    }

    /** Whether any modifier list already carries {@code @Param} — used when adding one. */
    static boolean isParam(List<IExtendedModifier> modifiers) {
        return BotAnnotation.PARAM.isOn(modifiers);
    }
}
