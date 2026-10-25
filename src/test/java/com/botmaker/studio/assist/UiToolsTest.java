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
        stage.setTitle("My bots");
        stage.setScene(new Scene(new VBox(new MenuBar(file), name, archive, field), 300, 200));
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
        assertTrue(ui.press(archive).startsWith("Pressed r" + archive + " (button \"Archive\")."));
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
