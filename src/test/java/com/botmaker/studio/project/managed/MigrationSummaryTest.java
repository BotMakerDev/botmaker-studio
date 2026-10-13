package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.MigrationTypes.Area;
import com.botmaker.studio.project.managed.MigrationTypes.Frame;
import com.botmaker.studio.project.managed.MigrationTypes.OldArea;
import com.botmaker.studio.project.managed.MigrationTypes.OldFrame;
import com.botmaker.studio.project.managed.MigrationTypes.OldShot;
import com.botmaker.studio.project.managed.MigrationTypes.OldTint;
import com.botmaker.studio.project.managed.MigrationTypes.Shot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What list_migrations and set_plugin_version say of the bot's migrations. */
class MigrationSummaryTest {

    private static final String OLD = "OldShot (com.botmaker.studio.project.managed.MigrationTypes.OldShot)";
    private static final String NEW = "Shot (com.botmaker.studio.project.managed.MigrationTypes.Shot)";

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(MigrationTypes.types(), List.of());

    private static final List<ManagedValue<?>> DECLARED = List.of(
            ManagedValue.openSet(TestValue.Id.OLD_SHOTS).of(OldShot.class).in("OldShots").because("Old."),
            ManagedValue.openSet(TestValue.Id.SHOTS).of(Shot.class).in("Shots").because("New."));

    private static final List<ManagedMigrations.Param> PARAMS = List.of(new ManagedMigrations.Param("file",
            String.class), new ManagedMigrations.Param("threshold", int.class));

    private static ManagedMigrations.Method method(String name, String problem) {
        return new ManagedMigrations.Method(Path.of("Migrations.java"), "Migrations", name, OldShot.class, Shot.class,
                PARAMS, "new MigrationTypes.Shot(file, threshold, true)", Map.of(), problem);
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
    void aTypeNamedBackResolvesByFullOrUniqueSimpleName() {
        ClassLoader loader = getClass().getClassLoader();
        assertEquals(Optional.of(OldShot.class), MigrationSummary.named(OLD, loader, List.of()));
        assertEquals(Optional.of(OldShot.class), MigrationSummary.named(OldShot.class.getCanonicalName(), loader,
                List.of()));
        assertEquals(Optional.of(Shot.class), MigrationSummary.named(" Shot ", loader, List.of(OldShot.class,
                Shot.class)));
        assertEquals(Optional.empty(), MigrationSummary.named("Shot", loader, List.of()));
        assertEquals(Optional.empty(), MigrationSummary.named("com.bot.Nothing", loader, List.of()));
    }

    @Test
    void aPairIsReadAsTheWindowSeedsIt() {
        String text = MigrationSummary.mapping(GRAMMAR, DECLARED, List.of(), OldShot.class, null);
        List<String> lines = text.lines().toList();
        assertEquals(OLD + " → " + NEW + ", the replacement its plugin names.", lines.getFirst());
        assertTrue(lines.contains("- file (String): from file — or: fixed <String>"), text);
        assertTrue(lines.contains("- grey (boolean): fixed false — or: fixed <boolean>"), text);
        assertTrue(lines.contains("Not in the bot yet: the mapping above is a first guess."), text);
        assertEquals("Ready to write.", lines.getLast());

        String area = MigrationSummary.mapping(GRAMMAR, DECLARED, List.of(), OldShot.class, Area.class);
        assertTrue(area.contains("The migration window does not offer Area for OldShot's values."), area);
    }

    @Test
    void aSavedMethodSeedsThePairAndOneByHandIsSaidSo() {
        String saved = MigrationSummary.mapping(GRAMMAR, DECLARED, List.of(method("shot", null)), OldShot.class,
                Shot.class);
        assertTrue(saved.contains("- grey (boolean): fixed true — or: fixed <boolean>"), saved);
        assertTrue(saved.contains("Saved in the bot as Migrations.shot."), saved);

        ManagedMigrations.Method byHand = new ManagedMigrations.Method(Path.of("Migrations.java"), "Migrations",
                "shot", OldShot.class, Shot.class, PARAMS, "Shots.make(file)", Map.of(), null);
        String hand = MigrationSummary.mapping(GRAMMAR, DECLARED, List.of(byHand), OldShot.class, Shot.class);
        assertTrue(hand.contains("Migrations.shot maps them, written by hand, so it is not shown; writing this "
                + "mapping replaces it."), hand);
        assertTrue(hand.contains("- grey (boolean): fixed false"), hand);
    }

    @Test
    void aPairNamesThePairsItGoesThroughAndEnumsTheirArms() {
        String frame = MigrationSummary.mapping(GRAMMAR, DECLARED, List.of(), OldFrame.class, Frame.class);
        assertTrue(frame.contains("It goes through " + MigrationSummary.type(OldArea.class) + " → "
                + MigrationSummary.type(Area.class) + ", each a mapping of its own (read_migration)."), frame);

        String tint = MigrationSummary.mapping(GRAMMAR, DECLARED, List.of(), OldTint.class, null);
        assertTrue(tint.contains("each can become CRIMSON, NAVY:\n- RED → CRIMSON\n- BLUE → NAVY"), tint);

        String refused = MigrationSummary.mapping(GRAMMAR, DECLARED, List.of(), OldTint.class, Shot.class);
        assertTrue(refused.endsWith("It cannot be mapped: An enum migrates only to another enum: each of its "
                + "constants is paired with one of the new type's."), refused);
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
