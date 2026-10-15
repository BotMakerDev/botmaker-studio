package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.plugin.grammar.ValueGrammar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The bot's migrations as sentences, for the assistant's {@code list_migrations}, {@code read_migration} and
 * {@code set_plugin_version} (2026-10-13): what the Review tab's retired rows, the migration window's steps and
 * methods and the upgrade's dropped-values warning say, with each type named so a later tool call can name it back.
 */
public final class MigrationSummary {

    private MigrationSummary() {}

    /** {@code OldShot (com.bot.OldShot)}: what a person reads, and the name a tool takes back. */
    public static String type(Class<?> type) {
        if (type == null) return "nothing";
        String full = type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
        return type.getSimpleName().equals(full) ? full : type.getSimpleName() + " (" + full + ")";
    }

    /** {@code 312 values of ImageTemplate (…) are of a retired type; its plugin points to Picture (…); 12 copied.} */
    public static String pending(MigrationSteps.Pending pending) {
        return pending.values() + (pending.values() == 1 ? " value of " : " values of ") + type(pending.from())
                + ", a type its plugin retired"
                + (pending.to() == null ? ", naming no replacement" : " for " + type(pending.to()))
                + (pending.copied() == 0 ? "; none copied yet."
                : pending.copied() == pending.values() ? "; all copied." : "; " + pending.copied() + " copied.");
    }

    /** {@code Migrations.shot: OldShot (…) → Shot (…)}, and why it cannot be used when it cannot. */
    public static String method(ManagedMigrations.Method method) {
        return method.qualified() + ": " + type(method.source()) + " → " + type(method.target())
                + (method.problem() == null ? "" : " — cannot be used: " + method.problem());
    }

    private static String type(java.lang.reflect.Type type) {
        return type instanceof Class<?> c ? type(c) : type == null ? "nothing" : type.getTypeName();
    }

    /**
     * What a move to {@code to} does to one set's values, and how to keep them: the migration on the version the
     * bot has now, when one of the bound types can take them.
     */
    public static String stranded(MigrationSteps.Stranded set, String to) {
        String what = set.type() == null ? "the set " + set.id() : type(set.type());
        return set.values() + (set.values() == 1 ? " value of " : " values of ") + what + " not copied yet: " + to
                + " drops their set, so the move removes them (one the bot's code names is kept, unmarked) or "
                + "resets them"
                + (set.to() != null ? "; their plugin points to " + type(set.to()) : "")
                + (set.migratable() ? ". To keep them, migrate them first, on the version the bot has now "
                + "(migration list)."
                : ". They cannot be migrated; the version saved before the move keeps them.");
    }

    /**
     * The class a tool names back: as {@link #type} says it, by its full name, or by its simple name when one of
     * {@code known} alone has it; empty when none.
     */
    public static Optional<Class<?>> named(String name, ClassLoader loader, Collection<Class<?>> known) {
        String text = name == null ? "" : name.strip();
        int open = text.lastIndexOf('(');
        if (open > 0 && text.endsWith(")")) text = text.substring(open + 1, text.length() - 1).strip();
        if (text.isEmpty()) return Optional.empty();
        String wanted = text;
        List<Class<?>> simple = new ArrayList<>();
        for (Class<?> type : new LinkedHashSet<>(known)) {
            if (type == null) continue;
            if (wanted.equals(type.getCanonicalName()) || wanted.equals(type.getName())) return Optional.of(type);
            if (wanted.equals(type.getSimpleName())) simple.add(type);
        }
        if (!wanted.contains(".")) return simple.size() == 1 ? Optional.of(simple.getFirst()) : Optional.empty();
        // A nested class's canonical name: each trailing dot may be the binary name's '$'.
        for (String binary = wanted; ; ) {
            try {
                return Optional.of(Class.forName(binary, false, loader));
            } catch (ClassNotFoundException | LinkageError e) {
                int dot = binary.lastIndexOf('.');
                if (dot < 0) return Optional.empty();
                binary = binary.substring(0, dot) + "$" + binary.substring(dot + 1);
            }
        }
    }

