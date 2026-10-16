package com.botmaker.studio.project.managed;

import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.MigrationSteps.Choice;
import com.botmaker.studio.project.managed.MigrationSteps.Shape;
import com.botmaker.studio.project.managed.MigrationSteps.Step;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The migration window's steps for a pair as the assistant writes them (2026-10-13; since 2026-10-16
 * {@code migration copy_values} gives no lines, the bot's rule being the mapping): each pair seeded as the window
 * seeds it — the bot's {@code @Migrate} method, else the first guess — then changed by the lines given:
 * {@code grey: fixed true},
 * {@code area: from area}, {@code tag: built from name}, {@code RED: CRIMSON}. A line for a pair a part goes
 * through starts with either type's simple name ({@code Area.w: fixed 0}). Pure.
 */
public final class MigrationMapping {

    private MigrationMapping() {}

    /** The steps to plan, the migrated pair's first, or why they cannot be: never both. */
    public record Asked(List<Step> steps, String problem) {

        static Asked refused(String problem) {
            return new Asked(List.of(), problem);
        }
    }

    /** One line given: {@code prefix} the type it names, {@code null} for the migrated pair. */
    private record Line(String text, String prefix, String key, String value) {}

    /** The parts' choices the window opens {@code shape} with: the bot's method's, else the first guess. */
    public static List<Choice> seeded(ValueGrammar grammar, List<ManagedMigrations.Method> methods, Shape.Parts shape,
                                      Class<?> from, Class<?> to) {
        return saved(grammar, methods, from, to).filter(Step.Calls.class::isInstance)
                .map(step -> ((Step.Calls) step).choices()).orElseGet(() -> MigrationSteps.matched(grammar, shape));
    }

    /** The arms the window opens {@code shape} with: the bot's method's, else the first guess. */
    public static Map<String, String> seeded(ValueGrammar grammar, List<ManagedMigrations.Method> methods,
                                             Shape.Constants shape, Class<?> from, Class<?> to) {
        return saved(grammar, methods, from, to).filter(Step.Arms.class::isInstance)
                .map(step -> ((Step.Arms) step).arms()).orElseGet(() -> MigrationSteps.matched(shape));
    }

    /** The bot's method mapping the pair, read back as the window's step; empty when none or written by hand. */
    public static Optional<Step> saved(ValueGrammar grammar, List<ManagedMigrations.Method> methods, Class<?> from,
                                       Class<?> to) {
        return methods.stream().filter(m -> m.maps(from, to)).findFirst()
                .flatMap(method -> MigrationSteps.read(grammar, method));
    }

    /**
     * {@code from} → {@code to} and every pair its parts go through, each seeded and changed by {@code mapping}'s
     * lines, as far as the choices reach — what the window's Apply plans.
     */
    public static Asked steps(ValueGrammar grammar, List<ManagedMigrations.Method> methods, Class<?> from,
                              Class<?> to, String mapping) {
        List<Line> lines = new ArrayList<>();
        // Lines only: a fixed value may hold ';' or ':' of its own.
        for (String text : (mapping == null ? "" : mapping).split("\n")) {
            if (text.isBlank()) continue;
            Optional<Line> line = line(text.strip());
            if (line.isEmpty()) {
                return Asked.refused("\"" + text.strip() + "\" is not a line of a mapping: write part: from <part>, "
                        + "part: built from <part>, part: fixed <value>, or OLD: NEW for a constant.");
            }
            lines.add(line.get());
        }
        Set<Line> used = new LinkedHashSet<>();
        List<Step> out = new ArrayList<>();
        Set<Map.Entry<Class<?>, Class<?>>> seen = new LinkedHashSet<>();
        List<Map.Entry<Class<?>, Class<?>>> queue = new ArrayList<>(List.of(Map.entry(from, to)));
        while (!queue.isEmpty()) {
            Map.Entry<Class<?>, Class<?>> pair = queue.removeFirst();
            if (!seen.add(pair)) continue;
            Class<?> source = pair.getKey();
            Class<?> made = pair.getValue();
            String name = source.getSimpleName() + " → " + made.getSimpleName() + ": ";
            boolean first = out.isEmpty();
            List<Line> mine = lines.stream().filter(line -> line.prefix() == null ? first
                    : line.prefix().equals(source.getSimpleName()) || line.prefix().equals(made.getSimpleName()))
                    .toList();
            used.addAll(mine);
            switch (MigrationSteps.shape(grammar, source, made)) {
                case Shape.Refused refused -> {
                    return Asked.refused(name + refused.reason());
                }
                case Shape.Parts parts -> {
                    List<Choice> choices = new ArrayList<>(seeded(grammar, methods, parts, source, made));
                    for (Line line : mine) {
                        String wrong = choose(grammar, parts, choices, line);
                        if (wrong != null) return Asked.refused(name + wrong);
                    }
                    String problem = MigrationSteps.problem(parts, choices);
                    if (problem != null) return Asked.refused(name + problem);
                    out.add(new Step.Calls(source, made, Collections.unmodifiableList(choices)));
                    queue.addAll(MigrationSteps.nested(parts, choices));
                }
                case Shape.Constants constants -> {
                    Map<String, String> arms = new LinkedHashMap<>(seeded(grammar, methods, constants, source, made));
                    for (Line line : mine) {
                        if (!constants.from().contains(line.key())) {
                            return Asked.refused(name + source.getSimpleName() + " has no constant " + line.key()
                                    + "; its constants: " + String.join(", ", constants.from()) + ".");
                        }
                        if (!constants.to().contains(line.value())) {
                            return Asked.refused(name + line.key() + " cannot become " + line.value() + "; it can "
                                    + "become " + String.join(", ", constants.to()) + ".");
                        }
                        arms.put(line.key(), line.value());
                    }
                    String problem = MigrationSteps.problem(constants, arms);
                    if (problem != null) return Asked.refused(name + problem);
                    out.add(new Step.Arms(source, made, Collections.unmodifiableMap(arms)));
                }
            }
        }
        List<String> unused = lines.stream().filter(line -> !used.contains(line)).map(Line::text).toList();
        if (!unused.isEmpty()) {
            return Asked.refused(String.join("; ", unused) + ": no pair this migration goes through has "
                    + (unused.size() == 1 ? "it" : "them") + "; prefix a line with its type's simple name "
                    + "(the pairs it goes through).");
        }
        return new Asked(List.copyOf(out), null);
    }

