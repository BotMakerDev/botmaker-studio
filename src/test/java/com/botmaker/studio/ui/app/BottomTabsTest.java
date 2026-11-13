package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bottom tabs that come and go (2026-11-13): Debug with a debug session, Usages from Find Usages until closed,
 * Terminal from View ▸ Terminal or dev mode — and a saved layout naming one that is not there opens on Run.
 */
class BottomTabsTest extends FxHeadlessTest {

    private BottomTabs tabs;

    @Override
    public void start(Stage stage) {
        Map<BottomTab, Node> contents = new EnumMap<>(BottomTab.class);
        for (BottomTab which : BottomTab.values()) contents.put(which, new Label(which.title()));
        tabs = new BottomTabs(contents);
    }

    @Test
    void onlyTheAlwaysTabsAreThereAtOpen() {
        assertEquals(List.of(BottomTab.RUN, BottomTab.REPORTS, BottomTab.VERSIONS), shown());
    }

    @Test
    void debugComesWithItsSessionAndGoesWithIt() {
        fx(() -> tabs.show(BottomTab.DEBUG));
        assertEquals(List.of(BottomTab.RUN, BottomTab.REPORTS, BottomTab.DEBUG, BottomTab.VERSIONS), shown());
        fx(() -> tabs.select(BottomTab.DEBUG));
        fx(() -> tabs.hide(BottomTab.DEBUG));
        assertFalse(fx(() -> tabs.isShown(BottomTab.DEBUG)));
        assertEquals(BottomTab.RUN, fx(tabs::selected), "Run shows when the tab showing goes, not its neighbour");
    }

    @Test
    void usagesComesWhenSelectedAndCloses() {
        fx(() -> tabs.select(BottomTab.USAGES));
        assertEquals(BottomTab.USAGES, fx(tabs::selected));
        Tab usages = tabs.tabs().get(BottomTab.USAGES);
        assertTrue(fx(usages::isClosable));
        fx(() -> tabs.node().getTabs().remove(usages));   // what its ✕ does
        assertFalse(fx(() -> tabs.isShown(BottomTab.USAGES)));
    }

    /** Its ✕ brings Run forward, as hide() does, not the neighbour JavaFX would pick. */
    @Test
    void closingTheShownTabBringsRunForward() {
        fx(() -> tabs.select(BottomTab.USAGES));
        Tab usages = tabs.tabs().get(BottomTab.USAGES);
        fx(() -> {
            usages.getOnCloseRequest().handle(new Event(Tab.TAB_CLOSE_REQUEST_EVENT));
            tabs.node().getTabs().remove(usages);
        });
        assertEquals(BottomTab.RUN, fx(tabs::selected));
    }

    @Test
    void theTerminalFollowsTheMenuItemBothWays() {
        fx(() -> tabs.terminalProperty().set(true));
        assertEquals(BottomTab.TERMINAL, fx(tabs::selected), "ticked from the menu, it comes forward");
        assertEquals(List.of(BottomTab.RUN, BottomTab.REPORTS, BottomTab.TERMINAL, BottomTab.VERSIONS), shown());
        fx(() -> tabs.node().getTabs().remove(tabs.tabs().get(BottomTab.TERMINAL)));
        assertFalse(fx(() -> tabs.terminalProperty().get()), "the tab's ✕ unticks View ▸ Terminal");
    }

    @Test
    void devModeKeepsTheTerminalWithoutItsCross() {
        fx(() -> tabs.keepTerminal(true));
        assertTrue(fx(() -> tabs.isShown(BottomTab.TERMINAL)));
        assertEquals(BottomTab.RUN, fx(tabs::selected), "shown, not selected: no shell starts at open");
        assertTrue(fx(() -> tabs.terminalProperty().get()));
        assertFalse(fx(() -> tabs.tabs().get(BottomTab.TERMINAL).isClosable()));
        assertTrue(fx(() -> tabs.terminalKeptProperty().get()));
        fx(() -> tabs.keepTerminal(false));
        assertTrue(fx(() -> tabs.isShown(BottomTab.TERMINAL)), "turning dev mode off leaves the tab for the user");
        assertTrue(fx(() -> tabs.tabs().get(BottomTab.TERMINAL).isClosable()));
    }

    @Test
    void aSavedLayoutOnATabThatIsNotThereOpensOnRun() {
        fx(() -> tabs.select(BottomTab.VERSIONS));
        fx(() -> tabs.restore(BottomTab.DEBUG));
        assertEquals(BottomTab.RUN, fx(tabs::selected));
        fx(() -> tabs.restore(null));
        assertEquals(BottomTab.RUN, fx(tabs::selected));
        fx(() -> tabs.restore(BottomTab.REPORTS));
        assertEquals(BottomTab.REPORTS, fx(tabs::selected));
    }

    private List<BottomTab> shown() {
        Map<Tab, BottomTab> keys = new IdentityHashMap<>();
        tabs.tabs().forEach((which, tab) -> keys.put(tab, which));
        return fx(() -> tabs.node().getTabs().stream().map(keys::get).toList());
    }

    private static void fx(Runnable action) {
        fx(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T fx(Callable<T> call) {
        try {
            return WaitForAsyncUtils.asyncFx(call).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
