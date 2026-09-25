package com.botmaker.studio.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
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
import java.util.EnumSet;
import java.util.Objects;

/**
 * Serves {@link McpTools} over MCP's streamable HTTP transport, so any MCP client — Claude Code, Cursor, Codex,
 * Gemini CLI, opencode, a local runner — can edit the file open in Studio through the host's own edit checks.
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
     * Starts serving {@code file} on {@code port} ({@code 0} for any free one).
     *
     * @throws Exception when the port is taken or Jetty will not start; nothing is left running
     */
    public static McpEndpoint start(int port, String token, LiveFile file) throws Exception {
        Objects.requireNonNull(token, "token");
        McpJsonMapper json = new JacksonMcpJsonMapper(new ObjectMapper());
        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(json).mcpEndpoint(PATH).build();
        McpSyncServer mcp = McpServer.sync(transport)
                .serverInfo("botmaker-studio", "1")
                .instructions(McpTools.INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(McpTools.all(json, file))
                .build();

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
