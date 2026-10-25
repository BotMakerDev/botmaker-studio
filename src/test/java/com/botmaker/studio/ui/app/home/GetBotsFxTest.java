package com.botmaker.studio.ui.app.home;

import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.HiddenBots;
import com.botmaker.studio.sharing.PublisherRank;
import com.botmaker.studio.sharing.ShowCommunity;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Get more bots' Get (2026-10-25): a bot nobody reviewed is agreed to before anything is cloned. */
class GetBotsFxTest extends FxHeadlessTest {

    private static final GalleryEntry TAVERN = GetBotsTest.bot("Tavern", "someone", PublisherRank.COMMUNITY,
            "Plays the tavern");
    private static final GalleryEntry FIRESTONE = GetBotsTest.bot("Firestone", "LiQiyeDev", PublisherRank.ADMIN,
            "Idle game farming");

    /** What happened, in order: "asked <name>", "got <name>", "home". */
    private final List<String> events = new CopyOnWriteArrayList<>();
    private volatile boolean agree;

    @Override
    public void start(Stage stage) {
        GetBots getBots = new GetBots(new GetBots.Shelf() {
            @Override
            public Path get(GalleryEntry entry) {
                events.add("got " + entry.name());
                return Path.of("/p", entry.name());
            }

            @Override
            public boolean has(GalleryEntry entry) {
                return events.contains("got " + entry.name());
            }
        }, entry -> {
            events.add("asked " + entry.name());
            return agree;
        }, () -> events.add("home"), new ShowCommunity(true), new HiddenBots(Set.of()));
        stage.setScene(new Scene(new StackPane(getBots.node()), 900, 600));
        getBots.show(List.of(TAVERN, FIRESTONE), true);
        stage.show();
    }

    private Button getOf(String name) {
        return lookup(".getbots-card").queryAll().stream()
                .filter(card -> card.lookupAll(".label").stream()
                        .anyMatch(n -> n instanceof Label l && name.equals(l.getText())))
                .findFirst().orElseThrow()
                .lookupAll(".button").stream().map(Button.class::cast)
                .filter(b -> "Get".equals(b.getText())).findFirst().orElseThrow();
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
        clickOn(getOf("Tavern"));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(List.of("asked Tavern"), events, "declined: nothing cloned");

        agree = true;
        clickOn(getOf("Tavern"));
        waitFor(4);
        assertEquals(List.of("asked Tavern", "asked Tavern", "got Tavern", "home"), events);
    }

    @Test
    void anOfficialBotIsGotWithoutAsking() {
        clickOn(getOf("Firestone"));
        waitFor(2);
        assertEquals(List.of("got Firestone", "home"), events);
        assertTrue(lookup("In My bots").tryQuery().isPresent());
    }
}
