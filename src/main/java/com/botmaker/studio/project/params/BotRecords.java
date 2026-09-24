package com.botmaker.studio.project.params;

import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.SourceNames;
import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.source.ValueTypeResolver;
import com.botmaker.studio.services.BotSources;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleType;
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
 * <p><b>Why this is separate from the grammar.</b> A {@link ValueGrammar} describes what plugins declared; a
 * bot's own {@code record Point(int x, int y)} is declared by no plugin, and a second project's
 * {@code Point} is a different class. What the host has and no plugin does is the bot's source — so the
 * grammar declines a {@link ValueTypes.BotClass} ({@code ValueGrammar.initializer} says so in as many words)
 * and this class answers it instead, with the same shape of answer: parts as source with their forms, and a
 * call composed back over them.
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
 * there. That is why {@code ValueGrammar.freshInitializer} answers empty for a declared form and why nothing
 * here supplies one.
 *
 * <p><b>Read without a classpath.</b> A record is keyed by its canonical name ({@code com.bot.Outer.Inner} for a
 * member type), and a component's type is resolved through its unit's imports by {@link ValueTypeResolver}. A
 * generic record's {@code T} is an unknown leaf, and a record that contains itself is refused with that as
 * the reason rather than walked forever.
 */
public final class BotRecords {

    /** One component of a record: what it is called, and the form its declared type means. */
    public record Component(String name, java.lang.reflect.Type form) {}

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

    private static final BotRecords NONE = new BotRecords(Map.of(), ValueGrammar.empty());

    private final Map<String, Shape> byQualifiedName;

    /** What decides whether a component's own type is one a value can be written of. */
    private final ValueGrammar grammar;

    private BotRecords(Map<String, Shape> byQualifiedName, ValueGrammar grammar) {
        this.byQualifiedName = Map.copyOf(byQualifiedName);
        this.grammar = grammar == null ? ValueGrammar.empty() : grammar;
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
    public static BotRecords scan(ProjectConfig config, ProjectState state, ValueGrammar grammar) {
        if (config == null) return NONE;
        List<String> sources = new ArrayList<>();
        BotSources.scan(config, state, (file, source) -> sources.add(source));
        return of(grammar, sources);
    }

    /**
     * The same over source text, which is how this is tested — no project, no filesystem, exactly as
     * {@link JavaParameterSource} is.
     */
    static BotRecords of(ValueGrammar grammar, List<String> sources) {
        Map<String, Raw> raw = new LinkedHashMap<>();
        for (String source : sources) collect(source, raw);
        if (raw.isEmpty()) return NONE;

        Set<String> names = Set.copyOf(raw.keySet());
        Map<String, Shape> shapes = new LinkedHashMap<>();
        for (Raw found : raw.values()) {
            List<Component> components = new ArrayList<>(found.components().size());
            for (RawComponent component : found.components()) {
                components.add(new Component(component.name(), ValueTypeResolver.of(
                        grammar, component.type(), component.extraDimensions(), names)));
            }
            shapes.put(found.qualifiedName(), new Shape(found.qualifiedName(), found.simpleName(),
                    found.isRecord(), List.copyOf(components)));
        }
        return new BotRecords(shapes, grammar);
    }

    /**
     * The canonical name of every class the bot declares — what the type resolver asks, so a field typed
     * {@code Point} is the bot's {@code Point} rather than an unknown leaf.
     */
    public Set<String> declaredNames() {
        return byQualifiedName.keySet();
    }

    /** The record a type names, or empty when the bot does not declare one by that name. */
    public Optional<Shape> find(ValueTypes.BotClass declared) {
        return declared == null ? Optional.empty()
                : Optional.ofNullable(byQualifiedName.get(declared.qualifiedName()));
    }

    /** This record's components in canonical order, or empty for a name the bot does not declare. */
    public List<Component> componentsOf(ValueTypes.BotClass declared) {
        return find(declared).map(Shape::components).orElse(List.of());
    }

    /**
     * Why a value of {@code declared} cannot be written here, or {@code null} when it can.
     *
     * <p>Every answer names the component that is the problem, because "this type is not supported" is the
     * message the whole design exists to avoid: the author can register a type, change a component or stop
     * expecting a cell, and none of those is choosable from a sentence that does not say which one.
     */
    public String whyNotEditable(ValueTypes.BotClass declared) {
        return whyNotEditable(declared, new LinkedHashSet<>());
    }

    private String whyNotEditable(ValueTypes.BotClass declared, Set<String> seen) {
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
            if (component.form() instanceof ValueTypes.BotClass nested) {
                String why = whyNotEditable(nested, seen);
                if (why != null) return why;
                continue;
            }
            if (!grammar.known(component.form())) {
                return shape.simpleName() + "." + component.name() + " is a "
                        + ValueTypes.sourceName(component.form()) + ", which no installed plugin declares as a value";
            }
        }
        seen.remove(shape.qualifiedName());
        return null;
    }

