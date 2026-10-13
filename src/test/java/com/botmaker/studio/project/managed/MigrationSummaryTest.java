package com.botmaker.studio.project.managed;

import com.botmaker.studio.project.managed.MigrationTypes.OldShot;
import com.botmaker.studio.project.managed.MigrationTypes.Shot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What list_migrations and set_plugin_version say of the bot's migrations. */
class MigrationSummaryTest {

    private static final String OLD = "OldShot (com.botmaker.studio.project.managed.MigrationTypes.OldShot)";
    private static final String NEW = "Shot (com.botmaker.studio.project.managed.MigrationTypes.Shot)";

    private static ManagedMigrations.Method method(String name, String problem) {
        return new ManagedMigrations.Method(Path.of("Migrations.java"), "Migrations", name, OldShot.class, Shot.class,
                List.of(), "new Shot(old.file(), old.threshold(), false)", Map.of(), problem);
    }

    @Test
    void aTypeIsNamedAsReadAndAsATakesItBack() {
        assertEquals(OLD, MigrationSummary.type(OldShot.class));
        assertEquals("String (java.lang.String)", MigrationSummary.type(String.class));
        assertEquals("nothing", MigrationSummary.type(null));
    }

    @Test
    void aRetiredTypeSaysItsReplacementAndHowFarItIsCopied() {
        assertEquals("2 values of " + OLD + ", a type its plugin retired for " + NEW + "; none copied yet.",
                MigrationSummary.pending(new MigrationSteps.Pending(OldShot.class, Shot.class, 2, 0)));
        assertEquals("1 value of " + OLD + ", a type its plugin retired, naming no replacement; all copied.",
                MigrationSummary.pending(new MigrationSteps.Pending(OldShot.class, null, 1, 1)));
        assertTrue(MigrationSummary.pending(new MigrationSteps.Pending(OldShot.class, Shot.class, 3, 1))
                .endsWith("; 1 copied."));
    }

    @Test
    void aStrandedSetSaysWhetherItCanBeKept() {
        String kept = MigrationSummary.stranded(new MigrationSteps.Stranded("SHOTS", OldShot.class, Shot.class, 2,
                true), "SDK 2.0.0");
        assertEquals("2 values of " + OLD + " not copied yet: SDK 2.0.0 drops their set, so the move removes them "
                + "(one the bot's code names is kept, unmarked) or resets them; their plugin points to " + NEW
                + ". To keep them, migrate them first, on the version the bot has now (list_migrations).", kept);
        String lost = MigrationSummary.stranded(new MigrationSteps.Stranded("SHOTS", null, null, 1, false),
                "SDK 2.0.0");
        assertTrue(lost.startsWith("1 value of the set SHOTS not copied yet: "), lost);
        assertTrue(lost.endsWith("They cannot be migrated; the version saved before the move keeps them."), lost);
    }

    @Test
    void theListingNamesEachMethodAndTheOnesNothingNeeds() {
        ManagedMigrations.Method shot = method("shot", null);
        ManagedMigrations.Method broken = method("again", "both migrate OldShot to Shot; keep one.");
        String text = MigrationSummary.listing(List.of(new MigrationSteps.Pending(OldShot.class, Shot.class, 2, 0)),
                List.of(shot, broken), List.of(method("shot", null), broken));
        assertEquals("""
                To migrate:
                - 2 values of %1$s, a type its plugin retired for %2$s; none copied yet.
                The bot's @Migrate methods:
                - Migrations.shot: %1$s → %2$s (nothing needs it now)
                - Migrations.again: %1$s → %2$s — cannot be used: both migrate OldShot to Shot; keep one."""
                .formatted(OLD, NEW), text);
        assertEquals("No value of a retired type is left to migrate.\nThe bot has no @Migrate method.",
                MigrationSummary.listing(List.of(), List.of(), List.of()));
    }
}
