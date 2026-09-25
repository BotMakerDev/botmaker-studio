package com.botmaker.studio.assist;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * The assistant's tools as MCP serves them: each call goes through {@link AssistTools} over a fresh
 * {@link AssistTurn}. An MCP client has no "end of reply" Studio can see, so <b>every edit is its own
 * turn</b>, committed when it is accepted, and therefore its own undo step.
 */
public final class McpTools {

    private McpTools() {}

    /** What the server tells every client at connect: how to work through these tools. */
    public static final String INSTRUCTIONS = """
            You edit a bot in BotMaker Studio, a block editor over Java. You work only through the tools.

            - Read the file with read_tree before editing, and again after edits: ids below an insertion shift.
            - Insert only what list_palette offers, by its id. You cannot write Java directly.
            - A slot takes a value of its type: a literal, a constant, or a factory call of that type.
            - Every edit is compiled. A REFUSED answer says why; fix the cause and try again, or explain.
            - Each accepted edit lands in the user's file at once, as one step they can undo.
            - Keep replies short: say what you changed, or what you could not do and why.
            """;

    private static final String NO_FILE = "No file is open in BotMaker Studio. Open one and try again.";

    public static List<SyncToolSpecification> all(McpJsonMapper json, LiveFile file) {
        return List.of(
                tool(json, "list_palette",
                        "List what can be inserted into the open bot file: statement blocks and plugin calls. Each "
                                + "entry's id is what insert_block takes. Filter by a word, or pass an empty string.",
                        schema(Map.of("filter", "a word to filter by label or category; empty for everything"), List.of()),
                        file, (tools, args) -> tools.listPalette(text(args, "filter")), false),
                tool(json, "read_tree",
                        "Read the file open in Studio: its methods, each body's id, the statements in each body in "
                                + "order and each statement's value slots with the type they expect. Read it again "
                                + "after an edit: ids below an insertion shift.",
                        schema(Map.of(), List.of()), file, (tools, args) -> tools.readTree(), false),
                tool(json, "list_errors", "List the compile errors the open file has now.",
                        schema(Map.of(), List.of()), file, (tools, args) -> tools.listErrors(), false),
                tool(json, "insert_block",
                        "Insert a palette entry as a statement in a body, before the statement now at index. The "
                                + "edit is compiled first and refused if it adds an error; accepted edits are applied "
                                + "to the file at once, as one undo step.",
                        schema(Map.of("bodyId", "the body id, from read_tree",
                                        "index", "where to insert, 0 for first",
                                        "paletteId", "the palette id, from list_palette"),
                                List.of("bodyId", "index", "paletteId")),
                        file, (tools, args) -> tools.insertBlock(text(args, "bodyId"), number(args, "index"),
                                text(args, "paletteId")), true),
                tool(json, "set_slot",
                        "Write a value into a slot: a literal, an enum or static constant of the slot's type, or a "
                                + "factory call that type declares. Anything else is refused.",
                        schema(Map.of("slotId", "the slot id, from read_tree", "value", "the value"),
                                List.of("slotId", "value")),
                        file, (tools, args) -> tools.setSlot(text(args, "slotId"), text(args, "value")), true),
                tool(json, "delete_block", "Delete a statement. Refused if the file would stop compiling.",
                        schema(Map.of("blockId", "the statement id, from read_tree"), List.of("blockId")),
                        file, (tools, args) -> tools.deleteBlock(text(args, "blockId")), true));
    }

    private static SyncToolSpecification tool(McpJsonMapper json, String name, String description, String schema,
                                              LiveFile file,
                                              BiFunction<AssistTools, Map<String, Object>, String> call,
                                              boolean edits) {
        Tool tool = Tool.builder().name(name).description(description).inputSchema(json, schema).build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> run(file, request, call, edits))
                .build();
    }

    static CallToolResult run(LiveFile file, CallToolRequest request,
                              BiFunction<AssistTools, Map<String, Object>, String> call, boolean edits) {
        Optional<AssistTurn> turn;
        try {
            turn = file.begin();
        } catch (RuntimeException e) {
            return result("Studio could not read the open file: " + e.getMessage(), true);
        }
        if (turn.isEmpty()) return result(NO_FILE, true);
        AssistTools tools = new AssistTools();
        tools.begin(turn.get());
        String answer;
        try {
            answer = call.apply(tools, request.arguments() == null ? Map.of() : request.arguments());
        } catch (IllegalArgumentException e) {
            return result("REFUSED: " + e.getMessage(), true);
        }
        if (!edits) return result(answer, false);
        if (turn.get().acceptedEdits() == 0) return result(answer, true);
        return result(answer + "\n" + file.commit(turn.get(), "the assistant's change (MCP)"), false);
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

    /** A JSON schema of string properties, except {@code index}, which is an integer. */
    private static String schema(Map<String, String> properties, List<String> required) {
        StringBuilder out = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        boolean first = true;
        for (Map.Entry<String, String> property : new java.util.TreeMap<>(properties).entrySet()) {
            if (!first) out.append(',');
            first = false;
            String type = property.getKey().equals("index") ? "integer" : "string";
            out.append('"').append(property.getKey()).append("\":{\"type\":\"").append(type)
                    .append("\",\"description\":\"").append(property.getValue().replace("\"", "\\\"")).append("\"}");
        }
        out.append("},\"required\":[");
        for (int i = 0; i < required.size(); i++) {
            if (i > 0) out.append(',');
            out.append('"').append(required.get(i)).append('"');
        }
        return out.append("],\"additionalProperties\":false}").toString();
    }
}
