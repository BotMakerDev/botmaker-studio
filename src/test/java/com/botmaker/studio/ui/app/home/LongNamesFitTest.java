package com.botmaker.studio.ui.app.home;

import com.botmaker.cli.catalog.Catalog;
import com.botmaker.studio.sharing.BmkDownload;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.HiddenBots;
import com.botmaker.studio.sharing.PublisherRank;
import com.botmaker.studio.sharing.ShowCommunity;
import com.botmaker.studio.ui.app.home.MyBots.CompileState;
import com.botmaker.studio.ui.app.home.MyBots.MyBot;
import com.botmaker.studio.ui.app.home.MyBots.State;
import com.botmaker.studio.ui.app.loading.LoadingPage;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.scene.Node;
import javafx.scene.control.Labeled;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Nothing on a card is cut (2026-10-25, spec § 1b, A): a long name wraps in a 280 px card, on My bots and Get more
 * bots alike, and the buttons fit on one line.
 */
class LongNamesFitTest extends FxHeadlessTest {

    private static final String LONG = "Firestone Idle Expedition Farmer Deluxe";

    @Override
    public void start(Stage stage) {
        List<MyBot> bots = List.of(new MyBot(Path.of("/p/" + LONG), LONG, Optional.of("LiQiyeDev"),
                Optional.of("1.0.2"), LocalDateTime.of(2026, 10, 19, 9, 0), Optional.of("1.1.0"), true, State.READY));
        GalleryEntry got = new GalleryEntry(LONG, "LiQiyeDev", "firestone", "Idle game farming", List.of(), "",
                "1.0.2", PublisherRank.ADMIN, 0, List.of(), PublisherRank.ADMIN,
                List.of(new Catalog.Bot.Release("1.0.2", "abc1234", List.of(), "ab".repeat(32))));
        GetBots.Shelf shelf = new GetBots.Shelf() {
            @Override
            public Path download(GalleryEntry entry, BmkDownload.Progress progress) {
                return Path.of("/cache/x.bmk");
            }

            @Override
            public Path cloneOf(GalleryEntry entry) {
                return Path.of("/p/x");
            }
        };
        RunHome home = new RunHome(stage, new RunHome.Actions() {
            @Override
            public void run(Path dir) {}

            @Override
            public void edit(Path dir, boolean freshlyCreated) {}

            @Override
            public void make(LoadingPage.Making making) {}
        }, () -> bots, shelf, dir -> CompileState.OK);
        stage.setScene(home.createScene());
        stage.setWidth(1200);
        stage.setHeight(800);
        stage.show();
        GetBots getBots = new GetBots(shelf, e -> true, new GetBots.Got() {
            @Override
            public void run(GalleryEntry entry) {}

            @Override
            public void cloned(Path dir) {}
        }, new ShowCommunity(true), new HiddenBots(Set.of()));
        getBots.show(List.of(got), true);
        Stage more = new Stage();
        more.setScene(ThemedWindows.scene(new StackPane(getBots.node()), 1200, 600));
        more.show();
    }

    @Test
    void aLongNameWrapsAndNothingIsCut() throws TimeoutException {
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> lookup(".mybots-card").queryAll().size() == 1
                && lookup(".getbots-card").queryAll().size() == 1);
        WaitForAsyncUtils.waitForFxEvents();
        for (String card : List.of(".mybots-card", ".getbots-card")) {
            Node node = lookup(card).query();
            assertEquals(RunHome.CARD_WIDTH, node.getLayoutBounds().getWidth(), 0.5, card + " is 280 px wide");
            assertEquals(List.of(), cut(node), card + ": every label whole");
        }
    }

    /** Each label under {@code root} whose drawn text is not its text: an ellipsis, as the ui tool says "cut". */
    private static List<String> cut(Node root) {
        List<String> cut = new ArrayList<>();
        for (Node n : root.lookupAll("*")) {
            if (!(n instanceof Labeled labeled) || !labeled.isVisible() || labeled.getText() == null) continue;
            if (labeled.getText().isBlank() || !(labeled.lookup(".text") instanceof Text shown)) continue;
            String drawn = shown.getText().replace("\n", " ").strip();
            if (!drawn.equals(labeled.getText().replace("\n", " ").strip())) {
                cut.add(labeled.getText() + " -> " + drawn);
            }
        }
        return cut;
    }
}
