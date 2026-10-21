package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;
import com.botmaker.plugin.api.assist.Key;
import com.botmaker.plugin.api.overlay.Area;
import com.botmaker.studio.services.ClassFiles;

import java.util.ArrayList;
import java.util.List;

import static com.botmaker.studio.assist.FileTools.FILE;
import static com.botmaker.studio.assist.McpTools.given;
import static com.botmaker.studio.assist.McpTools.said;
import static com.botmaker.studio.assist.McpTools.saidOneOf;

/** The tools over Studio itself ({@link StudioDriver}): targets, the overlay's caret, boxes on the game, the code's shape. */
final class StudioTools {

    private StudioTools() {}

    static final String TARGET = "the target's key, or Label.method, from list_targets";
    private static final String SYMBOL = "the declaration: Type, Type.member (a method, field or constant) or "
            + "Type.method.local";
    private static final String REVIEW_ID = "the item's id, from list";

    record Target(@Describe(TARGET) String target) {
    }

    record Caret(@Describe(value = FILE, optional = true) String file,
                 @Describe("the statement id, from read_tree") String blockId) {
    }

    record Box(@Describe("left edge") int x, @Describe("top edge") int y, @Describe("width") int width,
               @Describe("height") int height,
               @Describe(value = "what the box shows", optional = true) String label) {
    }

    record NewFile(@Describe("the class's name: Helpers") String name,
                   @Describe(value = "what it declares; a class when left out", optional = true) ClassFiles.Kind kind,
                   @Describe(value = "the package, com.mybot.util; the bot's main package when left out",
                           optional = true) @Key("package") String packageName) {
    }

    record File(@Describe("the file, as list_files names it") String file) {
    }

    record Symbol(@Describe(SYMBOL) String symbol) {
    }

    record Rename(@Describe(SYMBOL) String symbol, @Describe("the new name") String to) {
    }

    /** {@code review}: the functions a refactor changed by guessing and marked for the user to look at. */
    sealed interface Review {
        @Describe("the marked functions, each with its id and what the mark says")
        record List() implements Review {
        }

        @Describe("mark one looked at; the mark stays in the code as the record")
        record Mark(@Describe(REVIEW_ID) String id) implements Review {
        }

        @Describe("take its mark out of the code")
        record Unmark(String id) implements Review {
        }

        @Describe("put its function back as it was before the refactor, from the bot's versions; it may not compile")
        record Undo(String id) implements Review {
        }
    }

    static List<AssistantTool<?>> of(StudioDriver driver) {
        return List.of(
                said("list_targets",
                        "List where an activity's blocks go: the methods the bot's plugins call targets, with the file "
                                + "each is in. set_target and run_activity take the key.",
                        () -> {
                            List<StudioDriver.Target> targets = driver.targets();
                            if (targets.isEmpty()) return "No plugin finds a target in this bot.";
                            List<String> lines = new ArrayList<>();
                            for (StudioDriver.Target t : targets) {
                                lines.add(t.key() + "  " + t.group() + " ▸ " + t.label() + "." + t.method() + "()  in "
                                        + t.file());
                            }
                            return String.join("\n", lines);
                        }),
                said("set_target",
                        "Open a target in Studio's editor, and in the overlay editor when it is open, so the user "
                                + "sees where you work.",
                        Target.class, call -> driver.setTarget(call.target())),
                said("move_caret",
                        "Park the overlay editor's caret on a statement, its probe shown on the game.",
                        Caret.class, call -> driver.moveCaret(given(call.file()), call.blockId())),
                said("show_area",
                        "Box an area on the game for the user, in the bot's pixels, in the overlay editor.",
                        Box.class, call -> driver.showArea(new Area(call.x(), call.y(), call.width(), call.height()),
                                given(call.label()))),
                said("add_file",
                        "Add an empty class, interface, record or enum to the bot, saved as a version.",
                        NewFile.class, call -> driver.addFile(call.name(), call.kind(), given(call.packageName()))),
                said("delete_file",
                        "Delete one of the bot's files, a safety version saved first. Refused while anything uses "
                                + "it, saying where; never the entry point or a plugin's file.",
                        File.class, call -> driver.deleteFile(call.file())),
                said("find_usages", "List every use of a declaration: file, line, method and the line's text.",
                        Symbol.class, call -> driver.findUsages(call.symbol())),
                said("rename",
                        "Rename a declaration and its uses across the bot, as one undo step; a class takes its file. "
                                + "Refused when the bot would stop compiling, and for a plugin's file.",
                        Rename.class, call -> driver.rename(call.symbol(), call.to())),
                said("open", "Open a declaration in Studio's editor, so the user sees it.",
                        Symbol.class, call -> driver.open(call.symbol())),
                saidOneOf("review",
                        "The functions a refactor changed by guessing and marked for the user: list, mark, unmark, "
                                + "undo.",
                        Review.class, action -> switch (action) {
                            case Review.List list -> driver.listReview();
                            case Review.Mark mark -> driver.markReviewed(mark.id());
                            case Review.Unmark unmark -> driver.removeMark(unmark.id());
                            case Review.Undo undo -> driver.undoChange(undo.id());
                        }),
                said("undo",
                        "Take the last step back, as Studio's ↶ does: yours, the user's or a plugin window's. Says "
                                + "which, and what is next each way.",
                        driver::undo),
                said("redo", "Do the last undone step again, as Studio's ↷ does.", driver::redo));
    }
}
