package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.BotSources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The bot's own user parameters: {@code @Param} fields, read off its sources and written back to them.
 *
 * <p><b>The declaration is the Java</b> (2026-09-17). Until then a user parameter was a row in a plugin's
 * JSON file that a bot read back by name, so a typo compiled and answered the type's fallback and the
 * declaration lived where the bot's author could not see it. A plugin's JSON keeps what is genuinely the
 * plugin's — a flow, capture targets, an activity's enable flag — and those still arrive through
 * {@code PluginHost.parameterRows}.
 *
 * <p><b>Buffers before files, both written.</b> Everything here goes through {@code BotSources}, which is
 * the walk that reads a file's open editor buffer where there is one and writes a rewrite to both copies.
 * A scan that read the disk would miss the user's last ten minutes; a write that touched only the disk
 * would be undone by the next save.
 *
 * <p><b>Nothing is cached.</b> The source file is the truth and it is cheap to re-read — a bot has a
 * handful of files and a parameters window is opened by a person, not by a loop. A cache here would be a
 * second answer to "what does this bot declare", which is the failure the JSON arrangement had.
 */
public final class JavaParameters {

    /** The class a new parameter goes into when the project has no {@code @Param} class yet. */
    public static final String DEFAULT_CLASS = "Parameters";

    private JavaParameters() {}

    /** Every {@code @Param} field the bot declares, file by file, in the order the walk visits them. */
    public static List<JavaParameter> scan(ProjectConfig config, ProjectState state) {
        return scan(config, state, PluginHost.valueTypes());
    }

    /** The same, against a given catalog — the seam a test uses, and the one place the catalog enters. */
    public static List<JavaParameter> scan(ProjectConfig config, ProjectState state, ValueCatalog catalog) {
        if (config == null) return List.of();
        List<JavaParameter> out = new ArrayList<>();
        BotSources.scan(config, state, (file, source) ->
                out.addAll(JavaParameterSource.read(file, source, catalog)));
        return List.copyOf(out);
    }

    /** The classes that declare at least one parameter, in the order they were found. */
    public static List<String> classes(ProjectConfig config, ProjectState state) {
        Set<String> names = new LinkedHashSet<>();
        for (JavaParameter parameter : scan(config, state)) names.add(parameter.className());
        return List.copyOf(names);
    }

    /**
     * Whether {@code qualifier} is a class that declares parameters — whether {@code <qualifier>.<name>} in
     * the user's source is a reference to one at all.
     */
    public static boolean isQualifier(ProjectConfig config, ProjectState state, String qualifier) {
        return qualifier != null && !qualifier.isBlank() && classes(config, state).contains(qualifier);
    }

    // ---- edits ------------------------------------------------------------------------------------------
    //
    // Each one answers whether anything changed. False is an ordinary outcome — a field somebody else
    // renamed while the window was open, a value the type cannot spell — and the window says so rather than
    // failing.

    /** Replaces a parameter's value with {@code value}, as the type's own Java. */
    public static boolean setValue(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                   List<String> value) {
        ValueCatalog catalog = PluginHost.valueTypes();
        return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.setValue(
                source, catalog, parameter.className(), parameter.name(), parameter.row().type(), value));
    }

    /**
     * Renames a parameter and repoints every reference to it, in every file.
     *
     * <p>Project-wide because a bot reads {@code Parameters.restBetween} wherever it likes: a rename that
     * touched only the declaring file would leave the bot not compiling, which is the one outcome an editor
     * must never produce from a rename.
     */
    public static boolean rename(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                 String newName) {
        return rewriteAll(config, state, source ->
                JavaParameterEdits.rename(source, parameter.className(), parameter.name(), newName));
    }

    /** Changes a parameter's type, resetting its value to that type's default. */
    public static boolean retype(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                 ValueChoice choice) {
        ValueCatalog catalog = PluginHost.valueTypes();
        return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.retype(
                source, catalog, parameter.className(), parameter.name(), choice));
    }

    /** Sets or clears {@code @Param} members — a blank value removes the member. */
    public static boolean setMembers(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                     Map<String, String> members) {
        return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.setMembers(
                source, parameter.className(), parameter.name(), members));
    }

    /** Sets or clears {@code @Param(options = …)}. */
    public static boolean setOptions(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                     List<String> options) {
        return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.setOptions(
                source, parameter.className(), parameter.name(), options));
    }

    /**
     * Declares a new parameter in {@code className}, which must already exist.
     *
     * <p>Creating the class is not done here and is the window's call: a file appearing in a project is a
     * bigger event than a field appearing in a file, and the person should be the one who asks for it.
     */
    public static boolean add(ProjectConfig config, ProjectState state, String className, String fieldName,
                              ValueChoice choice, List<String> value, String category, String description) {
        ValueCatalog catalog = PluginHost.valueTypes();
        return rewriteAll(config, state, source -> JavaParameterEdits.add(
                source, catalog, className, fieldName, choice, value, category, description));
    }

    /**
     * Removes a parameter's declaration. Its <em>uses</em> are left alone.
     *
     * <p>Deliberately: what a use should become is a judgement — a literal default plus a review mark — and
     * a removal whose uses were silently rewritten is a bot that still compiles and behaves differently.
     * The window makes that call and tells the user what it did.
     */
    public static boolean remove(ProjectConfig config, ProjectState state, JavaParameter parameter) {
        return rewrite(config, state, parameter.file(),
                source -> JavaParameterEdits.remove(source, parameter.className(), parameter.name()));
    }

    /** Every place in the bot that names this parameter, for the window to show before a destructive edit. */
    public static List<Use> uses(ProjectConfig config, ProjectState state, JavaParameter parameter) {
        List<Use> uses = new ArrayList<>();
        String needle = parameter.qualified();
        BotSources.scan(config, state, (file, source) -> {
            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(needle)) uses.add(new Use(file, i + 1, lines[i].strip()));
            }
        });
        return List.copyOf(uses);
    }

    /** One line of the bot's source that names a parameter. */
    public record Use(Path file, int line, String text) {}

    // ---- plumbing ---------------------------------------------------------------------------------------

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

    /** Applies {@code edit} to every file — a rename, and an add whose target class could be anywhere. */
    private static boolean rewriteAll(ProjectConfig config, ProjectState state, UnaryOperator<String> edit) {
        if (config == null) return false;
        boolean[] changed = {false};
        BotSources.forEach(config, state, (visited, source) -> {
            String rewritten = edit.apply(source);
            if (rewritten.equals(source)) return null;
            changed[0] = true;
            return rewritten;
        });
        return changed[0];
    }
}
