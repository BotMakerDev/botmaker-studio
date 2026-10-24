package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.ui.app.StartingPoints.Fetch;
import com.botmaker.studio.ui.app.StartingPoints.Kind;
import com.botmaker.studio.ui.app.StartingPoints.StartChoice;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** New Project's Start from cards (2026-10-24): one pick, kept across a refill, never swapped silently. */
class StartCardsTest extends FxHeadlessTest {

    private final List<StartChoice> heard = new ArrayList<>();
    private StartCards cards;

    @Override
    public void start(Stage stage) {
        cards = new StartCards(heard::add);
        stage.setScene(new Scene((Parent) cards.node(), 600, 400));
        stage.show();
    }

    private static GalleryEntry bot(String name) {
        return new GalleryEntry(name, "LiQiyeDev", name, "A " + name + " bot.", List.of());
    }

    /** The cards for {@code bots}, every one of them listed. */
    private void fill(List<GalleryEntry> bots, Fetch fetch) {
        interact(() -> cards.fill(StartingPoints.rows(bots, fetch), bots, ""));
    }

    private boolean saying(String text) {
        return lookup((Node n) -> n instanceof Label l && l.isVisible() && text.equals(l.getText()))
                .tryQuery().isPresent();
    }

    @Test
    void aKeptPickSurvivesTheCatalogsAnswer() {
        fill(List.of(bot("farm")), Fetch.PENDING);
        assertEquals(StartChoice.BLANK, cards.picked(), "Blank when the window opens");
        assertEquals(2, lookup(".np-card").queryAll().size(), "the loading row is no card");

        interact(() -> cards.pick(new StartChoice(Kind.REMEMBERED, bot("farm"))));
        fill(List.of(bot("farm"), bot("mine")), Fetch.ANSWERED);

        assertEquals(Kind.BOT, cards.picked().kind());
        assertEquals("farm", cards.picked().entry().name());
        assertEquals(cards.picked(), heard.getLast(), "the confirmed pick is heard, so Create can enable");
    }

    /** Review: a later refill (the community toggle) does not pick Blank behind the user's back. */
    @Test
    void aBotTheCatalogDroppedLeavesNothingPicked() {
        fill(List.of(bot("farm")), Fetch.ANSWERED);
        interact(() -> cards.pick(StartChoice.of(bot("farm"))));
        fill(List.of(bot("mine")), Fetch.ANSWERED);

        assertNull(cards.picked());
        assertNull(heard.getLast());
        assertTrue(saying("Farm is no longer listed — pick where to start"));

        fill(List.of(bot("mine")), Fetch.ANSWERED);
        assertNull(cards.picked(), "still nothing picked after another refill");
        assertTrue(saying("Farm is no longer listed — pick where to start"));

        interact(() -> cards.pick(StartChoice.BLANK));
        assertFalse(saying("Farm is no longer listed — pick where to start"));
    }

    /** Review: a bot a filter hides is still listed, so it is no "no longer listed". */
    @Test
    void aBotOnlyHiddenFallsBackToBlank() {
        List<GalleryEntry> listed = List.of(bot("farm"), bot("mine"));
        fill(listed, Fetch.ANSWERED);
        interact(() -> cards.pick(StartChoice.of(bot("farm"))));
        interact(() -> cards.fill(StartingPoints.rows(List.of(bot("mine")), Fetch.ANSWERED), listed, ""));

        assertEquals(StartChoice.BLANK, cards.picked());
        assertFalse(saying("Farm is no longer listed — pick where to start"));
    }

    @Test
    void keysMoveAndPick() {
        fill(List.of(bot("farm")), Fetch.ANSWERED);
        interact(() -> lookup(".np-card").queryAll().iterator().next().requestFocus());
        type(KeyCode.RIGHT);
        type(KeyCode.SPACE);

        assertEquals("farm", cards.picked().entry().name());
        assertEquals(1, lookup(".np-card-picked").queryAll().size(), "exactly one card picked");
    }

    @Test
    void noBotCardSaysWhy() {
        interact(() -> cards.fill(StartingPoints.rows(List.of(), Fetch.ANSWERED), List.of(),
                NewProjectForm.emptyLine(false)));
        assertTrue(saying(NewProjectForm.emptyLine(false)));
        assertEquals(StartChoice.BLANK, cards.picked());
    }
}
