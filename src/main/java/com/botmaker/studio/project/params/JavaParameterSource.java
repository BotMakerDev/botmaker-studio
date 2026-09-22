package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.plugin.api.value.Visibility;
import com.botmaker.studio.plugin.grammar.JavaExpressions;
import com.botmaker.studio.plugin.grammar.JdkLiterals;
import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.ValueForm;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <p><b>Syntax only, no bindings.</b> The parser is given source and nothing else — no classpath, no
 * project — so a type is whatever it is <em>written</em> as: {@code Duration} matches the registered type
 * whose Java name ends in {@code Duration}, and {@code List<Rect>} is a list of one. That is weaker than a
 * resolved binding and is the right weakness: a bot whose dependencies do not resolve (mid-edit, a pom
 * being changed, a plugin not yet installed) still shows its parameters instead of an empty window.
 */
public final class JavaParameterSource {

    /** The annotation's simple name, which is how it is matched. Its package is not resolvable here. */
    static final String ANNOTATION = "Param";

    /**
     * The annotation's fully qualified name, which is what an import or a qualified use spells. The
     * contract's since 2026-09-22; plugin-basics held it before, and a use spelling that one still matches,
     * because the annotation is matched by its simple name.
     */
    public static final String ANNOTATION_FQN = "com.botmaker.plugin.api.params.Param";

    private JavaParameterSource() {}

    /**
     * What the <em>bot itself</em> declares, asked by the name a type is written as.
     *
     * <p>This parser has no bindings, so it cannot tell a class the project declares from one nobody has
     * heard of — both are simply names. {@link BotRecords} can, because it has read the bot's other sources,
     * and it is handed in here as this one question rather than as a dependency: the reader stays pure and a
     * test still drives it with source text alone.
     */
    @FunctionalInterface
    public interface Declarations {

        /** The qualified name of the bot's own class {@code written} means, or {@code null} for none. */
        String qualify(String written);

