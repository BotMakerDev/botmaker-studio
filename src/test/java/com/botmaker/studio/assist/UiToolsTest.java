package com.botmaker.studio.assist;

import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiToolsTest extends FxHeadlessTest {

    private final AtomicInteger archived = new AtomicInteger();
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Label name = new Label("A bot whose name is far too long for its card");
        name.setMaxWidth(80);
        Button archive = new Button("Archive");
        archive.setOnAction(e -> archived.incrementAndGet());
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
        root.getChildren().addAll(add, open, broken);
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
        assertTrue(chars < 1_500, "ui is " + chars + " characters");
    }
}
