package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.params.BotRecords;
import com.botmaker.studio.services.BotSources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * A plugin's own values: {@code @Managed} methods, read off the bot's sources and written back to them.
 *
 * <p><b>The twin of {@code JavaParameters}, over the same walk and for the same reasons.</b> Buffers before
 * files, both written, so a scan does not miss the user's last ten minutes and a rewrite is not undone by
 * the next save. Nothing is cached: the source file is the truth, it is cheap to re-read, and a cache here
 * would be a second answer to "what does this bot hold", which is the failure the JSON arrangement had.
 *
 * <p><b>This is where {@code plugins/<id>/<name>.json} went</b> (2026-09-20). A plugin's data is a method
 * in a file the plugin shipped and the user owns, so a developer with no BotMaker installed can read it,
 * grep it, refactor it and hand it to a compiler — and a rename is a compile error rather than a silently
 * empty value three screens into a run.
 */
public final class JavaManagedValues {

    private JavaManagedValues() {}

    /** Every {@code @Managed} method the bot declares, file by file, in the order the walk visits them. */
    public static List<ManagedMethod> scan(ProjectConfig config, ProjectState state) {
        return scan(config, state, PluginHost.valueTypes());
    }

    /** The same, against a given catalog — the seam a test uses, and the one place the catalog enters. */
    public static List<ManagedMethod> scan(ProjectConfig config, ProjectState state, ValueCatalog catalog) {
        if (config == null) return List.of();
        // Two walks, as the parameters scan does: a value may be typed with a record declared in a file the
        // managed walk has not reached yet, so what the bot declares has to be known before the first read.
        BotRecords records = BotRecords.scan(config, state, catalog);
        List<ManagedMethod> out = new ArrayList<>();
        BotSources.scan(config, state, (file, source) ->
                out.addAll(JavaManagedSource.read(file, source, catalog, records)));
        return List.copyOf(out);
    }

    /** The value carrying {@code id}, or empty when the bot declares none — the first one if it declares two. */
    public static Optional<ManagedMethod> find(ProjectConfig config, ProjectState state, String id) {
        return find(config, state, id, PluginHost.valueTypes());
    }

    /** The same, against a given catalog. */
    public static Optional<ManagedMethod> find(ProjectConfig config, ProjectState state, String id,
                                               ValueCatalog catalog) {
        if (id == null || id.isBlank()) return Optional.empty();
        for (ManagedMethod value : scan(config, state, catalog)) {
            if (id.equals(value.id())) return Optional.of(value);
        }
        return Optional.empty();
    }

    /**
     * Replaces what {@code value}'s method returns, and answers whether anything changed.
     *
     * <p>False is an ordinary outcome — a method somebody edited while the window was open, an expression
     * the file no longer has a single return for — and the caller says so rather than failing.
     */
    public static boolean setValue(ProjectConfig config, ProjectState state, ManagedMethod value,
                                   String expression, List<String> imports) {
        return rewrite(config, state, value.file(), source -> JavaManagedEdits.setValue(
                source, value.className(), value.methodName(), expression, imports));
    }

    /** Applies {@code edit} to one file, buffer and disk, and answers whether it changed anything. */
    private static boolean rewrite(ProjectConfig config, ProjectState state, Path file,
                                   UnaryOperator<String> edit) {
        if (config == null || file == null) return false;
        boolean[] changed = {false};
        BotSources.forEach(config, state, (visited, source) -> {
            if (!visited.equals(file.toAbsolutePath().normalize())) return null;
            String rewritten = edit.apply(source);
            if (rewritten.equals(source)) return null;
            changed[0] = true;
            return rewritten;
        });
        return changed[0];
    }
}
