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

    /**
     * One edit of {@code edit_code}. A field two edits share is described once, on the first that has it: the
     * assistant reads one sentence per field.
     */
    sealed interface Edit {
        @Describe("insert a palette entry before the statement now at index")
        record Insert(@Describe("a body's id, from read_tree") String bodyId,
                      @Describe("the place in that body, 0 for first") int index,
                      @Describe("the entry's id, from list_palette") String paletteId) implements Edit {
        }

        @Describe("write a value into a slot")
        record Set(@Describe("a slot's id, from read_tree") String slotId,
                   @Describe("the slot's value") String value) implements Edit {
        }

        @Describe("delete a statement")
        record Delete(@Describe("a statement's id, from read_tree") String blockId) implements Edit {
        }

        @Describe("move a statement into a body, before the statement now at index")
        record Move(String blockId, String bodyId, int index) implements Edit {
        }

        @Describe("add one more argument, seeded with a default, to a call that takes any number "
                + "(ImageTemplateGroup.of, Keyboard.sequence…)")
        record AddArgument(@Describe("a call statement's id, or a slot holding the call") String callId)
                implements Edit {
        }

        @Describe("rename or retype the variable a Declare Variable statement declares: its uses follow a new "
                + "name, a new type resets its value; what is not given is kept")
        record EditVariable(String blockId,
                            @Describe(value = "the variable's new name", optional = true) String name,
                            @Describe(value = "its new type: int, String, Point…", optional = true) String type)
                implements Edit {
        }
    }

    record Edits(@Describe(value = FILE, optional = true) String file,
                 @Describe("the edits, in order; one edit is a list of one") List<Edit> edits) implements FileCall {
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
                        "List what edit_code's insert takes: statement blocks and plugin calls. Empty, the index of "
                                + "every name by class; filtered by a class or words, each entry's id.",
                        Palette.class, (tools, call) -> tools.listPalette(given(call.filter()))),
                reading(bot, "read_tree",
                        "Read a file's methods: each body's id, its statements in order, and each statement's value "
                                + "slots with the type they take.",
                        Tree.class, (tools, call) -> tools.readTree(given(call.method()))),
                reading(bot, "list_errors", "List the compile errors a file has now.",
                        OnFile.class, (tools, call) -> tools.listErrors()),
                editing(bot, "edit_code",
                        "Edit a file's blocks: the edits in order, each compiled as it is applied, kept as one undo "
                                + "step, or none kept if one is refused (the answer says which). Ids are the tree's "
                                + "as the earlier edits left it, so insert from the bottom up. A slot's value is a "
                                + "literal, a constant (Pictures.ORE), a factory call of its type, a palette call "
                                + "(call:ImageFinder.find(ImageTemplate)), an operator ("
                                + String.join(", ", AssistTurn.operatorIds()) + ") or var:<name> of a variable in "
                                + "scope; what a call or operator brings, read_tree lists as slots and bodies.",
                        Edits.class, (tools, call) -> applyEdits(tools, call.edits())),
                editing(bot, "add_method",
                        "Add a method to a class, after its other members. Types: int, double, boolean, String, a "
                                + "plugin's type (Point, Picture…), List<…> of one, or void.",
                        NewMethod.class, (tools, call) -> tools.addMethod(given(call.className()), call.name(),
                                given(call.returns()), call.params() == null ? List.of() : call.params())),
                checked(bot, "edit_signature",
                        "Change a method's name, return type or parameters; what is not given is kept, and its calls "
                                + "in the file follow. Refused when another file would stop compiling: rename "
                                + "renames a method used elsewhere.",
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
                case Edit.Move move -> tools.moveBlock(move.blockId(), move.bodyId(), move.index());
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
