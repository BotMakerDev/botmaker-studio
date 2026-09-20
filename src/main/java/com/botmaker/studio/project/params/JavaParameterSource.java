package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.ParameterRow;
import com.botmaker.plugin.api.value.Range;
import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueContainer;
import com.botmaker.plugin.api.value.ValueForm;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.plugin.api.value.Visibility;
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

    /** The annotation's fully qualified name, which is what an import or a qualified use spells. */
    public static final String ANNOTATION_FQN = "com.botmaker.plugin.basics.params.Param";

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
     * Every {@code @Param} field in {@code source}, in the order they are written.
     *
     * @param file    the path the rows are attributed to; may be {@code null} in a test
     * @param catalog the merged value catalog, which decides what a field's Java type <em>is</em>
     */
    public static List<JavaParameter> read(Path file, String source, ValueCatalog catalog) {
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
                    out.add(read(file, source, catalog, className, field, annotation, fragment));
                }
                return false;
            }
        });
        return List.copyOf(out);
    }

    /** One fragment of one field — {@code @Param public static int a = 1, b = 2;} declares two. */
    private static JavaParameter read(Path file, String source, ValueCatalog catalog, String className,
                                      FieldDeclaration field, Annotation annotation,
                                      VariableDeclarationFragment fragment) {
        Map<String, Object> members = members(annotation);
        String name = fragment.getName().getIdentifier();
        String initializer = fragment.getInitializer() == null ? ""
                : text(source, fragment.getInitializer());

        ValueForm form = formOf(catalog, field.getType(), fragment.getExtraDimensions());
        Optional<Object> value = catalog.valueOf(form, initializer);

        String note = whyNotEditable(field, form, value, initializer);
        // The row's value is the initialiser *as the author wrote it*, readable or not. A cell that cannot
        // be edited still has to show what the field holds, and the reading above decides only whether it
        // may be replaced.
        ParameterRow.Builder row = ParameterRow.named(name, form)
                .value(initializer)
                .description(string(members, "description"))
                .category(string(members, "category"))
                .visibility(Visibility.fromId(string(members, "visibility")))
                .options(strings(members, "options"));
        String min = string(members, "min");
        String max = string(members, "max");
        if (!min.isBlank() || !max.isBlank()) {
            row.bounds(new Range(min.isBlank() ? null : min, max.isBlank() ? null : max));
        }
        return new JavaParameter(file, className, row.build(), initializer, note.isEmpty(), note);
    }

    /**
     * Why this field's value cell is read-only, or {@code ""} when it is not.
     *
     * <p>Each answer is a sentence a person can act on, because the alternative — a grey cell with no
     * reason — is the failure this whole design exists to avoid. A field is never <em>rejected</em>: it is
     * still listed, still shows what it holds, and still tells the author what to change.
     */
    private static String whyNotEditable(FieldDeclaration field, ValueForm form,
                                         Optional<Object> value, String initializer) {
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
            // 32-generic-values.md §A bot's own generic class: writing `new Box<>(…)` needs a constructor
            // this parser cannot see, and inventing a placeholder into a user's own class is the one thing
            // the design refuses outright. Phase F is where it becomes readable.
            return declared.qualifiedName() + " is a class this bot declares, "
                    + "and there is no constructor Studio can write for it yet";
        }
        String unknown = firstUnknown(form);
        if (unknown != null) {
            return form instanceof ValueForm.Leaf
                    ? "no installed plugin registers " + unknown + " as a value type"
                    : "type argument " + unknown + " is not a known value type";
        }
        if (initializer.isBlank()) {
            return "no initialiser: there is no value to show or replace";
        }
        if (value.isEmpty()) {
            return "written by hand as " + initializer + ", which is kept rather than replaced";
        }
        return "";
    }

    /**
     * The {@link ValueForm} a written type means, all the way down: a registered leaf, a registered
     * container over forms, and {@link ValueType#unknown} for anything else.
     *
     * <p><b>Recursive, and unbounded.</b> {@code Map<String, List<Duration>>} reads as what it is. Until
     * 2026-09-20 this answered a {@code ValueChoice}, which could say a type and <em>one</em> list around
     * it, so a field javac accepts perfectly well read as unknown and refused to be edited. Only the
     * picker is capped, because a four-level value cell is not drawable in a table row; nothing caps what
     * may be read out of a user's file, shown and left intact.
     *
     * <p>An array is <em>not</em> a container. {@code int[]} is a Java spelling no codec emits, so an array
     * field is read as unknown and comes out read-only — visible, honest, and not silently rewritten into a
     * {@code List}. A wildcard, a type variable and a container whose written arity disagrees with the
     * registered one read the same way, and for the same reason.
     */
    static ValueForm formOf(ValueCatalog catalog, Type type, int extraDimensions) {
        if (type == null) return ValueForm.of(unknown(""));
        if (extraDimensions > 0 || type.isArrayType()) {
            return ValueForm.of(unknown(type.toString().strip()));
        }
        if (type instanceof ParameterizedType parameterized) {
            String raw = parameterized.getType().toString().strip();
            Optional<ValueContainer<?>> container = catalog.containerForJava(raw);
            List<?> arguments = parameterized.typeArguments();
            if (container.isEmpty() || container.get().arity() != arguments.size()) {
                return ValueForm.of(unknown(type.toString().strip()));
            }
            List<ValueForm> forms = new ArrayList<>(arguments.size());
            for (Object argument : arguments) forms.add(formOf(catalog, (Type) argument, 0));
            return new ValueForm.Of(container.get(), forms);
        }
        if (type.isWildcardType() || type.isIntersectionType() || type.isUnionType()) {
            return ValueForm.of(unknown(type.toString().strip()));
        }
        return ValueForm.of(registered(catalog, type));
    }

    /**
     * The first leaf in {@code form} that nothing registers, as it is written — or {@code null} when every
     * one of them is known.
     *
     * <p>Depth-first in written order, so the reason a cell gives names the argument a reader's eye reaches
     * first. One unknown leaf anywhere makes the whole form unreadable, which is the same rule
     * {@code ValueCatalog.valueOf} applies to one unreadable part of a list.
     */
    private static String firstUnknown(ValueForm form) {
        return switch (form) {
            case ValueForm.Leaf leaf -> leaf.type().known() ? null : leaf.type().id();
            case ValueForm.Of of -> {
                for (ValueForm argument : of.arguments()) {
                    String found = firstUnknown(argument);
                    if (found != null) yield found;
                }
                yield null;
            }
            case ValueForm.Declared declared -> declared.qualifiedName();
        };
    }

    /**
     * The registered type a written name means.
     *
     * <p>Asked by simple name as well as fully qualified, because {@code Duration} and
     * {@code java.time.Duration} are the same field written two ways and only the source says which. A name
     * two plugins both end with is resolved by the catalog's registration order, which is the same order
     * every other menu resolves by.
     */
    private static ValueType registered(ValueCatalog catalog, Type type) {
        String written = type.toString().strip();
        Optional<ValueType> exact = catalog.forJava(written);
        if (exact.isPresent()) return exact.get();
        for (ValueType candidate : catalog.types()) {
            String javaName = candidate.javaName();
            if (javaName == null) continue;
            if (javaName.equals(written)) return candidate;
            int lastDot = javaName.lastIndexOf('.');
            if (lastDot >= 0 && javaName.substring(lastDot + 1).equals(written)) return candidate;
        }
        return unknown(written);
    }

    /** An unknown type keyed by what was written, so the cell can say which name nobody registers. */
    private static ValueType unknown(String written) {
        return ValueType.unknown(written);
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
                constant(pair.getValue()).ifPresent(v -> out.put(name, v));
            }
        }
        return out;
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

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> members, String name) {
        Object value = members.get(name);
        if (value instanceof List<?> list) return List.copyOf((List<String>) list);
        return value instanceof String single ? List.of(single) : List.of();
    }

    /** The source text a node occupies, exactly as written — whitespace, comments and all. */
    static String text(String source, ASTNode node) {
        int start = node.getStartPosition();
        if (start < 0 || start + node.getLength() > source.length()) return "";
        return source.substring(start, start + node.getLength());
    }

    /** The name of the type a node is declared in, or {@code null} for one declared nowhere. */
    static String enclosingTypeName(ASTNode node) {
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
