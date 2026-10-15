package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AgentReply;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;
import com.botmaker.plugin.api.assist.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.botmaker.studio.assist.McpTools.given;

/**
 * The tools over the bot's files: each call opens its file on a fresh {@link AssistTurn} ({@link LiveBot#begin})
 * and, for an edit, commits what was accepted as one undo step.
 */
final class FileTools {

    private FileTools() {}

    /** What every file tool's {@code file} says. */
    static final String FILE = "the file, as list_files names it (its path, file name or class name); empty for the "
            + "file open in Studio";

    /** The parameters of a tool on one file. */
    interface FileCall {
        /** The file, as the assistant named it; {@code null} or empty for the file open in Studio. */
        String file();
    }

    record OnFile(@Describe(value = FILE, optional = true) String file) implements FileCall {
    }

    record Palette(@Describe(value = "words, any of which an entry's id, label, category, parameter or return type "
            + "holds (ImageFinder, wait gone); empty for the index", optional = true) String filter,
                   @Describe(value = FILE, optional = true) String file) implements FileCall {
    }

    record Tree(@Describe(value = FILE, optional = true) String file,
                @Describe(value = "one method's name; empty for every method", optional = true) String method)
            implements FileCall {
    }

    record Insert(@Describe(value = FILE, optional = true) String file,
                  @Describe("the body id, from read_tree") String bodyId,
                  @Describe("where to insert, 0 for first") int index,
                  @Describe("the palette id, from list_palette") String paletteId) implements FileCall {
    }

    record SetSlot(@Describe(value = FILE, optional = true) String file,
                   @Describe("the slot id, from read_tree") String slotId,
                   @Describe("the value") String value) implements FileCall {
    }

    record Block(@Describe(value = FILE, optional = true) String file,
                 @Describe("the statement id, from read_tree") String blockId) implements FileCall {
    }

    record Variable(@Describe(value = FILE, optional = true) String file,
                    @Describe("the declaring statement's id, from read_tree") String blockId,
                    @Describe(value = "its new name", optional = true) String name,
                    @Describe(value = "its new type, as the dialog names it: int, String, Point…", optional = true)
                    String type) implements FileCall {
    }

    record Argument(@Describe(value = FILE, optional = true) String file,
                    @Describe("the call: a call statement's id or a slot holding the call") String callId)
            implements FileCall {
    }

    record Move(@Describe(value = FILE, optional = true) String file,
                @Describe("the statement id, from read_tree") String blockId,
                @Describe("the body it goes into, from read_tree") String bodyId,
                @Describe("where in that body, 0 for first") int index) implements FileCall {
    }

    record NewMethod(@Describe(value = FILE, optional = true) String file,
                     @Describe(value = "the class; empty for the file's first", optional = true) @Key("class")
                     String className,
                     @Describe("the method's name") String name,
                     @Describe(value = "the type it gives back, void for nothing", optional = true) String returns,
                     @Describe(value = "its parameters, in order", optional = true) List<SignatureNames.Param> params)
            implements FileCall {
    }

    record Signature(@Describe(value = FILE, optional = true) String file,
                     @Describe("the method, name or Owner.name") String method,
                     @Describe(value = "its new name; empty to keep it", optional = true) String name,
                     @Describe(value = "the type it gives back, void for nothing", optional = true) String returns,
                     @Describe(value = "its parameters, in order", optional = true) List<SignatureNames.Param> params)
            implements FileCall {
    }

    /** One edit of {@code apply_edits}, each the edit of the tool of the same name. */
    sealed interface Edit {
        record Insert(String bodyId, int index, String paletteId) implements Edit {
        }

        record Set(String slotId, String value) implements Edit {
        }

        record Delete(String blockId) implements Edit {
        }

        record AddArgument(String callId) implements Edit {
        }

        record EditVariable(String blockId, @Describe(value = "", optional = true) String name,
                            @Describe(value = "", optional = true) String type) implements Edit {
        }
    }

    record Edits(@Describe(value = FILE, optional = true) String file,
                 @Describe("the edits, in order") List<Edit> edits) implements FileCall {
    }

