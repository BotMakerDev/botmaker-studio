package com.botmaker.studio.assist;

import com.botmaker.studio.parser.guard.RefusalJournal;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP endpoint over real HTTP, as a client speaks to it: initialize, list the tools, call one that edits.
 * The open file is a stand-in holding a string, so what is asserted is the transport, the auth and the
 * one-edit-one-commit rule — not Studio's canvas.
 */
class McpEndpointTest {

    private static final String TOKEN = "test-token";
    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                public void run() {
                    int count = 3;
                }
            }
            """;

    /** The open file, held as text; a commit replaces it. */
    private static final class FakeFile implements LiveFile {
        String source = SOURCE;
        final List<String> commits = new ArrayList<>();

        @Override
        public Optional<AssistTurn> begin() {
            return Optional.of(new AssistTurn(new AssistWorkspace(
                    ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects")),
                    Paths.get("Subject.java").toAbsolutePath(),
                    List.of(System.getProperty("java.class.path").split(File.pathSeparator)),
                    Paths.get("src", "main", "java").toAbsolutePath(),
                    ProjectTemplate.GAME_BOT, null, null, null,
                    RefusalJournal.in(Path.of(System.getProperty("java.io.tmpdir"), "botmaker-test-refusals")),
                    ValueGrammar.empty()), source));
        }

        @Override
        public String commit(AssistTurn turn, String label) {
            return turn.commit(source, label).map(event -> {
                source = event.newCode();
                commits.add(label);
                return "Applied.";
            }).orElse("Not applied.");
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private FakeFile file;
    private McpEndpoint endpoint;
    private String session;

    @BeforeEach
    void start() throws Exception {
        file = new FakeFile();
        endpoint = McpEndpoint.start(0, TOKEN, file);
    }

    @AfterEach
    void stop() {
        endpoint.close();
    }

    /** Posts one JSON-RPC message and answers the JSON reply, reading it out of an SSE frame when it is one. */
    private HttpResponse<String> post(String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint.url()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (session != null) request.header("Mcp-Session-Id", session);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> session = id);
        return response;
    }

    private JsonNode rpc(int id, String method, String params) throws Exception {
        HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}", TOKEN);
        assertEquals(200, response.statusCode(), response.body());
        String body = response.body();
        Matcher data = Pattern.compile("(?m)^data:\\s*(\\{.*})\\s*$").matcher(body);
        return json.readTree(data.find() ? data.group(1) : body);
    }

    private void initialize() throws Exception {
        JsonNode init = rpc(1, "initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}");
        assertEquals("botmaker-studio", init.path("result").path("serverInfo").path("name").asText(), init.toString());
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", TOKEN);
    }

    private String text(JsonNode callResult) {
        return callResult.path("result").path("content").path(0).path("text").asText();
    }

    @Test
    void aRequestWithoutTheTokenIsRefused() throws Exception {
        assertEquals(401, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", null).statusCode());
        assertEquals(401, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "wrong").statusCode());
    }

    @Test
    void theToolsAreListed() throws Exception {
        initialize();
        JsonNode tools = rpc(2, "tools/list", "{}").path("result").path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertEquals(List.of("list_palette", "read_tree", "list_errors", "insert_block", "set_slot", "delete_block"), names);
    }

    @Test
    void anAcceptedEditIsCommittedOnItsOwn() throws Exception {
        initialize();
        String tree = text(rpc(2, "tools/call", "{\"name\":\"read_tree\",\"arguments\":{}}"));
        Matcher body = Pattern.compile("\"name\":\"run\".*?\"body\":\\{\"id\":\"([^\"]+)\"").matcher(tree);
        assertTrue(body.find(), tree);

        JsonNode insert = rpc(3, "tools/call", "{\"name\":\"insert_block\",\"arguments\":{\"bodyId\":\""
                + body.group(1) + "\",\"index\":1,\"paletteId\":\"block:PRINT\"}}");
        assertFalse(insert.path("result").path("isError").asBoolean(), insert.toString());
        assertTrue(text(insert).contains("Applied."), text(insert));
        assertEquals(1, file.commits.size());
        assertTrue(file.source.contains("System.out.println"), file.source);
    }

    @Test
    void aRefusedEditCommitsNothingAndSaysWhy() throws Exception {
        initialize();
        JsonNode insert = rpc(2, "tools/call",
                "{\"name\":\"insert_block\",\"arguments\":{\"bodyId\":\"nowhere\",\"index\":0,\"paletteId\":\"block:PRINT\"}}");
        assertTrue(insert.path("result").path("isError").asBoolean(), insert.toString());
        assertTrue(text(insert).startsWith("REFUSED"), text(insert));
        assertTrue(file.commits.isEmpty());
        assertEquals(SOURCE, file.source);
    }

    @Test
    void theConfigSurvivesARestartWithItsToken(@TempDir Path dir) {
        Path saved = dir.resolve("mcp.json");
        McpConfig fresh = McpConfig.read(saved);
        assertFalse(fresh.enabled());
        assertEquals(McpConfig.DEFAULT_PORT, fresh.port());
        fresh.withEnabled(true).write(saved);

        McpConfig again = McpConfig.read(saved);
        assertTrue(again.enabled());
        assertEquals(fresh.token(), again.token());
        assertTrue(again.claudeCodeCommand().contains("Authorization: Bearer " + fresh.token()));
    }
}
