package com.botmaker.studio.project;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.project.managed.ManagedHolders;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which of the bot's files belong to a loaded plugin — the one answer the canvas lock and the explorer's icon
 * both read (2026-09-27).
 *
 * <p><b>The rule.</b> A file is a plugin's when it is {@code …/plugins/<segment>/<Holder>.java}, {@code segment}
 * is the last segment of a <em>loaded</em> plugin's id, and that plugin declares a {@link ManagedValue} whose
 * {@code holder} is {@code Holder} — exactly the file {@link ManagedHolders} writes when a project lacks it. A
 * file the user put in {@code plugins/} themselves, or one whose plugin is not loaded, is an ordinary file of
 * theirs: nothing here guesses from a folder name alone, which is what {@code ExplorerModel} did until then.
 *
 * <p><b>Why the whole file is locked.</b> Such a file is the plugin's surface in the bot: its {@code @Managed}
 * values are edited through the plugin's own windows, its {@code @Param} fields through the Parameters window,
 * and a member added to it on the canvas is code nobody's window knows about. The file on disk is still the
 * user's — Studio writes only what those windows write.
 */
public final class PluginFiles {

    /**
     * One holder a loaded plugin declares.
     *
     * @param pluginId   the plugin's id
     * @param pluginName what the user calls it ({@code StudioPlugin.displayName})
     * @param segment    the folder under {@code plugins/} its files are in
     * @param className  the holder class, and so the file's name without {@code .java}
     */
    public record Holder(String pluginId, String pluginName, String segment, String className) {

        /** The status-line badge of a file this holder is. */
        public String badge() {
            return pluginName + " plugin - Read Only";
        }

        /** Why the canvas does not edit it, and where to go instead. */
        public String reason() {
            return className + ".java belongs to the " + pluginName + " plugin. Change its values in the plugin's "
                    + "own windows, and its parameters in Project ▸ Parameters…";
        }
    }

    private PluginFiles() {}

    /** Every holder {@code plugins} declare, a plugin that cannot say costing only itself. */
    public static List<Holder> holdersOf(List<StudioPlugin> plugins) {
        List<Holder> out = new ArrayList<>();
        for (StudioPlugin plugin : plugins) {
            try {
                String segment = ManagedHolders.segment(plugin.id());
                List<ManagedValue<?>> values = plugin.managedValues();
                if (segment == null || values == null) continue;
                Set<String> classes = new LinkedHashSet<>();
                for (ManagedValue<?> value : values) {
                    if (value != null && value.holder() != null && !value.holder().isBlank()) {
                        classes.add(value.holder());
                    }
                }
                for (String className : classes) {
                    out.add(new Holder(plugin.id(), plugin.displayName(), segment, className));
                }
            } catch (RuntimeException | LinkageError e) {
                // A plugin built against a contract without managed values, or one that throws, owns no file.
            }
        }
        return List.copyOf(out);
    }

    /** The holder {@code file} is, among {@code holders}, or empty for an ordinary file. */
    public static Optional<Holder> owner(Path file, List<Holder> holders) {
        if (file == null || file.getFileName() == null) return Optional.empty();
        String name = file.getFileName().toString();
        if (!name.endsWith(".java")) return Optional.empty();
        Path folder = file.getParent();
        Path plugins = folder == null ? null : folder.getParent();
        if (plugins == null || plugins.getFileName() == null
                || !plugins.getFileName().toString().equals("plugins")) {
            return Optional.empty();
        }
        String segment = folder.getFileName().toString();
        String className = name.substring(0, name.length() - ".java".length());
        for (Holder holder : holders) {
            if (holder.segment().equals(segment) && holder.className().equals(className)) return Optional.of(holder);
        }
        return Optional.empty();
    }
}
