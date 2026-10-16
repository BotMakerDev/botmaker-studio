package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.meta.Migrate;
import com.botmaker.studio.migrate.Broken;
import com.botmaker.studio.migrate.OldElement;
import com.botmaker.studio.migrate.Sites;
import com.botmaker.studio.nav.Uses;
import com.botmaker.studio.services.upgrade.PluginUpgradeService;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The removal question's one sentence: counts, never the list of every use (2026-10-06). */
class RemovalSheetTest {

    private static Report report(List<Broken> broken) {
        return new Report("1.2.3", "", PluginUpgradeService.Operation.REMOVAL, List.of(), Map.of(), broken,
                List.of(), List.of(), List.of());
    }

    private static Broken broken(Migrate.Kind kind, String name, Broken.State state, int uses) {
        OldElement element = new OldElement(kind, "com.example.Mouse", name, List.of(), false, "void",
                "Lcom/example/Mouse;." + name);
        List<Sites.Site> sites = java.util.stream.IntStream.range(0, uses).mapToObj(i -> new Sites.Site(
                Path.of("Main.java"), element, Uses.Shape.STATEMENT, i * 10, 5, i + 1, "Main.run")).toList();
        return new Broken(element, sites, state, null);
    }

    @Test
    void aBotUsingNothingChangesOnlyThePom() {
        assertEquals("This bot uses nothing in SDK, so only the pom changes. A version of the project is saved "
                + "first.", RemovalSheet.summary("SDK", report(List.of())));
    }

    @Test
    void usesAndTypesAreCountedNotListed() {
        List<Broken> broken = List.of(broken(Migrate.Kind.METHOD, "click", Broken.State.NONE, 2),
                broken(Migrate.Kind.METHOD, "move", Broken.State.WRITTEN, 1),
                broken(Migrate.Kind.TYPE, "Mouse", Broken.State.NONE, 1));

        assertEquals("1 use takes your @Migrate rules. 2 uses take a default rule written into Migrations — a value"
                        + " standing for nothing, or the call deleted — marked for review. 1 type it declares stays as"
                        + " written where the bot writes it down, for you to change. A version of the project is"
                        + " saved first.",
                RemovalSheet.summary("SDK", report(broken)));
    }
}
