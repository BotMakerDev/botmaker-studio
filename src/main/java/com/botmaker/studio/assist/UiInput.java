package com.botmaker.studio.assist;

import com.botmaker.shared.platform.Os;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseDragEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The mouse, key and scroll events {@code ui} fires at a node as a user's would arrive (2026-10-28). Every method runs
 * on the FX thread.
 *
 * <p>A fired event cannot start a real gesture: JavaFX starts a drag only from the mouse events its own scene handled.
 * A press-drag-release gesture ({@code startFullDrag}) is played out here instead, its {@link MouseDragEvent}s fired
 * at the drop; a Dragboard drag-and-drop ({@code startDragAndDrop}) cannot be, and is refused as {@link Undrivable}.
 */
final class UiInput {

    /** The {@code MOUSE_DRAGGED} events between the press and the drop. */
    private static final int DRAG_STEPS = 5;

    private UiInput() {}

    /** What a fired event cannot do; {@code ui} refuses it, naming what to use instead. */
    static final class Undrivable extends IllegalArgumentException {
        Undrivable(String message) {
            super(message);
        }
    }

    /** Every key by its constant and its name, squashed as {@link Keys#code} looks them up. */
    private static final Map<String, KeyCode> BY_NAME = new HashMap<>();

    static {
        for (KeyCode code : KeyCode.values()) {
            BY_NAME.putIfAbsent(Keys.squashed(code.name()), code);
            BY_NAME.putIfAbsent(Keys.squashed(code.getName()), code);
        }
    }

    /** A key with its modifiers, as {@code Ctrl+Shift+A} names it. */
    record Keys(KeyCode code, boolean shift, boolean control, boolean alt, boolean meta) {

        /** {@code text} read: modifiers then one key, joined by {@code +}; {@code Shortcut} is Ctrl, or Cmd on a Mac. */
        static Keys of(String text) {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Say which keys, like Ctrl+S.");
            String[] parts = text.strip().split("\\s*\\+\\s*", -1);
            if (parts[parts.length - 1].isEmpty()) {
                throw new IllegalArgumentException("Name the key after the last +; the + key itself is Plus.");
            }
            boolean shift = false;
            boolean control = false;
            boolean alt = false;
            boolean meta = false;
            for (int i = 0; i < parts.length - 1; i++) {
                switch (parts[i].toLowerCase(Locale.ROOT)) {
                    case "shift" -> shift = true;
                    case "ctrl", "control" -> control = true;
                    case "alt", "option" -> alt = true;
                    case "meta", "cmd", "command", "super" -> meta = true;
                    case "shortcut" -> {
                        if (Os.current().isMac()) {
                            meta = true;
                        } else {
                            control = true;
                        }
                    }
                    default -> throw new IllegalArgumentException("No modifier \"" + parts[i]
                            + "\"; say Ctrl, Shift, Alt, Meta or Shortcut.");
                }
            }
            return new Keys(code(parts[parts.length - 1]), shift, control, alt, meta);
        }

        /** The key {@code name} names, by its constant or its name, spaces and case aside: {@code PageDown}, {@code Esc}. */
        private static KeyCode code(String name) {
            KeyCode code = BY_NAME.get(squashed(name));
            if (code != null) return code;
            throw new IllegalArgumentException("No key \"" + name + "\"; say a letter, a digit, Enter, Escape, Tab, "
                    + "Delete, Space, Up, PageDown or F5.");
        }

        private static String squashed(String name) {
            return name.replaceAll("[ _-]", "").toLowerCase(Locale.ROOT);
        }

        /**
         * What a text field would get from it, with no modifier but Shift: a letter in its case, or the key's own
         * character unshifted; Shift's symbols hang on the keyboard layout, so a shifted digit or sign types nothing.
         */
        String typed() {
            if (control || alt || meta) return "";
            if (code.isLetterKey()) return shift ? code.getChar() : code.getChar().toLowerCase(Locale.ROOT);
            String own = code.getChar();
            return !shift && own.length() == 1 && own.charAt(0) >= ' ' && own.charAt(0) != 0x7f ? own : "";
        }

        @Override
        public String toString() {
            List<String> names = new ArrayList<>();
            if (control) names.add("Ctrl");
            if (shift) names.add("Shift");
            if (alt) names.add("Alt");
            if (meta) names.add("Meta");
            names.add(code.getName());
            return String.join("+", names);
        }
    }

