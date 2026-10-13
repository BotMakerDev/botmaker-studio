package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AgentContext;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What an assistant does with Studio beyond editing a file (2026-10-06): where blocks go, the overlay's caret and
 * boxes on the game, and runs — the bot, one activity, one statement. {@link McpTools} serves each; the
 * implementation hops to the FX thread.
 *
 * <p><b>The game is touched only by runs.</b> There is no click or key here: an assistant acts on the game by
 * running the bot's own code, which the user can read, stop and undo, never by driving the mouse itself.
 *
 * <p>Each action answers a sentence saying what it did, or throws {@link IllegalArgumentException} with the
 * sentence saying why not and what to do instead; {@link McpTools} sends that back as a refusal.
 */
public interface StudioDriver {

    /**
     * Where blocks go: a method a plugin's target type found in the bot ({@code OverlayPart.targets}).
     *
     * @param key  {@code com.bot.Collect#body}, what {@link #setTarget} and {@link #runActivity} take
     * @param file the file declaring it, as {@link LiveBot#files} lists it
     */
    record Target(String key, String label, String group, String file, String method) {}

    /** A plugin's assistant tool, under the name it is served by: {@code <plugin segment>_<tool name>}. */
    record PluginTool(String name, String pluginName, AssistantTool<?> tool) {}

    /** How a bot built on a plugin is laid out, in the plugin's own words ({@code StudioPlugin.assistantGuide}). */
    record PluginGuide(String pluginName, String text) {}

    /** The open project's folder, where {@link GameNotes} keeps the bot's notes; empty with none open. */
    default Optional<Path> projectDir() {
        return Optional.empty();
    }

    /** The bound plugins' guides that say something, in plugin order. */
    default List<PluginGuide> pluginGuides() {
        return List.of();
    }

    /** The plugins' targets in the bot now. Blocking: the bot is parsed. */
    List<Target> targets();

    /** Opens {@code key}'s file in the editor, and in the overlay editor when it is open, inside its method. */
    String setTarget(String key);

    /** Parks the overlay editor's caret on the statement {@code blockId} of {@code file}. */
    String moveCaret(String file, String blockId);

    /** Boxes {@code area}, in the bot's pixels, on the game, captioned {@code label}. */
    String showArea(Area area, String label);

    /** ▶ Run the bot, as the toolbar does. */
    String runBot();

    /** ▶ Run {@code key}'s method on its own, through the plugin's trial entry. */
    String runActivity(String key);

    /**
     * ▶ Try the statement {@code blockId} of {@code file}. {@code values} gives an earlier local the statement
     * reads, by name, as a value of its type; one not given takes its value from the last run or is computed, as
     * the Try dialog defaults it, and one that can be neither is refused, named.
     */
    String tryStatement(String file, String blockId, Map<String, String> values);

    /** Stops the run, the try or the debug session. */
    String stop();

    // ---- structure, navigation and review (phase 8) — each refused here unless the implementation offers it

    /**
     * Writes an empty {@code kind} (class, interface, record, enum; empty for a class) named {@code name} in
     * {@code packageName} (empty for the bot's main package), as one version the user can go back to.
     */
    default String addFile(String name, String kind, String packageName) {
        throw new IllegalArgumentException("Studio cannot add a file here.");
    }

    /** Deletes the bot's file {@code file}; refused while anything uses what it declares. */
    default String deleteFile(String file) {
        throw new IllegalArgumentException("Studio cannot delete a file here.");
    }

    /** Every use of {@code symbol} ({@link SymbolNames}), one line each: file, line, enclosing method, text. */
    default String findUsages(String symbol) {
        throw new IllegalArgumentException("Studio cannot search here.");
    }

    /** Renames {@code symbol} and every use of it across the bot, as one undo step; refused when it would not compile. */
    default String rename(String symbol, String to) {
        throw new IllegalArgumentException("Studio cannot rename here.");
    }

    /** Opens {@code symbol}'s file in the editor and shows its declaration. */
    default String open(String symbol) {
        throw new IllegalArgumentException("Studio cannot open that here.");
    }

