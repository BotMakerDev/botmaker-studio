package com.botmaker.studio.assist;

import com.botmaker.core.assist.McpTools;
import com.botmaker.core.assist.StudioDriver;
import com.botmaker.core.assist.ToolGroup;
import com.botmaker.core.config.AppVersion;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.server.CoreClient;
import com.botmaker.core.server.CoreLauncher;
import com.botmaker.core.server.CoreLink;
import com.botmaker.core.server.Reply;
import com.botmaker.studio.services.CoreEdits;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Studio's tools as the core serves them (plan phase 10d): one channel to the user's core for the whole app, apart from
 * a project's ({@link CoreEdits}), on which Studio registers the tools it serves now — the home's on the bot list, a
 * project's own and its plugins' in the editor — and answers each call the core forwards. The core serves its own
 * edit tools beside them; Studio's are its screen, runs, versions and plugins.
 *
 * <p>Process-wide on purpose, as the MCP's one port is. A core that stops is started again and the tools registered
 * again ({@link CoreLink}). A call runs on a thread of its own, never on the channel's reader, which reads its answer.
 */
public final class CoreTools {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final McpJsonMapper MCP = new JacksonMcpJsonMapper(JSON);
    private static final ExecutorService CALLS = Executors.newCachedThreadPool(work -> {
        Thread thread = new Thread(work, "core-tool-call");
        thread.setDaemon(true);
        return thread;
    });
    /** Ties to the core and registers, one at a time. */
    private static final ExecutorService REGISTER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "core-tools-register");
        thread.setDaemon(true);
        return thread;
    });

    /** What is served now; guarded by the class. */
    private static Map<String, SyncToolSpecification> served = Map.of();
    /** What Studio serves with no project open. */
    private static List<SyncToolSpecification> homeTools = List.of();
    /** Who admits the core's own tools, or null when no project is open in this Studio. */
    private static StudioDriver driver;
    private static ToolGroup group = ToolGroup.NONE;
    private static CoreLink link;

    private CoreTools() {
    }

    /**
     * Studio serves {@code tools} with no project open, the home's ({@code HomeTools.served}), from now on and each time
     * a project's go ({@link #backHome}).
     */
    public static CompletableFuture<Optional<CoreLauncher.Address>> home(List<SyncToolSpecification> tools) {
        synchronized (CoreTools.class) {
            homeTools = List.copyOf(tools);
        }
        return serve(tools, null, ToolGroup.NONE);
    }

    /** The project's tools go: Studio serves the home's again. */
    public static void backHome() {
        List<SyncToolSpecification> tools;
        synchronized (CoreTools.class) {
            tools = homeTools;
        }
        serve(tools, null, ToolGroup.NONE);
    }

    /**
     * Studio serves {@code tools} for its open project ({@code McpTools.all}); the core asks {@code driver} and
     * {@code screen} before each of its own tools runs ({@link McpTools#admit}).
     *
     * @return once registered, where an MCP client reaches them ({@code McpEndpoint.url}) and the token it sends: empty
     *         when no core could be had
     */
    public static CompletableFuture<Optional<CoreLauncher.Address>> project(List<SyncToolSpecification> tools,
                                                                            StudioDriver projectDriver,
                                                                            ToolGroup screen) {
        return serve(tools, projectDriver, screen);
    }

    /** Lets the core go: Studio is ending. */
    public static void stop() {
        CoreLink tied;
        synchronized (CoreTools.class) {
            tied = link;
            link = null;
        }
        if (tied != null) tied.close();
    }

    /**
     * Registers {@code tools} with the core, tying to it first when Studio is not yet and has something to serve; off
     * the calling thread, since a core may have to be started. Registrations run one after another, in the order made.
     */
    private static CompletableFuture<Optional<CoreLauncher.Address>> serve(List<SyncToolSpecification> tools,
                                                                           StudioDriver projectDriver,
                                                                           ToolGroup screen) {
        Map<String, SyncToolSpecification> byName = new LinkedHashMap<>();
        tools.forEach(tool -> byName.putIfAbsent(tool.tool().name(), tool));
        synchronized (CoreTools.class) {
            served = byName;
            driver = projectDriver;
            group = screen;
        }
        return CompletableFuture.supplyAsync(CoreTools::register, REGISTER);
    }

    /** What is served now, sent to the core; a core to be had first when there is something to serve. */
    private static Optional<CoreLauncher.Address> register() {
        CoreLink tied;
        boolean empty;
        synchronized (CoreTools.class) {
            tied = link;
            empty = served.isEmpty();
        }
        if (tied == null && empty) return Optional.empty();
        try {
            if (tied == null) {
                // Only this thread ties: registrations run on it one at a time.
                tied = connect();
                synchronized (CoreTools.class) {
                    link = tied;
                }
            }
            send(tied.client());
            return Optional.of(tied.address());
        } catch (IOException e) {
            System.err.println("Studio's tools could not reach BotMaker's core: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static CoreLink connect() throws IOException {
        CoreLink tied = CoreLink.connect(CoreEdits.coreDir(), AppVersion.label(), CoreLauncher.process(),
                event -> { }, CoreTools::called, new CoreLink.Listener() {
                    @Override
                    public void stopped() {
                        // The project's own channel says so on the status line.
                    }

                    @Override
                    public void restarted() {
                    }

                    @Override
                    public void lost(IOException why) {
                        System.err.println("Studio's tools lost BotMaker's core: " + why.getMessage());
                        synchronized (CoreTools.class) {
                            link = null;
                        }
                    }
                });
        tied.rejoin(CoreTools::send);
        return tied;
    }

    /** What is served now, registered on {@code client}. */
    private static void send(CoreClient client) throws IOException {
        List<String> tools = new ArrayList<>();
        boolean admits;
        synchronized (CoreTools.class) {
            for (SyncToolSpecification spec : served.values()) tools.add(MCP.writeValueAsString(spec.tool()));
            admits = driver != null;
        }
        if (client.tools(tools, admits) instanceof EditResult.Refused refused) {
            throw new IOException(refused.reason());
        }
    }

    /** A call the core forwarded, answered from a thread of its own. */
    private static void called(Reply.Call call) {
        CALLS.execute(() -> {
            CallToolResult result = answer(call);
            CoreLink tied;
            synchronized (CoreTools.class) {
                tied = link;
            }
            if (tied == null) return;
            try {
                tied.client().called(call.call(), MCP.writeValueAsString(result));
            } catch (IOException e) {
                System.err.println("Studio's answer to " + call.tool() + " did not reach the core: " + e.getMessage());
            }
        });
    }

    private static CallToolResult answer(Reply.Call call) {
        Map<String, Object> arguments;
        try {
            arguments = JSON.readValue(call.arguments(), new TypeReference<>() { });
        } catch (IOException e) {
            return failed("The call's arguments could not be read: " + e.getMessage());
        }
        CallToolRequest request = new CallToolRequest(call.tool(), arguments);
        SyncToolSpecification tool;
        StudioDriver admitting;
        ToolGroup screen;
        synchronized (CoreTools.class) {
            tool = served.get(call.tool());
            admitting = driver;
            screen = group;
        }
        try {
            return switch (call.kind()) {
                case ADMIT -> admitting == null
                        ? CallToolResult.builder().content(List.of()).isError(false).build()
                        : McpTools.admit(admitting, screen, request);
                case RUN -> tool == null
                        ? failed(call.tool() + " is not served by Studio now; list the tools again.")
                        : tool.callHandler().apply(null, request);
            };
        } catch (RuntimeException e) {
            return failed(call.tool() + " failed in Studio: " + e);
        }
    }

    private static CallToolResult failed(String why) {
        return CallToolResult.builder().content(List.of(new McpSchema.TextContent("FAILED: " + why))).isError(true)
                .build();
    }
}
