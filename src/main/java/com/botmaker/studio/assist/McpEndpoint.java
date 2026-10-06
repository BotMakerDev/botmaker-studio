package com.botmaker.studio.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serves {@link McpTools} over MCP's streamable HTTP transport, so any MCP client — Claude Code, Cursor, Codex,
 * Gemini CLI, opencode, a local runner — can edit the bot open in Studio through the host's own edit checks,
 * and run it.
 * The Assistant tab starts it when it opens an AI tool ({@link AiTool}), and points that tool here.
 *
 * <p><b>Local and authenticated.</b> Bound to {@code 127.0.0.1} only, and every request must carry
 * {@code Authorization: Bearer <token>}: a loopback port is reachable by every process on the machine and, via
 * DNS rebinding, by a web page in its browser, and this one can rewrite the user's code. The token is
 * {@link McpConfig}'s, in a file only the user can read.
 */
public final class McpEndpoint implements AutoCloseable {

    public static final String PATH = "/mcp";

    private final Server jetty;
    private final McpSyncServer mcp;
    private final int port;

    private McpEndpoint(Server jetty, McpSyncServer mcp, int port) {
        this.jetty = jetty;
        this.mcp = mcp;
        this.port = port;
    }

    /**
     * Starts serving {@code bot} and {@code driver} on {@code port} ({@code 0} for any free one). The plugins'
     * tools are the ones {@code driver} offers now, and again each time a tool adds or removes a plugin; a plugin
     * changed another way (Plugins &amp; Libraries) is served on the endpoint's next start.
     *
     * @param log what runs and what it printed; the caller closes it
     * @throws Exception when the port is taken or Jetty will not start; nothing is left running
     */
    public static McpEndpoint start(int port, String token, LiveBot bot, StudioDriver driver, RunLog log)
            throws Exception {
        Objects.requireNonNull(token, "token");
        McpJsonMapper json = new JacksonMcpJsonMapper(new ObjectMapper());
        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(json).mcpEndpoint(PATH).build();
        AtomicReference<Runnable> refresh = new AtomicReference<>(() -> { });
        List<McpServerFeatures.SyncToolSpecification> own =
                McpTools.studio(json, bot, driver, log, () -> refresh.get().run());
        Set<String> ownNames = McpTools.names(own);
        List<McpServerFeatures.SyncToolSpecification> theirs = McpTools.pluginTools(json, driver, ownNames);
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>(own);
        tools.addAll(theirs);
        McpSyncServer mcp = McpServer.sync(transport)
                .serverInfo("botmaker-studio", "1")
                .instructions(McpTools.INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tools)
                .build();
        refresh.set(new PluginToolRefresh(mcp, json, driver, ownNames, McpTools.names(theirs))::run);

        Server jetty = new Server(new InetSocketAddress("127.0.0.1", port));
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(transport), PATH);
        context.addFilter(new FilterHolder(bearer(token)), "/*", EnumSet.of(DispatcherType.REQUEST));
        jetty.setHandler(context);
        try {
            jetty.start();
        } catch (Exception e) {
            mcp.close();
            jetty.stop();
            throw e;
        }
        int bound = ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
        return new McpEndpoint(jetty, mcp, bound);
    }

    public int port() {
        return port;
    }

    /** What a client is pointed at. */
    public String url() {
        return "http://127.0.0.1:" + port + PATH;
    }

    @Override
    public void close() {
        try {
            mcp.close();
        } finally {
            try {
                jetty.stop();
            } catch (Exception ignored) {
                // Stopping is best-effort: the port is released with the process in any case.
            }
        }
    }

    /**
     * Serves the plugins' tools anew after a tool added or removed a plugin: the ones served go, the bound
     * plugins' now come, and clients are told the list changed. One at a time: two plugin changes racing would
     * each remove what the other added.
     */
    private static final class PluginToolRefresh {
        private final McpSyncServer mcp;
        private final McpJsonMapper json;
        private final StudioDriver driver;
        private final Set<String> own;
        private Set<String> served;

        PluginToolRefresh(McpSyncServer mcp, McpJsonMapper json, StudioDriver driver, Set<String> own,
                          Set<String> served) {
            this.mcp = mcp;
            this.json = json;
            this.driver = driver;
            this.own = own;
            this.served = served;
        }

        synchronized void run() {
            try {
                List<McpServerFeatures.SyncToolSpecification> now = McpTools.pluginTools(json, driver, own);
                for (String name : served) mcp.removeTool(name);
                now.forEach(mcp::addTool);
                served = McpTools.names(now);
                mcp.notifyToolsListChanged();
            } catch (RuntimeException e) {
                // The change itself is done; a client that is not told lists the tools again on its next start.
                System.err.println("Warning: the plugins' assistant tools could not be served anew: " + e.getMessage());
            }
        }
    }

    /** Refuses a request whose bearer token is not {@code token}, compared in constant time. */
    private static Filter bearer(String token) {
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return (request, response, chain) -> {
            String header = ((HttpServletRequest) request).getHeader("Authorization");
            byte[] given = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, given)) {
                ((HttpServletResponse) response).sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            chain.doFilter(request, response);
        };
    }
}
