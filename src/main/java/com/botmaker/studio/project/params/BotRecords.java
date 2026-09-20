package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueForm;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.BotSources;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The records the bot itself declares, and the grammar for a value of one.
 *
 * <p><b>Why this is the host's and not the catalog's.</b> A {@code ValueCatalog} describes what plugins
 * registered; a bot's own {@code record Point(int x, int y)} is registered by nobody, has no codec, and a
 * second project's {@code Point} is a different class. What the host has and nothing else does is the bot's
 * source — so the contract's grammar declines a {@link ValueForm.Declared}
 * ({@code ValueCatalog.initializer} says so in as many words) and this class answers it instead, with the
 * same shape of answer: parts as source with their forms, and a call composed back over them.
 *
 * <h2>Records first, and only records</h2>
 *
 * <p>A record's canonical constructor is its components, in order, with no overload to choose between —
 * which is exactly what makes {@code new Point(1, 2)} decidable both ways without bindings. An ordinary
 * class may have five constructors and no way to tell which one an editor should write, so it is not
 * offered; a field of one is listed, shown as written and left alone, which is what read-only has meant
 * here all along.
 *
 * <p><b>A placeholder is never written into a user's class</b>
 * ({@code docs/refactor/32-generic-values.md} §<i>A bot's own generic class</i>): a record whose components
 * are not all readable has no default this host may invent, so the field keeps whatever its author put
 * there. That is why {@code ValueCatalog.defaultValue} answers empty for a declared form and why nothing
 * here supplies one.
 *
 * <p><b>Syntax only, like everything else in this package.</b> A component's type is whatever it is
 * <em>written</em> as, a generic record's {@code T} is an unknown leaf, and a record that contains itself is
 * refused with that as the reason rather than walked forever.
 */
public final class BotRecords {

    /** One component of a record: what it is called, and the form its declared type means. */
    public record Component(String name, ValueForm form) {}

    /**
     * One type the bot declares, by qualified name.
     *
     * <p>A class is carried here as well as a record, with no components and {@code isRecord} false. It has
     * to be: a field typed with the bot's own {@code Box} is not a type nobody registered, and the reason
     * its cell is read-only — <em>which</em> constructor would Studio write — is a different sentence from
     * the one an unknown leaf gets, and the only one its author can act on.
     */
    public record Shape(String qualifiedName, String simpleName, boolean isRecord,
                        List<Component> components) {}

    private static final BotRecords NONE = new BotRecords(Map.of());

    private final Map<String, Shape> byQualifiedName;

    private BotRecords(Map<String, Shape> byQualifiedName) {
        this.byQualifiedName = Map.copyOf(byQualifiedName);
    }

    /** No records at all — what a caller with no project has, and what the pure readers are given. */
    public static BotRecords none() {
        return NONE;
    }

    /**
     * Every record the bot declares, read off its own sources.
     *
     * <p>Two passes, because a record may name another: the names are collected first, so a component typed
     * {@code Point} resolves to the bot's {@code Point} however the two files are ordered on disk.
     */
    public static BotRecords scan(ProjectConfig config, ProjectState state, ValueCatalog catalog) {
        if (config == null) return NONE;
        List<String> sources = new ArrayList<>();
        BotSources.scan(config, state, (file, source) -> sources.add(source));
        return of(catalog, sources);
    }

    /**
     * The same over source text, which is how this is tested — no project, no filesystem, exactly as
     * {@link JavaParameterSource} is.
     */
    static BotRecords of(ValueCatalog catalog, List<String> sources) {
        Map<String, Raw> raw = new LinkedHashMap<>();
        for (String source : sources) collect(source, raw);
        if (raw.isEmpty()) return NONE;

        Map<String, String> qualified = new LinkedHashMap<>();
        for (Raw found : raw.values()) qualified.putIfAbsent(found.simpleName(), found.qualifiedName());
        JavaParameterSource.Declarations declarations = written -> {
            if (written == null) return null;
            if (qualified.containsValue(written)) return written;
            return qualified.get(written.substring(written.lastIndexOf('.') + 1));
        };

        Map<String, Shape> shapes = new LinkedHashMap<>();
        for (Raw found : raw.values()) {
            List<Component> components = new ArrayList<>(found.components().size());
            for (RawComponent component : found.components()) {
                components.add(new Component(component.name(), JavaParameterSource.formOf(
                        catalog, component.type(), component.extraDimensions(), declarations)));
            }
            shapes.put(found.qualifiedName(), new Shape(found.qualifiedName(), found.simpleName(),
                    found.isRecord(), List.copyOf(components)));
        }
        return new BotRecords(shapes);
    }

    /**
     * The qualified name of the record {@code written} means — {@link JavaParameterSource.Declarations} as
     * this class answers it, so a field typed {@code Point} becomes a declared form rather than an unknown
     * leaf.
     */
    public String qualifyDeclared(String written) {
        if (written == null || written.isBlank()) return null;
        String name = written.strip();
        if (byQualifiedName.containsKey(name)) return name;
        String simple = name.substring(name.lastIndexOf('.') + 1);
        for (Shape shape : byQualifiedName.values()) {
            if (shape.simpleName().equals(simple)) return shape.qualifiedName();
        }
        return null;
    }

    /** The record a form names, or empty when the bot does not declare one by that name. */
    public Optional<Shape> find(ValueForm.Declared declared) {
        return declared == null ? Optional.empty()
                : Optional.ofNullable(byQualifiedName.get(declared.qualifiedName()));
    }

    /** This record's components in canonical order, or empty for a name the bot does not declare. */
    public List<Component> componentsOf(ValueForm.Declared declared) {
        return find(declared).map(Shape::components).orElse(List.of());
    }

    /**
     * Why a value of {@code declared} cannot be written here, or {@code null} when it can.
     *
     * <p>Every answer names the component that is the problem, because "this type is not supported" is the
     * message the whole design exists to avoid: the author can register a type, change a component or stop
     * expecting a cell, and none of those is choosable from a sentence that does not say which one.
     */
    public String whyNotEditable(ValueForm.Declared declared) {
        return whyNotEditable(declared, new LinkedHashSet<>());
    }

    private String whyNotEditable(ValueForm.Declared declared, Set<String> seen) {
        Shape shape = byQualifiedName.get(declared.qualifiedName());
        if (shape == null || !shape.isRecord()) {
            return declared.qualifiedName() + " is a class this bot declares, and only a record has a "
                    + "constructor Studio can write without guessing which one you meant";
        }
        // The set is the path being walked, not everything seen: two components of the same record type are
        // a record used twice, and only a record reachable from itself is a value nobody can write down.
        if (!seen.add(shape.qualifiedName())) {
            return shape.simpleName() + " contains itself, so there is no value that could be written down";
        }
        if (!declared.arguments().isEmpty()) {
            // A generic record's components are written in terms of its type variables, and substituting
            // them needs the binding this package deliberately does not have.
            return shape.simpleName() + " takes type arguments, and Studio reads a record's components as "
                    + "they are written rather than resolving them";
        }
        for (Component component : shape.components()) {
            if (component.form() instanceof ValueForm.Declared nested) {
                String why = whyNotEditable(nested, seen);
                if (why != null) return why;
                continue;
            }
            if (!component.form().known()) {
                return shape.simpleName() + "." + component.name() + " is a "
                        + component.form().sourceName() + ", which no installed plugin registers as a value";
            }
        }
        seen.remove(shape.qualifiedName());
        return null;
    }

    /**
     * A {@code new Point(1, 2)} taken apart into its components' own source, each with the component's form
     * — the counterpart of {@code ValueCatalog.partsOfInitializer} for a class the bot declares.
     *
     * <p>Positional, because a record's canonical constructor is. A call with the wrong number of arguments
     * is not this record's canonical constructor — it is one of its other constructors, or a different class
     * entirely — and answering a partial reading of it would rewrite something nobody asked to rewrite.
     */
    public Optional<List<ValueCatalog.Part>> partsOf(ValueForm.Declared declared, String source) {
        Shape shape = byQualifiedName.get(declared == null ? "" : declared.qualifiedName());
        if (shape == null || source == null || source.isBlank()) return Optional.empty();
        String trimmed = source.strip();
        if (!(expression(trimmed) instanceof ClassInstanceCreation creation)) return Optional.empty();
        if (!names(creation.getType()).equals(shape.simpleName())
                && !names(creation.getType()).equals(shape.qualifiedName())) {
            return Optional.empty();
        }
        List<?> arguments = creation.arguments();
        if (arguments.size() != shape.components().size()) return Optional.empty();

        List<ValueCatalog.Part> parts = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            String written = JavaParameterSource.text(trimmed, (ASTNode) arguments.get(i));
            if (written.isBlank()) return Optional.empty();
            parts.add(new ValueCatalog.Part(shape.components().get(i).form(), written));
        }
        return Optional.of(List.copyOf(parts));
    }

    /**
     * Components already written as source, composed back into the constructor call — always fully
     * qualified, so the line compiles wherever the field is declared and no import can be forgotten.
     */
    public Optional<String> initializerOfParts(ValueForm.Declared declared, List<String> parts) {
        Shape shape = byQualifiedName.get(declared == null ? "" : declared.qualifiedName());
        if (shape == null || parts == null || parts.size() != shape.components().size()) {
            return Optional.empty();
        }
        if (parts.stream().anyMatch(part -> part == null || part.isBlank())) return Optional.empty();
        return Optional.of("new " + shape.qualifiedName() + "(" + String.join(", ", parts) + ")");
    }

    // ---- reading the bot's own sources -------------------------------------------------------------------

    private record RawComponent(String name, Type type, int extraDimensions) {}

    private record Raw(String qualifiedName, String simpleName, boolean isRecord,
                       List<RawComponent> components) {}

    private static void collect(String source, Map<String, Raw> out) {
        CompilationUnit unit = JavaParameterSource.parse(source);
        String packageName = unit.getPackage() == null ? ""
                : unit.getPackage().getName().getFullyQualifiedName();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(RecordDeclaration declaration) {
                List<RawComponent> components = new ArrayList<>();
                for (Object each : declaration.recordComponents()) {
                    SingleVariableDeclaration component = (SingleVariableDeclaration) each;
                    components.add(new RawComponent(component.getName().getIdentifier(),
                            component.getType(), component.getExtraDimensions()));
                }
                put(declaration.getName().getIdentifier(), true, components);
                return true;
            }

            @Override
            public boolean visit(TypeDeclaration declaration) {
                // A class and an interface are both carried, with no components: what they buy is the
                // *reason* a field of one is read-only, which is about its constructor and not about the
                // vocabulary. An enum is not, because a field of one is a leaf question and reads as one.
                put(declaration.getName().getIdentifier(), false, List.of());
                return true;
            }

            // A nested type is keyed by its own simple name, which is how a component inside the same file
            // writes it; an outer class is not part of that spelling.
            private void put(String simple, boolean isRecord, List<RawComponent> components) {
                String qualified = packageName.isEmpty() ? simple : packageName + "." + simple;
                out.putIfAbsent(qualified, new Raw(qualified, simple, isRecord, List.copyOf(components)));
            }
        });
    }

    /** The created type's name with any type arguments dropped — {@code Box<>} and {@code Box} are one. */
    private static String names(Type type) {
        String written = type == null ? "" : type.toString().strip();
        int angle = written.indexOf('<');
        return angle < 0 ? written : written.substring(0, angle).strip();
    }

    /**
     * One expression parsed on its own, or {@code null}.
     *
     * <p>A real parser rather than a bracket-matching split, because this is the one place the host is
     * reading Java it did not write: an author's {@code new Point(x + 1, f(2, 3))} is two arguments, and a
     * comma count would say three.
     */
    private static Expression expression(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(source.toCharArray());
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion());
        parser.setCompilerOptions(options);
        ASTNode node = parser.createAST(null);
        return node instanceof Expression parsed ? parsed : null;
    }
}
