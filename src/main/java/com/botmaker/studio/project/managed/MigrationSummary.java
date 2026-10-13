package com.botmaker.studio.project.managed;

import java.util.ArrayList;
import java.util.List;

/**
 * The bot's migrations as sentences, for the assistant's {@code list_migrations} and {@code set_plugin_version}
 * (2026-10-13): what the Review tab's retired rows, the migration window's methods and the upgrade's dropped-values
 * warning say, with each type named so a later tool call can name it back. Pure.
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
                + "(list_migrations)."
                : ". They cannot be migrated; the version saved before the move keeps them.");
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
