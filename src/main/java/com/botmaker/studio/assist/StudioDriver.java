package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AgentContext;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;

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