    static List<AssistantTool<?>> of(LiveBot bot) {
        return List.of(
                McpTools.said("list_files", "List the bot's source files, by path below its source root.", () -> {
                    List<String> files = bot.files();
                    return files.isEmpty() ? McpTools.NO_PROJECT : String.join("\n", files);
                }),
                reading(bot, "list_methods", "List a file's methods that have a body, with each body's id.",
                        OnFile.class, (tools, call) -> tools.listMethods()),
                reading(bot, "list_palette",
                        "List what can be inserted into a file: statement blocks and plugin calls. Call it empty "
                                + "first for the index, every name by class, then filter by a class or words to "
                                + "get each entry's id, which insert_block takes.",
                        Palette.class, (tools, call) -> tools.listPalette(given(call.filter()))),
                reading(bot, "read_tree",
                        "Read a file's methods: each body's id, the statements in each body in order and each "
                                + "statement's value slots with the type they expect. Give `method` to read one. "
                                + "An edit that shifts ids answers with the methods it reshaped.",
                        Tree.class, (tools, call) -> tools.readTree(given(call.method()))),
                reading(bot, "list_errors", "List the compile errors a file has now.",
                        OnFile.class, (tools, call) -> tools.listErrors()),
                editing(bot, "insert_block",
                        "Insert a palette entry as a statement in a body, before the statement now at index. The "
                                + "edit is compiled first and refused if it adds an error; accepted edits are applied "
                                + "to the file at once, as one undo step, and the file is opened in Studio.",
                        Insert.class, (tools, call) -> tools.insertBlock(call.bodyId(), call.index(),
                                call.paletteId())),
                editing(bot, "set_slot",
                        "Write a value into a slot: a literal, one of the bot's constants by name (Pictures.ORE), "
                                + "an enum or static constant of the slot's type, or a factory call that type "
                                + "declares. Or build one: a palette call id whose result fits the slot "
                                + "(call:ImageFinder.find(ImageTemplate)), or an operator ("
                                + String.join(", ", AssistTurn.operatorIds()) + "); the call's arguments and the "
                                + "operator's operands are then slots of their own, which read_tree lists, and "
                                + "op:LAMBDA's body (the action of ImageFinder.whileFindAny…) a body to insert into. "
                                + "Or a variable in scope there, its type the slot's: var:value names a lambda's "
                                + "parameter, a local or a parameter. "
                                + "Anything else is refused.",
                        SetSlot.class, (tools, call) -> tools.setSlot(call.slotId(), call.value())),
                editing(bot, "delete_block", "Delete a statement. Refused if the file would stop compiling.",
                        Block.class, (tools, call) -> tools.deleteBlock(call.blockId())),
                editing(bot, "apply_edits",
                        "Apply several edits to one file in one call, in order, as one undo step: each is an insert, "
                                + "a set, a delete, an add_argument or an edit_variable, taking the same fields as "
                                + "insert_block, set_slot, delete_block, add_argument and edit_variable. Each is "
                                + "compiled as it is applied; if any is refused, none is kept and the answer says "
                                + "which. Ids are those of the tree as the earlier edits left it: an insert shifts "
                                + "the statements below it, so insert from the bottom up, or count the shift.",
                        Edits.class, (tools, call) -> applyEdits(tools, call.edits())),
                editing(bot, "add_method",
                        "Add a method to a class of a file, after its other members, as the Add Function dialog does. "
                                + "Types are those Studio offers: int, double, boolean, String, a plugin's type "
                                + "(Point, Picture…), List<…> of one, or void to return nothing.",
                        NewMethod.class, (tools, call) -> tools.addMethod(given(call.className()), call.name(),
                                given(call.returns()), call.params() == null ? List.of() : call.params())),
                editing(bot, "edit_variable",
                        "Rename or retype a variable a Declare Variable statement declares, as the Edit Variable "
                                + "dialog does: its uses follow a new name, and a new type resets its value to "
                                + "that type's fresh one. What is not given is kept.",
                        Variable.class, (tools, call) -> tools.editVariable(call.blockId(), given(call.name()),
                                given(call.type()))),
                editing(bot, "add_argument",
                        "Add one more argument to a call that takes any number (ImageTemplateGroup.of, "
                                + "Keyboard.sequence…), seeded with a default; read_tree then lists its slot.",
                        Argument.class, (tools, call) -> tools.addArgument(call.callId())),
                editing(bot, "move_block",
                        "Move a statement into a body of the same file, before the statement now at index.",
                        Move.class, (tools, call) -> tools.moveBlock(call.blockId(), call.bodyId(), call.index())),
                checked(bot, "edit_signature",
                        "Change a method's name, return type or parameters; what is not given is kept. Its calls in "
                                + "the file follow, a parameter keeping its place by name and a new one getting a "
                                + "default. Refused when a call in another file would stop compiling — use rename "
                                + "to rename a method used elsewhere.",
                        Signature.class, (tools, call) -> tools.editSignature(call.method(), given(call.name()),
                                call.returns(), call.params())));
    }

