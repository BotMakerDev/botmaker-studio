package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AgentReply;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;
import com.botmaker.shared.config.CacheDirs;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.CheckBox;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.text.Text;
import javafx.scene.transform.Transform;
import javafx.stage.Modality;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.awt.image.BufferedImage;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * {@code ui}: Studio's own windows, read and driven as a user would (2026-10-25), so Claude can try the bot list,
 * the Run window and every dialog itself. Only in a dev Studio started with {@code BOTMAKER_MCP_UI=1}: an installed
 * Studio never serves it, and with the flag on it presses trust prompts too (the user's call).
 *
 * <p>{@code read} numbers what a window shows; a ref holds until the next {@code read}. Every scene access runs on the
 * FX thread; a press is fired there without waiting for it, since a press may open a dialog that waits for its own
 * answer. Keys, right and double clicks, scrolls and drags are events {@link UiInput} fires as a user's would arrive
 * (2026-10-28).
 *
 * <p>Built to find bugs as well as to drive (2026-10-28): a press or a type waits until what the windows show holds
 * still, then answers with what changed (a window opened is read whole), and every answer ends with the errors
 * {@link UiEvents} caught since the last one said, the stalls {@link FxWatch} saw and the heap. {@code perf} and
 * {@code profile} ({@link UiProfile}) say where the time went; {@link UiFindings} keeps it all for the next walk.
 */
final class UiTools {

    static final String FLAG_ENV = "BOTMAKER_MCP_UI";
    static final String FLAG_PROPERTY = "botmaker.mcp.ui";
    private static final String WINDOW = "a window's number from windows, or part of its title; the focused one "
            + "when left out";
    /** At most this many lines in one read; the rest is counted. */
    private static final int MOST_LINES = 400;
    private static final long SETTLE_MS = 400;
    /** Studio is idle once what its windows show holds this long with no progress spinning, ... */
    private static final long QUIET_MS = 300;
    /** ... looked at this often, and waited for at most this long. */
    private static final long IDLE_POLL_MS = 100;
    private static final long MOST_IDLE_MS = 5_000;
    /** What {@link #walk} writes for an indeterminate progress indicator. */
    private static final String SPINNING = "progress working";
    /** At most this many changed lines, and this many problems, in one answer. */
    private static final int MOST_CHANGES = 40;
    private static final int MOST_EVENTS = 10;
    /** The longest an endpoint's close waits for a press, and the moment after for its answer to go out. */
    private static final long MOST_PRESS_MS = MOST_IDLE_MS + 1_000;
    private static final long SENT_MS = 150;
    /** Presses in flight, over every endpoint's {@code ui}. */
    private static final AtomicInteger PRESSING = new AtomicInteger();

    /** {@code ui}'s actions. */
    sealed interface Ui {
        @Describe("the open windows, numbered")
        record Windows() implements Ui {
        }

        @Describe("what a window shows, each control with a ref; a label its width cuts short says cut")
        record Read(@Describe(value = WINDOW, optional = true) String window,
                    @Describe(value = "true lists the menu bar's items too", optional = true) Boolean menus)
                implements Ui {
        }

        @Describe("press a button, link, menu item, tab, check box, list item or clickable area")
        record Press(@Describe("a ref from read") int ref,
                     @Describe(value = "right opens its menu", optional = true) Mouse button,
                     @Describe(value = "2: a double click", optional = true) Integer count) implements Ui {
        }

        @Describe("put text in a field, or pick a choice box's item by its text")
        record Type(int ref, @Describe("the text") String text) implements Ui {
        }

        @Describe("keys, as Ctrl+Shift+A or Escape")
        record Key(String keys, @Describe(value = "the focused control when left out", optional = true) Integer ref)
                implements Ui {
        }

        @Describe("scroll a ref into view, or its list or pane by pages")
        record Scroll(int ref, @Describe(value = "down, negative up", optional = true) Double pages) implements Ui {
        }

        @Describe("drag one ref onto another")
        record Drag(int from, int to) implements Ui {
        }

        @Describe("a picture of a window")
        record Look(@Describe(value = WINDOW, optional = true) String window,
                    @Describe(value = "0.1 to 1; 0.5 when left out", optional = true) Double scale) implements Ui {
        }

        @Describe("wait until a text shows in any window or title")
        record Wait(@Describe("the text") String text,
                    @Describe(value = "seconds, 10 when left out", optional = true) Double seconds,
                    @Describe(value = "true: until it goes", optional = true) Boolean gone) implements Ui {
        }

        @Describe("Studio's newest errors")
        record Log(@Describe(value = "how many, 30 when left out", optional = true) Integer lines) implements Ui {
        }

        @Describe("stalls, slowest steps, node counts")
        record Perf(@Describe(value = "true: count from now", optional = true) Boolean mark) implements Ui {
        }

        @Describe("JFR: where the time goes between start and stop")
        record Profile(Step step) implements Ui {
        }
    }

    /** A {@link Ui.Profile}'s step. */
    enum Step {
        START, STOP
    }

    /** A {@link Ui.Press}'s mouse button. */
    enum Mouse {
        LEFT, RIGHT
    }

    /** What an input does, built on the FX thread: its step for {@code perf}, its answer's start, and the events. */
    private record Act(String step, String said, Runnable events) {
    }

    /** A press or type and its time to idle, -1 when Studio never held still. */
    private record Timed(String what, long ms) {
    }

    /** What the last read numbered: a {@link Node}, {@link MenuItem} or {@link Tab}. FX thread only. */
    private final Map<Integer, Object> refs = new HashMap<>();
    /** The window the refs are in; null before the first read. FX thread only. */
    private Window lastRead;
    /** The newest {@link UiEvents} event an answer has reported, over every endpoint: a new one repeats none. */
    private static final AtomicLong SEEN = new AtomicLong();
    /** Likewise the newest {@link FxWatch} stall reported, the one {@code perf} counts from, and the GC time said. */
    private static final AtomicLong STALLS_SEEN = new AtomicLong();
    private static final AtomicLong PERF_MARK = new AtomicLong();
    private static final AtomicLong GC_SAID = new AtomicLong(-1);
    /** The latest presses and types, oldest first, at most {@link #KEPT_STEPS}. */
    private static final Deque<Timed> STEPS = new ArrayDeque<>();
    private static final int KEPT_STEPS = 200;
    private static final int MOST_STALLS = 5;
    private static final int SLOWEST = 5;

    /** Whether this Studio serves {@code ui}: the flag set, and not an installed Studio. */
    static boolean enabled() {
        return enabled(System::getenv, System::getProperty);
    }

    static boolean enabled(UnaryOperator<String> env, UnaryOperator<String> properties) {
        String launcher = properties.apply("jpackage.app-path");
        if (launcher != null && !launcher.isBlank()) return false;
        String set = properties.apply(FLAG_PROPERTY);
        if (set == null || set.isBlank()) set = env.apply(FLAG_ENV);
        return set != null && (set.strip().equals("1") || set.strip().equalsIgnoreCase("true"));
    }

    AssistantTool<AssistantTool.OneOf<Ui>> tool() {
        return AssistantTool.named("ui")
                .describedAs("Studio's own windows: list, read, press, type, key, scroll, drag, look, wait; answers "
                        + "say what changed, new errors and stalls. Dev Studio only.")
                .takesOneOf(Ui.class)
                .handledBy((action, context) -> reported(handle(action)));
    }

    private AgentReply handle(Ui action) {
        try {
            return switch (action) {
                case Ui.Windows ignored -> AgentReply.text(onFx(UiTools::windows));
                case Ui.Read read -> AgentReply.text(onFx(() -> read(read.window(), Boolean.TRUE.equals(read.menus()))));
                case Ui.Press press -> AgentReply.text(press(press.ref(),
                        press.button() == Mouse.RIGHT ? MouseButton.SECONDARY : MouseButton.PRIMARY,
                        press.count() == null ? 1 : press.count()));
                case Ui.Type type -> AgentReply.text(typed(type.ref(), type.text()));
                case Ui.Key key -> AgentReply.text(key(key.ref(), key.keys()));
                case Ui.Scroll scroll -> AgentReply.text(scroll(scroll.ref(), scroll.pages()));
                case Ui.Drag drag -> AgentReply.text(drag(drag.from(), drag.to()));
                case Ui.Look look -> look(look.window(), look.scale());
                case Ui.Wait wait -> AgentReply.text(waitFor(wait.text(), wait.seconds(), Boolean.TRUE.equals(wait.gone())));
                case Ui.Log log -> AgentReply.text(log(log.lines()));
                case Ui.Perf perf -> AgentReply.text(perf(Boolean.TRUE.equals(perf.mark())));
                case Ui.Profile profile -> AgentReply.text(switch (profile.step()) {
                    case START -> UiProfile.start();
                    case STOP -> UiProfile.stop(CacheDirs.cacheRoot().resolve("ui-profile.jfr"));
                    case null -> throw new IllegalArgumentException("Say start or stop.");
                });
            };
        } catch (IllegalArgumentException refused) {
            return AgentReply.refused("REFUSED: " + refused.getMessage());
        } catch (RuntimeException failed) {
            return AgentReply.refused("Studio could not do that: " + failed.getMessage());
        }
    }

    /** {@code quit_studio} on the home endpoint: Studio's main window asked to close, a moment after the answer. */
    static String quit() {
        Window main = HomeEndpoint.mainWindow();
        if (main == null || !onFx(main::isShowing)) throw new IllegalArgumentException("Studio's window is not shown.");
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(SETTLE_MS);
            } catch (InterruptedException stopped) {
                return;
            }
            Platform.runLater(() -> main.fireEvent(new WindowEvent(main, WindowEvent.WINDOW_CLOSE_REQUEST)));
        });
        return "Closing Studio. start_studio opens it again.";
    }

    // ---- windows ------------------------------------------------------------------------------------------

    private static List<Window> showing() {
        return Window.getWindows().stream().filter(Window::isShowing).toList();
    }

    static String windows() {
        List<Window> all = showing();
        if (all.isEmpty()) return "No window is shown.";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < all.size(); i++) out.append(describe(i + 1, all.get(i))).append('\n');
        return out.toString().stripTrailing();
    }

    private static String describe(int number, Window window) {
        String kind = window instanceof ContextMenu ? "menu" : window instanceof PopupWindow ? "popup" : "window";
        String modal = window instanceof Stage s && s.getModality() != Modality.NONE ? " modal" : "";
        String focused = window.isFocused() ? " focused" : "";
        return number + " " + kind + " \"" + title(window) + "\" " + Math.round(window.getWidth()) + "x"
                + Math.round(window.getHeight()) + modal + focused;
    }

    private static String title(Window window) {
        return window instanceof Stage s && s.getTitle() != null ? s.getTitle() : "";
    }

    /**
     * The window {@code which} names: its number, part of its title; else an open menu (the newest), which takes
     * the keys while its owner keeps the focus; else the focused one, else the first.
     */
    static Window window(String which) {
        List<Window> all = showing();
        if (all.isEmpty()) throw new IllegalArgumentException("No window is shown.");
        if (which == null || which.isBlank()) {
            return all.reversed().stream().filter(ContextMenu.class::isInstance).findFirst()
                    .or(() -> all.stream().filter(Window::isFocused).findFirst()).orElse(all.getFirst());
        }
        String asked = which.strip();
        if (asked.matches("\\d+")) {
            int number = Integer.parseInt(asked);
            if (number < 1 || number > all.size()) {
                throw new IllegalArgumentException("No window " + number + "; windows lists " + all.size() + ".");
            }
            return all.get(number - 1);
        }
        String lower = asked.toLowerCase(Locale.ROOT);
        return all.stream().filter(w -> title(w).toLowerCase(Locale.ROOT).contains(lower)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No window titled like \"" + asked + "\"."));
    }

    // ---- read ---------------------------------------------------------------------------------------------

    String read(String which) {
        return read(which, false);
    }

    /** {@code menus}: the menu bar's items too, which run to thousands of characters in the editor. */
    String read(String which, boolean menus) {
        return read(window(which), menus);
    }

    /** {@code window} read, its controls numbered from r1; the refs held until now are dropped. FX thread. */
    private String read(Window window, boolean menus) {
        refs.clear();
        lastRead = window;
        List<String> lines = new ArrayList<>();
        lines.add(describe(showing().indexOf(window) + 1, window));
        if (window instanceof ContextMenu menu) {
            items(menu.getItems(), "", lines);
        } else if (window.getScene() != null) {
            walk(window.getScene().getRoot(), menus, lines);
        }
        if (lines.size() > MOST_LINES) {
            int more = lines.size() - MOST_LINES;
            lines = new ArrayList<>(lines.subList(0, MOST_LINES));
            lines.add("… " + more + " more lines");
        }
        return String.join("\n", lines);
    }

    private int ref(Object target) {
        int next = refs.size() + 1;
        refs.put(next, target);
        return next;
    }

    private void walk(Node node, boolean menus, List<String> lines) {
        if (node == null || !node.isVisible()) return;
        String off = node.isDisabled() ? " (off)" : "";
        if (node.getStyleClass().stream().anyMatch(c -> c.endsWith("card"))) lines.add("-- card");
        switch (node) {
            case MenuBar bar when !menus -> {
                lines.add("menu bar " + bar.getMenus().stream().map(Menu::getText).toList()
                        + " (menus true lists its items)");
                return;
            }
            case MenuBar bar -> {
                for (Menu menu : bar.getMenus()) items(menu.getItems(), menu.getText() + " ▸ ", lines);
                return;
            }
            case TabPane tabs -> {
                for (Tab tab : tabs.getTabs()) {
                    lines.add("r" + ref(tab) + " tab \"" + tab.getText() + "\""
                            + (tab.isSelected() ? " (selected)" : "") + (tab.isDisabled() ? " (off)" : ""));
                }
                Tab selected = tabs.getSelectionModel().getSelectedItem();
                if (selected != null) walk(selected.getContent(), menus, lines);
                return;
            }
            case TitledPane pane -> {
                lines.add("r" + ref(pane) + " section \"" + pane.getText() + "\"" + (pane.isExpanded() ? "" : " (closed)"));
                if (pane.isExpanded()) walk(pane.getContent(), menus, lines);
                return;
            }
            case TextInputControl field -> {
                String value = field instanceof PasswordField ? "••••" : field.getText();
                String prompt = field.getPromptText() == null || field.getPromptText().isBlank() ? ""
                        : " (prompt \"" + field.getPromptText() + "\")";
                lines.add("r" + ref(field) + " field \"" + value + "\"" + prompt + off);
                return;
            }
            case ComboBoxBase<?> box -> {
                lines.add("r" + ref(box) + " choice \"" + (box.getValue() == null ? "" : box.getValue()) + "\"" + off);
                return;
            }
            case ChoiceBox<?> box -> {
                lines.add("r" + ref(box) + " choice \"" + (box.getValue() == null ? "" : box.getValue()) + "\"" + off);
                return;
            }
            case ProgressIndicator progress -> {
                double done = progress.getProgress();
                lines.add("progress " + (done < 0 ? "working" : Math.round(done * 100) + "%"));
                return;
            }
            case Labeled labeled -> {
                labeled(labeled, off, lines);
                if (labeled.getGraphic() != null && !(labeled instanceof ButtonBase)) walk(labeled.getGraphic(), menus, lines);
                return;
            }
            case Text text -> {
                if (text.getText() != null && !text.getText().isBlank()) lines.add("text \"" + text.getText() + "\"");
                return;
            }
            default -> {
                if (node.getOnMouseClicked() != null || node.getOnMousePressed() != null) {
                    lines.add("r" + ref(node) + " click" + off);
                }
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) walk(child, menus, lines);
        }
    }

    private void labeled(Labeled labeled, String off, List<String> lines) {
        String text = labeled.getText() == null ? "" : labeled.getText();
        String kind = switch (labeled) {
            case CheckBox box -> "check" + (box.isSelected() ? " [x]" : " [ ]");
            case MenuButton ignored -> "menubutton";
            case ButtonBase button -> button.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("link")
                    ? "link" : "button";
            case IndexedCell<?> cell -> cell.isSelected() ? "item (selected)" : "item";
            default -> "label";
        };
        if (text.isBlank() && kind.equals("label")) return;
        if (kind.startsWith("item") && (text.isBlank() || ((IndexedCell<?>) labeled).isEmpty())) return;
        boolean pressable = !kind.equals("label");
        String shown = shownText(labeled);
        String cut = shown != null && !shown.equals(text) && !text.isBlank() ? " cut \"" + shown + "\"" : "";
        lines.add((pressable ? "r" + ref(labeled) + " " : "") + kind + " \"" + text + "\"" + cut + off);
    }

    /** What {@code labeled} draws, which an ellipsis shortens when its width is too small; null before its skin. */
    private static String shownText(Labeled labeled) {
        for (Node child : labeled.getChildrenUnmodifiable()) {
            if (child instanceof Text text) return text.getText();
        }
        return null;
    }

    private void items(List<MenuItem> items, String prefix, List<String> lines) {
        for (MenuItem item : items) {
            if (item instanceof SeparatorMenuItem || !item.isVisible()) continue;
            String name = prefix + (item.getText() == null ? "" : item.getText());
            if (item instanceof Menu sub) {
                items(sub.getItems(), name + " ▸ ", lines);
            } else {
                lines.add("r" + ref(item) + " menu \"" + name + "\"" + (item.isDisable() ? " (off)" : ""));
            }
        }
    }

    // ---- press and type -----------------------------------------------------------------------------------

    private Object target(int ref) {
        Object target = refs.get(ref);
        if (target == null) throw new IllegalArgumentException("No ref r" + ref + "; read the window again.");
        if (target instanceof Node node && (node.getScene() == null || node.getScene().getWindow() == null
                || !node.getScene().getWindow().isShowing())) {
            throw new IllegalArgumentException("r" + ref + " is gone from its window; read it again.");
        }
        return target;
    }

    /** Whether a press is in flight, which an endpoint's close waits for. */
    static boolean pressing() {
        return PRESSING.get() > 0;
    }

    /** Until no press is in flight, at most {@link #MOST_PRESS_MS}, then a moment for its answer to be sent. */
    static void awaitPresses() {
        long until = System.nanoTime() + MOST_PRESS_MS * 1_000_000;
        try {
            while (pressing() && System.nanoTime() < until) Thread.sleep(20);
            Thread.sleep(SENT_MS);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    String press(int ref) {
        return press(ref, MouseButton.PRIMARY, 1);
    }

    /** A press of {@code button}, {@code count} times; a plain press fires a control as its own action does. */
    String press(int ref, MouseButton button, int count) {
        if (count < 1 || count > 2) throw new IllegalArgumentException("Say a count of 1 or 2.");
        boolean plain = button == MouseButton.PRIMARY && count == 1;
        String how = button == MouseButton.SECONDARY ? "right-click" : count == 2 ? "double-click" : "press";
        String said = button == MouseButton.SECONDARY ? "Right-clicked" : count == 2 ? "Double-clicked" : "Pressed";
        return drive(() -> {
            String what = named(target(ref));
            Runnable run = plain ? action(ref) : clicks(ref, button, count);
            return new Act(how + " " + what, said + " r" + ref + " " + what, run);
        });
    }

    /** {@code keys} at {@code ref}, focused first, or at the focused control of the window {@code read} names. */
    String key(Integer ref, String keys) {
        UiInput.Keys parsed = UiInput.Keys.of(keys);
        return drive(() -> {
            Node node = ref == null ? focusOwner() : node(ref, "takes no keys");
            String what = ref == null ? named(node) : "r" + ref + " " + named(node);
            return new Act("key " + parsed + " at " + named(node), "Sent " + parsed + " to " + what, () -> {
                if (ref != null) node.requestFocus();
                UiInput.key(node, parsed);
            });
        });
    }

    /** {@code ref} brought into view in its scroll panes, or the wheel turned {@code pages} at it. */
    String scroll(int ref, Double pages) {
        return drive(() -> {
            Node node = node(ref, "cannot scroll");
            String what = "r" + ref + " " + named(node);
            if (pages != null) {
                if (!UiInput.wheels(node)) {
                    throw new IllegalArgumentException("r" + ref + " is in no scroll pane, list, tree or table.");
                }
                return new Act("scroll " + pages + " at " + named(node), "Scrolled " + pages + " pages at " + what,
                        () -> UiInput.scroll(node, pages));
            }
            if (!UiInput.scrolled(node)) throw new IllegalArgumentException("r" + ref + " is in no scroll pane.");
            return new Act("scroll to " + named(node), "Brought " + what + " into view", () -> UiInput.reveal(node));
        });
    }

    /** {@code from} dragged onto {@code to} with the primary button. */
    String drag(int from, int to) {
        return drive(() -> {
            Node start = node(from, "cannot be dragged");
            Node end = node(to, "cannot take a drop");
            return new Act("drag " + named(start) + " to " + named(end),
                    "Dragged r" + from + " " + named(start) + " onto r" + to + " " + named(end),
                    () -> UiInput.drag(start, end));
        });
    }

    /**
     * {@code prepare}d on the FX thread and its events fired there, then what they changed once Studio is idle.
     * Not waited for: a press that opens a dialog runs until the dialog is answered.
     */
    private String drive(Callable<Act> prepare) {
        PRESSING.incrementAndGet();
        try {
            Shown[] before = new Shown[1];
            Act act = onFx(() -> {
                Act made = prepare.call();
                before[0] = shown();
                return made;
            });
            AtomicReference<RuntimeException> failed = new AtomicReference<>();
            Platform.runLater(() -> {
                try {
                    act.events().run();
                } catch (RuntimeException e) {
                    failed.set(e);
                }
            });
            double took = awaitIdle();
            timed(act.step(), took);
            if (failed.get() instanceof UiInput.Undrivable refused) throw refused;
            if (failed.get() != null) {
                throw new IllegalStateException(act.step() + " failed: " + UiEvents.summary(failed.get()));
            }
            return act.said() + "; " + idle(took) + ".\n" + onFx(() -> changes(before[0]));
        } finally {
            PRESSING.decrementAndGet();
        }
    }

    /** The node {@code ref} names; {@code otherwise} says why a menu item or tab cannot. FX thread. */
    private Node node(int ref, String otherwise) {
        if (target(ref) instanceof Node node) return node;
        throw new IllegalArgumentException("r" + ref + " " + named(target(ref)) + " " + otherwise + ".");
    }

    /** What has the keys in the window {@code read} picks when none is named: its focus owner, else its root. */
    private static Node focusOwner() {
        Scene scene = window(null).getScene();
        if (scene == null || scene.getRoot() == null) throw new IllegalArgumentException("That window shows nothing.");
        return scene.getFocusOwner() != null ? scene.getFocusOwner() : scene.getRoot();
    }

    /** {@link #type} on the FX thread, then what it changed once Studio is idle. */
    String typed(int ref, String text) {
        Shown before = onFx(this::shown);
        String what = onFx(() -> named(target(ref)));
        String held = onFx(() -> type(ref, text));
        double took = awaitIdle();
        timed("type into " + what, took);
        return held + " " + capitalised(idle(took)) + ".\n" + onFx(() -> changes(before));
    }

    // ---- what an action changed ---------------------------------------------------------------------------

    /** The windows shown, and what the window the refs are in shows, without its refs. */
    private record Shown(List<Window> windows, Window read, List<String> lines) {
    }

    /** FX thread. */
    private Shown shown() {
        Window read = lastRead != null && lastRead.isShowing() ? lastRead : null;
        return new Shown(showing(), read, read == null ? List.of() : unnumbered(new UiTools().read(read, false)));
    }

    /**
     * What changed since {@code before}: a window opened is read whole, its refs this answer's; else the lines the
     * read window gained (with refs) and lost; else that nothing changed. FX thread.
     */
    private String changes(Shown before) {
        List<Window> now = showing();
        StringBuilder out = new StringBuilder();
        List<Window> closed = before.windows().stream().filter(w -> !now.contains(w)).toList();
        if (!closed.isEmpty()) {
            out.append("Closed: ").append(closed.stream().map(w -> w instanceof PopupWindow ? "a menu" : "\"" + title(w) + "\"")
                    .collect(Collectors.joining(", "))).append('\n');
        }
        List<Window> opened = now.stream().filter(w -> !before.windows().contains(w)).toList();
        if (!opened.isEmpty()) {
            if (opened.size() > 1) out.append("Opened ").append(opened.size()).append(" windows; the newest:\n");
            return out.append("Opened ").append(read(opened.getLast(), false)).toString();
        }
        Window target = before.read() != null && before.read().isShowing() ? before.read() : null;
        if (target == null) {
            return out.append(now.isEmpty() ? "No window is shown." : "Windows now:\n" + windows()).toString();
        }
        String after = read(target, false);
        List<String> afterLines = List.of(after.split("\n"));
        List<String> unnumberedAfter = unnumbered(after);
        List<String> lines = new ArrayList<>();
        for (String line : before.lines()) {
            if (!unnumberedAfter.contains(line)) lines.add("- " + line);
        }
        for (int i = 0; i < afterLines.size(); i++) {
            if (!before.lines().contains(unnumberedAfter.get(i))) lines.add("+ " + afterLines.get(i));
        }
        if (lines.isEmpty()) return out.append("Nothing changed in ").append(afterLines.getFirst()).append('.').toString();
        out.append("In ").append(afterLines.getFirst()).append(", refs renumbered:\n");
        int shownLines = Math.min(lines.size(), MOST_CHANGES);
        out.append(String.join("\n", lines.subList(0, shownLines)));
        if (lines.size() > shownLines) out.append("\n… ").append(lines.size() - shownLines).append(" more; read lists all");
        return out.toString();
    }

    private static List<String> unnumbered(String read) {
        return List.of(read.split("\n")).stream().map(line -> line.replaceFirst("^r\\d+ ", "")).toList();
    }

    /**
     * Until what every window shows has held still for {@link #QUIET_MS} with no progress spinning, at most
     * {@link #MOST_IDLE_MS}: the seconds it took, or -1 when it never held.
     */
    static double awaitIdle() {
        long start = System.nanoTime();
        long until = start + MOST_IDLE_MS * 1_000_000;
        String last = null;
        long stillSince = start;
        while (System.nanoTime() < until) {
            String now = onFx(UiTools::everything);
            long at = System.nanoTime();
            if (!now.equals(last)) {
                last = now;
                stillSince = at;
            } else if (at - stillSince >= QUIET_MS * 1_000_000 && !now.contains(SPINNING)) {
                return (stillSince - start) / 1e9;
            }
            try {
                Thread.sleep(IDLE_POLL_MS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return -1;
    }

    /** What every shown window shows, refs left out. FX thread. */
    private static String everything() {
        UiTools reader = new UiTools();
        return showing().stream().map(w -> reader.read(w, false).replaceAll("(?m)^r\\d+ ", ""))
                .collect(Collectors.joining("\n"));
    }

    private static String idle(double seconds) {
        return seconds < 0 ? "still changing after " + MOST_IDLE_MS / 1000 + " s"
                : String.format(Locale.ROOT, "settled after %.1f s", seconds);
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ---- what went wrong ----------------------------------------------------------------------------------

    /**
     * {@code reply}, then what went wrong in Studio since the last answer said, the stalls since then, and the heap
     * with the time GC took since then.
     */
    private static AgentReply reported(AgentReply reply) {
        StringBuilder out = new StringBuilder();
        List<UiEvents.Event> events = UiEvents.after(SEEN.get());
        if (!events.isEmpty()) {
            SEEN.accumulateAndGet(events.getLast().seq(), Math::max);
            int listed = Math.min(events.size(), MOST_EVENTS);
            out.append("Studio reported ").append(events.size()).append(" problem")
                    .append(events.size() == 1 ? "" : "s").append(" since the last answer:");
            for (UiEvents.Event event : events.subList(events.size() - listed, events.size())) {
                out.append("\n  ").append(event.line());
            }
            if (events.size() > listed) out.append("\n  (").append(events.size() - listed).append(" older; log lists them)");
            out.append('\n');
        }
        List<FxWatch.Stall> stalls = FxWatch.after(STALLS_SEEN.get());
        if (!stalls.isEmpty()) {
            STALLS_SEEN.accumulateAndGet(stalls.getLast().seq(), Math::max);
            int listed = Math.min(stalls.size(), MOST_STALLS);
            out.append("Studio's windows stalled ").append(stalls.size()).append(stalls.size() == 1 ? " time" : " times")
                    .append(" since the last answer:");
            for (FxWatch.Stall stall : stalls.subList(stalls.size() - listed, stalls.size())) {
                out.append("\n  ").append(stall.line());
            }
            if (stalls.size() > listed) out.append("\n  (").append(stalls.size() - listed).append(" older; perf lists them)");
            out.append('\n');
        }
        out.append(memory());
        return reply.and(AgentReply.text(out.toString()));
    }

    /** The heap in use, and GC's time since the last answer said it when it moved. */
    private static String memory() {
        long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long gc = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime).filter(ms -> ms > 0).sum();
        long said = GC_SAID.getAndSet(gc);
        String heap = "Heap " + used / (1024 * 1024) + " MB";
        return said < 0 || gc == said ? heap + "." : heap + "; GC " + (gc - said) + " ms since the last answer.";
    }

    /** Keeps a press or type and its time to idle; a finding row too. */
    private static void timed(String what, double seconds) {
        long ms = seconds < 0 ? -1 : Math.round(seconds * 1000);
        synchronized (STEPS) {
            STEPS.addLast(new Timed(what, ms));
            while (STEPS.size() > KEPT_STEPS) STEPS.removeFirst();
        }
        UiFindings.record(UiFindings.Kind.STEP, ms, what);
    }

    /** {@code perf}: the stalls since Studio started or the mark, the slowest steps, and each window's nodes. */
    static String perf(boolean mark) {
        List<FxWatch.Stall> stalls = FxWatch.after(PERF_MARK.get());
        StringBuilder out = new StringBuilder(stalls.isEmpty() ? "No stall" : stalls.size() + " stalls")
                .append(PERF_MARK.get() == 0 ? " since Studio started" : " since the mark")
                .append(" (over ").append(FxWatch.STALL_MS).append(" ms)").append(stalls.isEmpty() ? "." : ":");
        for (FxWatch.Stall stall : stalls) {
            out.append("\n  ").append(stall.at().truncatedTo(ChronoUnit.SECONDS)).append(' ').append(stall.line());
        }
        List<Timed> steps;
        synchronized (STEPS) {
            steps = new ArrayList<>(STEPS);
        }
        if (!steps.isEmpty()) {
            out.append("\nSlowest steps to idle:");
            steps.stream().sorted(Comparator.comparingLong((Timed t) -> t.ms() < 0 ? Long.MAX_VALUE : t.ms()).reversed())
                    .limit(SLOWEST).forEach(t -> out.append("\n  ")
                            .append(t.ms() < 0 ? "never idle" : t.ms() + " ms").append(' ').append(t.what()));
        }
        out.append("\nNodes:");
        onFx(() -> {
            List<Window> all = showing();
            for (int i = 0; i < all.size(); i++) {
                Scene scene = all.get(i).getScene();
                out.append("\n  ").append(describe(i + 1, all.get(i))).append(": ")
                        .append(scene == null ? 0 : nodes(scene.getRoot()));
            }
            return null;
        });
        if (mark) {
            PERF_MARK.set(FxWatch.last());
            out.append("\nMarked: perf counts stalls from now.");
        }
        return out.toString();
    }

    private static int nodes(Node node) {
        if (node == null) return 0;
        int count = 1;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) count += nodes(child);
        }
        return count;
    }

    /** {@code log}: the newest problems Studio reported, whether or not an answer said them. */
    private String log(Integer lines) {
        int count = lines == null ? 30 : Math.max(1, Math.min(UiEvents.KEPT, lines));
        List<UiEvents.Event> events = UiEvents.newest(count);
        SEEN.accumulateAndGet(UiEvents.last(), Math::max);
        if (events.isEmpty()) return "Studio reported no problem.";
        return events.stream().map(e -> "#" + e.seq() + " " + e.line()).collect(Collectors.joining("\n"));
    }

    /** What a press names in its answer: the target's kind and text. */
    private static String named(Object target) {
        String text = switch (target) {
            case MenuItem item -> item.getText();
            case Tab tab -> tab.getText();
            case Labeled labeled -> labeled.getText();
            default -> null;
        };
        String kind = target instanceof MenuItem ? "menu" : target instanceof Tab ? "tab"
                : target.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return text == null || text.isBlank() ? "(" + kind + ")" : "(" + kind + " \"" + text + "\")";
    }

    /** What pressing {@code ref} does. FX thread. */
    private Runnable action(int ref) {
        Object target = target(ref);
        return switch (target) {
                case MenuItem item when item.isDisable() ->
                        throw new IllegalArgumentException("r" + ref + " is off.");
                case MenuItem item -> () -> {
                    // Hidden first: an item that opens a dialog returns only once the dialog is answered.
                    if (item.getParentPopup() != null) item.getParentPopup().hide();
                    item.fire();
                };
                case Tab tab -> () -> tab.getTabPane().getSelectionModel().select(tab);
                case TitledPane pane -> () -> pane.setExpanded(!pane.isExpanded());
                case MenuButton button -> button::show;
                case ButtonBase button -> button::fire;
                case ListCell<?> cell -> clicks(ref, MouseButton.PRIMARY, 1);
                case Node node -> () -> UiInput.click(node, MouseButton.PRIMARY, 1);
                default -> throw new IllegalArgumentException("r" + ref + " cannot be pressed.");
        };
    }

    /** {@code count} clicks of {@code button} at {@code ref}, a list item selected first. FX thread. */
    private Runnable clicks(int ref, MouseButton button, int count) {
        Node node = node(ref, "takes only a plain press");
        return () -> {
            if (node instanceof ListCell<?> cell && cell.getListView() != null) {
                cell.getListView().getSelectionModel().select(cell.getIndex());
            }
            UiInput.click(node, button, count);
        };
    }

    String type(int ref, String text) {
        Object target = target(ref);
        String value = text == null ? "" : text;
        switch (target) {
            case TextInputControl field -> {
                field.requestFocus();
                field.setText(value);
                field.positionCaret(value.length());
            }
            case ComboBox<?> box -> pick(box.getItems(), value, item -> select(box, item));
            case ChoiceBox<?> box -> pick(box.getItems(), value, item -> select(box, item));
            default -> throw new IllegalArgumentException("r" + ref + " takes no text.");
        }
        return "r" + ref + " now holds \"" + value + "\".";
    }

    private static <T> void pick(List<T> items, String text, Consumer<T> choose) {
        for (T item : items) {
            if (String.valueOf(item).equalsIgnoreCase(text.strip())) {
                choose.accept(item);
                return;
            }
        }
        throw new IllegalArgumentException("No item \"" + text + "\"; it offers " + items + ".");
    }

    @SuppressWarnings("unchecked")
    private static <T> void select(ComboBox<T> box, Object item) {
        box.setValue((T) item);
    }

    @SuppressWarnings("unchecked")
    private static <T> void select(ChoiceBox<T> box, Object item) {
        box.setValue((T) item);
    }

    // ---- look and wait ------------------------------------------------------------------------------------

    private static AgentReply look(String which, Double scale) {
        double s = scale == null ? 0.5 : Math.max(0.1, Math.min(1, scale));
        Object[] taken = onFx(() -> {
            Window window = window(which);
            Scene scene = window.getScene();
            if (scene == null || scene.getRoot() == null) throw new IllegalArgumentException("That window shows nothing.");
            SnapshotParameters params = new SnapshotParameters();
            params.setTransform(Transform.scale(s, s));
            WritableImage image = scene.getRoot().snapshot(params, null);
            return new Object[] {describe(showing().indexOf(window) + 1, window), image};
        });
        WritableImage image = (WritableImage) taken[1];
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, width, height, pixels, 0, width);
        return AgentReply.text((String) taken[0]).and(AgentReply.image(out));
    }

    static String waitFor(String text, Double seconds, boolean gone) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Say what text to wait for.");
        double limit = seconds == null ? 10 : Math.max(0.2, Math.min(180, seconds));
        String lower = text.strip().toLowerCase(Locale.ROOT);
        long start = System.nanoTime();
        long until = start + (long) (limit * 1e9);
        String where = null;
        while (System.nanoTime() < until) {
            String found = onFx(() -> find(lower));
            if (found != null) where = found;
            String after = String.format(Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e9) + " s";
            if (!gone && found != null) return "\"" + text.strip() + "\" showed after " + after + ", in " + found + ".";
            if (gone && found == null) {
                return "\"" + text.strip() + "\" " + (where == null ? "was not shown" : "went after " + after) + ".";
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (gone) return "\"" + text.strip() + "\" still shows after " + limit + " s, in " + where + ".";
        return "No \"" + text.strip() + "\" after " + limit + " s. Windows:\n" + onFx(UiTools::windows);
    }

    /** The window that shows {@code lower} in its title or a text, described; null for none. */
    private static String find(String lower) {
        List<Window> all = showing();
        for (int i = 0; i < all.size(); i++) {
            Window window = all.get(i);
            boolean shows = title(window).toLowerCase(Locale.ROOT).contains(lower)
                    || window.getScene() != null && contains(window.getScene().getRoot(), lower);
            if (shows) return describe(i + 1, window);
        }
        return null;
    }

    private static boolean contains(Node node, String lower) {
        if (node == null || !node.isVisible()) return false;
        String said = switch (node) {
            case Labeled labeled -> labeled.getText();
            case Text text -> text.getText();
            case TextInputControl field -> field.getText();
            default -> null;
        };
        if (said != null && said.toLowerCase(Locale.ROOT).contains(lower)) return true;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (contains(child, lower)) return true;
            }
        }
        return false;
    }

    // ---- the FX thread ------------------------------------------------------------------------------------

    /** {@code call} on the FX thread, its answer back; its refusal stays a refusal. */
    static <T> T onFx(Callable<T> call) {
        try {
            if (Platform.isFxApplicationThread()) return call.call();
            FutureTask<T> task = new FutureTask<>(call);
            Platform.runLater(task);
            return task.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(failed.getCause());
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception other) {
            throw new IllegalStateException("Studio's window did not answer: " + other.getMessage(), other);
        }
    }
}
