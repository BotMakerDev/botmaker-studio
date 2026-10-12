package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.meta.Migrate;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.helpers.SourceFormatter;
import com.botmaker.studio.plugin.grammar.Factory;
import com.botmaker.studio.plugin.grammar.JavaExpressions;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.params.JavaParameterSource;
import com.botmaker.studio.project.source.BotAnnotation;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchExpression;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import javax.lang.model.SourceVersion;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The migration window's steps for one pair of types, read from and written to the bot's {@code @Migrate} methods
 * (2026-10-12, {@code docs/refactor/25-compatibility.md} §12) — the guided half of {@link ManagedMigrations}, which
 * runs them.
 *
 * <p>A pair has {@link Shape.Parts}: each part of the new type's factory is a part of the old type
 * ({@link Choice.From}), the part's type built by its own one-part factory from one ({@link Choice.Built},
 * {@code Path.of(file)} — derived from the declarations, never supplied by a plugin), or one value for every
 * constant ({@link Choice.Fixed}). Or it has {@link Shape.Constants}: each old enum constant paired with a new
 * one. A part taken from one of another type goes through the {@code @Migrate} method between the two types, a
 * step of its own ({@link #nested}).
 *
 * <p>{@link #method} writes one step as a tree; {@link #plan} puts every step's method in the bot — in place of
 * the method already mapping its pair, else in {@value #CLASS} beside the main class — and runs the migration over
 * the result, so what is applied compiles as a whole and nothing is written when it would not.
 */
public final class MigrationSteps {

    /** The class a new {@code @Migrate} method goes in, created in the bot's main package when missing. */
    public static final String CLASS = "Migrations";

    private MigrationSteps() {}

    /** One part of a type written as a call: its place in the factory, what a person reads it as, its class. */
    public record Part(int index, String label, Class<?> type) {

        /** {@code file (String)}. */
        public String describe() {
            return label + " (" + type.getSimpleName() + ")";
        }
    }

    /** What one part of the new type is made of. */
    public sealed interface Choice {

        /** The old type's part {@code part}: as it is when the types agree, else through their own migration. */
        record From(int part) implements Choice {}

        /** The new part's type made by its one-part factory from the old type's part {@code part}. */
        record Built(int part) implements Choice {}

        /** The same value for every migrated constant; equal to another of the same Java. */
        record Fixed(JavaValue value) implements Choice {

            @Override
            public boolean equals(Object other) {
                return other instanceof Fixed fixed && value != null && fixed.value != null
                        && value.source().equals(fixed.value.source());
            }

            @Override
            public int hashCode() {
                return value == null ? 0 : value.source().hashCode();
            }
        }
    }

    /** What the window shows for a pair: parts to fill, constants to pair, or why the pair cannot be mapped. */
    public sealed interface Shape {

        /** The old type's parts, every one a parameter; the new type's factory parts, each to be filled. */
        record Parts(List<Part> from, List<Part> to) implements Shape {}

        /** Two enums' constants, by name. */
        record Constants(List<String> from, List<String> to) implements Shape {}

        record Refused(String reason) implements Shape {}
    }

    /** One step answered: what each part of the new type, or each old constant, becomes. */
    public sealed interface Step {

        Class<?> from();

        Class<?> to();

        /** One choice per part of the new type's factory, in its order. */
        record Calls(Class<?> from, Class<?> to, List<Choice> choices) implements Step {}

        /** The new constant's name for each old one. */
        record Arms(Class<?> from, Class<?> to, Map<String, String> arms) implements Step {}
    }

    /**
     * A deprecated type whose values the bot still has, what its plugin says replaces it ({@code null}: nothing), and
     * how many of the values have an {@code @Migrated} copy.
     */
    public record Pending(Class<?> from, Class<?> to, int values, int copied) {}

    /**
     * An open set a plugin's next version drops, as the bot holds it now.
     *
     * @param type       what its values are, {@code null} when its declaration names no class
     * @param to         what its plugin says replaces {@code type}, {@code null} for nothing
     * @param values     how many of its values have no {@code @Migrated} copy
     * @param migratable whether the migration window can copy them: a set of {@code public static final} values,
     *                   and a type the version bound now has for them
     */
    public record Stranded(String id, Class<?> type, Class<?> to, int values, boolean migratable) {}

    /** One value as it is and as the migration would write it. */
    public record Row(String name, String before, String after) {}

    /** What applying would do: the change or why not, constant by constant, and each value before and after. */
    public record Draft(Refactor.Outcome outcome, ManagedMigrations.Report report, List<Row> rows) {}

    // --- what the bot has to migrate -----------------------------------------------------------------------

    /**
     * Every deprecated type the bot's open sets still hold values of, with how many and how many of those have an
     * {@code @Migrated} copy — counted as {@link #stranded} counts them — in declaration order.
     */
    public static List<Pending> pending(BotIndex index, ValueGrammar grammar, List<ManagedValue<?>> declared) {
        Map<Class<?>, int[]> counts = new LinkedHashMap<>();
        for (ManagedValue<?> set : declared) {
            if (!migratable(set) || !ValueGrammar.retired(set.type())) continue;
            List<ManagedSets.Member> members = ManagedSets.members(index, set.id(), grammar);
            if (members.isEmpty()) continue;
            Map<String, Class<?>> copies = MigrationCleanup.copies(index, grammar, declared, set.type());
            int[] count = counts.computeIfAbsent(set.type(), type -> new int[2]);
            count[0] += members.size();
            count[1] += (int) members.stream().filter(member -> copies.containsKey(member.name())).count();
        }
        List<Pending> out = new ArrayList<>();
        counts.forEach((type, count) -> out.add(new Pending(type, replacementOf(grammar, type), count[0], count[1])));
        return List.copyOf(out);
    }

    /**
     * Each of {@code own}'s open sets the bot holds values of that {@code target} — the plugin's next version —
     * does not declare with the same id and type, compared by name: the move's repair removes its values (the id is
     * gone) or resets them (the type is). A value with an {@code @Migrated} copy is not counted, and a set with none
     * left is not listed. {@code declared} is every bound plugin's, what the migration window offers types from.
     */
    public static List<Stranded> stranded(BotIndex index, ValueGrammar grammar, List<ManagedValue<?>> declared,
                                          List<ManagedValue<?>> own, List<ManagedValue<?>> target) {
        Set<String> kept = new HashSet<>();
        for (ManagedValue<?> set : target) {
            if (set != null && set.isOpenSet()) kept.add(key(set));
        }
        List<Stranded> out = new ArrayList<>();
        for (ManagedValue<?> set : own) {
            if (set == null || !set.isOpenSet() || kept.contains(key(set))) continue;
            List<ManagedSets.Member> members = ManagedSets.members(index, set.id(), grammar);
            boolean migratable = migratable(set) && !targets(grammar, declared, set.type()).isEmpty();
            Map<String, Class<?>> copies = migratable ? MigrationCleanup.copies(index, grammar, declared, set.type())
                    : Map.of();
            long left = members.stream().filter(member -> !copies.containsKey(member.name())).count();
            if (left > 0) {
                out.add(new Stranded(set.id(), set.type(), migratable ? replacementOf(grammar, set.type()) : null,
                        (int) left, migratable));
            }
        }
        return List.copyOf(out);
    }

    private static String key(ManagedValue<?> set) {
        return set.id() + " " + (set.type() == null ? "" : set.type().getName());
    }

    /** Whether any open set of the bot is of a type a plugin retired — the cheap question before {@link #pending}. */
    public static boolean anyRetired(List<ManagedValue<?>> declared) {
        return declared.stream().anyMatch(set -> migratable(set) && ValueGrammar.retired(set.type()));
    }

    /**
     * The types {@code from}'s values can be migrated into: every type an open set holds, other than itself and
     * the retired, whose shape fits — its plugin's replacement first.
     */
    public static List<Class<?>> targets(ValueGrammar grammar, List<ManagedValue<?>> declared, Class<?> from) {
        Set<Class<?>> out = new LinkedHashSet<>();
        Class<?> pointed = replacementOf(grammar, from);
        if (pointed != null) out.add(pointed);
        for (ManagedValue<?> set : declared) {
            if (migratable(set) && !ValueGrammar.retired(set.type())) out.add(set.type());
        }
        out.removeIf(to -> ManagedMigrations.named(to, from) || shape(grammar, from, to) instanceof Shape.Refused);
        return List.copyOf(out);
    }

    /** What the plugin declaring {@code type} says replaces it, or {@code null}. */
    public static Class<?> replacementOf(ValueGrammar grammar, Class<?> type) {
        try {
            return grammar.type(type).map(PluginType::replacedBy).orElse(null);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Whether an open set the bot can migrate holds values of {@code type}: what offers Migrate type… on one. */
    public static boolean held(List<ManagedValue<?>> declared, Class<?> type) {
        return declared.stream().anyMatch(set -> migratable(set) && same(set.type(), type));
    }

    private static boolean migratable(ManagedValue<?> set) {
        return set != null && set.isOpenSet() && !set.isEnum() && !set.isGrouped() && set.type() != null;
    }

    // --- one pair's steps ----------------------------------------------------------------------------------

    /** What mapping {@code from} to {@code to} asks of the author, or why it cannot be asked. */
    public static Shape shape(ValueGrammar grammar, Class<?> from, Class<?> to) {
        if (from == null || to == null) return new Shape.Refused("Pick both types.");
        if (ManagedMigrations.named(to, from)) return new Shape.Refused("Those are the same type.");
        if (from.isEnum() || to.isEnum()) {
            if (!from.isEnum() || !to.isEnum()) {
                return new Shape.Refused("An enum migrates only to another enum: each of its constants is paired"
                        + " with one of the new type's.");
            }
            return constants(to).isEmpty() ? new Shape.Refused(to.getSimpleName() + " has no constants.")
                    : new Shape.Constants(constants(from), constants(to));
        }
        Optional<ComponentType<?>> old = grammar.component(from);
        if (old.isEmpty()) {
            return new Shape.Refused(from.getSimpleName() + " is not written as a call, so it has no parts to take.");
        }
        Optional<ComponentType<?>> made = grammar.component(to);
        Optional<Factory> factory = made.flatMap(Factory::of);
        if (factory.isEmpty() || factory.get().kind() == Factory.Kind.RECEIVER) {
            return new Shape.Refused(to.getSimpleName() + " is not written as a call BotMaker can write.");
        }
        List<Part> parts = parts(old.get(), false);
        List<Part> wanted = parts(made.get(), true);
        if (parts.isEmpty() || !factory.get().fits(wanted.size())) {
            return new Shape.Refused(from.getSimpleName() + " or " + to.getSimpleName()
                    + " could not be taken apart.");
        }
        return new Shape.Parts(parts, wanted);
    }

    /** {@code component}'s parts, labelled; with {@code factoryOnly}, without the parts only a wither writes. */
    private static List<Part> parts(ComponentType<?> component, boolean factoryOnly) {
        List<Class<?>> types;
        try {
            types = component.componentTypes();
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
        if (types == null) return List.of();
        List<String> labels = ValueGrammar.partLabels(component);
        int count = factoryOnly ? types.size() - ValueGrammar.withersOf(component).size() : types.size();
        List<Part> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(new Part(i, labels.get(i), types.get(i)));
        return List.copyOf(out);
    }

    private static List<String> constants(Class<?> type) {
        return Arrays.stream(type.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).toList();
    }

    /**
     * Every choice but a fixed value that can fill the new type's part {@code target}: an old part of its type or
     * one migrating to it, and an old part its one-part factory builds it from.
     */
    public static List<Choice> offered(ValueGrammar grammar, Shape.Parts shape, int target) {
        Part wanted = shape.to().get(target);
        List<Choice> out = new ArrayList<>();
        for (Part part : shape.from()) {
            if (same(part.type(), wanted.type()) || nests(grammar, part.type(), wanted.type())) {
                out.add(new Choice.From(part.index()));
            }
            if (builds(grammar, part.type(), wanted.type())) out.add(new Choice.Built(part.index()));
        }
        return List.copyOf(out);
    }

    /**
     * A first guess for every part of the new type: the old part of its name, else one of its type not taken yet,
     * else one its factory builds it from, else its type's starting value; {@code null} where there is none.
     */
    public static List<Choice> matched(ValueGrammar grammar, Shape.Parts shape) {
        Set<Integer> used = new HashSet<>();
        List<Choice> out = new ArrayList<>();
        // Names first, over every part, so a part of the same name is never taken earlier by its type alone.
        for (Part wanted : shape.to()) {
            Choice named = offered(grammar, shape, wanted.index()).stream()
                    .filter(choice -> choice instanceof Choice.From from && !used.contains(from.part())
                            && shape.from().get(from.part()).label().equals(wanted.label()))
                    .findFirst().orElse(null);
            if (named instanceof Choice.From from) used.add(from.part());
            out.add(named);
        }
        for (Part wanted : shape.to()) {
            if (out.get(wanted.index()) != null) continue;
            List<Choice> offered = offered(grammar, shape, wanted.index());
            Choice chosen = offered.stream().filter(choice -> choice instanceof Choice.Built built
                            && shape.from().get(built.part()).label().equals(wanted.label())).findFirst()
                    .or(() -> offered.stream().filter(choice -> choice instanceof Choice.From from
                            && !used.contains(from.part())
                            && same(shape.from().get(from.part()).type(), wanted.type())).findFirst())
                    .or(() -> offered.stream().filter(Choice.Built.class::isInstance).findFirst())
                    .or(() -> startingValue(grammar, wanted.type()).map(Choice.Fixed::new))
                    .orElse(null);
            if (chosen instanceof Choice.From from) used.add(from.part());
            out.set(wanted.index(), chosen);
        }
        return java.util.Collections.unmodifiableList(out);
    }

    /**
     * {@code type}'s starting value, as a fixed choice starts: its declaration's, else a JDK value's zero —
     * {@code 0}, {@code false}, {@code ""}.
     */
    public static Optional<JavaValue> startingValue(ValueGrammar grammar, Class<?> type) {
        Optional<JavaValue> fresh = grammar.freshSpelling(type);
        if (fresh.isPresent() || type == null) return fresh;
        Class<?> primitive = java.lang.invoke.MethodType.methodType(type).unwrap().returnType();
        Object zero = type == String.class ? ""
                : primitive.isPrimitive() && primitive != void.class
                ? java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(primitive, 1), 0) : null;
        return zero == null ? Optional.empty() : grammar.spell(type, zero);
    }

    /** Each old constant paired with the new one of its name, else of its place, else the first. */
    public static Map<String, String> matched(Shape.Constants shape) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < shape.from().size(); i++) {
            String constant = shape.from().get(i);
            out.put(constant, shape.to().contains(constant) ? constant
                    : i < shape.to().size() ? shape.to().get(i) : shape.to().getFirst());
        }
        return out;
    }

    /** The pairs {@code choices} migrate a part through, each once: the steps the window opens next. */
    public static List<Map.Entry<Class<?>, Class<?>>> nested(Shape.Parts shape, List<Choice> choices) {
        Set<Map.Entry<Class<?>, Class<?>>> out = new LinkedHashSet<>();
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i) instanceof Choice.From from) {
                Class<?> have = shape.from().get(from.part()).type();
                Class<?> want = shape.to().get(i).type();
                if (!same(have, want)) out.add(Map.entry(have, want));
            }
        }
        return List.copyOf(out);
    }

    /** Why {@code choices} cannot be written yet, or {@code null}. */
    public static String problem(Shape.Parts shape, List<Choice> choices) {
        if (choices.size() != shape.to().size()) return "Every part of the new type needs a choice.";
        Map<Integer, Class<?>> forms = new LinkedHashMap<>();
        for (int i = 0; i < choices.size(); i++) {
            Choice choice = choices.get(i);
            Part wanted = shape.to().get(i);
            if (choice == null) return "Pick what " + wanted.label() + " is made of.";
            int part = switch (choice) {
                case Choice.From from -> from.part();
                case Choice.Built built -> built.part();
                case Choice.Fixed ignored -> -1;
            };
            if (part < 0) continue;
            Class<?> form = choice instanceof Choice.From ? wanted.type() : shape.from().get(part).type();
            Class<?> before = forms.putIfAbsent(part, form);
            if (before != null && !same(before, form)) {
                return shape.from().get(part).label() + " is used as two types; the method takes it as one.";
            }
        }
        return null;
    }

    /** Why {@code arms} cannot be written, or {@code null}. */
    public static String problem(Shape.Constants shape, Map<String, String> arms) {
        for (String constant : shape.from()) {
            if (!shape.to().contains(arms.get(constant))) return "Pick what " + constant + " becomes.";
        }
        return null;
    }

    private static boolean same(Class<?> a, Class<?> b) {
        return ManagedMigrations.named(a, b);
    }

    private static boolean nests(ValueGrammar grammar, Class<?> have, Class<?> want) {
        return !same(have, want) && !(shape(grammar, have, want) instanceof Shape.Refused);
    }

    /** Whether {@code want}'s factory takes exactly one part, of {@code have}'s type. */
    private static boolean builds(ValueGrammar grammar, Class<?> have, Class<?> want) {
        if (same(have, want)) return false;
        Optional<ComponentType<?>> component = grammar.component(want);
        Optional<Factory> factory = component.flatMap(Factory::of);
        if (factory.isEmpty() || factory.get().kind() == Factory.Kind.RECEIVER || !factory.get().fits(1)) return false;
        List<Part> parts = parts(component.get(), true);
        return parts.size() == 1 && same(parts.getFirst().type(), have);
    }

    // --- one step as a method ------------------------------------------------------------------------------

    /**
     * {@code step} as a {@code @Migrate} method of {@code ast} named {@code name}, every class by its simple name
     * and what that needs added to {@code imports}; empty when a part cannot be written.
     */
    public static Optional<MethodDeclaration> method(ValueGrammar grammar, AST ast, Step step, String name,
                                                     Set<String> imports) {
        Shape shape = shape(grammar, step.from(), step.to());
        Optional<org.eclipse.jdt.core.dom.Type> source = ValueTypes.node(ast, step.from(), imports);
        Optional<org.eclipse.jdt.core.dom.Type> target = ValueTypes.node(ast, step.to(), imports);
        if (source.isEmpty() || target.isEmpty()) return Optional.empty();

        MethodDeclaration method = ast.newMethodDeclaration();
        method.setName(ast.newSimpleName(name));
        SingleMemberAnnotation mark = ast.newSingleMemberAnnotation();
        mark.setTypeName(ast.newSimpleName(Migrate.class.getSimpleName()));
        TypeLiteral literal = ast.newTypeLiteral();
        literal.setType(source.get());
        mark.setValue(literal);
        modifiers(method).add(mark);
        modifiers(method).addAll(ast.newModifiers(Modifier.PUBLIC | Modifier.STATIC));
        method.setReturnType2(target.get());

        Optional<Expression> returned = switch (step) {
            case Step.Calls calls when shape instanceof Shape.Parts parts && problem(parts, calls.choices()) == null ->
                    calls(grammar, ast, method, step.to(), parts, calls.choices(), imports);
            case Step.Arms arms when shape instanceof Shape.Constants constants
                    && problem(constants, arms.arms()) == null -> arms(grammar, ast, method, arms, imports);
            default -> Optional.empty();
        };
        if (returned.isEmpty()) return Optional.empty();
        Block body = ast.newBlock();
        ReturnStatement ret = ast.newReturnStatement();
        ret.setExpression(returned.get());
        statements(body).add(ret);
        method.setBody(body);
        imports.add(Migrate.class.getName());
        return Optional.of(method);
    }

    /** One parameter per old part, typed as its part or as the type it migrates to; the new type's call over them. */
    private static Optional<Expression> calls(ValueGrammar grammar, AST ast, MethodDeclaration method, Class<?> to,
                                              Shape.Parts shape, List<Choice> choices, Set<String> imports) {
        List<String> names = parameterNames(shape.from());
        List<Class<?>> forms = new ArrayList<>(shape.from().stream().<Class<?>>map(Part::type).toList());
        List<JavaValue> written = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            Part wanted = shape.to().get(i);
            Optional<JavaValue> part = switch (choices.get(i)) {
                case Choice.From from -> {
                    forms.set(from.part(), wanted.type());
                    yield JavaValue.parse(names.get(from.part()));
                }
                case Choice.Built built -> JavaValue.parse(names.get(built.part()))
                        .flatMap(name -> grammar.call(wanted.type(), List.of(name)));
                case Choice.Fixed fixed -> Optional.ofNullable(fixed.value());
            };
            if (part.isEmpty()) return Optional.empty();
            written.add(part.get());
        }
        for (int i = 0; i < names.size(); i++) {
            Optional<org.eclipse.jdt.core.dom.Type> form = ValueTypes.node(ast, forms.get(i), imports);
            if (form.isEmpty()) return Optional.empty();
            SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
            parameter.setType(form.get());
            parameter.setName(ast.newSimpleName(names.get(i)));
            parameters(method).add(parameter);
        }
        Optional<JavaValue> call = grammar.call(to, written);
        call.ifPresent(value -> imports.addAll(value.imports()));
        return call.map(value -> value.copyInto(ast));
    }

    /** The one constant as {@code old}; a {@code switch} over it, one arm per old constant. */
    private static Optional<Expression> arms(ValueGrammar grammar, AST ast, MethodDeclaration method, Step.Arms step,
                                             Set<String> imports) {
        Optional<org.eclipse.jdt.core.dom.Type> form = ValueTypes.node(ast, step.from(), imports);
        if (form.isEmpty()) return Optional.empty();
        SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
        parameter.setType(form.get());
        parameter.setName(ast.newSimpleName("old"));
        parameters(method).add(parameter);

        SwitchExpression choice = ast.newSwitchExpression();
        choice.setExpression(ast.newSimpleName("old"));
        @SuppressWarnings("unchecked")
        List<Object> statements = choice.statements();
        for (String constant : constants(step.from())) {
            Optional<JavaValue> arm = constant(step.to(), step.arms().get(constant))
                    .flatMap(value -> grammar.spell(step.to(), value));
            if (arm.isEmpty()) return Optional.empty();
            SwitchCase label = ast.newSwitchCase();
            label.setSwitchLabeledRule(true);
            @SuppressWarnings("unchecked")
            List<Object> expressions = label.expressions();
            expressions.add(ast.newSimpleName(constant));
            statements.add(label);
            // An arrow arm's value: printed `case RED -> Tint.CRIMSON;`, read back as the arm's implicit yield.
            statements.add(ast.newExpressionStatement(arm.get().copyInto(ast)));
            imports.addAll(arm.get().imports());
        }
        return Optional.of(choice);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Optional<Object> constant(Class<?> type, String name) {
        try {
            return name == null ? Optional.empty() : Optional.of(Enum.valueOf((Class) type, name));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Each part's label as a Java name, unique: {@code file}, {@code threshold}; {@code part2} where it is not one. */
    static List<String> parameterNames(List<Part> parts) {
        Set<String> taken = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (Part part : parts) {
            String label = part.label();
            String name = label.isEmpty() ? "" : Character.toLowerCase(label.charAt(0)) + label.substring(1);
            if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) name = "part" + (part.index() + 1);
            String unique = name;
            for (int n = 2; !taken.add(unique); n++) unique = name + n;
            out.add(unique);
        }
        return List.copyOf(out);
    }

    // --- reading a method back -----------------------------------------------------------------------------

    /**
     * {@code method} as the window's step, or empty when it is not one the window writes — a body written by
     * hand, which the window offers to replace rather than show wrong.
     */
    public static Optional<Step> read(ValueGrammar grammar, ManagedMigrations.Method method) {
        if (method.problem() != null || !(method.source() instanceof Class<?> from)
                || !(method.target() instanceof Class<?> to)) {
            return Optional.empty();
        }
        return switch (shape(grammar, from, to)) {
            case Shape.Constants constants -> readArms(grammar, method, constants, from, to);
            case Shape.Parts parts -> readCalls(grammar, method, parts, from, to);
            case Shape.Refused ignored -> Optional.empty();
        };
    }

    private static Optional<Step> readArms(ValueGrammar grammar, ManagedMigrations.Method method,
                                           Shape.Constants shape, Class<?> from, Class<?> to) {
        Map<String, String> arms = new LinkedHashMap<>();
        for (String constant : shape.from()) {
            String arm = method.arms().getOrDefault(constant, method.arms().get(""));
            Optional<String> read = arm == null ? Optional.empty() : grammar.read(to, arm)
                    .filter(Enum.class::isInstance).map(value -> ((Enum<?>) value).name());
            if (read.isEmpty()) return Optional.empty();
            arms.put(constant, read.get());
        }
        return Optional.of(new Step.Arms(from, to, arms));
    }

    private static Optional<Step> readCalls(ValueGrammar grammar, ManagedMigrations.Method method, Shape.Parts shape,
                                            Class<?> from, Class<?> to) {
        if (method.params().size() != shape.from().size()) return Optional.empty();
        Expression body = JavaExpressions.parse(method.body());
        Optional<List<Expression>> arguments = arguments(grammar, to, body);
        if (arguments.isEmpty() || arguments.get().size() != shape.to().size()) return Optional.empty();
        List<String> names = method.params().stream().map(ManagedMigrations.Param::name).toList();
        List<Choice> choices = new ArrayList<>();
        for (int i = 0; i < shape.to().size(); i++) {
            Part wanted = shape.to().get(i);
            Expression argument = arguments.get().get(i);
            Choice choice = null;
            int named = argument instanceof SimpleName name ? names.indexOf(name.getIdentifier()) : -1;
            if (named >= 0) {
                choice = new Choice.From(named);
            } else {
                Optional<List<Expression>> built = arguments(grammar, wanted.type(), argument);
                if (built.isPresent() && built.get().size() == 1 && built.get().getFirst() instanceof SimpleName name
                        && names.indexOf(name.getIdentifier()) >= 0) {
                    choice = new Choice.Built(names.indexOf(name.getIdentifier()));
                }
            }
            if (choice == null) {
                choice = grammar.read(wanted.type(), new SourceNode(argument, argument.toString()))
                        .flatMap(value -> grammar.spell(wanted.type(), value)).map(Choice.Fixed::new).orElse(null);
            }
            if (choice == null) return Optional.empty();
            choices.add(choice);
        }
        Step.Calls step = new Step.Calls(from, to, List.copyOf(choices));
        return problem(shape, step.choices()) == null ? Optional.of(step) : Optional.empty();
    }

    /** {@code expression}'s arguments when it is a call to {@code type}'s own factory, else empty. */
    private static Optional<List<Expression>> arguments(ValueGrammar grammar, Class<?> type, Expression expression) {
        Optional<Factory> factory = grammar.component(type).flatMap(Factory::of);
        if (factory.isEmpty()) return Optional.empty();
        @SuppressWarnings("unchecked")
        List<Expression> arguments = switch (expression) {
            case MethodInvocation call when factory.get().matches(call, false) -> call.arguments();
            case ClassInstanceCreation creation when factory.get().matches(creation) -> creation.arguments();
            case null, default -> null;
        };
        return Optional.ofNullable(arguments).map(List::copyOf);
    }

    // --- putting the steps in the bot ----------------------------------------------------------------------

    /**
     * Every step's method written into the bot — over the method already mapping its pair, else into
     * {@value #CLASS} in {@code mainPackage}, created at {@code migrations} when missing — and the migration from
     * {@code from} to {@code to} run over that bot. The outcome rewrites both, or is refused with nothing
     * written: a method that cannot be written, a migration that cannot run, a result that would not compile.
     */
    public static Draft plan(BotIndex index, ValueGrammar grammar, List<ManagedValue<?>> declared, Path migrations,
                             String mainPackage, List<Step> steps, Class<?> from, Class<?> to) {
        return plan(index, grammar, declared, migrations, mainPackage, steps, from, to, Map.of());
    }

    /**
     * {@link #plan}, with {@code created} — files the bot lacks, the new type's holder ({@link #missingHolder}) —
     * added first and written with the rest.
     */
    public static Draft plan(BotIndex index, ValueGrammar grammar, List<ManagedValue<?>> declared, Path migrations,
                             String mainPackage, List<Step> steps, Class<?> from, Class<?> to,
                             Map<Path, String> created) {
        ManagedMigrations.Report none = new ManagedMigrations.Report(List.of(), List.of(), List.of());
        if (!created.isEmpty()) {
            Map<Path, String> with = new LinkedHashMap<>(index.sources());
            created.forEach(with::putIfAbsent);
            return withCreated(plan(index.replacing(with), grammar, declared, migrations, mainPackage, steps, from, to),
                    created, index);
        }
        Map<Path, String> written = new LinkedHashMap<>();
        List<ManagedMigrations.Method> existing = ManagedMigrations.methods(index, grammar);
        for (Step step : steps) {
            List<ManagedMigrations.Method> mapping = existing.stream()
                    .filter(method -> method.maps(step.from(), step.to())).toList();
            if (mapping.size() > 1) {
                return new Draft(new Refactor.Refused(String.join(" and ", mapping.stream()
                        .map(ManagedMigrations.Method::qualified).toList()) + " both migrate "
                        + step.from().getSimpleName() + " to " + step.to().getSimpleName()
                        + ": delete one, then apply again."), none, List.of());
            }
            Optional<ManagedMigrations.Method> over = mapping.stream().findFirst();
            Path file = over.map(ManagedMigrations.Method::file).orElse(migrations);
            String source = written.getOrDefault(file, index.sources().get(file));
            if (source == null) source = skeleton(mainPackage);
            String className = over.map(ManagedMigrations.Method::className).orElse(CLASS);
            String rewritten = withMethod(grammar, source, className, step, over.orElse(null));
            if (rewritten == null) {
                return new Draft(new Refactor.Refused("The migration from " + step.from().getSimpleName() + " to "
                        + step.to().getSimpleName() + " could not be written into " + className + "."), none,
                        List.of());
            }
            written.put(file, rewritten);
        }

        Map<Path, String> sources = new LinkedHashMap<>(index.sources());
        sources.putAll(written);
        BotIndex withMethods = index.replacing(sources);
        ManagedMigrations.Run run = ManagedMigrations.migrate(withMethods, grammar, declared, from, to);
        if (!(run.outcome() instanceof Refactor.Planned migrated)) return new Draft(run.outcome(), run.report(), List.of());

        Map<Path, String> rewrites = new LinkedHashMap<>(written);
        rewrites.putAll(migrated.rewrites());
        sources.putAll(migrated.rewrites());
        BotIndex after = index.replacing(sources);
        Optional<String> broken = index.firstNewError(after);
        if (broken.isPresent()) {
            return new Draft(new Refactor.Refused(migrated.summary() + " would stop the bot compiling ("
                    + broken.get() + "), so nothing was changed."), run.report(), List.of());
        }
        return new Draft(new Refactor.Planned(migrated.summary(), rewrites), run.report(),
                rows(index, after, grammar, declared, from, to, run.report()));
    }

    /** {@code draft} also writing each of {@code created} the bot did not have and the draft did not rewrite. */
    private static Draft withCreated(Draft draft, Map<Path, String> created, BotIndex before) {
        if (!(draft.outcome() instanceof Refactor.Planned planned)) return draft;
        Map<Path, String> rewrites = new LinkedHashMap<>();
        created.forEach((file, source) -> {
            if (!before.sources().containsKey(file)) rewrites.put(file, source);
        });
        rewrites.putAll(planned.rewrites());
        return new Draft(new Refactor.Planned(planned.summary(), rewrites), draft.report(), draft.rows());
    }

    /**
     * The open set {@code to}'s values would go in when the bot has no class for it — deleted by hand — else empty:
     * the holder its plugin writes is then created with the migration.
     */
    public static Optional<ManagedValue<?>> missingHolder(BotIndex index, List<ManagedValue<?>> declared, Class<?> to) {
        List<ManagedValue<?>> sets = declared.stream().filter(set -> migratable(set) && same(set.type(), to)).toList();
        return sets.size() == 1 && ManagedSets.holders(index, sets.getFirst().id()).isEmpty()
                ? Optional.of(sets.getFirst()) : Optional.empty();
    }

    /** Each old value beside what the migration wrote for it, or {@code ""} where it wrote nothing. */
    private static List<Row> rows(BotIndex before, BotIndex after, ValueGrammar grammar,
                                  List<ManagedValue<?>> declared, Class<?> from, Class<?> to,
                                  ManagedMigrations.Report report) {
        Map<String, String> made = new LinkedHashMap<>();
        for (ManagedValue<?> set : declared) {
            if (!migratable(set) || !same(set.type(), to)) continue;
            for (ManagedSets.Member member : ManagedSets.members(after, set.id(), grammar)) {
                if (report.written().contains(member.qualified())) made.put(member.name(), member.initializer());
            }
        }
        List<Row> out = new ArrayList<>();
        for (ManagedValue<?> set : declared) {
            if (!migratable(set) || !same(set.type(), from)) continue;
            for (ManagedSets.Member member : ManagedSets.members(before, set.id(), grammar)) {
                out.add(new Row(member.qualified(), Objects.requireNonNullElse(member.initializer(), ""),
                        made.getOrDefault(member.name(), "")));
            }
        }
        return List.copyOf(out);
    }

    /** {@code source} with {@code step}'s method in {@code className}, over {@code over}; {@code null} when not. */
    private static String withMethod(ValueGrammar grammar, String source, String className, Step step,
                                     ManagedMigrations.Method over) {
        CompilationUnit unit = BotParser.syntax(source);
        if (unit == null || !(ManagedSets.topLevel(unit, className) instanceof TypeDeclaration holder)) return null;
        MethodDeclaration replaced = over == null ? null : declared(holder, over);
        String name = replaced != null ? replaced.getName().getIdentifier() : freeName(holder, step);
        AST ast = unit.getAST();
        Set<String> imports = new LinkedHashSet<>();
        Optional<MethodDeclaration> method = method(grammar, ast, step, name, imports);
        if (method.isEmpty()) return null;
        ASTRewrite rewrite = ASTRewrite.create(ast);
        if (replaced != null) {
            rewrite.replace(replaced, method.get(), null);
        } else {
            ListRewrite body = rewrite.getListRewrite(holder, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
            body.insertLast(method.get(), null);
        }
        for (String each : imports) ImportManager.addImport(unit, rewrite, each);
        return SourceFormatter.formatChanged(source, AstRewriteHelper.applyRewrite(rewrite, source));
    }

    /** The declaration of {@code method} in {@code holder}: its name and its mark. */
    static MethodDeclaration declared(TypeDeclaration holder, ManagedMigrations.Method method) {
        MethodDeclaration[] found = {null};
        holder.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration each) {
                if (found[0] == null && each.getName().getIdentifier().equals(method.name())
                        && BotAnnotation.MIGRATE.on(each) != null
                        && method.className().equals(JavaParameterSource.enclosingTypeName(each))) {
                    found[0] = each;
                }
                return false;
            }
        });
        return found[0];
    }

    /** {@code picture}, else {@code pictureFromImageTemplate}, else that numbered: a name no method has. */
    private static String freeName(TypeDeclaration holder, Step step) {
        Set<String> taken = new HashSet<>();
        for (MethodDeclaration method : holder.getMethods()) taken.add(method.getName().getIdentifier());
        String to = step.to().getSimpleName();
        String base = Character.toLowerCase(to.charAt(0)) + to.substring(1);
        if (!SourceVersion.isIdentifier(base) || SourceVersion.isKeyword(base)) base = "migrated" + to;
        if (taken.add(base)) return base;
        String longer = base + "From" + step.from().getSimpleName();
        String unique = longer;
        for (int n = 2; !taken.add(unique); n++) unique = longer + n;
        return unique;
    }

    /** A new {@value #CLASS}: the class, its doc comment saying what it is for, and nothing else. */
    @SuppressWarnings("unchecked")
    static String skeleton(String mainPackage) {
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        CompilationUnit unit = ast.newCompilationUnit();
        PackageDeclaration declaration = ast.newPackageDeclaration();
        declaration.setName(ast.newName(mainPackage));
        unit.setPackage(declaration);
        TypeDeclaration type = ast.newTypeDeclaration();
        type.setName(ast.newSimpleName(CLASS));
        type.modifiers().addAll(ast.newModifiers(Modifier.PUBLIC | Modifier.FINAL));
        Javadoc javadoc = ast.newJavadoc();
        for (String line : List.of("How BotMaker copies a plugin's retired types into their replacements (Review ▸"
                        + " Migrate…).",
                "<p>Each @Migrate method maps one type to another. BotMaker reads it and never runs it; the bot",
                "never calls it. Delete a method, or this class, once no value of its old type is left.")) {
            TagElement tag = ast.newTagElement();
            TextElement text = ast.newTextElement();
            text.setText(line);
            tag.fragments().add(text);
            javadoc.tags().add(tag);
        }
        type.setJavadoc(javadoc);
        MethodDeclaration constructor = ast.newMethodDeclaration();
        constructor.setConstructor(true);
        constructor.setName(ast.newSimpleName(CLASS));
        constructor.modifiers().addAll(ast.newModifiers(Modifier.PRIVATE));
        constructor.setBody(ast.newBlock());
        type.bodyDeclarations().add(constructor);
        unit.types().add(type);
        StringBuilder out = new StringBuilder();
        SourceFormatter.format(unit.toString()).lines().forEach(line -> out.append(line.stripTrailing()).append('\n'));
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> modifiers(MethodDeclaration method) {
        return method.modifiers();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> parameters(MethodDeclaration method) {
        return method.parameters();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> statements(Block block) {
        return block.statements();
    }
}
