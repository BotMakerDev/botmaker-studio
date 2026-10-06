package com.botmaker.studio.assist;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.assist.AgentContext;
import com.botmaker.plugin.api.assist.AgentReply;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
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

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP endpoint over real HTTP, as a client speaks to it: initialize, list the tools, call them. The bot is two
 * stand-in files held as text and Studio a stand-in driver, so what is asserted is the transport, the auth, the
 * one-edit-one-commit rule, edits to a file that is not the open one, a plugin's tool and its image, and that
 * nothing starts on the game while something runs — not Studio's canvas.
 */
class McpEndpointTest {

    private static final String TOKEN = "test-token";
    private static final Path ROOT = Paths.get("src", "main", "java").toAbsolutePath();
    private static final Path SUBJECT = ROOT.resolve("com/mybot/Subject.java");
    private static final Path OTHER = ROOT.resolve("com/mybot/Other.java");
    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                public void run() {
                    int count = 3;
                }
            }
            """;
    private static final String OTHER_SOURCE = """
            package com.mybot;
            public class Other {
                public void go() {
                }
            }
            """;

    /** The bot, held as text: Subject is the open file; a commit replaces a file's text. */
    private static final class FakeBot implements LiveBot {
        final Map<Path, String> files = new LinkedHashMap<>(Map.of(SUBJECT, SOURCE, OTHER, OTHER_SOURCE));
        final List<String> commits = new ArrayList<>();

        @Override
        public Optional<AssistTurn> begin(String file) {
            Path path = file.isBlank() ? SUBJECT : LiveBot.resolve(files.keySet(), ROOT, file);
            return Optional.of(new AssistTurn(new AssistWorkspace(
                    ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects")), path,
                    List.of(System.getProperty("java.class.path").split(File.pathSeparator)), ROOT,
                    ProjectTemplate.GAME_BOT, null, null,
                    RefusalJournal.in(Path.of(System.getProperty("java.io.tmpdir"), "botmaker-test-refusals")),
                    ValueGrammar.empty(), List.of()), files.get(path)));
        }

        @Override
        public String commit(AssistTurn turn, String label) {
            return turn.commit(files.get(turn.file()), label).map(event -> {
                files.put(turn.file(), event.newCode());
                commits.add(turn.file().getFileName().toString());
                return "Applied.";
            }).orElse("Not applied.");
        }

        @Override
        public List<String> files() {
            return files.keySet().stream().map(p -> LiveBot.relative(ROOT, p)).toList();
        }

        /** What another file would say about an edit; null when nothing breaks. */
        String elsewhere;

        @Override
        public Optional<String> breaksElsewhere(AssistTurn turn) {
            return Optional.ofNullable(elsewhere);
        }
    }

    /** Studio, as far as the tools reach it: what was asked is recorded. */
    private static final class FakeDriver implements StudioDriver {
        final List<String> asked = new ArrayList<>();

        record Snap(int size) {}

        @Override public List<Target> targets() {
            return List.of(new Target("com.mybot.Other#go", "Other", "Activities", "com/mybot/Other.java", "go"));
        }
        @Override public String setTarget(String key) { return "set " + StudioDriver.target(targets(), key).key(); }
        @Override public String moveCaret(String file, String blockId) { return "caret"; }
        @Override public String showArea(Area area, String label) { return "boxed"; }
        @Override public String runBot() { asked.add("run"); return "running"; }
        @Override public String runActivity(String key) { asked.add("activity " + key); return "running"; }
        @Override public String tryStatement(String file, String blockId, Map<String, String> values) {
            asked.add("try " + blockId + " " + values);
            return "trying";
        }
        @Override public String stop() { return "stopped"; }
        @Override public String rename(String symbol, String to) { asked.add("rename " + symbol + " " + to); return "renamed"; }

        @Override
        public List<PluginTool> pluginTools() {
            AssistantTool<Snap> snap = AssistantTool.named("snap").describedAs("A square picture.")
                    .takes(Snap.class).handledBy((p, ctx) -> AgentReply.text("a " + p.size() + "px square")
                            .and(AgentReply.image(new BufferedImage(p.size(), p.size(), BufferedImage.TYPE_INT_RGB))));
            return List.of(new PluginTool("fake_snap", "Fake", snap));
        }

        @Override
        public AgentContext agentContext() {
            return new AgentContext() {
                @Override public StudioServices services() { return null; }
                @Override public Optional<BufferedImage> frame() { return Optional.empty(); }
                @Override public Optional<Area> watchedArea() { return Optional.empty(); }
            };
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final EventBus bus = new EventBus(false);
    private FakeBot bot;
    private FakeDriver driver;
    private RunLog log;
    /** What the run's service says between a request and the bot's start. */
    private volatile boolean compiling;
    private McpEndpoint endpoint;
    private String session;
    private int ids = 10;

    @BeforeEach
    void start() throws Exception {
        bot = new FakeBot();
        driver = new FakeDriver();
        log = new RunLog(bus, Clock.systemUTC(), () -> compiling);
        endpoint = McpEndpoint.start(0, TOKEN, bot, driver, log);
    }

    @AfterEach
    void stop() {
        endpoint.close();
        log.close();
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

    private JsonNode rpc(String method, String params) throws Exception {
        HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"id\":" + (ids++) + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}", TOKEN);
        assertEquals(200, response.statusCode(), response.body());
        String body = response.body();
        Matcher data = Pattern.compile("(?m)^data:\\s*(\\{.*})\\s*$").matcher(body);
        return json.readTree(data.find() ? data.group(1) : body);
    }

    private JsonNode call(String tool, String arguments) throws Exception {
        return rpc("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}");
    }

    private void initialize() throws Exception {
        JsonNode init = rpc("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}");
        assertEquals("botmaker-studio", init.path("result").path("serverInfo").path("name").asText(), init.toString());
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", TOKEN);
    }

    private static String text(JsonNode callResult) {
        return callResult.path("result").path("content").path(0).path("text").asText();
    }

    private static boolean isError(JsonNode callResult) {
        return callResult.path("result").path("isError").asBoolean();
    }

    /** {@code method}'s body id in {@code file}, from read_tree. */
    private String bodyOf(String file, String method) throws Exception {
        String tree = text(call("read_tree", "{\"file\":\"" + file + "\",\"method\":\"" + method + "\"}"));
        Matcher body = Pattern.compile("\"name\":\"" + method + "\".*?\"body\":\\{\"id\":\"([^\"]+)\"").matcher(tree);
        assertTrue(body.find(), tree);
        return body.group(1);
    }

    @Test
    void aRequestWithoutTheTokenIsRefused() throws Exception {
        assertEquals(401, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", null).statusCode());
        assertEquals(401, post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "wrong").statusCode());
    }

    @Test
    void theToolsAreListedWithThePluginsOwn() throws Exception {
        initialize();
        JsonNode tools = rpc("tools/list", "{}").path("result").path("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertEquals(List.of("list_files", "list_methods", "list_palette", "read_tree", "list_errors", "insert_block",
                "set_slot", "delete_block", "apply_edits", "add_method", "move_block", "edit_signature",
                "list_targets", "set_target", "move_caret", "show_area", "add_file", "find_usages", "rename", "open",
                "list_review", "mark_reviewed", "remove_mark", "undo_change", "run_bot", "run_activity", "try_statement", "stop", "run_state", "read_trace", "fake_snap"), names);
        JsonNode snap = tools.path(names.indexOf("fake_snap"));
        assertEquals("integer", snap.path("inputSchema").path("properties").path("size").path("type").asText(),
                snap.toString());
    }

    @Test
    void anAcceptedEditIsCommittedOnItsOwn() throws Exception {
        initialize();
        JsonNode insert = call("insert_block", "{\"bodyId\":\"" + bodyOf("", "run")
                + "\",\"index\":1,\"paletteId\":\"block:PRINT\"}");
        assertFalse(isError(insert), insert.toString());
        assertTrue(text(insert).contains("Applied."), text(insert));
        assertEquals(List.of("Subject.java"), bot.commits);
        assertTrue(bot.files.get(SUBJECT).contains("System.out.println"), bot.files.get(SUBJECT));
    }

    @Test
    void aFileThatIsNotOpenIsEditedByName() throws Exception {
        initialize();
        assertTrue(text(call("list_files", "{}")).contains("com/mybot/Other.java"));
        JsonNode insert = call("insert_block", "{\"file\":\"Other\",\"bodyId\":\"" + bodyOf("Other", "go")
                + "\",\"index\":0,\"paletteId\":\"block:PRINT\"}");
        assertFalse(isError(insert), insert.toString());
        assertEquals(List.of("Other.java"), bot.commits);
        assertEquals(SOURCE, bot.files.get(SUBJECT), "the open file is untouched");

        assertTrue(text(call("list_methods", "{\"file\":\"Other\"}")).startsWith("Other.go"));
        JsonNode byOwner = call("read_tree", "{\"file\":\"Other\",\"method\":\"Other.go\"}");
        assertFalse(isError(byOwner), "list_methods' spelling reads: " + byOwner);

        JsonNode unknown = call("read_tree", "{\"file\":\"Nowhere\"}");
        assertTrue(isError(unknown) && text(unknown).contains("list_files"), unknown.toString());
    }

    @Test
    void aRefusedEditCommitsNothingAndSaysWhy() throws Exception {
        initialize();
        JsonNode insert = call("insert_block", "{\"bodyId\":\"nowhere\",\"index\":0,\"paletteId\":\"block:PRINT\"}");
        assertTrue(isError(insert), insert.toString());
        assertTrue(text(insert).startsWith("REFUSED"), text(insert));
        assertTrue(bot.commits.isEmpty());
        assertEquals(SOURCE, bot.files.get(SUBJECT));
    }

    @Test
    void severalEditsLandAsOneStepOrNotAtAll() throws Exception {
        initialize();
        String body = bodyOf("", "run");
        JsonNode both = call("apply_edits", "{\"edits\":["
                + "{\"op\":\"insert\",\"bodyId\":\"" + body + "\",\"index\":1,\"paletteId\":\"block:PRINT\"},"
                + "{\"op\":\"insert\",\"bodyId\":\"" + body + "\",\"index\":1,\"paletteId\":\"block:PRINT\"}]}");
        assertFalse(isError(both), both.toString());
        assertEquals(1, bot.commits.size(), "one undo step");
        String after = bot.files.get(SUBJECT);
        assertEquals(2, after.split("System.out.println", -1).length - 1, after);

        JsonNode half = call("apply_edits", "{\"edits\":["
                + "{\"op\":\"insert\",\"bodyId\":\"" + body + "\",\"index\":0,\"paletteId\":\"block:PRINT\"},"
                + "{\"op\":\"delete\",\"blockId\":\"nowhere\"}]}");
        assertTrue(isError(half), half.toString());
        assertTrue(text(half).contains("edit 2 of 2"), text(half));
        assertEquals(1, bot.commits.size(), "nothing of a refused batch is kept");
        assertEquals(after, bot.files.get(SUBJECT));
    }

    @Test
    void aSignatureChangeThatBreaksAnotherFileIsNotKept() throws Exception {
        initialize();
        JsonNode added = call("add_method", "{\"name\":\"rest\",\"returns\":\"void\","
                + "\"params\":[{\"name\":\"seconds\",\"type\":\"int\"}]}");
        assertFalse(isError(added), added.toString());
        assertTrue(bot.files.get(SUBJECT).contains("rest(int seconds)"), bot.files.get(SUBJECT));

        bot.elsewhere = "That change would stop the bot compiling (Other.java:3 …)";
        JsonNode changed = call("edit_signature", "{\"method\":\"rest\",\"name\":\"pause\"}");
        assertTrue(isError(changed) && text(changed).contains("Other.java:3"), changed.toString());
        assertEquals(1, bot.commits.size(), "only the add was kept");

        bot.elsewhere = null;
        assertFalse(isError(call("edit_signature", "{\"method\":\"rest\",\"name\":\"pause\"}")));
        assertTrue(bot.files.get(SUBJECT).contains("pause(int seconds)"), bot.files.get(SUBJECT));
    }

    @Test
    void navigationGoesToStudioAndWhatItLacksIsRefused() throws Exception {
        initialize();
        assertEquals("renamed", text(call("rename", "{\"symbol\":\"Other.go\",\"to\":\"start\"}")));
        assertEquals(List.of("rename Other.go start"), driver.asked);
        JsonNode review = call("list_review", "{}");
        assertTrue(isError(review) && text(review).startsWith("REFUSED"), review.toString());
    }

    @Test
    void aPluginToolAnswersWithItsImage() throws Exception {
        initialize();
        JsonNode snap = call("fake_snap", "{\"size\":4}");
        assertFalse(isError(snap), snap.toString());
        JsonNode content = snap.path("result").path("content");
        assertEquals("a 4px square", content.path(0).path("text").asText());
        assertEquals("image", content.path(1).path("type").asText(), content.toString());
        assertEquals("image/png", content.path(1).path("mimeType").asText());

        JsonNode wrong = call("fake_snap", "{\"size\":\"big\"}");
        assertTrue(isError(wrong), wrong.toString());
    }

    @Test
    void nothingStartsOnTheGameWhileSomethingRuns() throws Exception {
        initialize();
        assertFalse(isError(call("try_statement", "{\"blockId\":\"b1\",\"values\":{\"n\":\"3\"}}")));
        assertEquals(List.of("try b1 {n=3}"), driver.asked);

        // Asked for and still compiling: the bot has not started, and a second one must not.
        compiling = true;
        JsonNode during = call("run_bot", "{}");
        assertTrue(isError(during) && text(during).contains("compiling"), during.toString());
        compiling = false;

        bus.publish(new CoreApplicationEvents.ProgramStartedEvent());
        JsonNode refused = call("try_statement", "{\"blockId\":\"b1\"}");
        assertTrue(isError(refused) && text(refused).contains("Stop it first"), refused.toString());
        assertTrue(isError(call("run_activity", "{\"target\":\"Other.go\"}")));
        assertTrue(isError(call("run_bot", "{}")));
        assertEquals(1, driver.asked.size(), "the driver was not asked again");
        assertTrue(text(call("run_state", "{}")).startsWith("Running the bot"));

        bus.publish(new CoreApplicationEvents.OutputAppendedEvent("hello\nwor"));
        bus.publish(new CoreApplicationEvents.ProgramStoppedEvent());
        String trace = text(call("read_trace", "{\"lines\":5}"));
        assertTrue(trace.contains("hello\nwor\n── stopped the bot"), trace);
        assertTrue(text(call("run_state", "{}")).startsWith("Nothing runs"));
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
