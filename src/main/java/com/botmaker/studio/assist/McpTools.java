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
 * The assistant's tools as MCP serves them, in five groups: the bot's files ({@link AssistTools} over a fresh
 * {@link AssistTurn} per call), Studio ({@link StudioDriver}: targets, the overlay's caret, boxes on the game),
 * runs (the bot, one activity, one statement, and what they printed — {@link RunLog}), the project (versions,
 * plugins, parameters, settings), and every bound plugin's own tools ({@code <plugin>_<name>}).
 *
 * <p>An MCP client has no "end of reply" Studio can see, so <b>every edit is its own turn</b>, committed when it
 * is accepted, and therefore its own undo step.
 */
public final class McpTools {

    private McpTools() {}

    /** What the server tells every client at connect: how to work through these tools. */
    public static final String INSTRUCTIONS = """
            You build a bot in BotMaker Studio, a block editor over Java. You work only through the tools.

            Work in this loop, one small step at a time:
            1. Look. list_targets says where each activity's blocks go and set_target opens one; list_files
               and list_methods say where the rest is. A plugin's own tools (named <plugin>_<tool>) see the
               game: a screenshot, its pictures, what matches where.
            2. Pictures. Make the pictures and places the bot looks for with the plugin's tools; each becomes
               one of the bot's constants (Pictures.ORE).
            3. Blocks. read_tree a method, then insert what list_palette offers, by its id, and set_slot each
               value. apply_edits makes several edits in one call, as one step. add_method, edit_signature,
               move_block, rename and find_usages shape the code.
            4. Try. try_statement runs one statement, run_activity one activity, run_bot the whole bot. They
               act on the game: it is the only way you do.
            5. Read. read_trace says what the run printed and traced; run_state whether it still runs; stop
               ends it.
            6. Fix what the trace shows, and go round again.

            - Every file tool takes `file`; leave it empty for the open file. Read the tree again after an
              edit: ids below an insertion shift.
            - You cannot write Java. A slot takes a value of its type: a literal, one of the bot's constants,
              or a constant or factory call of that type.
            - Every edit is compiled. A REFUSED answer says why; fix the cause and try again, or explain.
            - Each accepted edit lands in the user's file at once, as one step they can undo. checkpoint
              before a large change saves a version to go back to with revert.
            - list_params and set_param_default are the values the bot's user sets; list_plugins,
              search_plugins, add_plugin and remove_plugin what it is built from.
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

    /** Studio's own tools and the plugins' as they are now. */
    public static List<SyncToolSpecification> all(McpJsonMapper json, LiveBot bot, StudioDriver driver, RunLog log) {
        List<SyncToolSpecification> tools = new ArrayList<>(studio(json, bot, driver, log, () -> { }));
        tools.addAll(pluginTools(json, driver, names(tools)));
        return tools;
    }

    /**
     * Studio's own tools, without the plugins'.
     *
     * @param pluginsChanged run after a tool added or removed a plugin, so the plugins' tools are served anew
     */
    static List<SyncToolSpecification> studio(McpJsonMapper json, LiveBot bot, StudioDriver driver, RunLog log,
                                              Runnable pluginsChanged) {
        List<SyncToolSpecification> tools = new ArrayList<>(fileTools(json, bot));
        tools.addAll(studioTools(json, driver));
        tools.addAll(runTools(json, driver, log));
        tools.addAll(projectTools(json, driver, log, pluginsChanged));
        return tools.stream().map(t -> guarded(driver, t)).toList();
    }

    /** {@code tool}, refused while the project is about to reload ({@link StudioDriver#reloading}). */
    private static SyncToolSpecification guarded(StudioDriver driver, SyncToolSpecification tool) {
        return SyncToolSpecification.builder().tool(tool.tool()).callHandler((exchange, request) -> driver.reloading()
                ? result("REFUSED: Studio is reloading the project after a revert. Call again once it has "
                + "reopened.", true)
                : tool.callHandler().apply(exchange, request)).build();
    }

    /**
     * The bound plugins' tools, but one whose name is in {@code taken}: a name that is Studio's own or an earlier
     * plugin's would shadow it, so the first one keeps it.
     */
    static List<SyncToolSpecification> pluginTools(McpJsonMapper json, StudioDriver driver, java.util.Set<String> taken) {
        java.util.Set<String> names = new java.util.HashSet<>(taken);
        List<SyncToolSpecification> out = new ArrayList<>();
        for (SyncToolSpecification tool : pluginTools(json, driver)) {
            if (names.add(tool.tool().name())) out.add(guarded(driver, tool));
            else System.err.println("Warning: a plugin's assistant tool " + tool.tool().name() + " is taken; skipped.");
        }
        return out;
    }

    static java.util.Set<String> names(List<SyncToolSpecification> tools) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        tools.forEach(t -> names.add(t.tool().name()));
        return names;
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
                        EDITS_SCHEMA, request -> run(bot, request, McpTools::applyEdits, true)),
                spec(json, "add_method",
                        "Add a method to a class of a file, after its other members, as the Add Function dialog does. "
                                + "Types are those Studio offers: int, double, boolean, String, a plugin's type "
                                + "(Point, Picture…), List<…> of one, or void to return nothing.",
                        METHOD_SCHEMA.formatted("""
                                "class":{"type":"string","description":"the class; empty for the file's first"},
                                "name":{"type":"string","description":"the method's name"},""", "[\"name\"]"),
                        request -> run(bot, request, (tools, args) -> tools.addMethod(text(args, "class"),
                                text(args, "name"), text(args, "returns"), list(args, "params")), true)),
                edit(json, "move_block",
                        "Move a statement into a body of the same file, before the statement now at index.",
                        List.of(FILE, Prop.text("blockId", "the statement id, from read_tree"),
                                Prop.text("bodyId", "the body it goes into, from read_tree"),
                                Prop.whole("index", "where in that body, 0 for first")),
                        bot, (tools, args) -> tools.moveBlock(text(args, "blockId"), text(args, "bodyId"),
                                number(args, "index")), true),
                spec(json, "edit_signature",
                        "Change a method's name, return type or parameters; what is not given is kept. Its calls in "
                                + "the file follow, a parameter keeping its place by name and a new one getting a "
                                + "default. Refused when a call in another file would stop compiling — use rename "
                                + "to rename a method used elsewhere.",
                        METHOD_SCHEMA.formatted("""
                                "method":{"type":"string","description":"the method, name or Owner.name"},
                                "name":{"type":"string","description":"its new name; empty to keep it"},""",
                                "[\"method\"]"),
                        request -> runChecked(bot, request, (tools, args) -> tools.editSignature(text(args, "method"),
                                text(args, "name"), args.containsKey("returns") ? text(args, "returns") : null,
                                args.containsKey("params") ? list(args, "params") : null))));
    }

    /** A method's input: {@code %1$s} its naming properties, {@code %2$s} the required list. */
    private static final String METHOD_SCHEMA = """
            {"type":"object","properties":{
              "file":{"type":"string","description":"the file, as list_files names it; empty for the file open in Studio"},
              %1$s
              "returns":{"type":"string","description":"the type it gives back, void for nothing"},
              "params":{"type":"array","description":"its parameters, in order","items":{"type":"object",
                "properties":{"name":{"type":"string"},"type":{"type":"string"}},
                "required":["name","type"],"additionalProperties":false}}},
             "required":%2$s,"additionalProperties":false}""";

    /**
     * {@link #run} for an edit that can break another file: once accepted, the whole bot is compiled with it, and
     * nothing is committed when another file would stop compiling.
     */
    private static CallToolResult runChecked(LiveBot bot, CallToolRequest request,
                                             BiFunction<AssistTools, Map<String, Object>, String> call) {
        return run(bot, request, (tools, args) -> {
            String answer = call.apply(tools, args);
            if (!answer.startsWith("OK")) return answer;
            Optional<String> broken;
            try {
                broken = bot.breaksElsewhere(tools.turn());
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (RuntimeException e) {
                // Kept nothing: an edit whose effect on the rest of the bot is unknown is not one to land.
                throw new IllegalArgumentException("Studio could not compile the rest of the bot to check that "
                        + "change (" + e.getMessage() + "), so nothing was changed.");
            }
            if (broken.isPresent()) throw new IllegalArgumentException(broken.get());
            return answer;
        }, true);
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
                                number(args, "width"), number(args, "height")), text(args, "label"))),
                act(json, "add_file",
                        "Add an empty class to the bot's main package, for methods to go in. It is saved as a "
                                + "version the user can go back to.",
                        List.of(Prop.text("name", "the class's name: Helpers")), args -> driver.addFile(text(args, "name"))),
                act(json, "find_usages", "List every use of a declaration of the bot, by binding: file, line, "
                                + "the method it is in, and the line's text.",
                        List.of(SYMBOL), args -> driver.findUsages(text(args, "symbol"))),
                act(json, "rename",
                        "Rename a declaration and every use of it across the bot, as one step the user can undo. "
                                + "Refused, with why, when the bot would stop compiling.",
                        List.of(SYMBOL, Prop.text("to", "the new name")),
                        args -> driver.rename(text(args, "symbol"), text(args, "to"))),
                act(json, "open", "Open a declaration in Studio's editor, so the user sees it.",
                        List.of(SYMBOL), args -> driver.open(text(args, "symbol"))),
                act(json, "list_review",
                        "List the functions a refactor changed by guessing and marked for the user to look at, each "
                                + "with its id and what the mark says.",
                        List.of(), args -> driver.listReview()),
                act(json, "mark_reviewed", "Mark a review item looked at; the mark stays in the code as the record.",
                        List.of(REVIEW_ID), args -> driver.markReviewed(text(args, "id"))),
                act(json, "remove_mark", "Take a review item's mark out of the code.",
                        List.of(REVIEW_ID), args -> driver.removeMark(text(args, "id"))),
                act(json, "undo_change",
                        "Put a review item's function back as it was before the refactor that marked it, from the "
                                + "bot's versions. What comes back may not compile.",
                        List.of(REVIEW_ID), args -> driver.undoChange(text(args, "id"))));
    }

    private static final Prop SYMBOL = Prop.text("symbol",
            "the declaration: Type, Type.member (a method, field or constant) or Type.method.local");
    private static final Prop REVIEW_ID = Prop.text("id", "the item's id, from list_review");

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

    // ---- versions, plugins and the project -----------------------------------------------------------------

    private static List<SyncToolSpecification> projectTools(McpJsonMapper json, StudioDriver driver,
                                                            RunLog log, Runnable pluginsChanged) {
        return List.of(
                act(json, "list_versions",
                        "List the bot's versions, newest first: each one's id, when it was made, who made it and "
                                + "its name. revert takes the id.",
                        List.of(new Prop("limit", "integer", "how many, 20 when not given", false)),
                        args -> driver.listVersions(args.get("limit") == null ? 20 : number(args, "limit"))),
                act(json, "checkpoint",
                        "Save the bot as it is now as a named version — before a change you may want to undo as a "
                                + "whole. The user sees it in the Versions tab.",
                        List.of(Prop.text("label", "what the version is: Before the battle loop")),
                        args -> driver.checkpoint(text(args, "label"))),
                act(json, "revert",
                        "Put the whole bot back as a version had it. What it is now is saved as a version first, so "
                                + "nothing is lost. The project reloads, which ends an assistant session running in "
                                + "Studio's Assistant tab: say so to the user before calling it.",
                        List.of(Prop.text("version", "the version's id, from list_versions")),
                        args -> idle(log, () -> driver.revert(text(args, "version")))),
                act(json, "list_plugins",
                        "List the plugins the bot uses, each with its version and whether it loaded. A plugin brings "
                                + "the palette's calls and the types slots take, and may bring tools of its own.",
                        List.of(), args -> driver.listPlugins()),
                act(json, "search_plugins", "Search the plugin registry, by a word of a plugin's name, id, "
                                + "description or tags.",
                        List.of(Prop.optionalText("query", "the word; empty for every plugin")),
                        args -> driver.searchPlugins(text(args, "query"))),
                act(json, "add_plugin",
                        "Add a plugin from the registry to the bot, at the version the registry checked. The user "
                                + "is asked on screen first: a plugin runs with Studio's permissions. Its tools "
                                + "are served once it loads.",
                        List.of(Prop.text("id", "the plugin's id, from search_plugins")),
                        args -> changed(pluginsChanged, driver.addPlugin(text(args, "id")))),
                spec(json, "remove_plugin",
                        "Remove a plugin from the bot. Without confirm it only says what would change: the calls "
                                + "into it that become a default value or are deleted, and its files. With "
                                + "confirm a version is saved first, then the calls are repaired and the "
                                + "functions they were in are marked for review.",
                        """
                                {"type":"object","properties":{
                                  "id":{"type":"string","description":"the plugin, as list_plugins names it"},
                                  "deleteFiles":{"type":"boolean","description":"also delete the files it keeps in the bot (its pictures, its flow)"},
                                  "confirm":{"type":"boolean","description":"true to remove it; false or absent to only see what would change"}},
                                 "required":["id"],"additionalProperties":false}""",
                        request -> {
                            Map<String, Object> args = arguments(request);
                            boolean confirm = flag(args, "confirm");
                            return answer(() -> {
                                String done = driver.removePlugin(text(args, "id"), flag(args, "deleteFiles"), confirm);
                                return confirm ? changed(pluginsChanged, done) : done;
                            });
                        }),
                act(json, "list_params",
                        "List the bot's parameters — its @Param fields, the values its user can set — with each "
                                + "one's type and value.",
                        List.of(), args -> driver.listParams()),
                act(json, "set_param_default",
                        "Set a parameter's value in the code: what the bot runs with until its user changes it.",
                        List.of(Prop.text("param", "the parameter: Class.name, or its name alone when only one has it"),
                                Prop.text("value", "a value of its type, as set_slot takes it")),
                        args -> driver.setParamDefault(text(args, "param"), text(args, "value"))),
                act(json, "get_settings", "List the project settings you may change, each with its value and "
                                + "what it takes.",
                        List.of(), args -> driver.settings()),
                act(json, "set_setting", "Change a project setting, one get_settings lists.",
                        List.of(Prop.text("key", "the setting's key, from get_settings"),
                                Prop.text("value", "its new value")),
                        args -> driver.setSetting(text(args, "key"), text(args, "value"))));
    }

    /** {@code answer}, after telling the endpoint the plugins may have changed. */
    private static String changed(Runnable pluginsChanged, String answer) {
        pluginsChanged.run();
        return answer;
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
        return spec(json, name, description, schema(props), request -> answer(() -> call.apply(arguments(request))));
    }

    /** {@code call}'s sentence as a result; an {@link IllegalArgumentException} is its refusal. */
    private static CallToolResult answer(java.util.function.Supplier<String> call) {
        try {
            return result(call.get(), false);
        } catch (IllegalArgumentException e) {
            return result("REFUSED: " + e.getMessage(), true);
        } catch (RuntimeException e) {
            return result("Studio could not do that: " + e.getMessage(), true);
        }
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

    /** {@code key} as a yes or no; no when not given. */
    private static boolean flag(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(value).strip());
    }

    /** {@code key}'s list; empty when not given. */
    private static List<?> list(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return List.of();
        if (value instanceof List<?> items) return items;
        throw new IllegalArgumentException(key + " is a list, not " + value);
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