    /**
     * {@code keys} pressed, typed when they type something, and released at {@code target}; the scene's accelerators
     * hear them on the way, as they do a real key's.
     */
    static void key(Node target, Keys keys) {
        String typed = keys.typed();
        fireKey(target, KeyEvent.KEY_PRESSED, typed.isEmpty() ? keys.code().getChar() : typed, keys);
        if (!typed.isEmpty()) fireKey(target, KeyEvent.KEY_TYPED, typed, keys);
        fireKey(target, KeyEvent.KEY_RELEASED, typed.isEmpty() ? keys.code().getChar() : typed, keys);
    }

    private static void fireKey(Node target, EventType<KeyEvent> type, String text, Keys keys) {
        Event.fireEvent(target, new KeyEvent(type, type == KeyEvent.KEY_TYPED ? text : KeyEvent.CHAR_UNDEFINED, text,
                keys.code(), keys.shift(), keys.control(), keys.alt(), keys.meta()));
    }

    /** A point in the scene and on the screen; the screen's is the scene's while the window has none. */
    private record At(double x, double y, double screenX, double screenY) {
        static At centreOf(Node node) {
            Bounds bounds = node.getLayoutBounds();
            Point2D scene = node.localToScene(bounds.getCenterX(), bounds.getCenterY());
            Point2D screen = node.localToScreen(bounds.getCenterX(), bounds.getCenterY());
            return screen == null ? new At(scene.getX(), scene.getY(), scene.getX(), scene.getY())
                    : new At(scene.getX(), scene.getY(), screen.getX(), screen.getY());
        }

        At toward(At end, double share) {
            return new At(x + (end.x - x) * share, y + (end.y - y) * share,
                    screenX + (end.screenX - screenX) * share, screenY + (end.screenY - screenY) * share);
        }
    }

    /**
     * {@code count} clicks of {@code button} at {@code node}'s centre: pressed, released, clicked, each counting up;
     * the secondary button's first click asks for the context menu too, on its press as on Linux and macOS, on its
     * release as on Windows.
     */
    static void click(Node node, MouseButton button, int count) {
        At at = At.centreOf(node);
        boolean onRelease = Os.current().isWindows();
        for (int clicks = 1; clicks <= count; clicks++) {
            boolean menu = button == MouseButton.SECONDARY && clicks == 1;
            mouse(node, MouseEvent.MOUSE_PRESSED, at, button, clicks, true, menu && !onRelease);
            if (menu && !onRelease) contextMenu(node, at);
            mouse(node, MouseEvent.MOUSE_RELEASED, at, button, clicks, true, menu && onRelease);
            if (menu && onRelease) contextMenu(node, at);
            mouse(node, MouseEvent.MOUSE_CLICKED, at, button, clicks, true, false);
        }
    }

