package com.botmaker.studio.ui.app.terminal;

import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import javafx.concurrent.Worker;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page the Terminal tab draws: the vendored xterm.js loads in JavaFX's WebKit and the bridge object the
 * page talks through exists. A program is not started here — that is {@code PtySessionTest}'s — so this holds
 * the one thing no other test can: that xterm.js still parses in the WebKit this JavaFX ships.
 */
class TerminalViewTest extends FxHeadlessTest {

    private WebView web;

    @Override
    public void start(Stage stage) {
        web = new WebView();
        web.getEngine().load(TerminalView.class.getResource("/terminal/index.html").toExternalForm());
        stage.setScene(new Scene(web, 640, 360));
        stage.show();
    }

    @Test
    void theVendoredXtermLoadsInThisWebKit() throws Exception {
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS,
                () -> WaitForAsyncUtils.asyncFx(() -> web.getEngine().getLoadWorker().getState())
                        .get() == Worker.State.SUCCEEDED);
        assertEquals("function", interactAndGet("typeof Terminal"));
        assertEquals("function", interactAndGet("typeof FitAddon.FitAddon"));
        assertEquals("function", interactAndGet("typeof bm.start"));
    }

    /** The whole wire: the page reports a size, the program starts, its output is drawn, keystrokes reach it. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aProgramDrawsOnTheScreenAndHearsTheKeyboard() throws Exception {
        CompletableFuture<Integer> exit = new CompletableFuture<>();
        TerminalView view = WaitForAsyncUtils.asyncFx(() -> {
            TerminalView v = new TerminalView(new TerminalView.Launch(
                    List.of("/bin/sh", "-c", "echo BM_READY; read x; echo BM_GOT_$x"),
                    Path.of(System.getProperty("java.io.tmpdir")), Map.of()),
                    exit::complete);
            web.getScene().setRoot((Parent) v.node());
            return v;
        }).get(5, TimeUnit.SECONDS);
        WebView page = (WebView) view.node();

        waitForScreen(page, "BM_READY");
        WaitForAsyncUtils.asyncFx(() -> page.getEngine().executeScript("window.java.onData('ok\\r')"))
                .get(5, TimeUnit.SECONDS);
        waitForScreen(page, "BM_GOT_ok");
        assertEquals(0, exit.get(10, TimeUnit.SECONDS));
        WaitForAsyncUtils.asyncFx(view::dispose).get(5, TimeUnit.SECONDS);
    }

    private static void waitForScreen(WebView page, String text) throws Exception {
        String js = "(function(){var b=term?term.buffer.active:null,s='';if(!b)return s;"
                + "for(var i=0;i<b.length;i++){var l=b.getLine(i);if(l)s+=l.translateToString(true)+'\\n';}return s;})()";
        WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS,
                () -> WaitForAsyncUtils.asyncFx(() -> String.valueOf(page.getEngine().executeScript(js)))
                        .get().contains(text));
    }

    @Test
    void everyThemeHasColours() {
        for (BlockTheme.ThemeType type : BlockTheme.ThemeType.values()) {
            String json = TerminalView.themeJson(type);
            assertTrue(json.contains("background:") && json.contains("foreground:"), type + ": " + json);
        }
    }

    @Test
    void aQuotedStringSurvivesItsQuotes() {
        assertEquals("'it\\'s a \\\\ path'", TerminalView.quote("it's a \\ path"));
    }

    private String interactAndGet(String js) throws Exception {
        return WaitForAsyncUtils.asyncFx(() -> String.valueOf(web.getEngine().executeScript(js)))
                .get(5, TimeUnit.SECONDS);
    }
}