    /**
     * The whole of {@code read_migration}: how {@code from}'s values become {@code to}'s — {@code null} for its
     * plugin's replacement, else the first type that fits, as the migration window preselects — part by part or
     * constant by constant, as the bot's {@code @Migrate} method has it or else as first guessed, with what else
     * each can be, the pairs it goes through and why it cannot be written yet.
     */
    public static String mapping(ValueGrammar grammar, List<ManagedValue<?>> declared,
                                 List<ManagedMigrations.Method> methods, Class<?> from, Class<?> to) {
        List<Class<?>> targets = MigrationSteps.targets(grammar, declared, from);
        Class<?> replacement = MigrationSteps.replacementOf(grammar, from);
        boolean held = MigrationSteps.held(declared, from);
        List<String> lines = new ArrayList<>();
        if (to == null) {
            // As the window preselects: targets lead with the replacement, unless its shape cannot be mapped.
            if (replacement != null && !targets.contains(replacement)) {
                lines.add(type(from) + " cannot be mapped to " + type(replacement) + ", the replacement its plugin "
                        + "names: " + reason(MigrationSteps.shape(grammar, from, replacement)));
            }
            if (targets.isEmpty()) {
                lines.add("No type a plugin declares can take " + type(from) + "'s values.");
                return String.join("\n", lines);
            }
            to = targets.getFirst();
        }
        lines.add(type(from) + " → " + type(to)
                + (ManagedMigrations.named(to, replacement) ? ", the replacement its plugin names." : "."));
        if (held && !targets.contains(to)) {
            lines.add("The migration window does not offer " + to.getSimpleName() + " for " + from.getSimpleName()
                    + "'s values.");
        }
        if (held && targets.size() > 1) {
            lines.add("Its values can also go into: " + String.join(", ", targets.stream()
                    .filter(each -> !ManagedMigrations.named(each, replacement)).map(MigrationSummary::type)
                    .toList()) + ".");
        }
        Class<?> made = to;
        Optional<ManagedMigrations.Method> existing = methods.stream().filter(m -> m.maps(from, made)).findFirst();
        Optional<MigrationSteps.Step> saved = existing.flatMap(method -> MigrationSteps.read(grammar, method));
        String problem;
        List<Map.Entry<Class<?>, Class<?>>> nested = List.of();
        switch (MigrationSteps.shape(grammar, from, to)) {
            case MigrationSteps.Shape.Refused refused -> {
                lines.add("It cannot be mapped: " + refused.reason());
                return String.join("\n", lines);
            }
            case MigrationSteps.Shape.Parts parts -> {
                List<MigrationSteps.Choice> choices = MigrationMapping.seeded(grammar, methods, parts, from, made);
                lines.add("Each part of " + to.getSimpleName() + " and what it is made of, then what else it could "
                        + "be:");
                for (MigrationSteps.Part part : parts.to()) {
                    List<String> others = new ArrayList<>();
                    for (MigrationSteps.Choice offered : MigrationSteps.offered(grammar, parts, part.index())) {
                        if (!offered.equals(choices.get(part.index()))) others.add(choice(parts, offered));
                    }
                    others.add("fixed <" + part.type().getSimpleName() + ">");
                    lines.add("- " + part.describe() + ": " + choice(parts, choices.get(part.index())) + " — or: "
                            + String.join("; ", others));
                }
                problem = MigrationSteps.problem(parts, choices);
                nested = MigrationSteps.nested(parts, choices);
            }
            case MigrationSteps.Shape.Constants constants -> {
                Map<String, String> arms = MigrationMapping.seeded(grammar, methods, constants, from, made);
                lines.add("Each constant of " + from.getSimpleName() + ", and what it becomes — each can become "
                        + String.join(", ", constants.to()) + ":");
                arms.forEach((old, now) -> lines.add("- " + old + " → " + (now == null ? "nothing yet" : now)));
                problem = MigrationSteps.problem(constants, arms);
            }
        }
        if (saved.isPresent()) {
            lines.add("Saved in the bot as " + existing.get().qualified() + ".");
        } else if (existing.isPresent()) {
            ManagedMigrations.Method method = existing.get();
            lines.add(method.qualified() + (method.problem() != null ? " maps them but cannot be used: "
                    + method.problem() : " maps them, written by hand, so it is not shown")
                    + "; writing this mapping replaces it. The mapping above is a first guess.");
        } else {
            lines.add("Not in the bot yet: the mapping above is a first guess.");
        }
        if (!nested.isEmpty()) {
            lines.add("It goes through " + String.join(", ", nested.stream()
                    .map(pair -> type(pair.getKey()) + " → " + type(pair.getValue())).toList())
                    + ", each a mapping of its own (migration read).");
        }
        lines.add(problem == null ? "Ready to write (migration set)." : "Not ready: " + problem);
        return String.join("\n", lines);
    }

