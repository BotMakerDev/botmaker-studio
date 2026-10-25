package com.botmaker.studio.ui.app.home;

import com.botmaker.cli.catalog.Catalog;
import com.botmaker.studio.sharing.BmkDownload;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.HiddenBots;
import com.botmaker.studio.sharing.PublisherRank;
import com.botmaker.studio.sharing.ShowCommunity;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Get more bots' Get and Edit in Studio (2026-10-25): a bot nobody reviewed is agreed to first; Get downloads the
 * pinned {@code .bmk}, Edit in Studio clones; a bot with no {@code .bmk} offers only Edit; one in My bots says so.
 */
class GetBotsFxTest extends FxHeadlessTest {

    private static final GalleryEntry TAVERN = ready(GetBotsTest.bot("Tavern", "someone", PublisherRank.COMMUNITY,
            "Plays the tavern"));
    private static final GalleryEntry FIRESTONE = ready(GetBotsTest.bot("Firestone", "LiQiyeDev",
            PublisherRank.ADMIN, "Idle game farming"));
    private static final GalleryEntry MINER = GetBotsTest.bot("Miner", "vetted", PublisherRank.TRUSTED, "Mines ore");

    /** {@code entry} with its listed tag pinned with a {@code .bmk}. */
    private static GalleryEntry ready(GalleryEntry entry) {
        return new GalleryEntry(entry.name(), entry.owner(), entry.repo(), entry.description(), entry.tags(), "",
                entry.tag(), entry.rank(), 0, List.of(), PublisherRank.ADMIN,
                List.of(new Catalog.Bot.Release(entry.tag(), "abc1234", List.of(), "ab".repeat(32))));
    }

    /** What happened, in order: "asked <name>", "downloaded <name>", "cloned <name>", "run <name>", "edit <name>". */
    private final List<String> events = new CopyOnWriteArrayList<>();
    private volatile boolean agree;
    private GetBots getBots;

    @Override
    public void start(Stage stage) {
        getBots = new GetBots(new GetBots.Shelf() {
            @Override
            public Path download(GalleryEntry entry, BmkDownload.Progress progress) {
                events.add("downloaded " + entry.name());
                return Path.of("/cache", entry.name() + ".bmk");
            }

            @Override
            public Path cloneOf(GalleryEntry entry) {
                events.add("cloned " + entry.name());
                return Path.of("/p", entry.name());
            }
        }, entry -> {
            events.add("asked " + entry.name());
            return agree;
        }, new GetBots.Got() {
            @Override
            public void downloaded(GalleryEntry entry, Path bmk) {
                events.add("run " + bmk.getFileName());
            }

            @Override
            public void cloned(Path dir) {
                events.add("edit " + dir.getFileName());
            }
        }, new ShowCommunity(true), new HiddenBots(Set.of()));
        stage.setScene(new Scene(new StackPane(getBots.node()), 1200, 600));
        getBots.show(List.of(TAVERN, FIRESTONE, MINER), true);
        stage.show();
    }

    private Node cardOf(String name) {
        return lookup(".getbots-card").queryAll().stream()
                .filter(card -> card.lookupAll(".label").stream()
                        .anyMatch(n -> n instanceof Label l && name.equals(l.getText())))
                .findFirst().orElseThrow();
    }

    private Optional<Button> button(String card, String text) {
        return cardOf(card).lookupAll(".button").stream().map(Button.class::cast)
                .filter(b -> text.equals(b.getText())).findFirst();
    }

    private boolean says(String card, String text) {
        return cardOf(card).lookupAll(".label").stream().anyMatch(n -> n instanceof Label l
                && l.getText() != null && l.getText().startsWith(text));
    }

    private void waitFor(int count) {
        try {
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> events.size() >= count);
        } catch (TimeoutException e) {
            throw new AssertionError("only " + events, e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void getAsksFirst() {
        agree = false;
        clickOn(button("Tavern", "Get").orElseThrow());
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(List.of("asked Tavern"), events, "declined: nothing downloaded");

        agree = true;
        clickOn(button("Tavern", "Get").orElseThrow());
        waitFor(4);
        assertEquals(List.of("asked Tavern", "asked Tavern", "downloaded Tavern", "run Tavern.bmk"), events);
    }

    @Test
    void anOfficialBotIsDownloadedWithoutAsking() {
        clickOn(button("Firestone", "Get").orElseThrow());
        waitFor(2);
        assertEquals(List.of("downloaded Firestone", "run Firestone.bmk"), events);
    }

    @Test
    void editInStudioClones() {
        clickOn(button("Firestone", "Edit in Studio").orElseThrow());
        waitFor(2);
        assertEquals(List.of("cloned Firestone", "edit Firestone"), events);
    }

    @Test
    void aBotWithNoBmkOffersOnlyEdit() {
        assertTrue(button("Miner", "Get").isEmpty(), "nothing to download");
        assertTrue(button("Miner", "Edit in Studio").isPresent());
        assertTrue(says("Miner", "No ready-to-run file yet"));
    }

    @Test
    void aBotInMyBotsByItsRepositorySaysSo() {
        Platform.runLater(() -> getBots.mine(Set.of(MyBots.key("liqiyedev", "FIRESTONE"))));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(button("Firestone", "In My bots").isPresent());
        assertTrue(button("Firestone", "Get").isEmpty() && button("Firestone", "Edit in Studio").isEmpty());
        assertTrue(button("Tavern", "Get").isPresent(), "another bot is not");
    }
}
