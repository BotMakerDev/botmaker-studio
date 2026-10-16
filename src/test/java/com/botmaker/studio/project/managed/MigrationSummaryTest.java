package com.botmaker.studio.project.managed;

import com.botmaker.studio.project.managed.MigrationTypes.OldShot;
import com.botmaker.studio.project.managed.MigrationTypes.Shot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What migration list and plugins set_version say of the bot's migrations. */
class MigrationSummaryTest {

    private static final String OLD = "OldShot (com.botmaker.studio.project.managed.MigrationTypes.OldShot)";

    @Test
    void aTypeIsNamedAsReadAndAsATakesItBack() {
        assertEquals(OLD, MigrationSummary.type(OldShot.class));
        assertEquals("String (java.lang.String)", MigrationSummary.type(String.class));
        assertEquals("nothing", MigrationSummary.type(null));
    }

    @Test
    void aRetiredTypeSaysHowFarItIsCopied() {
        assertEquals("2 values of " + OLD + ", a type its plugin retired; none copied yet.",
                MigrationSummary.pending(new MigrationSteps.Pending(OldShot.class, 2, 0)));
        assertEquals("1 value of " + OLD + ", a type its plugin retired; all copied.",
                MigrationSummary.pending(new MigrationSteps.Pending(OldShot.class, 1, 1)));
        assertTrue(MigrationSummary.pending(new MigrationSteps.Pending(OldShot.class, 3, 1))
                .endsWith("; 1 copied."));
    }

    @Test
    void aStrandedSetSaysWhetherItCanBeKept() {
        String kept = MigrationSummary.stranded(new MigrationSteps.Stranded("SHOTS", OldShot.class, 2, true),
                "SDK 2.0.0");
        assertEquals("2 values of " + OLD + " not copied yet: SDK 2.0.0 drops their set, so the move removes them "
                + "(one the bot's code names is kept, unmarked) or resets them. To keep them, copy them first,"
                + " on the version the bot has now (migration list).", kept);
        String lost = MigrationSummary.stranded(new MigrationSteps.Stranded("SHOTS", null, 1, false),
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
}
