package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AgentReply;
import com.botmaker.plugin.api.assist.ToolParam;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The assistant's tools as MCP serves them, in four groups: the bot's files ({@link AssistTools} over a fresh
 * {@link AssistTurn} per call), Studio ({@link StudioDriver}: targets, the overlay's caret, boxes on the game),
 * runs (the bot, one activity, one statement, and what they printed — {@link RunLog}), and every bound plugin's
 * own tools ({@code <plugin>_<name>}).
 *
 * <p>An MCP client has no "end of reply" Studio can see, so <b>every edit is its own turn</b>, committed when it
 * is accepted, and therefore its own undo step.
 */
public final class McpTools {

    private McpTools() {}

    /** What the server tells every client at connect: how to work through these tools. */
    public static final String INSTRUCTIONS = """
            You build a bot in BotMaker Studio, a block editor over Java. You work only through the tools.

            - list_files and list_targets say where things are. A target is where an activity's blocks go;
              set_target opens it in Studio. Every file tool takes `file`; leave it empty for the open file.
            - Read a method with read_tree before editing, and again after edits: ids below an insertion shift.
            - Insert only what list_palette offers, by its id. You cannot write Java directly.
            - A slot takes a value of its type: a literal, one of the bot's constants (Pictures.ORE), or a
              constant or factory call of that type.
            - Every edit is compiled. A REFUSED answer says why; fix the cause and try again, or explain.
            - Each accepted edit lands in the user's file at once, as one step they can undo.
            - You act on the game only by running the bot's code: try_statement, run_activity, run_bot. Then
              read_trace says what happened, and run_state whether it still runs. stop ends it.
            - Keep replies short: say what you changed, or what you could not do and why.
            """;

    private static final String NO_PROJECT = "No project is open in BotMaker Studio. Open one and try again.";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One property of a tool's input. */
    private record Prop(String name, String type, String description, boolean required) {
        static Prop text(String name, String description) {
            return new Prop(name, "string", description, true);
        }

        static Prop optionalText(String name, String description) {
            return new Prop(name, "string", description, false);
        }

        static Prop whole(String name, String description) {
            return new Prop(name, "integer", description, true);
        }
    }

    private static final Prop FILE = Prop.optionalText("file",
            "the file, as list_files names it (its path, file name or class name); empty for the file open in Studio");

    public static List<SyncToolSpecification> all(McpJsonMapper json, LiveBot bot, StudioDriver driver, RunLog log) {
        List<SyncToolSpecification> tools = new ArrayList<>(fileTools(json, bot));
        tools.addAll(studioTools(json, driver));
        tools.addAll(runTools(json, driver, log));
        java.util.Set<String> taken = new java.util.HashSet<>();
        tools.forEach(t -> taken.add(t.tool().name()));
        for (SyncToolSpecification tool : pluginTools(json, driver)) {
            // A plugin's name that is Studio's own or another plugin's would shadow it: the first one keeps it.
            if (taken.add(tool.tool().name())) tools.add(tool);
            else System.err.println("Warning: a plugin's assistant tool " + tool.tool().name() + " is taken; skipped.");
        }
        return tools;
    }

    // ---- the bot's files -----------------------------------------------------------------------------------

    private static List<SyncToolSpecification> fileTools(McpJsonMapper json, LiveBot bot) {
        return List.of(
                act(json, "list_files", "List the bot's source files, by path below its source root.",
                        List.of(), args -> {
                            List<String> files = bot.files();
                            return files.isEmpty() ? NO_PROJECT : String.join("\n", files);
                        }),
                edit(json, "list_methods", "List a file's methods that have a body, with each body's id.",
                        List.of(FILE), bot, (tools, args) -> tools.listMethods(), false),
                edit(json, "list_palette",
                        "List what can be inserted into a file: statement blocks and plugin calls. Each entry's id "
                                + "is what insert_block takes. Filter by a word, or pass an empty string.",
                        List.of(Prop.optionalText("filter", "a word to filter by label or category; empty for everything"),
                                FILE),
                        bot, (tools, args) -> tools.listPalette(text(args, "filter")), false),
                edit(json, "read_tree",
                        "Read a file's methods: each body's id, the statements in each body in order and each "
                                + "statement's value slots with the type they expect. Give `method` to read one. "
                                + "Read it again after an edit: ids below an insertion shift.",
                        List.of(FILE, Prop.optionalText("method", "one method's name; empty for every method")),
                        bot, (tools, args) -> tools.readTree(text(args, "method")), false),
                edit(json, "list_errors", "List the compile errors a file has now.",
                        List.of(FILE), bot, (tools, args) -> tools.listErrors(), false),
                edit(json, "insert_block",
                        "Insert a palette entry as a statement in a body, before the statement now at index. The "
                                + "edit is compiled first and refused if it adds an error; accepted edits are applied "
                                + "to the file at once, as one undo step, and the file is opened in Studio.",
                        List.of(FILE, Prop.text("bodyId", "the body id, from read_tree"),
                                Prop.whole("index", "where to insert, 0 for first"),
                                Prop.text("paletteId", "the palette id, from list_palette")),
                        bot, (tools, args) -> tools.insertBlock(text(args, "bodyId"), number(args, "index"),
                                text(args, "paletteId")), true),
                edit(json, "set_slot",
                        "Write a value into a slot: a literal, one of the bot's constants by name (Pictures.ORE), "
                                + "an enum or static constant of the slot's type, or a factory call that type "
                                + "declares. Anything else is refused.",
                        List.of(FILE, Prop.text("slotId", "the slot id, from read_tree"), Prop.text("value", "the value")),
                        bot, (tools, args) -> tools.setSlot(text(args, "slotId"), text(args, "value")), true),
                edit(json, "delete_block", "Delete a statement. Refused if the file would stop compiling.",
                        List.of(FILE, Prop.text("blockId", "the statement id, from read_tree")),
                        bot, (tools, args) -> tools.deleteBlock(text(args, "blockId")), true),
                spec(json, "apply_edits",
                        "Apply several edits to one file in one call, in order, as one undo step: each is an insert, "
                                + "a set or a delete, taking the same fields as insert_block, set_slot and "
                                + "delete_block. Each is compiled as it is applied; if any is refused, none is kept "
                                + "and the answer says which. Ids are those of the tree as the earlier edits left "
                                + "it: an insert shifts the statements below it, so insert from the bottom up, or "
                                + "count the shift.",
                        EDITS_SCHEMA, request -> run(bot, request, McpTools::applyEdits, true)));
    }

    private static final String EDITS_SCHEMA = """
            {"type":"object","properties":{
              "file":{"type":"string","description":"the file, as list_files names it; empty for the file open in Studio"},
              "edits":{"type":"array","minItems":1,"items":{"type":"object","properties":{
                "op":{"type":"string","enum":["insert","set","delete"]},
                "bodyId":{"type":"string"},"index":{"type":"integer"},"paletteId":{"type":"string"},
                "slotId":{"type":"string"},"value":{"type":"string"},"blockId":{"type":"string"}},
                "required":["op"],"additionalProperties":false}}},
             "required":["edits"],"additionalProperties":false}""";

    /**
     * {@code edits} applied in order on one turn; the first refusal ends it and the turn is not committed, since a
     * turn whose edits are not all kept would land half a change.
     */
    private static String applyEdits(AssistTools tools, Map<String, Object> args) {
        if (!(args.get("edits") instanceof List<?> edits) || edits.isEmpty()) {
            throw new IllegalArgumentException("edits is a list of at least one edit.");
        }
        List<String> done = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            if (!(edits.get(i) instanceof Map<?, ?> raw)) throw new IllegalArgumentException("edit " + (i + 1) + " is not an object.");
            Map<String, Object> edit = new LinkedHashMap<>();
            raw.forEach((k, v) -> edit.put(String.valueOf(k), v));
            String answer = switch (text(edit, "op")) {
                case "insert" -> tools.insertBlock(text(edit, "bodyId"), number(edit, "index"), text(edit, "paletteId"));
                case "set" -> tools.setSlot(text(edit, "slotId"), text(edit, "value"));
                case "delete" -> tools.deleteBlock(text(edit, "blockId"));
                default -> throw new IllegalArgumentException("edit " + (i + 1) + ": op is insert, set or delete, not "
                        + text(edit, "op") + ".");
            };
            if (answer.startsWith("REFUSED")) {
                throw new IllegalArgumentException("edit " + (i + 1) + " of " + edits.size() + " was refused, so none "
                        + "was kept" + (done.isEmpty() ? "" : " (" + String.join("; ", done) + ")") + ": "
                        + answer.substring("REFUSED: ".length()));
            }
            done.add(answer.replaceFirst("^OK: ", ""));
        }
        return "OK: " + String.join("; ", done);
    }

    private static SyncToolSpecification edit(McpJsonMapper json, String name, String description, List<Prop> props,
                                              LiveBot bot, BiFunction<AssistTools, Map<String, Object>, String> call,
                                              boolean edits) {
        return spec(json, name, description, schema(props), request -> run(bot, request, call, edits));
    }

    static CallToolResult run(LiveBot bot, CallToolRequest request,
                              BiFunction<AssistTools, Map<String, Object>, String> call, boolean edits) {
        Map<String, Object> args = arguments(request);
        Optional<AssistTurn> turn;
        try {
            turn = bot.begin(text(args, "file"));
        } catch (IllegalArgumentException e) {
            return result("REFUSED: " + e.getMessage(), true);
        } catch (RuntimeException e) {
            return result("Studio could not read the file: " + e.getMessage(), true);
        }
        if (turn.isEmpty()) return result(NO_PROJECT, true);
        AssistTools tools = new AssistTools();
        tools.begin(turn.get());
        String answer;
        try {
            answer = call.apply(tools, args);
        } catch (IllegalArgumentException e) {
            return result("REFUSED: " + e.getMessage(), true);
        }
        if (!edits) return result(answer, false);
        if (turn.get().acceptedEdits() == 0) return result(answer, true);
        return result(answer + "\n" + bot.commit(turn.get(), "the assistant's change (MCP)"), false);
    }

    // ---- Studio --------------------------------------------------------------------------------------------

    private static List<SyncToolSpecification> studioTools(McpJsonMapper json, StudioDriver driver) {
        return List.of(
                act(json, "list_targets",
                        "List where an activity's blocks go: the methods the bot's plugins call targets, with the file "
                                + "each is in. set_target and run_activity take the key.",
                        List.of(), args -> {
                            List<StudioDriver.Target> targets = driver.targets();
                            if (targets.isEmpty()) return "No plugin finds a target in this bot.";
                            List<String> lines = new ArrayList<>();
                            for (StudioDriver.Target t : targets) {
                                lines.add(t.key() + "  " + t.group() + " ▸ " + t.label() + "." + t.method() + "()  in "
                                        + t.file());
                            }
                            return String.join("\n", lines);
                        }),
                act(json, "set_target",
                        "Open a target in Studio — its file in the editor and, when the overlay editor is open, there "
                                + "too — so the user sees where you are working.",
                        List.of(Prop.text("target", "the target's key, or Label.method, from list_targets")),
                        args -> driver.setTarget(text(args, "target"))),
                act(json, "move_caret",
                        "Park the overlay editor's caret on a statement, so the user sees it and its probe is shown "
                                + "on the game. Needs the overlay editor open.",
                        List.of(FILE, Prop.text("blockId", "the statement id, from read_tree")),
                        args -> driver.moveCaret(text(args, "file"), text(args, "blockId"))),
                act(json, "show_area",
                        "Box an area on the game, in the bot's pixels (what screenshots and clicks use), with a "
                                + "label — to show the user what you mean. Needs the overlay editor open.",
                        List.of(Prop.whole("x", "left edge"), Prop.whole("y", "top edge"),
                                Prop.whole("width", "width"), Prop.whole("height", "height"),
                                Prop.optionalText("label", "what the box shows")),
                        args -> driver.showArea(new Area(number(args, "x"), number(args, "y"),
                                number(args, "width"), number(args, "height")), text(args, "label"))));
    }

    // ---- runs ----------------------------------------------------------------------------------------------

    private static List<SyncToolSpecification> runTools(McpJsonMapper json, StudioDriver driver, RunLog log) {
        return List.of(
                act(json, "run_bot", "Run the whole bot, as the toolbar's ▶ Run does. It acts on the game.",
                        List.of(), args -> idle(log, driver::runBot)),
                act(json, "run_activity",
                        "Run one target's method on its own, through the bot's plugin, without the rest of the bot. "
                                + "It acts on the game.",
                        List.of(Prop.text("target", "the target's key, or Label.method, from list_targets")),
                        args -> idle(log, () -> driver.runActivity(text(args, "target")))),
                act(json, "try_statement",
                        "Run one statement on its own, with the earlier values it reads — taken from the last run, "
                                + "computed when that only looks at the screen, or given in `values`. It acts on the "
                                + "game. A value it needs and cannot find is refused by name.",
                        List.of(FILE, Prop.text("blockId", "the statement id, from read_tree"),
                                new Prop("values", "object", "earlier locals the statement reads, by name, each a "
                                        + "value of its type as set_slot takes it", false)),
                        args -> idle(log, () -> driver.tryStatement(text(args, "file"), text(args, "blockId"),
                                values(args)))),
                act(json, "stop", "Stop the run, the try or the debug session.", List.of(), args -> driver.stop()),
                act(json, "run_state", "Say whether something runs now, and for how long.", List.of(),
                        args -> log.state()),
                act(json, "read_trace",
                        "Read the last lines the bot printed and traced, oldest first, with Studio's markers where a "
                                + "run started and stopped.",
                        List.of(new Prop("lines", "integer", "how many lines, 50 when not given", false)),
                        args -> log.tail(args.get("lines") == null ? 50 : number(args, "lines"))));
    }

    /** Runs {@code start} only while nothing is on the game, else refuses saying what is. */
    private static String idle(RunLog log, java.util.function.Supplier<String> start) {
        if (log.busy()) throw new IllegalArgumentException(log.state() + " Stop it first (stop), or wait for it.");
        return start.get();
    }

    // ---- the plugins' tools --------------------------------------------------------------------------------

    private static List<SyncToolSpecification> pluginTools(McpJsonMapper json, StudioDriver driver) {
        List<SyncToolSpecification> out = new ArrayList<>();
        for (StudioDriver.PluginTool owned : driver.pluginTools()) {
            String description = owned.tool().description() + " (from " + owned.pluginName() + ")";
            out.add(spec(json, owned.name(), description, schemaOf(owned.tool().params()), request -> {
                AgentReply reply;
                try {
                    reply = owned.tool().invoke(arguments(request), driver.agentContext());
                } catch (RuntimeException e) {
                    return result(owned.name() + " could not start: " + e.getMessage(), true);
                }
                return reply(reply);
            }));
        }
        return out;
    }

    /** A plugin's reply as MCP content: its text and its PNGs, in order. */
    static CallToolResult reply(AgentReply reply) {
        CallToolResult.Builder out = CallToolResult.builder().isError(reply.isRefused());
        for (AgentReply.Part part : reply.parts()) {
            switch (part.kind()) {
                case TEXT -> out.addTextContent(part.text());
                case IMAGE -> out.addContent(new McpSchema.ImageContent(null,
                        Base64.getEncoder().encodeToString(part.png()), "image/png"));
            }
        }
        return out.build();
    }

    /** A plugin tool's parameters as a JSON schema. */
    static String schemaOf(List<ToolParam> params) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolParam param : params) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", param.kind().jsonType());
            if (param.kind() == ToolParam.Kind.TEXT_LIST) property.put("items", Map.of("type", "string"));
            if (!param.choices().isEmpty()) property.put("enum", param.choices());
            if (!param.description().isEmpty()) property.put("description", param.description());
            properties.put(param.name(), property);
            if (!param.optional()) required.add(param.name());
        }
        return object(properties, required);
    }

    // ---- plumbing ------------------------------------------------------------------------------------------

    /** A tool that answers a sentence; an {@link IllegalArgumentException} is its refusal. */
    private static SyncToolSpecification act(McpJsonMapper json, String name, String description, List<Prop> props,
                                             Function<Map<String, Object>, String> call) {
        return spec(json, name, description, schema(props), request -> {
            try {
                return result(call.apply(arguments(request)), false);
            } catch (IllegalArgumentException e) {
                return result("REFUSED: " + e.getMessage(), true);
            } catch (RuntimeException e) {
                return result("Studio could not do that: " + e.getMessage(), true);
            }
        });
    }

    private static SyncToolSpecification spec(McpJsonMapper json, String name, String description, String schema,
                                              Function<CallToolRequest, CallToolResult> handler) {
        Tool tool = Tool.builder().name(name).description(description).inputSchema(json, schema).build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> handler.apply(request))
                .build();
    }

    private static Map<String, Object> arguments(CallToolRequest request) {
        return request.arguments() == null ? Map.of() : request.arguments();
    }

    private static CallToolResult result(String text, boolean error) {
        return CallToolResult.builder().addTextContent(text).isError(error).build();
    }

    private static String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : value.toString();
    }

    private static int number(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, not " + value);
        }
    }

    /** {@code values}: names to value text. */
    private static Map<String, String> values(Map<String, Object> args) {
        Object given = args.get("values");
        if (given == null) return Map.of();
        if (!(given instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("values is an object of names to values, not " + given);
        }
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        return out;
    }

    private static String schema(List<Prop> props) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Prop prop : props) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", prop.type());
            if (prop.type().equals("object")) property.put("additionalProperties", Map.of("type", "string"));
            property.put("description", prop.description());
            properties.put(prop.name(), property);
            if (prop.required()) required.add(prop.name());
        }
        return object(properties, required);
    }

    private static String object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        try {
            return JSON.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
