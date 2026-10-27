package com.botmaker.studio.ui.app;

import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.DerivedFacts;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.PublishedBotPlugins;
import com.botmaker.studio.sharing.PublisherRank;
import com.botmaker.studio.sharing.ShowCommunity;
import com.botmaker.studio.ui.app.StartingPoints.StartChoice;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** New Project's plugin cards (2026-10-24): the ✓ toggles, a locked card says who gives it and is no pick. */
class NewProjectPluginsCardsTest extends FxHeadlessTest {

    private static final PluginRegistry.Plugin SDK = new PluginRegistry.Plugin("BotMaker SDK",
            "com.github.BotMakerDev:botmaker-sdk", "BotMakerDev/botmaker-sdk", "The SDK.", null, null, null, "",
            "v2.0.0", PublisherRank.ADMIN, 5, List.of(new Catalog.Plugin.Release("v2.0.0", null, null, null, null,
            null, List.of(new DerivedFacts.Pin("BotMakerDev/botmaker-plugin-basics", "v0.2.0")))));
    private static final PluginRegistry.Plugin BASICS = new PluginRegistry.Plugin("Basics",
            "com.github.BotMakerDev:botmaker-plugin-basics", "BotMakerDev/botmaker-plugin-basics", "", null, null,
            null, "", "v0.2.0", PublisherRank.ADMIN, 1);

    private NewProjectPlugins plugins;

    @Override
    public void start(Stage stage) {
        plugins = new NewProjectPlugins(CompletableFuture.completedFuture(List.of(SDK, BASICS)),
                new ShowCommunity(true), () -> {});
        stage.setScene(new Scene((Parent) plugins.node(), 600, 400));
        stage.show();
    }

    /** The card whose name is {@code name}. */
    private Node card(String name) {
        Node label = lookup((Node n) -> n instanceof Label l && l.getStyleClass().contains("np-card-name")
                && name.equals(l.getText())).query();
        for (Node n = label; n != null; n = n.getParent()) if (n.getStyleClass().contains("np-card")) return n;
        throw new AssertionError("no card " + name);
    }

    private boolean saying(String text) {
        return lookup((Node n) -> n instanceof Label l && l.isVisible() && text.equals(l.getText()))
                .tryQuery().isPresent();
    }

    private void clickCard(String name) {
        WaitForAsyncUtils.waitForFxEvents();
        clickOn(card(name));
    }

    @Test
    void aTickedPluginLocksWhatItBrings() {
        clickCard("BotMaker SDK");

        assertEquals(List.of(SDK), plugins.picked());
        assertTrue(saying("brought by BotMaker SDK"));
        assertTrue(card("Basics").getStyleClass().contains("np-card-locked"));

        clickCard("Basics");
        assertEquals(List.of(SDK), plugins.picked(), "a click on a locked card changes nothing");

        clickCard("BotMaker SDK");
        assertEquals(List.of(), plugins.picked());
        assertFalse(saying("brought by BotMaker SDK"));
    }

    /** Review Focus 2: Basics ticked, then the SDK that brings it: Basics turns locked and leaves the picks. */
    @Test
    void tickedThenBroughtLeavesPicked() {
        clickCard("Basics");
        assertEquals(List.of(BASICS), plugins.picked());

        clickCard("BotMaker SDK");
        assertEquals(List.of(SDK), plugins.picked());

        clickCard("BotMaker SDK");
        assertEquals(List.of(BASICS), plugins.picked(), "unlocked again, the user's own tick is back");
    }

    @Test
    void aBotsOwnPluginIsUsedByIt() {
        GalleryEntry firestone = new GalleryEntry("firestone", "LiQiyeDev", "Firestone", "", List.of(), "", "1.0.0",
                PublisherRank.ADMIN, 0, List.of(new PublishedBotPlugins.Pin("BotMakerDev/botmaker-sdk", "v1.9.0")), PublisherRank.ADMIN);
        interact(() -> plugins.lockFor(StartChoice.of(firestone)));

        assertTrue(saying("used by Firestone"));
        assertTrue(saying("v1.9.0"), "the bot's pin, not the catalog's tag");
        assertTrue(saying("brought by BotMaker SDK"));
        assertEquals(List.of(), plugins.picked());

        interact(() -> plugins.lockFor(StartChoice.BLANK));
        assertFalse(saying("used by Firestone"));
    }

    @Test
    void keysSkipALockedCard() {
        clickCard("BotMaker SDK");
        type(KeyCode.RIGHT);
        assertTrue(card("BotMaker SDK").isFocused(), "the only other card is locked: focus stays");
        type(KeyCode.SPACE);
        assertEquals(List.of(), plugins.picked());
    }

    @Test
    void anUnreachableCatalogSaysSo() {
        NewProjectPlugins offline = new NewProjectPlugins(CompletableFuture.failedFuture(new Exception("offline")),
                new ShowCommunity(true), () -> {});
        interact(() -> stage().getScene().setRoot((Parent) offline.node()));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(saying("No plugins listed — the catalog could not be reached. Add them later from Project ▸ "
                + "Plugins & Libraries."));
    }

    private Stage stage() {
        return (Stage) plugins.node().getScene().getWindow();
    }
}
