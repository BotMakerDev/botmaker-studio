package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.managed.MigrationSteps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The warning before a move that drops a set the bot has values in: each set, then what each answer does. */
class StrandedWarningTest {

    private static InstalledPluginsTab.Dropped dropped(Class<?> type, Class<?> to, int values, boolean migratable) {
        return new InstalledPluginsTab.Dropped("SDK", "2.0.0",
                new MigrationSteps.Stranded("ID", type, to, values, migratable));
    }

    @Test
    void eachSetIsNamedWithItsCountAndReplacement() {
        String text = InstalledPluginsTab.droppedText(List.of(dropped(Integer.class, Long.class, 312, true),
                dropped(Short.class, null, 1, false)));

        assertTrue(text.contains("• 312 values of Integer: SDK 2.0.0 drops their set; it points to Long.\n"), text);
        assertTrue(text.contains("• 1 value of Short: SDK 2.0.0 drops their set; nothing in the version you have now"
                + " can take them.\n"), text);
        assertTrue(text.contains("starting with Integer; migrate the others"), text);
    }

    @Test
    void migrateFirstStartsWithTheFirstSetItCanCopy() {
        List<InstalledPluginsTab.Dropped> dropped = List.of(dropped(Short.class, null, 1, false),
                dropped(Integer.class, null, 2, true));

        assertTrue(InstalledPluginsTab.firstMigratable(dropped).orElseThrow().set().type() == Integer.class);
        assertTrue(InstalledPluginsTab.droppedText(dropped).contains("starting with Integer"));
    }

    @Test
    void nothingToMigrateOffersNoMigration() {
        List<InstalledPluginsTab.Dropped> dropped = List.of(dropped(Short.class, null, 1, false));

        assertTrue(InstalledPluginsTab.firstMigratable(dropped).isEmpty());
        String text = InstalledPluginsTab.droppedText(dropped);
        assertFalse(text.contains("Migrate first"), text);
        assertTrue(text.endsWith("still has them."), text);
    }
}
