package com.botmaker.studio.assist;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ModelFactory#models} against a real HTTP server on a loopback port, speaking each provider's shape. */
class ModelListingTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** Serves {@code body} as JSON at every path ending in {@code suffix}, and 404 elsewhere. */
    private String serve(String suffix, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            int status = exchange.getRequestURI().getPath().endsWith(suffix) ? 200 : 404;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static List<String> ids(ModelFactory.Listing listing) {
        return assertInstanceOf(ModelFactory.Listing.Models.class, listing, listing.toString())
                .models().stream().map(ModelFactory.Choice::id).toList();
    }

    @Test
    void ollamaListsWhatIsInstalledMostRecentFirst() throws IOException {
        String url = serve("/api/tags", """
                {"models": [
                  {"name": "llama3.2:latest", "model": "llama3.2:latest", "modified_at": "2026-01-01T10:00:00Z",
                   "size": 1, "digest": "a", "details": {}},
                  {"name": "qwen3:latest", "model": "qwen3:latest", "modified_at": "2026-06-01T10:00:00Z",
                   "size": 1, "digest": "b", "details": {}}
                ]}""");

        assertEquals(List.of("qwen3:latest", "llama3.2:latest"),
                ids(ModelFactory.models(Provider.OLLAMA, url, name -> null)));
    }

    @Test
    void anOpenAiCompatibleServerOffersChatModelsOnlyNewestFirst() throws IOException {
        String url = serve("/models", """
                {"object": "list", "data": [
                  {"id": "old-chat", "object": "model", "created": 1600000000, "owned_by": "me"},
                  {"id": "text-embedding-nomic", "object": "model", "created": 1800000000, "owned_by": "me"},
                  {"id": "new-chat", "object": "model", "created": 1700000000, "owned_by": "me"},
                  {"id": "whisper-large", "object": "model", "created": 1700000001, "owned_by": "me"}
                ]}""");

        assertEquals(List.of("new-chat", "old-chat"),
                ids(ModelFactory.models(Provider.OPENAI_COMPATIBLE, url + "/v1", name -> null)));
    }

    @Test
    void aServerThatIsDownIsASentence() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        ModelFactory.Listing listing = ModelFactory.models(Provider.OLLAMA, "http://127.0.0.1:" + port, name -> null);

        ModelFactory.Listing.Problem problem = assertInstanceOf(ModelFactory.Listing.Problem.class, listing);
        assertTrue(problem.reason().startsWith("Could not list Ollama (local) models at http://127.0.0.1:"),
                problem.reason());
    }

    @Test
    void aHostedProviderWithoutItsKeyAsksNobody() throws IOException {
        serve("/models", "{}");

        ModelFactory.Listing listing = ModelFactory.models(Provider.ANTHROPIC, "", name -> null);

        ModelFactory.Listing.Problem problem = assertInstanceOf(ModelFactory.Listing.Problem.class, listing);
        assertTrue(problem.reason().contains("ANTHROPIC_API_KEY"), problem.reason());
        assertEquals(0, requests.get());
    }
}
