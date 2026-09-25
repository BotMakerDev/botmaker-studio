package com.botmaker.studio.ui.fx;

import com.botmaker.studio.ui.render.menu.MenuTracker;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One canvas menu at a time. Every opener builds a fresh {@link ContextMenu}, so before {@link MenuTracker} a
 * slot's "+" clicked while the insert menu was up left both open, each able to edit the file under the other.
 */
class MenuTrackerTest extends FxHeadlessTest {

    private Button first;
    private Button second;

    @Override
    public void start(Stage stage) {
        first = new Button("first");
        second = new Button("second");
        stage.setScene(new Scene(new HBox(first, second), 300, 100));
        stage.show();
    }

    @Test
    void showingATrackedMenuClosesTheOneBefore() {
        ContextMenu a = MenuTracker.track(new ContextMenu(new MenuItem("a")));
        ContextMenu b = MenuTracker.track(new ContextMenu(new MenuItem("b")));

        interact(() -> a.show(first, Side.BOTTOM, 0, 0));
        assertTrue(a.isShowing());

        interact(() -> b.show(second, Side.BOTTOM, 0, 0));
        assertTrue(b.isShowing());
        assertFalse(a.isShowing(), "the first menu must close when the second opens");

        interact(b::hide);
    }

    @Test
    void clickingASecondOpenerWhileAMenuIsUpOpensItsMenu() {
        ContextMenu a = MenuTracker.track(new ContextMenu(new MenuItem("a")));
        ContextMenu b = MenuTracker.track(new ContextMenu(new MenuItem("b")));
        interact(() -> second.setOnAction(e -> b.show(second, Side.BOTTOM, 0, 0)));

        interact(() -> a.show(first, Side.BOTTOM, 0, 0));
        clickOn(second);

        assertTrue(b.isShowing(), "the click that closed the first menu must still reach the second opener");
        assertFalse(a.isShowing());
        interact(b::hide);
    }

    @Test
    void anUntrackedMenuIsLeftAlone() {
        ContextMenu tracked = MenuTracker.track(new ContextMenu(new MenuItem("t")));
        ContextMenu other = new ContextMenu(new MenuItem("o"));

        interact(() -> other.show(first, Side.BOTTOM, 0, 0));
        interact(() -> tracked.show(second, Side.BOTTOM, 0, 0));
        assertTrue(other.isShowing(), "a dialog's own menu is not the canvas's to close");

        interact(() -> {
            other.hide();
            tracked.hide();
        });
    }
}