    /** The most values {@link #draft} lists one by one. */
    static final int DRAFT_ROWS = 40;

    /**
     * What applying {@code draft} would do, as the window's preview table and status say it: each value as it is
     * and as copied, those kept as the author made them and those not migrated.
     */
    public static String draft(MigrationSteps.Draft draft) {
        List<String> lines = new ArrayList<>();
        if (draft.outcome() instanceof Refactor.Refused refused) lines.add("Refused: " + refused.reason());
        if (!draft.rows().isEmpty()) {
            lines.add(draft.rows().size() + (draft.rows().size() == 1 ? " value, as it is → as copied:"
                    : " values, each as it is → as copied:"));
            draft.rows().stream().limit(DRAFT_ROWS)
                    .forEach(row -> lines.add("- " + row.name() + ": " + row.before() + " → " + row.after()));
            if (draft.rows().size() > DRAFT_ROWS) lines.add("… and " + (draft.rows().size() - DRAFT_ROWS) + " more.");
        }
        ManagedMigrations.Report report = draft.report();
        if (!report.kept().isEmpty()) lines.add("Kept as you made them: " + String.join(", ", report.kept()) + ".");
        if (!report.failed().isEmpty()) lines.add("Not migrated: " + String.join("; ", report.failed()) + ".");
        return String.join("\n", lines);
    }

    private static String reason(MigrationSteps.Shape shape) {
        return shape instanceof MigrationSteps.Shape.Refused refused ? refused.reason() : "its parts do not fit.";
    }

    /** {@code from file}, {@code built from file}, {@code fixed false}: one part's choice, as a tool takes it back. */
    private static String choice(MigrationSteps.Shape.Parts shape, MigrationSteps.Choice choice) {
        return switch (choice) {
            case null -> "nothing yet";
            case MigrationSteps.Choice.From from -> "from " + shape.from().get(from.part()).label();
            case MigrationSteps.Choice.Built built -> "built from " + shape.from().get(built.part()).label();
            case MigrationSteps.Choice.Fixed fixed -> "fixed " + (fixed.value() == null ? "?" : fixed.value().source());
        };
    }

    /** The whole of {@code list_migrations}: the retired types, the methods the bot keeps and those nothing needs. */
    public static String listing(List<MigrationSteps.Pending> pending, List<ManagedMigrations.Method> methods,
                                 List<ManagedMigrations.Method> unneeded) {
        List<String> lines = new ArrayList<>();
        if (pending.isEmpty()) lines.add("No value of a retired type is left to migrate.");
        else {
            lines.add("To migrate:");
            pending.forEach(p -> lines.add("- " + pending(p)));
        }
        if (methods.isEmpty()) lines.add("The bot has no @Migrate method.");
        else {
            lines.add("The bot's @Migrate methods:");
            // A method that cannot be used says why; that it is unneeded too would hide it.
            methods.forEach(m -> lines.add("- " + method(m)
                    + (m.problem() == null && unneeded.contains(m) ? " (nothing needs it now)" : "")));
        }
        return String.join("\n", lines);
    }
}
