package com.botmaker.studio.ui.app.terminal;

import com.botmaker.studio.services.terminal.PtySession;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * One terminal screen: xterm.js in a {@link WebView}, wired to one {@link PtySession}.
 *
 * <p>The program is started on the page's first size report, so it begins at the size it is drawn at rather
 * than 80×24 and then a resize. Output from the reader thread is gathered and written to the page once per
 * FX pulse at most, base64-encoded, so a program printing fast costs one script call per frame rather than
 * one per read. Keystrokes come back through the {@link Bridge} the page calls.
 *
 * <p>Ctrl+Shift+C / Ctrl+Shift+V copy and paste through the system clipboard: a page has no clipboard of its
 * own in a WebView, so the page asks Java.
 */
public final class TerminalView {

    /** What to run, where, and what to add to the environment. */
    public record Launch(List<String> command, Path directory, Map<String, String> env) {
        public Launch {
            command = List.copyOf(command);
            env = Map.copyOf(env);
        }
    }

    private static final String FONT_FAMILY =
            "'JetBrains Mono', 'DejaVu Sans Mono', 'Liberation Mono', 'Cascadia Mono', Consolas, monospace";
    private static final int FONT_SIZE = 13;

    private final Launch launch;
    private final IntConsumer onExit;
    private final WebView web = new WebView();
    private final WebEngine engine = web.getEngine();
    /** Held here: the page keeps only a weak reference to a Java object set on {@code window}. */
    private final Bridge bridge = new Bridge();
    private final Consumer<BlockTheme.ThemeType> themeListener = t -> script("bm.theme(" + themeJson(t) + ")");

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private boolean flushQueued;
    private boolean pageReady;
    private PtySession session;
    /** Set on the first size report: a program that failed to start is not retried on every resize. */
    private boolean started;
    private boolean disposed;

    /** {@code onExit} runs on the FX thread with the program's exit code. */
    public TerminalView(Launch launch, IntConsumer onExit) {
        this.launch = launch;
        this.onExit = onExit;
        web.setContextMenuEnabled(false);
        web.setOnContextMenuRequested(e -> contextMenu().show(web, e.getScreenX(), e.getScreenY()));
        engine.getLoadWorker().stateProperty().addListener((o, was, now) -> {
            if (now == Worker.State.SUCCEEDED) pageLoaded();
        });
        engine.load(TerminalView.class.getResource("/terminal/index.html").toExternalForm());
        BlockTheme.addThemeChangeListener(themeListener);
    }

    public Node node() {
        return web;
    }

    public void focus() {
        web.requestFocus();
        script("bm.focus()");
    }

    public boolean isRunning() {
        return session != null && session.isAlive();
    }

    /** Ends the program and stops listening for the theme. Idempotent. */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        BlockTheme.removeThemeChangeListener(themeListener);
        if (session != null) {
            PtySession closing = session;
            // close() waits up to a second for the program to go; never on the FX thread.
            Thread t = new Thread(closing::close, "pty-close");
            t.setDaemon(true);
            t.start();
        }
    }

    private void pageLoaded() {
        JSObject window = (JSObject) engine.executeScript("window");
        window.setMember("java", bridge);
        pageReady = true;
        script("bm.start(" + themeJson(BlockTheme.getCurrentThemeType()) + ", " + quote(FONT_FAMILY) + ", "
                + FONT_SIZE + ")");
    }

    private void startSession(int cols, int rows) {
        try {
            session = PtySession.start(launch.command(), launch.directory(), launch.env(), cols, rows,
                    this::received, code -> Platform.runLater(() -> exited(code)));
        } catch (IOException | RuntimeException e) {
            print("\r\n\u001B[31mCould not start " + String.join(" ", launch.command()) + ": " + e.getMessage()
                    + "\u001B[0m\r\n");
            onExit.accept(-1);
        }
    }

    private void exited(int code) {
        if (disposed) return;
        print("\r\n\u001B[2m[exited with code " + code + "]\u001B[0m\r\n");
        onExit.accept(code);
    }

    /** From the reader thread. */
    private void received(byte[] chunk) {
        synchronized (pending) {
            pending.writeBytes(chunk);
            if (flushQueued) return;
            flushQueued = true;
        }
        Platform.runLater(this::flush);
    }

    private void print(String text) {
        received(text.getBytes(StandardCharsets.UTF_8));
    }

    private void flush() {
        byte[] bytes;
        synchronized (pending) {
            flushQueued = false;
            if (!pageReady || disposed) return;
            bytes = pending.toByteArray();
            pending.reset();
        }
        if (bytes.length > 0) script("bm.write('" + Base64.getEncoder().encodeToString(bytes) + "')");
    }

    private void script(String js) {
        if (!pageReady || disposed) return;
        engine.executeScript(js);
    }

    private void paste() {
        String text = Clipboard.getSystemClipboard().getString();
        if (text == null || text.isEmpty()) return;
        script("bm.paste('" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "')");
    }

    private static void copy(String text) {
        if (text == null || text.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private ContextMenu contextMenu() {
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> copy(pageReady ? (String) engine.executeScript("bm.selection()") : ""));
        MenuItem paste = new MenuItem("Paste");
        paste.setOnAction(e -> paste());
        return new ContextMenu(copy, paste);
    }

    /** xterm's colours for each of Studio's themes. */
    static String themeJson(BlockTheme.ThemeType type) {
        return switch (type) {
            case DEFAULT -> "{background:'#ffffff',foreground:'#1f2328',cursor:'#1f2328',cursorAccent:'#ffffff',"
                    + "selectionBackground:'#b6d7ff'}";
            case DARK -> "{background:'#1e1f22',foreground:'#dfe1e5',cursor:'#dfe1e5',cursorAccent:'#1e1f22',"
                    + "selectionBackground:'#214283'}";
            case BLACK -> "{background:'#000000',foreground:'#e6e6e6',cursor:'#e6e6e6',cursorAccent:'#000000',"
                    + "selectionBackground:'#264f78'}";
            case HIGH_CONTRAST -> "{background:'#000000',foreground:'#ffffff',cursor:'#ffff00',"
                    + "cursorAccent:'#000000',selectionBackground:'#1aebff',selectionForeground:'#000000'}";
        };
    }

    static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** What the page calls. Public, because WebKit only reaches public members of a public class. */
    public final class Bridge {
        public void onData(String data) {
            if (session != null) session.write(data.getBytes(StandardCharsets.UTF_8));
        }

        public void onBinary(String data) {
            if (session != null) session.write(data.getBytes(StandardCharsets.ISO_8859_1));
        }

        public void onResize(int cols, int rows) {
            if (disposed) return;
            if (!started) {
                started = true;
                startSession(cols, rows);
            } else if (session != null) {
                session.resize(cols, rows);
            }
        }

        public void copy(String text) {
            TerminalView.copy(text);
        }

        public void paste() {
            TerminalView.this.paste();
        }
    }
}
