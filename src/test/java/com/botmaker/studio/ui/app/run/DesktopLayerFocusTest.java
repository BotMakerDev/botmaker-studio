package com.botmaker.studio.ui.app.run;

import com.botmaker.shared.capture.GenericWindow;
import com.botmaker.shared.capture.NativeController;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLayerFocusTest {

    private static final GenericWindow GAME = new GenericWindow(1L, "Firestone", new Rectangle(0, 0, 10, 10));
    private static final GenericWindow LAYER =
            new GenericWindow(2L, DesktopLayer.TITLE, new Rectangle(0, 0, 10, 10));
    private static final GenericWindow TERMINAL = new GenericWindow(3L, "terminal", new Rectangle(0, 0, 10, 10));

    @Test
    void theLayerHoldingTheFocusGivesItBack() {
        List<GenericWindow> focused = new ArrayList<>();
        DesktopLayer.handFocusBack(controller(LAYER, focused), GAME);
        assertEquals(List.of(GAME), focused);
    }

    @Test
    void noWindowHoldingTheFocusGivesItBack() {
        List<GenericWindow> focused = new ArrayList<>();
        DesktopLayer.handFocusBack(controller(null, focused), GAME);
        assertEquals(List.of(GAME), focused);
    }

    @Test
    void aWindowTheUserPickedSinceKeepsTheFocus() {
        List<GenericWindow> focused = new ArrayList<>();
        DesktopLayer.handFocusBack(controller(TERMINAL, focused), GAME);
        assertTrue(focused.isEmpty());
    }

    @Test
    void nothingToGiveBackWhenNoWindowOrTheLayerHadIt() {
        List<GenericWindow> focused = new ArrayList<>();
        DesktopLayer.handFocusBack(controller(LAYER, focused), null);
        DesktopLayer.handFocusBack(controller(LAYER, focused), LAYER);
        assertTrue(focused.isEmpty());
    }

    /** A controller whose foreground window is {@code foreground}, recording each window it is asked to focus. */
    private static NativeController controller(GenericWindow foreground, List<GenericWindow> focused) {
        return (NativeController) Proxy.newProxyInstance(NativeController.class.getClassLoader(),
                new Class<?>[]{NativeController.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getForegroundWindow" -> foreground;
                    case "focusWindow" -> {
                        focused.add((GenericWindow) args[0]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