    /**
     * {@code edits} applied in order on one turn; the first refusal ends it and the turn is not committed, since a
     * turn whose edits are not all kept would land half a change.
     */
    private static String applyEdits(AssistTools tools, List<Edit> edits) {
        if (edits.isEmpty()) throw new IllegalArgumentException("edits is a list of at least one edit.");
        List<String> done = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            String answer = switch (edits.get(i)) {
                case Edit.Insert insert -> tools.insertBlock(insert.bodyId(), insert.index(), insert.paletteId());
                case Edit.Set set -> tools.setSlot(set.slotId(), set.value());
                case Edit.Delete delete -> tools.deleteBlock(delete.blockId());
                case Edit.AddArgument argument -> tools.addArgument(argument.callId());
                case Edit.EditVariable variable -> tools.editVariable(variable.blockId(), given(variable.name()),
                        given(variable.type()));
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

    /** A tool that reads a file and changes nothing. */
    private static <P extends Record & FileCall> AssistantTool<P> reading(
            LiveBot bot, String name, String description, Class<P> params, BiFunction<AssistTools, P, String> call) {
        return AssistantTool.named(name).describedAs(description).takes(params)
                .handledBy((sent, context) -> run(bot, sent.file(), tools -> call.apply(tools, sent), false));
    }

    /** A tool that edits a file, its accepted edits committed as one undo step. */
    private static <P extends Record & FileCall> AssistantTool<P> editing(
            LiveBot bot, String name, String description, Class<P> params, BiFunction<AssistTools, P, String> call) {
        return AssistantTool.named(name).describedAs(description).takes(params)
                .handledBy((sent, context) -> run(bot, sent.file(), tools -> call.apply(tools, sent), true));
    }

    /**
     * {@link #editing} for an edit that can break another file: once accepted, the whole bot is compiled with it, and
     * nothing is committed when another file would stop compiling.
     */
    private static <P extends Record & FileCall> AssistantTool<P> checked(
            LiveBot bot, String name, String description, Class<P> params, BiFunction<AssistTools, P, String> call) {
        return editing(bot, name, description, params, (tools, sent) -> {
            String answer = call.apply(tools, sent);
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
        });
    }

    private static AgentReply run(LiveBot bot, String file, Function<AssistTools, String> call, boolean edits) {
        Optional<AssistTurn> turn;
        try {
            turn = bot.begin(given(file));
        } catch (IllegalArgumentException e) {
            return AgentReply.refused("REFUSED: " + e.getMessage());
        } catch (RuntimeException e) {
            return AgentReply.refused("Studio could not read the file: " + e.getMessage());
        }
        if (turn.isEmpty()) return AgentReply.refused(McpTools.NO_PROJECT);
        AssistTools tools = new AssistTools();
        tools.begin(turn.get());
        List<BlockView.Method> before = edits ? turn.get().tree() : List.of();
        String answer;
        try {
            answer = call.apply(tools);
        } catch (IllegalArgumentException e) {
            return AgentReply.refused("REFUSED: " + e.getMessage());
        }
        if (!edits) return AgentReply.text(answer);
        if (turn.get().acceptedEdits() == 0) return AgentReply.refused(answer);
        String reshaped = tools.reshaped(before);
        return AgentReply.text(answer + "\n" + bot.commit(turn.get(), "the assistant's change (MCP)")
                + (reshaped.isEmpty() ? "" : "\nThe methods it reshaped, ids as they are now: " + reshaped));
    }
}