    /** The functions a refactor marked for the user to look at, each with its id and what the mark says. */
    default String listReview() {
        throw new IllegalArgumentException("Studio has no review list here.");
    }

    /** Sets the mark {@code id} reviewed: it stays in the code as the record. */
    default String markReviewed(String id) {
        throw new IllegalArgumentException("Studio has no review list here.");
    }

    /** Takes the mark {@code id} out of the code. */
    default String removeMark(String id) {
        throw new IllegalArgumentException("Studio has no review list here.");
    }

    /** Puts the function the mark {@code id} is on back as the newest version without the mark has it. */
    default String undoChange(String id) {
        throw new IllegalArgumentException("Studio has no review list here.");
    }

    // ---- versions, plugins and the project (phase 9) — each refused here unless the implementation offers it

    /** The bot's newest {@code limit} versions, newest first: id, when, who and what. */
    default String listVersions(int limit) {
        throw new IllegalArgumentException("Studio keeps no versions here.");
    }

    /** Saves the bot as it is now as a version named {@code label}, a milestone the user sees in Versions. */
    default String checkpoint(String label) {
        throw new IllegalArgumentException("Studio keeps no versions here.");
    }

    /**
     * Puts the whole bot back as the version {@code version} had it, after saving what it is now as a version,
     * then reloads the project.
     */
    default String revert(String version) {
        throw new IllegalArgumentException("Studio keeps no versions here.");
    }

    /**
     * Whether the project is about to reload — a {@link #revert} put the files back and the editor still holds
     * what was there before — so no tool may touch the editor, run or save: each would write the old code back.
     */
    default boolean reloading() {
        return false;
    }

    /**
     * Where a {@link #setPluginVersion} move is while the user is asked or it runs, empty otherwise: the tools
     * that touch the bot's files or its plugins wait for it.
     */
    default String pluginMoving() {
        return "";
    }

    /** The plugins the bot's pom declares, each with its version and whether it loaded. */
    default String listPlugins() {
        throw new IllegalArgumentException("Studio has no plugins here.");
    }

    /** The registry's plugins {@code query} matches; every one for a blank query. */
    default String searchPlugins(String query) {
        throw new IllegalArgumentException("Studio has no plugin registry here.");
    }

    /** Adds the registry's plugin {@code id} to the pom, once the user agrees on screen. */
    default String addPlugin(String id) {
        throw new IllegalArgumentException("Studio cannot add a plugin here.");
    }

    /**
     * What removing the declared plugin {@code id} would change, or, when {@code confirm}, removes it: a version
     * first, the calls into it repaired, and its files deleted when {@code deleteFiles}.
     */
    default String removePlugin(String id, boolean deleteFiles, boolean confirm) {
        throw new IllegalArgumentException("Studio cannot remove a plugin here.");
    }

    /**
     * What moving the declared plugin {@code id} to {@code version} would change, or, when {@code confirm}, asks
     * the user on screen and answers at once; once they agree the move runs in the background — a version first,
     * the calls into it repaired, the pom written — and {@code moved} runs when it ends, whether or not it wrote.
     * {@link #listPlugins} says where it is.
     */
    default String setPluginVersion(String id, String version, boolean confirm, Runnable moved) {
        throw new IllegalArgumentException("Studio cannot move a plugin here.");
    }

    /**
     * What the bot has to migrate: the values of each type a plugin retired, with its replacement and how many are
     * copied, and the bot's {@code @Migrate} methods, those nothing needs any more marked.
     */
    default String listMigrations() {
        throw new IllegalArgumentException("Studio cannot read migrations here.");
    }

    /**
     * How the values of the type named {@code from} become the type named {@code to} (blank: its replacement, else
     * the first that fits), as the migration window shows the pair: each part's or constant's pick, saved or first
     * guessed, what else it can be, the pairs it goes through and why it cannot be written yet.
     */
    default String readMigration(String from, String to) {
        throw new IllegalArgumentException("Studio cannot read migrations here.");
    }

