package com.botmaker.studio.project;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.studio.project.managed.HolderFiles;
import com.botmaker.studio.project.managed.ManagedHolders;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which of the bot's files belong to a loaded plugin — the one answer the canvas lock and the explorer's icon
 * both read (2026-09-27).
 *
 * <p><b>The rule.</b> A file is a plugin's when it is {@code …/plugins/<segment>/<Holder>.java}, {@code segment}
 * is a <em>loaded</em> plugin's ({@link ManagedHolders#segment}, from its coordinate), and that plugin declares a {@link ManagedValue} whose
 * {@code holder} is {@code Holder} — exactly the file {@link ManagedHolders} writes when a project lacks it. A
 * file the user put in {@code plugins/} themselves, or one whose plugin is not loaded, is an ordinary file of
 * theirs: nothing here guesses from a folder name alone, which is what {@code ExplorerModel} did until then.
 *
 * <p><b>Or the plugin's mark, wherever the file is</b> (2026-10-30, the user's call): a class named {@code Holder}
 * that carries the mark of a value the plugin holds in it is that plugin's too, as {@link HolderFiles} finds it.
 * The folder moved on 2026-10-27, and a bot whose holders were written before — Firestone's {@code plugins/sdk/}
 * — had its plugin's files taken for its own until then. The folder is where a missing holder is written.
 *
 * <p><b>Why the whole file is locked.</b> Such a file is the plugin's surface in the bot: its managed
 * values are edited through the plugin's own windows, its {@code @Param} fields through the Parameters window,
 * and a member added to it on the canvas is code nobody's window knows about. The file on disk is still the
 * user's — Studio writes only what those windows write.
 */
public final class PluginFiles {

    /**
     * One holder a loaded plugin declares.
     *
     * @param plugin     the plugin's coordinate
     * @param pluginName what the user calls it (its pom's {@code <name>})
     * @param segment    the folder under {@code plugins/} its files are written in
     * @param className  the holder class, and so the file's name without {@code .java}
     * @param values     every value the plugin declares, whose marks find the holder outside its folder
     */
    public record Holder(PluginCoordinate plugin, String pluginName, String segment, String className,
                         List<ManagedValue<?>> values) {

        public Holder {
            values = List.copyOf(values);
        }

        /** A holder known by its folder only. */
        public Holder(PluginCoordinate plugin, String pluginName, String segment, String className) {
            this(plugin, pluginName, segment, className, List.of());
        }

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

    /** The last marked-holder answer per file, kept while its text and the holders are the same. */
    private record Marked(String source, List<Holder> holders, Optional<Holder> owner) {}

    private static final Map<Path, Marked> MARKED = new ConcurrentHashMap<>();

    /** The holders {@link #MARKED} answers for; another list (a bind, another project) empties it. */
    private static volatile List<Holder> markedFor = List.of();

    private PluginFiles() {}

    /** Every holder {@code plugins} declare, a plugin that cannot say costing only itself. */
    public static List<Holder> holdersOf(List<LoadedPlugin> plugins) {
        List<Holder> out = new ArrayList<>();
        for (LoadedPlugin plugin : plugins) {
            try {
                String segment = ManagedHolders.segment(plugin.coordinate());
                List<ManagedValue<?>> values = plugin.plugin().managedValues();
                if (segment == null || values == null) continue;
                List<ManagedValue<?>> declared = values.stream().filter(v -> v != null).toList();
                Set<String> classes = new LinkedHashSet<>();
                for (ManagedValue<?> value : declared) {
                    if (value.holder() != null && !value.holder().isBlank()) classes.add(value.holder());
                }
                for (String className : classes) {
                    out.add(new Holder(plugin.coordinate(), plugin.name(), segment, className, declared));
                }
            } catch (RuntimeException | LinkageError e) {
                // A plugin built against a contract without managed values, or one that throws, owns no file.
            }
        }
        return List.copyOf(out);
    }

    /**
     * The holder {@code file} is, among {@code holders}, or empty for an ordinary file. A file outside its
     * plugin's folder is read from disk, and only when its name is a holder's.
     */
    public static Optional<Holder> owner(Path file, List<Holder> holders) {
        return owner(file, null, holders);
    }

    /** The same, {@code source} being the file's text, or null to read it from disk when it is needed. */
    public static Optional<Holder> owner(Path file, String source, List<Holder> holders) {
        if (file == null || file.getFileName() == null) return Optional.empty();
        String name = file.getFileName().toString();
        if (!name.endsWith(".java")) return Optional.empty();
        String className = name.substring(0, name.length() - ".java".length());
        String segment = ManagedHolders.segmentOf(file);
        List<Holder> named = new ArrayList<>();
        for (Holder holder : holders) {
            if (!holder.className().equals(className)) continue;
            if (holder.segment().equals(segment)) return Optional.of(holder);
            if (!holder.values().isEmpty()) named.add(holder);
        }
        if (named.isEmpty()) return Optional.empty();
        String text = source != null ? source : read(file);
        return text == null ? Optional.empty() : marked(file, text, holders, named);
    }

    /** Which of {@code named} marks a value it holds in {@code file}'s class, remembered per file text. */
    private static Optional<Holder> marked(Path file, String source, List<Holder> holders, List<Holder> named) {
        Path key = file.toAbsolutePath().normalize();
        if (markedFor != holders) {
            MARKED.clear();
            markedFor = holders;
        }
        Marked last = MARKED.get(key);
        if (last != null && last.holders() == holders && last.source().equals(source)) return last.owner();
        Optional<Holder> owner = Optional.empty();
        try {
            CompilationUnit unit = BotParser.syntax(source);
            for (Holder holder : named) {
                HolderFiles.Key held = new HolderFiles.Key(holder.segment(), holder.className());
                if (HolderFiles.find(Map.of(key, unit), Map.of(holder.plugin(), holder.values())).containsKey(held)) {
                    owner = Optional.of(holder);
                    break;
                }
            }
        } catch (RuntimeException e) {
            // Text JDT cannot read is nobody's holder.
        }
        MARKED.put(key, new Marked(source, holders, owner));
        return owner;
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
