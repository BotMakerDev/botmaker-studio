package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;
import com.botmaker.plugin.api.assist.Key;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import com.botmaker.studio.services.ClassFiles;

import java.util.ArrayList;
import java.util.List;

import static com.botmaker.studio.assist.FileTools.FILE;
import static com.botmaker.studio.assist.McpTools.given;
import static com.botmaker.studio.assist.McpTools.said;

/** The tools over Studio itself ({@link StudioDriver}): targets, the overlay's caret, boxes on the game, the code's shape. */
final class StudioTools {

    private StudioTools() {}

    static final String TARGET = "the target's key, or Label.method, from list_targets";
    private static final String SYMBOL = "the declaration: Type, Type.member (a method, field or constant) or "
            + "Type.method.local";
    private static final String REVIEW_ID = "the item's id, from list_review";

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

    record ReviewItem(@Describe(REVIEW_ID) String id) {
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
                        "Open a target in Studio — its file in the editor and, when the overlay editor is open, there "
                                + "too — so the user sees where you are working.",
                        Target.class, call -> driver.setTarget(call.target())),
                said("move_caret",
                        "Park the overlay editor's caret on a statement, so the user sees it and its probe is shown "
                                + "on the game. Needs the overlay editor open.",
                        Caret.class, call -> driver.moveCaret(given(call.file()), call.blockId())),
                said("show_area",
                        "Box an area on the game, in the bot's pixels (what screenshots and clicks use), with a "
                                + "label — to show the user what you mean. Needs the overlay editor open.",
                        Box.class, call -> driver.showArea(new Area(call.x(), call.y(), call.width(), call.height()),
                                given(call.label()))),
                said("add_file",
                        "Add an empty class, interface, record or enum to the bot, for methods to go in. It is "
                                + "saved as a version the user can go back to.",
                        NewFile.class, call -> driver.addFile(call.name(), call.kind(), given(call.packageName()))),
                said("delete_file",
                        "Delete one of the bot's files. Refused, with every place that still uses it, while anything "
                                + "uses what it declares; never the entry point or a plugin's file. A safety version "
                                + "is saved first.",
                        File.class, call -> driver.deleteFile(call.file())),
                said("find_usages", "List every use of a declaration of the bot, by binding: file, line, "
                                + "the method it is in, and the line's text.",
                        Symbol.class, call -> driver.findUsages(call.symbol())),
                said("rename",
                        "Rename a declaration and every use of it across the bot, as one step the user can undo; "
                                + "a class takes its file with it, after a safety version. Refused, with why, when the "
                                + "bot would stop compiling, and for a plugin's file.",
                        Rename.class, call -> driver.rename(call.symbol(), call.to())),
                said("open", "Open a declaration in Studio's editor, so the user sees it.",
                        Symbol.class, call -> driver.open(call.symbol())),
                said("list_review",
                        "List the functions a refactor changed by guessing and marked for the user to look at, each "
                                + "with its id and what the mark says.",
                        driver::listReview),
                said("mark_reviewed", "Mark a review item looked at; the mark stays in the code as the record.",
                        ReviewItem.class, call -> driver.markReviewed(call.id())),
                said("remove_mark", "Take a review item's mark out of the code.",
                        ReviewItem.class, call -> driver.removeMark(call.id())),
                said("undo_change",
                        "Put a review item's function back as it was before the refactor that marked it, from the "
                                + "bot's versions. What comes back may not compile.",
                        ReviewItem.class, call -> driver.undoChange(call.id())),
                said("undo",
                        "Take the last step back, as Studio's ↶ (Ctrl+Z) does: an edit of yours, the user's, or one a "
                                + "plugin window made. Says which step it took back and what is next each way.",
                        driver::undo),
                said("redo",
                        "Do the last undone step again, as Studio's ↷ (Ctrl+Y) does. Says which step and what is next "
                                + "each way.",
                        driver::redo));
    }
}