    private static void contextMenu(Node node, At at) {
        Event.fireEvent(node, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, at.x(), at.y(),
                at.screenX(), at.screenY(), false, null));
    }

    /** A primary-button event of {@code type} at {@code at}, not moved since its press. */
    private static void mouse(Node node, EventType<MouseEvent> type, At at) {
        mouse(node, type, at, MouseButton.PRIMARY, 1, true, false);
    }

    /** {@code still}: not moved since the press, which tells a click from a drag. */
    private static void mouse(Node node, EventType<MouseEvent> type, At at, MouseButton button, int clicks,
                              boolean still, boolean popupTrigger) {
        boolean down = type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_DRAGGED
                || type == MouseEvent.DRAG_DETECTED;
        Event.fireEvent(node, new MouseEvent(type, at.x(), at.y(), at.screenX(), at.screenY(), button, clicks,
                false, false, false, false, down && button == MouseButton.PRIMARY, false,
                down && button == MouseButton.SECONDARY, true, popupTrigger, still, null));
    }

    /**
     * A primary-button drag from {@code from}'s centre to {@code to}'s: pressed, dragged in steps and released on
     * {@code from}, as JavaFX delivers them; when {@code from} starts a press-drag-release gesture, {@code to} gets
     * its drag entered, over, released and exited.
     *
     * <p>{@code startFullDrag} is told apart by the refusal it throws, which ends {@code from}'s drag-detected handler
     * there: whatever that handler does after it is not done.
     *
     * @throws Undrivable when {@code from} starts a Dragboard drag-and-drop, which only the platform can carry
     */
    static void drag(Node from, Node to) {
        At start = At.centreOf(from);
        At end = At.centreOf(to);
        At first = start.toward(end, 1.0 / DRAG_STEPS);
        mouse(from, MouseEvent.MOUSE_PRESSED, start);
        mouse(from, MouseEvent.MOUSE_DRAGGED, first, MouseButton.PRIMARY, 1, false, false);
        boolean full = false;
        try {
            mouse(from, MouseEvent.DRAG_DETECTED, first, MouseButton.PRIMARY, 1, false, false);
        } catch (IllegalStateException | NullPointerException started) {
            if (String.valueOf(started.getMessage()).contains("drag and drop")) {
                mouse(from, MouseEvent.MOUSE_RELEASED, first, MouseButton.PRIMARY, 1, false, false);
                throw new Undrivable("That is a drag-and-drop source: only a real mouse carries its drop. Do it with "
                        + "edit_code or the plugin's own tools, or ask the user to drag it.");
            }
            if (!startedFullDrag(started)) throw started;
            full = true;
        }
        for (int step = 2; step <= DRAG_STEPS; step++) {
            mouse(from, MouseEvent.MOUSE_DRAGGED, start.toward(end, step / (double) DRAG_STEPS), MouseButton.PRIMARY,
                    1, false, false);
        }
        if (full) {
            // The _TARGET kinds only: each node on the way turns them into its own entered and exited.
            for (var type : List.of(MouseDragEvent.MOUSE_DRAG_ENTERED_TARGET, MouseDragEvent.MOUSE_DRAG_OVER,
                    MouseDragEvent.MOUSE_DRAG_RELEASED, MouseDragEvent.MOUSE_DRAG_EXITED_TARGET)) {
                boolean down = type == MouseDragEvent.MOUSE_DRAG_ENTERED_TARGET || type == MouseDragEvent.MOUSE_DRAG_OVER;
                Event.fireEvent(to, new MouseDragEvent(type, end.x(), end.y(), end.screenX(), end.screenY(),
                        MouseButton.PRIMARY, 1, false, false, false, false, down, false, false, true, false, null, from));
            }
        }
        mouse(from, MouseEvent.MOUSE_RELEASED, end, MouseButton.PRIMARY, 1, false, false);
    }

    /** Whether {@code failure} is {@code startFullDrag} refusing a gesture JavaFX did not start itself. */
    private static boolean startedFullDrag(RuntimeException failure) {
        StackTraceElement[] frames = failure.getStackTrace();
        for (int i = 0; i < Math.min(3, frames.length); i++) {
            if (frames[i].getMethodName().equals("startFullDrag")) return true;
        }
        return false;
    }

    /**
     * The wheel turned at {@code node} by {@code pages} of the control that scrolls it, down when positive; that
     * control takes it as it takes a real wheel's.
     */
    static void scroll(Node node, double pages) {
        double dy = -pages * scroller(node).getLayoutBounds().getHeight();
        At at = At.centreOf(node);
        Event.fireEvent(node, new ScrollEvent(ScrollEvent.SCROLL, at.x(), at.y(), at.screenX(), at.screenY(),
                false, false, false, false, false, false, 0, dy, 0, dy,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
    }

    /** Whether a wheel at {@code node} has something to turn: {@link #scroller} finds one. */
    static boolean wheels(Node node) {
        return scroller(node) != null;
    }

    /** The nearest scroll pane, list, tree or table holding {@code node}, or {@code node} itself; null for none. */
    private static Node scroller(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at instanceof ScrollPane || at instanceof ListView<?> || at instanceof TreeView<?>
                    || at instanceof TableView<?> || at instanceof TreeTableView<?>) {
                return at;
            }
        }
        return null;
    }

    /** Whether a scroll pane holds {@code node}, so {@link #reveal} has something to move. */
    static boolean scrolled(Node node) {
        for (Node at = node.getParent(); at != null; at = at.getParent()) {
            if (at instanceof ScrollPane) return true;
        }
        return false;
    }

    /** Every scroll pane holding {@code node} moved so its top left shows, innermost first. */
    static void reveal(Node node) {
        for (Node at = node.getParent(); at != null; at = at.getParent()) {
            if (!(at instanceof ScrollPane pane) || pane.getContent() == null) continue;
            Node content = pane.getContent();
            Bounds in = content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            Bounds all = content.getLayoutBounds();
            Bounds view = pane.getViewportBounds();
            double spareY = all.getHeight() - view.getHeight();
            double spareX = all.getWidth() - view.getWidth();
            if (spareY > 0) {
                pane.setVvalue(pane.getVmin() + (pane.getVmax() - pane.getVmin())
                        * Math.clamp((in.getMinY() - all.getMinY()) / spareY, 0, 1));
            }
            if (spareX > 0) {
                pane.setHvalue(pane.getHmin() + (pane.getHmax() - pane.getHmin())
                        * Math.clamp((in.getMinX() - all.getMinX()) / spareX, 0, 1));
            }
        }
    }
}
