package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AssistantTool;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The MCP endpoint of a dev Studio with no project's endpoint up (2026-10-25): on the bot list, in the Run window,
 * while a project opens. It serves {@code ui}, {@code quit_studio} and, once the shell is set, {@link HomeTools}
 * (2026-10-28), and only when {@link UiTools#enabled}, so Claude can drive Studio's own windows end to end. A
 * project's endpoint (the Assistant tab's) takes the port over:
 * it calls {@link #yieldPort} before it binds and {@link #serve} once it has let go.
 *
 * <p>Process-wide on purpose: there is one port ({@link McpConfig}), and whichever endpoint holds it is this
 * Studio's.
 */
public final class HomeEndpoint {

    static final String INSTRUCTIONS = "BotMaker Studio shows no project's tools now (the bot list, the Run window, "
            + "or a project still opening). ui reads and drives its windows; list_projects and open_project open a "
            + "bot, whose editor's tools then take this address over.";
    /** How long a start waits for a project's endpoint to let go of the port. */
    private static final int BIND_TRIES = 20;
    private static final long BIND_PAUSE_MS = 250;

    /**
     * What the home's tools ask of the client that shows the bot list ({@link HomeTools}); Studio's is
     * {@code HomeShell}, which {@code BotMakerStudio} sets. Every method may be called from any thread.
     */
    public interface Shell {
        /** Opens {@code dir} in the Runner when {@code run}, else in the editor, on the client's own thread. */
        void open(Path dir, boolean run);

        /** My bots as the bot list shows them, newest first. */
        List<Listed> bots();

        /** The published bots a project can start from, a line each, for the model to read. */
        String publishedBots();

        /**
         * Makes the project {@code name}, blank or from the published {@code bot}, with {@code plugins}, asking on
         * screen what needs the user's yes, then hands its folder to {@code opened}, whose answer this returns.
         *
         * @throws IllegalArgumentException with the sentence that says why it was not made
         */
        String create(String name, String bot, List<String> plugins, Function<Path, String> opened);

        /** What a question on screen waits for, or empty when none is up. */
        String busy();

        /** One of My bots: its folder and name, whose, its version, when it was used, and what state it is in. */
        record Listed(Path dir, String name, Optional<String> owner, Optional<String> version, LocalDateTime lastUsed,
                      boolean gone, boolean unpacked, Optional<String> available) {
        }
    }

    private static McpEndpoint running;
    /** Null until the shell is set: the home then serves {@code ui} and {@code quit_studio} only. */
    private static Shell shell;
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

    /** Whether this is a dev Studio Claude drives ({@link UiTools#enabled}): it serves each project it opens too. */
    public static boolean driven() {
        return UiTools.enabled();
    }

    /** What {@code open_project} and {@code create_project} ask of Studio; set before {@link #serve}. */
    public static synchronized void shell(Shell opens) {
        shell = opens;
    }

    private static void start(long mine) {
        McpConfig config = McpConfig.load();
        List<SyncToolSpecification> tools = tools();
        for (int attempt = 0; attempt < BIND_TRIES; attempt++) {
            try {
                synchronized (HomeEndpoint.class) {
                    if (generation != mine || running != null) return;
                    running = McpEndpoint.startHome(config.port(), config.token(), tools, StudioToolGroup.INSTANCE);
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
        List<AssistantTool<?>> own = new ArrayList<>(List.of(new UiTools().tool(), McpTools.said("quit_studio",
                "Close BotMaker Studio; through the botmaker mcp-bridge, start_studio opens it again.",
                UiTools::quit)));
        Shell opens;
        synchronized (HomeEndpoint.class) {
            opens = shell;
        }
        if (opens != null) own.addAll(HomeTools.of(opens));
        return own.stream().map(t -> McpTools.withNews(StudioToolGroup.INSTANCE, McpTools.served(json, t))).toList();
    }
}
