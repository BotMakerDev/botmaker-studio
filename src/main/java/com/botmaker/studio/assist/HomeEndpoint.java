package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AssistantTool;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.stage.Window;

import java.util.List;

/**
 * The MCP endpoint of a dev Studio with no project's endpoint up (2026-10-25): on the bot list, in the Run window,
 * while a project opens. It serves only {@code ui} and {@code quit_studio}, and only when {@link UiTools#enabled}, so
 * Claude can drive Studio's own windows end to end. A project's endpoint (the Assistant tab's) takes the port over:
 * it calls {@link #yieldPort} before it binds and {@link #serve} once it has let go.
 *
 * <p>Process-wide on purpose: there is one port ({@link McpConfig}), and whichever endpoint holds it is this
 * Studio's.
 */
public final class HomeEndpoint {

    static final String INSTRUCTIONS = "BotMaker Studio shows no project's tools now (the bot list, the Run window, "
            + "or a project still opening). ui reads and drives its windows; the editor's tools come back when a "
            + "project's endpoint starts.";
    /** How long a start waits for a project's endpoint to let go of the port. */
    private static final int BIND_TRIES = 20;
    private static final long BIND_PAUSE_MS = 250;

    private static McpEndpoint running;
    private static Window main;
    /** Bumped by every {@link #yieldPort}: a start that finishes after one lets go at once. */
    private static long generation;

    private HomeEndpoint() {}

    /** Starts serving, off the calling thread, unless the flag is off or it serves already. */
    public static synchronized void serve() {
        if (!UiTools.enabled() || running != null) return;
        long mine = ++generation;
        Thread starter = new Thread(() -> start(mine), "mcp-home-start");
        starter.setDaemon(true);
        starter.start();
    }

    /**
     * Lets go of the port, for a project's endpoint. Under the same lock as a bind, so a start that is binding
     * finishes first and is closed here, never after the project's endpoint has begun to bind.
     */
    public static synchronized void yieldPort() {
        generation++;
        if (running != null) running.close();
        running = null;
    }

    /** Studio's main window, which {@code quit_studio} closes. */
    public static synchronized void mainWindow(Window window) {
        main = window;
    }

    static synchronized Window mainWindow() {
        return main;
    }

    private static void start(long mine) {
        McpConfig config = McpConfig.load();
        List<SyncToolSpecification> tools = tools();
        for (int attempt = 0; attempt < BIND_TRIES; attempt++) {
            try {
                synchronized (HomeEndpoint.class) {
                    if (generation != mine || running != null) return;
                    running = McpEndpoint.startHome(config.port(), config.token(), tools);
                    System.out.println("UI tools served on " + running.url());
                    return;
                }
            } catch (Exception taken) {
                try {
                    Thread.sleep(BIND_PAUSE_MS);
                } catch (InterruptedException stopped) {
                    return;
                }
            }
        }
        System.err.println("The UI tools could not listen on port " + config.port() + ".");
    }

    private static List<SyncToolSpecification> tools() {
        JacksonMcpJsonMapper json = new JacksonMcpJsonMapper(new ObjectMapper());
        List<AssistantTool<?>> own = List.of(new UiTools().tool(), McpTools.said("quit_studio",
                "Close BotMaker Studio; through the botmaker mcp-bridge, start_studio opens it again.",
                UiTools::quit));
        return own.stream().map(t -> McpTools.served(json, t)).toList();
    }
}