    /**
     * The migration window's Apply for the pair: its {@code @Migrate} methods and the {@code @Migrated} copies, the
     * pair's steps seeded as {@link #readMigration} says them and changed by {@code mapping}'s lines. Without
     * {@code confirm}, what it would write and each value before and after; with it, written as one version.
     */
    default String setMigration(String from, String to, String mapping, boolean confirm) {
        throw new IllegalArgumentException("Studio cannot migrate values here.");
    }

    /**
     * Points the uses of {@code from}'s values named in {@code names} (comma-separated; blank: all) at their copies in
     * {@code to} (blank: the one type holding them); a use its copy does not fit is left, its function marked.
     */
    default String switchUses(String from, String to, String names) {
        throw new IllegalArgumentException("Studio cannot switch uses here.");
    }

    /**
     * Removes {@code from}'s originals copied into {@code to} that nothing uses, then the {@code @Migrate} methods
     * nothing needs; without {@code confirm} only says what would go.
     */
    default String removeMigrated(String from, String to, boolean confirm) {
        throw new IllegalArgumentException("Studio cannot remove migrated values here.");
    }

    /** The bot's {@code @Param} fields: each one's name, type, value and whether it can be set. */
    default String listParams() {
        throw new IllegalArgumentException("Studio reads no parameters here.");
    }

    /** Sets the {@code @Param} field {@code param}'s value in the code, read by the host grammar from {@code value}. */
    default String setParamDefault(String param, String value) {
        throw new IllegalArgumentException("Studio sets no parameters here.");
    }

    /**
     * A plugin's {@code @Param} field {@code param} as its plugin declares it now: its first value, or written anew
     * when it is another type. Only says what it would do unless {@code confirm}.
     */
    default String resetParam(String param, boolean confirm) {
        throw new IllegalArgumentException("Studio resets no parameters here.");
    }

    /** Deletes the field {@code field} of {@code file}, refused while anything uses it. */
    default String deleteField(String file, String field) {
        throw new IllegalArgumentException("Studio deletes no fields here.");
    }

    /** The project settings an assistant may read and set, each with its value and what it takes. */
    default String settings() {
        throw new IllegalArgumentException("Studio has no settings here.");
    }

    /** Sets the project setting {@code key}, one {@link #settings} lists, to {@code value}. */
    default String setSetting(String key, String value) {
        throw new IllegalArgumentException("Studio has no settings here.");
    }

    /** The vetted templates New Project offers, each with its name and what it is. */
    default String listTemplates() {
        throw new IllegalArgumentException("Studio has no templates here.");
    }

    /**
     * Creates the project {@code name} as New Project does — from the vetted template {@code template}, blank when
     * it is empty, with the registry's {@code plugins} declared — then opens it in place of this one.
     */
    default String createProject(String name, String template, List<String> plugins) {
        throw new IllegalArgumentException("Studio cannot create a project here.");
    }

    /** The bound plugins' assistant tools, in plugin order. */
    List<PluginTool> pluginTools();

    /** What a plugin's tool is handed: the watched screen and its frame, boxes on the game, the services. */
    AgentContext agentContext();

    /**
     * The target {@code given} names in {@code targets}: its key, {@code Label.method} or its label alone.
     *
     * @throws IllegalArgumentException naming the targets, when none matches
     */
    static Target target(List<Target> targets, String given) {
        String wanted = given == null ? "" : given.strip();
        Optional<Target> found = targets.stream().filter(t -> t.key().equals(wanted)).findFirst()
                .or(() -> targets.stream().filter(t -> (t.label() + "." + t.method()).equals(wanted)
                        || (t.label() + "." + t.method() + "()").equals(wanted)).findFirst())
                .or(() -> {
                    List<Target> byLabel = targets.stream().filter(t -> t.label().equals(wanted)).toList();
                    return byLabel.size() == 1 ? Optional.of(byLabel.getFirst()) : Optional.empty();
                });
        return found.orElseThrow(() -> new IllegalArgumentException(targets.isEmpty()
                ? "No plugin finds a target in this bot."
                : "No target " + wanted + ". The targets are " + targets.stream().map(Target::key).toList() + "."));
    }
}