    /**
     * A {@code new Point(1, 2)} taken apart into its components' own source, each with the component's form
     * — the counterpart of {@code ValueGrammar.partsOfInitializer} for a class the bot declares.
     *
     * <p>Positional, because a record's canonical constructor is. A call with the wrong number of arguments
     * is not this record's canonical constructor — it is one of its other constructors, or a different class
     * entirely — and answering a partial reading of it would rewrite something nobody asked to rewrite.
     */
    public Optional<List<ValueGrammar.Part>> partsOf(ValueTypes.BotClass declared, String source) {
        Shape shape = byQualifiedName.get(declared == null ? "" : declared.qualifiedName());
        if (shape == null || source == null || source.isBlank()) return Optional.empty();
        Optional<SourceNode> parsed = SourceNode.parse(source);
        if (parsed.isEmpty() || !(parsed.get().node() instanceof ClassInstanceCreation creation)) {
            return Optional.empty();
        }
        if (!names(creation.getType(), shape.qualifiedName())) return Optional.empty();
        List<?> arguments = creation.arguments();
        if (arguments.size() != shape.components().size()) return Optional.empty();

        List<ValueGrammar.Part> parts = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            SourceNode written = parsed.get().child((Expression) arguments.get(i));
            if (written.source().isBlank()) return Optional.empty();
            parts.add(new ValueGrammar.Part(shape.components().get(i).form(), written));
        }
        return Optional.of(List.copyOf(parts));
    }

    /**
     * Components that are already values, composed back into the constructor call as a tree — the record
     * named fully qualified, so the line compiles wherever the field is declared and no import can be
     * forgotten. Each component's own imports are carried.
     */
    public Optional<JavaValue> compose(ValueTypes.BotClass declared, List<JavaValue> parts) {
        Shape shape = byQualifiedName.get(declared == null ? "" : declared.qualifiedName());
        if (shape == null || parts == null || parts.size() != shape.components().size()
                || parts.stream().anyMatch(java.util.Objects::isNull)) {
            return Optional.empty();
        }
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        ClassInstanceCreation creation = ast.newClassInstanceCreation();
        creation.setType(ast.newSimpleType(ast.newName(shape.qualifiedName())));
        Set<String> imports = new LinkedHashSet<>();
        @SuppressWarnings("unchecked")
        List<Expression> arguments = creation.arguments();
        for (JavaValue part : parts) {
            arguments.add(part.copyInto(ast));
            imports.addAll(part.imports());
        }
        return Optional.of(JavaValue.built(creation, imports));
    }

    // ---- reading the bot's own sources -------------------------------------------------------------------

    private record RawComponent(String name, Type type, int extraDimensions) {}

    private record Raw(String qualifiedName, String simpleName, boolean isRecord,
                       List<RawComponent> components) {}

    private static void collect(String source, Map<String, Raw> out) {
        CompilationUnit unit = JavaParameterSource.parse(source);
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(RecordDeclaration declaration) {
                List<RawComponent> components = new ArrayList<>();
                for (Object each : declaration.recordComponents()) {
                    SingleVariableDeclaration component = (SingleVariableDeclaration) each;
                    components.add(new RawComponent(component.getName().getIdentifier(),
                            component.getType(), component.getExtraDimensions()));
                }
                put(declaration, true, components);
                return true;
            }

            @Override
            public boolean visit(TypeDeclaration declaration) {
                // A class and an interface are both carried, with no components: what they buy is the
                // *reason* a field of one is read-only, which is about its constructor and not about the
                // vocabulary. An enum is not, because a field of one is a leaf question and reads as one.
                put(declaration, false, List.of());
                return true;
            }

            // Keyed by canonical name — com.bot.Outer.Inner for a member type — which is what a binding says
            // and what the type resolver asks for; the imports and the enclosing types decide a written name.
            private void put(AbstractTypeDeclaration declaration, boolean isRecord, List<RawComponent> components) {
                String qualified = SourceNames.canonicalOf(declaration);
                out.putIfAbsent(qualified, new Raw(qualified, declaration.getName().getIdentifier(), isRecord,
                        List.copyOf(components)));
            }
        });
    }

    /** Whether a {@code new} expression's type — {@code Box<>} or {@code Box} alike — names {@code canonical}. */
    private static boolean names(Type type, String canonical) {
        Type raw = type instanceof ParameterizedType parameterized ? parameterized.getType() : type;
        return raw instanceof SimpleType simple && SourceNames.refersTo(simple.getName(), canonical);
    }

}