        /** What a caller with no project has: every name is somebody else's. */
        Declarations NONE = written -> null;
    }

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
        List<JavaParameter> out = new ArrayList<>();
        parse(source).accept(new ASTVisitor() {
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
        Map<String, Object> members = members(annotation);
        String name = fragment.getName().getIdentifier();
        String initializer = fragment.getInitializer() == null ? ""
                : text(source, fragment.getInitializer());

        ValueForm form = formOf(grammar, field.getType(), fragment.getExtraDimensions(),
                records::qualifyDeclared);
        // The grammar declines a declared form by design — only the host's view of the bot's own source can
        // read one — so the second reader is asked for exactly that case and for no other.
        boolean readable = form instanceof ValueForm.Declared declared
                ? records.partsOf(declared, initializer).isPresent()
                : grammar.valueOf(form, initializer).isPresent();

        String note = whyNotEditable(grammar, field, form, records, readable, initializer);
        // The row's value is the initialiser *as the author wrote it*, readable or not. A cell that cannot
        // be edited still has to show what the field holds, and the reading above decides only whether it
        // may be replaced.
        ParameterRow row = ParameterRow.named(name, form.sourceName())
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
    private static String whyNotEditable(ValueGrammar grammar, FieldDeclaration field, ValueForm form,
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
        if (form instanceof ValueForm.Declared declared) {
            // 32-generic-values.md §A bot's own generic class. A record's canonical constructor is what makes
            // `new Point(1, 2)` writable without guessing; anything else keeps whatever its author wrote, and
            // no placeholder is ever invented for it.
            String why = records.whyNotEditable(declared);
            if (why != null) return why;
        }
        String unknown = grammar.firstUnknown(form);
        if (unknown != null) {
            return form instanceof ValueForm.Leaf
                    ? "no installed plugin declares " + unknown + " as a value type"
                    : "type argument " + unknown + " is not a known value type";
        }
        if (initializer.isBlank()) {
            return "no initialiser: there is no value to show or replace";
        }
        if (!readable) {
            return "written by hand as " + initializer + ", which is kept rather than replaced";
        }
        return "";
    }

    /**
     * The {@link ValueForm} a written type means, all the way down: a leaf the grammar knows, a host
     * container over forms, and a leaf keyed by what was written for anything else.
     *
     * <p><b>Recursive, and unbounded.</b> {@code Map<String, List<Duration>>} reads as what it is. Until
     * 2026-09-20 this answered a {@code ValueChoice}, which could say a type and <em>one</em> list around
     * it, so a field javac accepts perfectly well read as unknown and refused to be edited. Only the
     * picker is capped, because a four-level value cell is not drawable in a table row; nothing caps what
     * may be read out of a user's file, shown and left intact.
     *
     * <p>An array is <em>not</em> a container. {@code int[]} is a Java spelling the grammar never writes, so
     * an array field is read as unknown and comes out read-only — visible, honest, and not silently rewritten
     * into a {@code List}. A wildcard, a type variable and a container whose written arity disagrees with its
     * own read the same way, and for the same reason.
     */
    public static ValueForm formOf(ValueGrammar grammar, Type type, int extraDimensions) {
        return formOf(grammar, type, extraDimensions, Declarations.NONE);
    }

    /**
     * The same, told what the bot declares itself — which is what turns an unknown leaf into a
     * {@link ValueForm.Declared}.
     *
     * <p><b>A plugin's type wins a name clash</b>, so a bot declaring its own {@code Point} beside the SDK's
     * still reads as the SDK's. That is the weaker answer and the safer one: it is the reading every previous
     * release gave, and a field whose meaning changed because a file elsewhere in the project was renamed
     * would be the surprise this parser exists not to spring.
     */
    public static ValueForm formOf(ValueGrammar grammar, Type type, int extraDimensions,
                                   Declarations declarations) {
        if (type == null) return ValueForm.of("");
        String written = type.toString().strip();
        if (extraDimensions > 0 || type.isArrayType()) return ValueForm.of(written);
        if (type instanceof ParameterizedType parameterized) {
            String raw = parameterized.getType().toString().strip();
            Optional<ValueContainer<?>> container = grammar.containerForJava(raw);
            List<?> arguments = parameterized.typeArguments();
            List<ValueForm> forms = new ArrayList<>(arguments.size());
            for (Object argument : arguments) forms.add(formOf(grammar, (Type) argument, 0, declarations));
            if (container.isPresent() && container.get().arity() == arguments.size()) {
                return new ValueForm.Of(container.get(), forms);
            }
            String declared = declarations.qualify(raw);
            if (declared != null) return new ValueForm.Declared(declared, forms);
            return ValueForm.of(written);
        }
        if (type.isWildcardType() || type.isIntersectionType() || type.isUnionType()) {
            return ValueForm.of(written);
        }
        String known = grammar.qualify(written);
        if (known != null) return ValueForm.of(known);
        String declared = declarations.qualify(written);
        return declared == null ? ValueForm.of(written) : new ValueForm.Declared(declared, List.of());
    }

    /**
     * The {@code @Param} annotation on this field, or {@code null}. Public for {@code LockResolver}: what makes
     * a field a parameter is stated here once, and the canvas refusing to edit one asks the same question.
     */
    public static Annotation paramAnnotation(FieldDeclaration field) {
        for (Object modifier : field.modifiers()) {
            if (!(modifier instanceof Annotation annotation)) continue;
            String name = annotation.getTypeName().getFullyQualifiedName();
            if (name.equals(ANNOTATION) || name.equals(ANNOTATION_FQN)
                    || name.endsWith("." + ANNOTATION)) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * The annotation's members as written — a {@code String} for a single value, a {@code List<String>} for
     * an array.
     *
     * <p>Only constant text is read. {@code @Param(category = SOME_CONSTANT)} compiles and means something
     * a parser without bindings cannot know, so it reads as absent rather than as the constant's name: an
     * empty category is a parameter in the default group, and a wrong one is a parameter under a heading
     * nobody wrote.
     */
    static Map<String, Object> members(Annotation annotation) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (annotation instanceof SingleMemberAnnotation single) {
            // @Param("Limits") is not legal — there is no `value()` member — but a future one would be, and
            // reading it as `value` costs nothing and is what every other annotation reader does.
            constant(single.getValue()).ifPresent(v -> out.put("value", v));
            return out;
        }
        if (!(annotation instanceof NormalAnnotation normal)) return out;
        for (Object each : normal.values()) {
            MemberValuePair pair = (MemberValuePair) each;
            String name = pair.getName().getIdentifier();
            if (pair.getValue() instanceof ArrayInitializer array) {
                List<String> items = new ArrayList<>();
                for (Object element : array.expressions()) {
                    constant((Expression) element).ifPresent(items::add);
                }
                out.put(name, List.copyOf(items));
            } else {
                Optional<String> text = constant(pair.getValue());
                if (text.isPresent()) out.put(name, text.get());
                else numeric(pair.getValue()).ifPresent(v -> out.put(name, v));
            }
        }
        return out;
    }

    /**
     * A numeric literal's value — {@code min = 0}, {@code max = 2.5}, {@code min = -1} — or empty for
     * anything else. {@code @Param}'s bounds have been {@code double} since 2026-09-22; they were text only
     * because a codec parsed them.
     */
    private static Optional<Double> numeric(Expression expression) {
        return JdkLiterals.readAny(expression.toString())
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).doubleValue());
    }

    /**
     * A string literal's content, or empty for anything that is not one — with one exception.
     *
     * <p><b>{@code Param.PUBLIC} and {@code Param.EDITOR} are resolved</b>, because the annotation declares
     * those two constants precisely so that nobody writes the strings down, and a reader that could not
     * read its own vocabulary would turn the better spelling into a silent "absent". Only a
     * <em>qualified</em> name is taken, and only one qualified by {@code Param} — a bare {@code PUBLIC}
     * could be any constant in the file, and guessing at it is what the rest of this method exists not to
     * do. The value is the contract's own {@link Visibility} id, so there is no third copy of the strings.
     */
    private static Optional<String> constant(Expression expression) {
        if (expression instanceof StringLiteral literal) return Optional.of(literal.getLiteralValue());
        if (expression instanceof QualifiedName qualified
                && qualified.getQualifier().getFullyQualifiedName().endsWith(ANNOTATION)) {
            return switch (qualified.getName().getIdentifier()) {
                case "PUBLIC" -> Optional.of(Visibility.PUBLIC.id());
                case "EDITOR" -> Optional.of(Visibility.EDITOR_ONLY.id());
                default -> Optional.empty();
            };
        }
        return Optional.empty();
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
        for (IExtendedModifier modifier : modifiers) {
            if (modifier instanceof Annotation annotation
                    && annotation.getTypeName().getFullyQualifiedName().endsWith(ANNOTATION)) {
                return true;
            }
        }
        return false;
    }
}