    /** {@code Area.w: fixed 0} or {@code grey → fixed true}, split; empty when it is neither. */
    private static Optional<Line> line(String text) {
        // The first separator: what follows it is the value, which may hold any of them.
        int split = -1;
        int width = 1;
        for (String separator : List.of(":", "→", "->")) {
            int at = text.indexOf(separator);
            if (at >= 0 && (split < 0 || at < split)) {
                split = at;
                width = separator.length();
            }
        }
        if (split <= 0) return Optional.empty();
        String key = text.substring(0, split).strip();
        String value = text.substring(split + width).strip();
        if (key.isEmpty() || value.isEmpty()) return Optional.empty();
        int dot = key.lastIndexOf('.');
        return Optional.of(dot < 0 ? new Line(text, null, key, value)
                : new Line(text, key.substring(0, dot).strip(), key.substring(dot + 1).strip(), value));
    }

    /** Sets the part {@code line} names to its choice; why it cannot be, or {@code null}. */
    private static String choose(ValueGrammar grammar, Shape.Parts shape, List<Choice> choices, Line line) {
        MigrationSteps.Part wanted = shape.to().stream().filter(part -> part.label().equals(line.key())).findFirst()
                .orElse(null);
        if (wanted == null) {
            return "the new type has no part " + line.key() + "; its parts: " + String.join(", ",
                    shape.to().stream().map(MigrationSteps.Part::label).toList()) + ".";
        }
        String value = line.value();
        Choice choice;
        if (value.startsWith("fixed ")) {
            String java = value.substring("fixed ".length()).strip();
            Optional<Choice> fixed = grammar.read(wanted.type(), java)
                    .flatMap(read -> grammar.spell(wanted.type(), read)).map(Choice.Fixed::new);
            if (fixed.isEmpty()) return java + " is not a " + wanted.type().getSimpleName() + " for " + wanted.label() + ".";
            choice = fixed.get();
        } else {
            boolean built = value.startsWith("built from ");
            if (!built && !value.startsWith("from ")) {
                return wanted.label() + ": write from <part>, built from <part> or fixed <value>, not " + value + ".";
            }
            String label = value.substring(built ? "built from ".length() : "from ".length()).strip();
            MigrationSteps.Part old = shape.from().stream().filter(part -> part.label().equals(label)).findFirst()
                    .orElse(null);
            if (old == null) {
                return "the old type has no part " + label + "; its parts: " + String.join(", ",
                        shape.from().stream().map(MigrationSteps.Part::label).toList()) + ".";
            }
            choice = built ? new Choice.Built(old.index()) : new Choice.From(old.index());
            if (!MigrationSteps.offered(grammar, shape, wanted.index()).contains(choice)) {
                return wanted.describe() + " cannot be " + value + " (" + old.describe() + ").";
            }
        }
        choices.set(wanted.index(), choice);
        return null;
    }
}
