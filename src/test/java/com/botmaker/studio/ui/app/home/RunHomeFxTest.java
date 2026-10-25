package com.botmaker.studio.ui.app.home;

import com.botmaker.studio.sharing.BmkDownload;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.ui.app.home.MyBots.CompileState;
import com.botmaker.studio.ui.app.home.MyBots.MyBot;
import com.botmaker.studio.ui.app.home.MyBots.State;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The home (2026-10-25): one card per project, ▶ Run and Edit in Studio on each, a gone folder said. */
class RunHomeFxTest extends FxHeadlessTest {

    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 10, 19, 9, 0);
    private final List<String> asked = new ArrayList<>();

    @Override
    public void start(Stage stage) {
        List<MyBot> bots = List.of(
                new MyBot(Path.of("/p/Firestone"), "Firestone", Optional.of("LiQiyeDev"), Optional.of("1.0.0"),
                        MONDAY, Optional.of("1.1.0"), true, State.READY),
                new MyBot(Path.of("/p/PushTest"), "PushTest", Optional.empty(), Optional.empty(),
                        MONDAY, Optional.empty(), true, State.READY),
                new MyBot(Path.of("/elsewhere/Gone"), "Gone", Optional.empty(), Optional.empty(),
                        LocalDateTime.MIN, Optional.empty(), false, State.GONE));
        RunHome home = new RunHome(stage, new RunHome.Actions() {
            @Override
            public void run(Path dir) {
                asked.add("run " + dir.getFileName());
            }

            @Override
            public void edit(Path dir, boolean freshlyCreated) {
                asked.add("edit " + dir.getFileName());
            }
        }, () -> bots, new GetBots.Shelf() {
            @Override
            public Path download(GalleryEntry entry, BmkDownload.Progress progress) {
                return Path.of("/cache", entry.name() + ".bmk");
            }

            @Override
            public Path cloneOf(GalleryEntry entry) {
                return Path.of("/p", entry.name());
            }
        }, dir -> dir.endsWith("PushTest") ? CompileState.ERRORS : CompileState.OK);
        stage.setScene(home.createScene());
        stage.setWidth(900);
        stage.setHeight(700);
        stage.show();
    }

    /** The cards, once the off-thread read has filled them. */
    private List<Node> cards() {
        try {
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> lookup(".mybots-card").queryAll().size() == 3);
        } catch (TimeoutException e) {
            throw new AssertionError("the three cards never showed", e);
        }
        return new ArrayList<>(lookup(".mybots-card").queryAll());
    }

    private static boolean says(Node card, String text) {
        return card.lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && text.equals(l.getText()))
                || card.lookupAll(".button").stream().anyMatch(n -> n instanceof Button b && text.equals(b.getText()));
    }

    @Test
    void eachProjectIsACardWithRunAndEdit() {
        List<Node> cards = cards();
        assertEquals(3, cards.size());
        for (Node card : cards.subList(0, 2)) {
            assertTrue(says(card, "▶ Run"), "a ▶ Run button");
            assertTrue(says(card, "Edit in Studio"), "an Edit in Studio button");
        }
        assertTrue(says(cards.get(0), "by LiQiyeDev · 1.0.0"));
        assertTrue(says(cards.get(0), "1.1.0 available"));
        assertTrue(says(cards.get(1), "mine"));
    }

    @Test
    void theButtonsAskTheShell() {
        cards();
        clickOn(lookup("▶ Run").queryButton());
        clickOn(lookup("Edit in Studio").queryButton());
        assertEquals(List.of("run Firestone", "edit Firestone"), asked);
    }

    @Test
    void aGoneFolderSaysSo() {
        Node gone = cards().get(2);
        assertTrue(says(gone, "This bot's folder is gone."));
        assertTrue(says(gone, "Remove from My bots"));
        assertTrue(!says(gone, "▶ Run") && !says(gone, "Edit in Studio"), "nothing to open");
    }

    @Test
    void aBotThatDoesNotCompileSaysSoAndKeepsEdit() {
        Node broken = cards().get(1);
        try {
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> broken.lookupAll(".mybots-broken").stream()
                    .anyMatch(Node::isVisible));
        } catch (TimeoutException e) {
            throw new AssertionError("the compile state never showed", e);
        }
        Button run = (Button) broken.lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && "▶ Run".equals(b.getText())).findFirst().orElseThrow();
        assertTrue(run.isDisabled(), "nothing to run until it compiles");
        assertTrue(says(broken, "Edit in Studio"));
        assertTrue(cards().get(0).lookupAll(".mybots-broken").stream().noneMatch(Node::isVisible));
    }

    @Test
    void theTopBarOffersANewBot() {
        assertTrue(lookup("＋ New bot").tryQuery().isPresent());
        assertTrue(lookup("Get more bots").tryQuery().isPresent());
    }
}
