package com.botmaker.studio.assist;

import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiToolsTest extends FxHeadlessTest {

    private final AtomicInteger archived = new AtomicInteger();
    private final AtomicInteger renamed = new AtomicInteger();
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Label name = new Label("A bot whose name is far too long for its card");
        name.setMaxWidth(80);
        Button archive = new Button("Archive");
        archive.setOnAction(e -> archived.incrementAndGet());
        MenuItem rename = new MenuItem("Rename");
        rename.setOnAction(e -> renamed.incrementAndGet());
        archive.setContextMenu(new ContextMenu(rename));
        TextField field = new TextField();
        field.setPromptText("Name");
        Menu file = new Menu("File", null, new MenuItem("My Bots"));
        VBox root = new VBox(new MenuBar(file), name, archive, field);
        Button add = new Button("Add");
        add.setOnAction(e -> root.getChildren().add(new Label("Added")));
        Button open = new Button("Open");
        open.setOnAction(e -> {
            Stage dialog = new Stage();
            dialog.setTitle("Confirm");
            Button ok = new Button("OK");
            ok.setOnAction(done -> dialog.close());
            dialog.setScene(new Scene(new VBox(ok), 120, 60));
            dialog.show();
        });
        Button broken = new Button("Broken");
        broken.setOnAction(e -> {
            throw new IllegalStateException("no bot");
        });
        Button slow = new Button("Slow");
        slow.setOnAction(e -> slowly());
        root.getChildren().addAll(add, open, broken, slow);
        stage.setTitle("My bots");
        stage.setScene(new Scene(root, 300, 300));
        stage.show();
    }

    private static int refOf(String read, String what) {
        Matcher m = Pattern.compile("r(\\d+) " + Pattern.quote(what)).matcher(read);
        assertTrue(m.find(), what + " not in:\n" + read);
        return Integer.parseInt(m.group(1));
    }

    @Test
    void aLabelItsWidthCutsShortSaysCut() {
        String read = UiTools.onFx(() -> new UiTools().read(null));
        assertTrue(read.contains("label \"A bot whose name is far too long for its card\" cut \""), read);
        assertTrue(read.contains("button \"Archive\""), read);
        assertTrue(read.contains("field \"\" (prompt \"Name\")"), read);
    }

    @Test
    void aPressFiresTheButtonAndTypeFillsTheField() {
        UiTools ui = new UiTools();
        String read = UiTools.onFx(() -> ui.read("my bots"));
        int archive = refOf(read, "button \"Archive\"");
        assertTrue(ui.press(archive).startsWith("Pressed r" + archive + " (button \"Archive\");"));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, archived.get());
        assertFalse(UiTools.pressing());
        int field = refOf(read, "field");
        UiTools.onFx(() -> ui.type(field, "Firestone"));
        assertTrue(UiTools.onFx(() -> ui.read(null)).contains("field \"Firestone\""));
    }

    @Test
    void theMenuBarIsNamedOnlyUnlessAskedFor() {
        UiTools ui = new UiTools();
        String read = UiTools.onFx(() -> ui.read(null));
        assertTrue(read.contains("menu bar [File]"), read);
        assertFalse(read.contains("File ▸ My Bots"), read);
        assertTrue(UiTools.onFx(() -> ui.read(null, true)).contains("menu \"File ▸ My Bots\""));
    }

    @Test
    void anOpenMenuIsReadWhenNoWindowIsNamed() {
        ContextMenu menu = new ContextMenu(new MenuItem("Archive…"));
        UiTools.onFx(() -> {
            menu.show(stage);
            return null;
        });
        try {
            String read = UiTools.onFx(() -> new UiTools().read(null));
            assertTrue(read.contains("menu \"Archive…\""), read);
            assertFalse(read.contains("button \"Archive\""), read);
        } finally {
            UiTools.onFx(() -> {
                menu.hide();
                return null;
            });
        }
    }

    @Test
    void aRefFromBeforeTheWindowChangedIsRefused() {
        UiTools ui = new UiTools();
        int archive = refOf(UiTools.onFx(() -> ui.read(null)), "button \"Archive\"");
        UiTools.onFx(() -> {
            stage.getScene().setRoot(new VBox(new Label("Archived")));
            return null;
        });
        assertThrows(IllegalArgumentException.class, () -> ui.press(archive));
        assertThrows(IllegalArgumentException.class, () -> ui.press(99));
    }

    @Test
    void aPressSaysWhatItAddedWithItsRefs() {
        UiTools ui = new UiTools();
        int add = refOf(UiTools.onFx(() -> ui.read("my bots")), "button \"Add\"");
        String answer = ui.press(add);
        assertTrue(answer.contains("settled after"), answer);
        assertTrue(answer.contains("+ label \"Added\""), answer);
        assertFalse(answer.contains("button \"Archive\""), answer);
    }

    @Test
    void aPressThatOpensAWindowReadsIt() {
        UiTools ui = new UiTools();
        int open = refOf(UiTools.onFx(() -> ui.read("my bots")), "button \"Open\"");
        String answer = ui.press(open);
        assertTrue(answer.contains("Opened") && answer.contains("\"Confirm\""), answer);
        int ok = refOf(answer, "button \"OK\"");
        String closed = ui.press(ok);
        assertTrue(closed.contains("Closed: \"Confirm\""), closed);
    }

    @Test
    void aPressWhoseHandlerThrowsSaysWhereItFailed() {
        UiTools ui = new UiTools();
        int broken = refOf(UiTools.onFx(() -> ui.read("my bots")), "button \"Broken\"");
        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> ui.press(broken));
        assertTrue(failed.getMessage().contains("IllegalStateException: no bot at UiToolsTest"), failed.getMessage());
    }

    @Test
    void waitGoneAnswersOnceNoWindowShowsTheText() {
        assertTrue(UiTools.waitFor("Archive", 0.3, true).contains("still shows"));
        assertTrue(UiTools.waitFor("Nowhere at all", 1.0, true).contains("was not shown"));
    }

    @Test
    void aStackTraceOnStderrKeepsItsFirstLineCausesAndFirstBotMakerFrame() {
        long before = UiEvents.last();
        for (String line : List.of("java.lang.NullPointerException: name",
                "\tat java.base/java.util.Objects.requireNonNull(Objects.java:259)",
                "\tat com.botmaker.studio.ui.app.RunHome.open(RunHome.java:40)",
                "\tat com.botmaker.studio.ui.app.RunHome.show(RunHome.java:12)",
                "\t... 4 more")) {
            UiEvents.stderr(line);
        }
        List<String> lines = UiEvents.after(before).stream().map(UiEvents.Event::text).toList();
        assertEquals(List.of("java.lang.NullPointerException: name",
                "  at com.botmaker.studio.ui.app.RunHome.open(RunHome.java:40)"), lines);
    }

    @Test
    void anUncaughtExceptionIsSummedUpWithItsCause() {
        long before = UiEvents.last();
        UiEvents.uncaught("JavaFX Application Thread",
                new IllegalStateException("could not open", new IOException("gone")));
        String text = UiEvents.after(before).getFirst().line();
        assertTrue(text.startsWith("uncaught on JavaFX Application Thread: IllegalStateException: could not open"
                + " ← IOException: gone at UiToolsTest."), text);
    }

    private static void slowly() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private static long spin(long ms) {
        long until = System.nanoTime() + ms * 1_000_000;
        long sum = 0;
        while (System.nanoTime() < until) sum += Long.numberOfTrailingZeros(sum + System.nanoTime());
        return sum;
    }

    @Test
    void aSlowHandlerIsAStallNamedByItsFrame() throws InterruptedException {
        FxWatch.start();
        UiTools ui = new UiTools();
        int slow = refOf(UiTools.onFx(() -> ui.read("my bots")), "button \"Slow\"");
        long before = FxWatch.last();
        ui.press(slow);
        for (int i = 0; i < 50 && FxWatch.after(before).isEmpty(); i++) Thread.sleep(20);
        List<FxWatch.Stall> stalls = FxWatch.after(before);
        assertFalse(stalls.isEmpty(), "no stall seen");
        FxWatch.Stall stall = stalls.getLast();
        assertTrue(stall.ms() >= FxWatch.STALL_MS && stall.line().contains("UiToolsTest.slowly"), stall.line());
        String perf = UiTools.perf(false);
        assertTrue(perf.contains("UiToolsTest.slowly") && perf.contains("press (button \"Slow\")"), perf);
        assertTrue(perf.contains("Nodes:\n  1 window \"My bots\""), perf);
    }

    @Test
    void aProfileSaysWhereTheFxThreadWasBusy(@TempDir Path dir) {
        UiProfile.start();
        UiTools.onFx(() -> spin(600));
        String summary = UiProfile.stop(dir.resolve("ui-profile.jfr"));
        assertTrue(Files.exists(dir.resolve("ui-profile.jfr")));
        assertTrue(summary.contains("FX thread (") && summary.contains("UiToolsTest.spin"), summary);
        assertThrows(IllegalArgumentException.class, () -> UiProfile.stop(dir.resolve("again.jfr")));
    }

    /** {@code node} added at the bottom of the bot list's window. */
    private void add(Node node) {
        UiTools.onFx(() -> ((VBox) stage.getScene().getRoot()).getChildren().add(node));
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void aListIsReadFromItsModelAndAPressedRowIsSelectedInView() {
        ListView<String> list = new ListView<>(FXCollections.observableArrayList(
                IntStream.range(0, 300).mapToObj(i -> "item " + i).toList()));
        list.setPrefHeight(60);
        AtomicInteger doubled = new AtomicInteger();
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) doubled.incrementAndGet();
        });
        add(list);
        UiTools ui = new UiTools();
        String read = UiTools.onFx(() -> ui.read(null));
        assertTrue(read.contains("list 300 rows") && read.contains("row \"item 99\""), read);
        assertTrue(read.contains("… 200 more rows"), read);
        assertFalse(read.contains("\"item 250\""), read);

        String found = UiTools.onFx(() -> ui.read(null, false, null, "item 250"));
        assertTrue(found.lines().anyMatch(l -> l.matches("r\\d+ row \"item 250\"")), found);
        String answer = ui.press(null, "item 250", MouseButton.PRIMARY, 1);
        assertTrue(answer.startsWith("Pressed r") && answer.contains("(row \"item 250\")"), answer);
        assertEquals(250, UiTools.onFx(() -> list.getSelectionModel().getSelectedIndex()));
        assertTrue(UiTools.onFx(() -> ui.read(null, false, null, "item 250")).contains("(selected)"));

        ui.press(null, "item 251", MouseButton.PRIMARY, 2);
        assertEquals(1, doubled.get());
        assertEquals(251, UiTools.onFx(() -> list.getSelectionModel().getSelectedIndex()));
    }

    @Test
    void aTreeSaysWhichRowsAreOpenAndExpandOpensOne() {
        TreeItem<String> firestone = new TreeItem<>("Firestone");
        firestone.getChildren().add(new TreeItem<>("Screens"));
        TreeItem<String> root = new TreeItem<>("Bots");
        root.getChildren().addAll(firestone, new TreeItem<>("Idle"));
        root.setExpanded(true);
        TreeView<String> tree = new TreeView<>(root);
        tree.setPrefHeight(80);
        add(tree);
        UiTools ui = new UiTools();
        String read = UiTools.onFx(() -> ui.read(null));
        assertTrue(read.contains("row [-] \"Bots\""), read);
        assertTrue(read.contains("row   [+] \"Firestone\"") && read.contains("row   \"Idle\""), read);
        assertFalse(read.contains("Screens"), read);

        String opened = ui.expand(refOf(read, "row   [+] \"Firestone\""));
        assertTrue(opened.startsWith("Opened r") && opened.contains("+ r") && opened.contains("\"Screens\""), opened);
        assertTrue(UiTools.onFx(firestone::isExpanded));
        assertThrows(IllegalArgumentException.class, () -> ui.expand(refOf(read, "row   \"Idle\"")));
        assertThrows(IllegalArgumentException.class, () -> ui.expand(refOf(read, "button \"Archive\"")));
    }

    @Test
    void aTableRowSaysItsColumns() {
        TableView<String> table = new TableView<>(FXCollections.observableArrayList("Firestone", "Idle"));
        TableColumn<String, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(row -> new SimpleStringProperty(row.getValue()));
        TableColumn<String, Integer> size = new TableColumn<>("Size");
        size.setCellValueFactory(row -> new SimpleObjectProperty<>(row.getValue().length()));
        table.getColumns().addAll(List.of(name, size));
        add(table);
        String read = UiTools.onFx(() -> new UiTools().read(null));
        assertTrue(read.contains("table [Name, Size] 2 rows"), read);
        assertTrue(read.matches("(?s).*row \"Firestone 9\".*|.*row \"Firestone \\| 9\".*"), read);
    }

    @Test
    void aControlWithNoTextSaysItsTooltipAccessibleTextOrId() {
        Button run = new Button();
        run.setTooltip(new Tooltip("Run the bot\nF5"));
        Region card = new Region();
        card.setAccessibleText("card Start");
        Region minimap = new Region();
        minimap.setId("minimap");
        minimap.setOnMouseClicked(e -> { });
        add(new VBox(run, card, minimap));
        String read = UiTools.onFx(() -> new UiTools().read(null));
        assertTrue(read.contains("button \"Run the bot\""), read);
        assertTrue(read.contains("part \"card Start\""), read);
        assertTrue(read.contains("click #minimap"), read);
    }

    @Test
    void aControlKeepsItsRefAcrossReadsAndChanges() {
        UiTools ui = new UiTools();
        int archive = refOf(UiTools.onFx(() -> ui.read(null)), "button \"Archive\"");
        add(new Label("Later"));
        assertEquals(archive, refOf(UiTools.onFx(() -> ui.read(null)), "button \"Archive\""));
        int add = refOf(UiTools.onFx(() -> ui.read(null)), "button \"Add\"");
        String answer = ui.press(add);
        assertFalse(answer.contains("renumbered"), answer);
        ui.press(archive);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, archived.get());
    }

    @Test
    void readWithinAPartOrFindingATextAndPressOrTypeByText() {
        Region panel = new VBox(new Button("Inner"));
        panel.setAccessibleText("panel");
        add(panel);
        UiTools ui = new UiTools();
        int part = refOf(UiTools.onFx(() -> ui.read(null)), "part \"panel\"");
        String within = UiTools.onFx(() -> ui.read(null, false, part, null));
        assertTrue(within.lines().findFirst().orElseThrow().endsWith("within (vbox \"panel\")"), within);
        assertTrue(within.contains("button \"Inner\"") && !within.contains("Archive"), within);
        String found = UiTools.onFx(() -> ui.read(null, false, null, "arch"));
        assertEquals(2, found.lines().count(), found);
        assertTrue(UiTools.onFx(() -> ui.read(null, false, null, "nowhere")).contains("No line holds \"nowhere\"."));

        ui.press(null, "Archive", MouseButton.PRIMARY, 1);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, archived.get());
        IllegalArgumentException several = assertThrows(IllegalArgumentException.class,
                () -> ui.press(null, "o", MouseButton.PRIMARY, 1));
        assertTrue(several.getMessage().contains("places; say a ref") && several.getMessage().contains("\"Open\""),
                several.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ui.press(null, "Nowhere", MouseButton.PRIMARY, 1));
        ui.typed(null, "Name", "Firestone");
        assertTrue(UiTools.onFx(() -> ui.read(null)).contains("field \"Firestone\""));
    }

    @Test
    void aTextLongerThanAReadShowsIsFoundByItsWholeText() {
        String whole = "Download every picture this bot needs from the catalog before running it";
        AtomicInteger pressed = new AtomicInteger();
        Button longOne = new Button(whole);
        longOne.setOnAction(e -> pressed.incrementAndGet());
        add(new VBox(longOne, new Button("Download every picture")));
        new UiTools().press(null, whole, MouseButton.PRIMARY, 1);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, pressed.get());
    }

    @Test
    void aRefInATabNoLongerShownIsRefused() {
        Tab first = new Tab("First", new Button("Inside"));
        Tab second = new Tab("Second", new Label("Elsewhere"));
        TabPane tabs = new TabPane(first, second);
        add(tabs);
        UiTools ui = new UiTools();
        int inside = refOf(UiTools.onFx(() -> ui.read(null)), "button \"Inside\"");
        UiTools.onFx(() -> {
            tabs.getSelectionModel().select(second);
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();
        UiTools.onFx(() -> ui.read(null));
        assertThrows(IllegalArgumentException.class, () -> ui.press(inside));
    }

    @Test
    void aRightClickOpensTheMenuWhoseItemsArePressable() {
        UiTools ui = new UiTools();
        int archive = refOf(UiTools.onFx(() -> ui.read("my bots")), "button \"Archive\"");
        String answer = ui.press(archive, MouseButton.SECONDARY, 1);
        assertTrue(answer.startsWith("Right-clicked r" + archive) && answer.contains("Opened"), answer);
        String closed = ui.press(refOf(answer, "menu \"Rename\""));
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, renamed.get(), closed);
        assertEquals(0, archived.get());
    }

    @Test
    void aDoubleClickCountsTwo() {
        AtomicInteger doubled = new AtomicInteger();
        Region twice = new Region();
        twice.setMinSize(20, 20);
        twice.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) doubled.incrementAndGet();
        });
        add(twice);
        UiTools ui = new UiTools();
        int ref = refOf(UiTools.onFx(() -> ui.read("my bots")), "click");
        assertTrue(ui.press(ref, MouseButton.PRIMARY, 2).startsWith("Double-clicked r" + ref));
        assertEquals(1, doubled.get());
        assertThrows(IllegalArgumentException.class, () -> ui.press(ref, MouseButton.PRIMARY, 3));
    }

    @Test
    void keysReachTheSceneAcceleratorsAndTypeIntoTheFocusedField() {
        AtomicInteger accelerated = new AtomicInteger();
        UiTools.onFx(() -> stage.getScene().getAccelerators()
                .put(KeyCombination.valueOf("Shortcut+Shift+A"), accelerated::incrementAndGet));
        UiTools ui = new UiTools();
        String read = UiTools.onFx(() -> ui.read("my bots"));
        ui.key(null, "Ctrl+Shift+A");
        assertEquals(1, accelerated.get());
        String answer = ui.key(refOf(read, "field"), "x");
        assertTrue(answer.contains(" field \"x\""), answer);
        assertEquals(KeyCode.PAGE_DOWN, UiInput.Keys.of("PageDown").code());
        assertEquals("Ctrl+Shift+A", UiInput.Keys.of("ctrl + shift + a").toString());
        assertThrows(IllegalArgumentException.class, () -> UiInput.Keys.of("Hyper+A"));
        assertThrows(IllegalArgumentException.class, () -> UiInput.Keys.of("Ctrl+Nowhere"));
        assertThrows(IllegalArgumentException.class, () -> UiInput.Keys.of("Ctrl++"));
        assertEquals(".", UiInput.Keys.of("Period").typed());
        assertEquals("", UiInput.Keys.of("Enter").typed());
    }

    @Test
    void aScrollBringsARowIntoViewOrTurnsTheWheel() {
        VBox rows = new VBox();
        for (int i = 1; i <= 40; i++) rows.getChildren().add(new Button("Row " + i));
        ScrollPane pane = new ScrollPane(rows);
        pane.setPrefHeight(60);
        add(pane);
        UiTools ui = new UiTools();
        String read = UiTools.onFx(() -> ui.read("my bots"));
        assertTrue(ui.scroll(refOf(read, "button \"Row 40\""), null).startsWith("Brought"));
        assertEquals(1.0, UiTools.onFx(pane::getVvalue), 0.01);
        UiTools.onFx(() -> {
            pane.setVvalue(0);
            return null;
        });
        ui.scroll(refOf(read, "button \"Row 1\""), 1.0);
        assertTrue(UiTools.onFx(pane::getVvalue) > 0, "the wheel did not move the pane");
        assertThrows(IllegalArgumentException.class, () -> ui.scroll(refOf(read, "button \"Archive\""), null));
        assertThrows(IllegalArgumentException.class, () -> ui.scroll(refOf(read, "button \"Archive\""), 1.0));
    }

    @Test
    void aPressDragReleaseGestureDropsOnItsTarget() {
        AtomicInteger dropped = new AtomicInteger();
        AtomicInteger entered = new AtomicInteger();
        AtomicInteger clickedOnRelease = new AtomicInteger();
        Region knob = new Region();
        knob.setMinSize(20, 20);
        knob.setOnMousePressed(e -> {});
        knob.setOnMouseReleased(e -> {
            if (e.isStillSincePress()) clickedOnRelease.incrementAndGet();
        });
        knob.setOnDragDetected(e -> knob.startFullDrag());
        Button bin = new Button("Bin");
        bin.setOnMouseDragEntered(e -> entered.incrementAndGet());
        bin.setOnMouseDragReleased(e -> {
            if (e.getGestureSource() == knob) dropped.incrementAndGet();
        });
        add(new VBox(knob, bin));
        UiTools ui = new UiTools();
        String read = UiTools.onFx(() -> ui.read("my bots"));
        String answer = ui.drag(refOf(read, "click"), refOf(read, "button \"Bin\""));
        assertTrue(answer.startsWith("Dragged"), answer);
        assertEquals(1, dropped.get());
        assertEquals(1, entered.get());
        assertEquals(0, clickedOnRelease.get());
    }

    @Test
    void aDragboardDragIsRefusedNamingWhatToUse() {
        Region card = new Region();
        card.setMinSize(20, 20);
        card.setOnMousePressed(e -> {});
        card.setOnDragDetected(e -> card.startDragAndDrop(TransferMode.MOVE));
        add(card);
        UiTools ui = new UiTools();
        String read = UiTools.onFx(() -> ui.read("my bots"));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ui.drag(refOf(read, "click"), refOf(read, "button \"Archive\"")));
        assertTrue(refused.getMessage().contains("edit_code"), refused.getMessage());
    }

    @Test
    void onlyAFlaggedDevStudioServesIt() {
        assertFalse(UiTools.enabled(key -> null, key -> null));
        assertTrue(UiTools.enabled(Map.of(UiTools.FLAG_ENV, "1")::get, key -> null));
        assertFalse(UiTools.enabled(Map.of(UiTools.FLAG_ENV, "1")::get,
                Map.of("jpackage.app-path", "/opt/botmaker/bin/BotMaker")::get));
    }

    @Test
    void itStaysSmallForTheAssistantToRead() {
        var tool = new UiTools().tool();
        int chars = tool.description().length() + McpTools.schemaOf(tool.schema()).length();
        // 1,500 until perf and profile came, 1,800 until key, scroll and drag, 2,400 until expand, within and find
        // (2026-10-28).
        assertTrue(chars < 2_600, "ui is " + chars + " characters");
    }
}
