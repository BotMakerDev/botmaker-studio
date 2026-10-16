package com.botmaker.studio.project.managed;

import com.botmaker.studio.nav.Refactor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * The values of retired types as sentences, for the assistant's {@code migration list}, {@code migration
 * copy_values} and {@code plugins set_version} (2026-10-13): what Migrate's values section, the migration window's
 * preview and the upgrade's dropped-values warning say, with each type named so a later tool call can name it back.
 */
public final class MigrationSummary {

    private MigrationSummary() {}

    /** {@code OldShot (com.bot.OldShot)}: what a person reads, and the name a tool takes back. */
    public static String type(Class<?> type) {
        if (type == null) return "nothing";
        String full = type.getCanonicalName() == null ? type.getName() : type.getCanonicalName();
        return type.getSimpleName().equals(full) ? full : type.getSimpleName() + " (" + full + ")";
    }

    /** {@code 312 values of ImageTemplate (…), a type its plugin retired; 12 copied.} */
    public static String pending(MigrationSteps.Pending pending) {
        return pending.values() + (pending.values() == 1 ? " value of " : " values of ") + type(pending.from())
                + ", a type its plugin retired"
                + (pending.copied() == 0 ? "; none copied yet."
                : pending.copied() == pending.values() ? "; all copied." : "; " + pending.copied() + " copied.");
    }

    /**
     * What a move to {@code to} does to one set's values, and how to keep them: the copy on the version the bot
     * has now, when one of the bound types can take them.
     */
    public static String stranded(MigrationSteps.Stranded set, String to) {
        String what = set.type() == null ? "the set " + set.id() : type(set.type());
        return set.values() + (set.values() == 1 ? " value of " : " values of ") + what + " not copied yet: " + to
                + " drops their set, so the move removes them (one the bot's code names is kept, unmarked) or "
                + "resets them"
                + (set.migratable() ? ". To keep them, copy them first, on the version the bot has now "
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
}
