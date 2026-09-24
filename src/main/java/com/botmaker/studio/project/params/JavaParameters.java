package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.plugin.api.params.Param;
import com.botmaker.plugin.api.value.Visibility;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectWrites;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.services.BotSources;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The project's parameters: {@code @Param} fields, read off the bot's own sources and written back to them.
 *
 * <p><b>The declaration is the Java</b> (2026-09-17). Until then a user parameter was a row in a plugin's
 * JSON file that a bot read back by name, so a typo compiled and answered the type's fallback and the
 * declaration lived where the bot's author could not see it.
 *
 * <p><b>And it is the only source of a row</b> (2026-09-22). A second one stood beside it for five days:
 * {@code ParameterSurface} merged these fields with the rows a plugin declared through
 * {@code StudioPlugin.parameterRows}, which is why a section had an id ({@code java:<Class>} or a plugin's
 * group) rather than simply being a class. Nothing ever declared a plugin row — basics'
 * {@code ParameterStore.declare} had no caller anywhere — so that half read a pre-2026-09-17 project's JSON
 * and nothing else, and it is deleted with the contract surface under it. **A plugin that wants a row of its
 * own puts a {@code @Param} field in the file it ships**, and {@link BotSources#scan} finds it here with no
 * special case: {@code plugins/sdk/Sdk.java} is one of the bot's sources.
 *
 * <p><b>A section is a class, and the handle is the pair {@code (className, fieldName)}.</b> Two classes may
 * each declare a {@code timeout}, which javac already allows and already keeps apart, so nothing here is
 * keyed by a name alone.
 *
 * <p><b>Buffers before files, both written.</b> Everything here goes through {@link BotSources}, which is
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

    // ---- reading ----------------------------------------------------------------------------------------

    /** Every {@code @Param} field the bot declares, file by file, in the order the walk visits them. */
    public static List<JavaParameter> scan(ProjectConfig config, ProjectState state) {
        return scan(config, state, PluginHost.grammar());
    }

    /**
     * The same, against a given grammar — the seam a test uses, and the one place the vocabulary enters.
     *
     * <p>Every edit below takes one too, for a reason worth stating: the grammar is what turns a value into
     * the Java a field is initialised with, so a window and a test that disagreed about it would write two
     * different files from the same click.
     */
    public static List<JavaParameter> scan(ProjectConfig config, ProjectState state, ValueGrammar grammar) {
        if (config == null) return List.of();
        // Two walks rather than one, and cheap for the same reason nothing here is cached: a field may be
        // typed with a record declared in a file the parameter walk has not reached yet, so what the bot
        // declares has to be known in full before the first field is read.
        BotRecords records = BotRecords.scan(config, state, grammar);
        List<JavaParameter> out = new ArrayList<>();
        BotParser parser = BotParser.of(state);
        BotSources.scan(config, state, (file, source) ->
                out.addAll(JavaParameterSource.read(file, source, grammar, records, parser)));
        return List.copyOf(out);
    }

    /**
     * The classes that declare at least one parameter, in the order they were found — the window's sections.
     *
     * <p>A class with no fields left still gets its section for as long as the window is open; that is the
     * window's doing, not this method's. What is listed here is what the project currently declares.
     */
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

    /**
     * Every category the window may file a parameter under: every string the bot's own fields actually use.
     *
     * <p>A {@code @Param}'s category is free text — the six the SDK used to declare were a vocabulary, and a
     * bot's author names their own — so the only way to know one exists is that something is filed under it.
     */
    public static List<String> categories(ProjectConfig config, ProjectState state) {
        return categories(config, state, PluginHost.grammar());
    }

    /** The same, against a given grammar. */
    public static List<String> categories(ProjectConfig config, ProjectState state, ValueGrammar grammar) {
        Set<String> out = new LinkedHashSet<>();
        for (JavaParameter parameter : scan(config, state, grammar)) {
            if (!parameter.row().category().isBlank()) out.add(parameter.row().category());
        }
        return List.copyOf(out);
    }

    // ---- changing ---------------------------------------------------------------------------------------
    //
    // Each one answers the row as it reads back, or empty when nothing was written. Empty is an ordinary
    // outcome — a field somebody else renamed while the window was open, a value the type cannot spell —
    // and the window says so rather than failing.

    /**
     * Sets a field's value and answers the row as it now stands.
     *
     * <p>The initialiser is re-read from the source afterwards rather than assumed, for the same reason a
     * plugin's answer used to be rendered rather than the edit: what the file says is what the bot runs.
     */
    public static Optional<ParameterRow> setValue(ProjectConfig config, ProjectState state,
                                                  JavaParameter parameter, String value) {
        return setValue(config, state, parameter, value, List.of(), PluginHost.grammar());
    }

    /** The same, adding the imports the value's Java names — what an editor hands back beside it. */
    public static Optional<ParameterRow> setValue(ProjectConfig config, ProjectState state,
                                                  JavaParameter parameter, String value, List<String> imports) {
        return setValue(config, state, parameter, value, imports, PluginHost.grammar());
    }

    /** The same, against a given grammar. */
    public static Optional<ParameterRow> setValue(ProjectConfig config, ProjectState state,
                                                  JavaParameter parameter, String value, List<String> imports,
                                                  ValueGrammar grammar) {
        if (parameter == null || !parameter.editable()) return Optional.empty();
        writeValue(config, state, parameter, value, imports);
        return reread(config, state, parameter.className(), parameter.name(), grammar);
    }

    /**
     * Applies {@code wanted} to the field {@code parameter} names — name, type, category, note, visibility,
     * bounds, choices and value, each through the edit that owns it.
     *
     * <p><b>Only what differs is written, and the order matters.</b> The name first, because everything
     * after it is found by name; the type next, because retyping resets the value; the annotation members
     * together, because they are one annotation; the value last, because everything before it changes what a
     * value may be.
     */
    public static Optional<ParameterRow> declare(ProjectConfig config, ProjectState state,
                                                 JavaParameter parameter, ParameterRow wanted,
                                                 Type wantedForm) {
        return declare(config, state, parameter, wanted, wantedForm, PluginHost.grammar());
    }

    /**
     * The same, against a given grammar. {@code wantedForm} is the type the field should have, which the
     * row cannot say for itself — it carries the type's written name, not its tree.
     */
    public static Optional<ParameterRow> declare(ProjectConfig config, ProjectState state,
                                                 JavaParameter parameter, ParameterRow wanted,
                                                 Type wantedForm, ValueGrammar grammar) {
        if (parameter == null || wanted == null) return Optional.empty();
        String className = parameter.className();
        ParameterRow before = parameter.row();
        String name = before.name();

        if (!wanted.name().equals(name)) {
            if (!rename(config, state, parameter, wanted.name())) return Optional.empty();
            name = wanted.name();
        }
        JavaParameter held = find(config, state, className, name, grammar).orElse(null);
        if (held == null) return Optional.empty();

        if (wantedForm != null && !wantedForm.equals(parameter.form())) {
            retype(config, state, held, wantedForm, grammar);
            held = find(config, state, className, name, grammar).orElse(null);
            if (held == null) return Optional.empty();
        }

        Map<String, String> members = new LinkedHashMap<>();
        if (!wanted.category().equals(before.category())) members.put("category", wanted.category());
        if (!wanted.description().equals(before.description())) {
            members.put("description", wanted.description());
        }
        if (wanted.visibility() != before.visibility()) {
            // The annotation spells a visibility as the plugin's own id string — the contract's enum is off a
            // bot's classpath — and EDITOR is the default, so the editor-only case removes the member rather
            // than writing the default down.
            members.put("visibility", wanted.visibility() == Visibility.PUBLIC
                    ? Visibility.PUBLIC.id() : "");
        }
        // An infinite bound is the annotation's default, so it removes the member rather than writing
        // Double.NEGATIVE_INFINITY down.
        if (Double.compare(wanted.min(), before.min()) != 0) members.put("min", bound(wanted.min()));
        if (Double.compare(wanted.max(), before.max()) != 0) members.put("max", bound(wanted.max()));
        if (!members.isEmpty()) {
            setMembers(config, state, held, members);
            held = find(config, state, className, name, grammar).orElse(null);
            if (held == null) return Optional.empty();
        }

        if (!wanted.options().equals(before.options())) {
            setOptions(config, state, held, wanted.options());
            held = find(config, state, className, name, grammar).orElse(null);
            if (held == null) return Optional.empty();
        }

        if (!wanted.value().equals(before.value()) && held.editable()) {
            writeValue(config, state, held, wanted.value(), List.of());
        }
        return reread(config, state, className, name, grammar);
    }

    /** A bound as the literal {@code @Param} takes — {@code 0}, {@code 2.5} — or {@code ""} for none. */
    static String bound(double value) {
        if (Double.isInfinite(value) || Double.isNaN(value)) return "";
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return Long.toString((long) value);
        return Double.toString(value);
    }

    /**
     * Declares a new field in {@code className}, creating that class when the project has none.
     *
     * <p>Answers the row as it reads back, or empty when nothing was written — a name already taken, a type
     * whose default cannot be spelled as Java, or a class that could not be created.
     */
    public static Optional<ParameterRow> add(ProjectConfig config, ProjectState state, String className,
                                             String name, Type form, String value,
                                             String category, String description) {
        return add(config, state, className, name, form, value, category, description,
                PluginHost.grammar());
    }

    /** The same, against a given grammar. */
    public static Optional<ParameterRow> add(ProjectConfig config, ProjectState state, String className,
                                             String name, Type form, String value,
                                             String category, String description, ValueGrammar grammar) {
        if (config == null || className == null || className.isBlank()) return Optional.empty();
        if (find(config, state, className, name, grammar).isPresent()) return Optional.empty();
        if (!classes(config, state).contains(className) && !createClass(config, className)) {
            return Optional.empty();
        }
        rewriteAll(config, state, source -> JavaParameterEdits.add(
                source, grammar, className, name, form, value, category, description));
        return reread(config, state, className, name, grammar);
    }

    /**
     * Removes a parameter's declaration. Its <em>uses</em> are left alone.
     *
     * <p>Deliberately: what a use should become is a judgement — a literal default plus a review mark — and
     * a removal whose uses were silently rewritten is a bot that still compiles and behaves differently.
     * The window makes that call and tells the user what it did.
     */
    public static boolean remove(ProjectConfig config, ProjectState state, JavaParameter parameter) {
        if (parameter == null) return false;
        return rewrite(config, state, parameter.file(),
                source -> JavaParameterEdits.remove(source, parameter.className(), parameter.name()));
    }

    /**
     * Writes an empty {@code @Param} holder class into the bot's package, and answers whether it is there.
     *
     * <p>A file appearing in a project is a bigger event than a field appearing in a file, so this is called
     * only when a person asks for a parameter and there is nowhere to put it. An existing file is never
     * overwritten — it is somebody's work, and a class that exists is a class a field can go into. The
     * write goes through {@link ProjectWrites}, which takes the history snapshot that makes a file the user
     * did not type undoable.
     */
    public static boolean createClass(ProjectConfig config, String className) {
        Path file = config.mainPackageDir().resolve(className + ".java");
        String source = """
                package %s;

                import %s;

                /**
                 * The bot's settings. Each field is one row of the Parameters window, and the bot reads it by
                 * name — <code>%s.restBetween</code> — so a misspelling is a compile error and the type is the
                 * type.
                 */
                public final class %s {

                    private %s() {}
                }
                """.formatted(config.mainPackage(), Param.class.getCanonicalName(), className,
                className, className);
        return ProjectWrites.create(config, file, source, "Create " + className);
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

    // ---- the single-field edits ---------------------------------------------------------------------
    //
    // Private, because each one leaves the project in a state only declare() knows is finished: a retype
    // resets the value, an annotation rewrite has to be re-read before the next edit finds the field. Each
    // answers whether anything changed.

    /** Replaces a parameter's value with {@code initializer}, which is already the type's own Java. */
    private static boolean writeValue(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                      String initializer, List<String> imports) {
        return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.setValue(
                source, parameter.className(), parameter.name(), initializer, imports));
    }

    /**
     * Renames a parameter and repoints every reference to it, in every file.
     *
     * <p>Project-wide because a bot reads {@code Parameters.restBetween} wherever it likes: a rename that
     * touched only the declaring file would leave the bot not compiling, which is the one outcome an editor
     * must never produce from a rename.
     */
    private static boolean rename(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                  String newName) {
        return rewriteAll(config, state, source ->
                JavaParameterEdits.rename(source, parameter.className(), parameter.name(), newName));
    }

    /** Changes a parameter's type, resetting its value to that type's default. */
    private static boolean retype(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                  Type form, ValueGrammar grammar) {
        return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.retype(
                source, grammar, parameter.className(), parameter.name(), form));
    }

    /** Sets or clears {@code @Param} members — a blank value removes the member. */
    private static boolean setMembers(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                      Map<String, String> members) {
        return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.setMembers(
                source, parameter.className(), parameter.name(), members));
    }

    /** Sets or clears {@code @Param(options = …)}. */
    private static boolean setOptions(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                      List<String> options) {
        return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.setOptions(
                source, parameter.className(), parameter.name(), options));
    }

    // ---- plumbing ---------------------------------------------------------------------------------------

    /** The field called {@code name} in {@code className}, as the source reads <em>now</em>. */
    public static Optional<JavaParameter> find(ProjectConfig config, ProjectState state, String className,
                                               String name, ValueGrammar grammar) {
        for (JavaParameter parameter : scan(config, state, grammar)) {
            if (parameter.is(className, name)) return Optional.of(parameter);
        }
        return Optional.empty();
    }

    private static Optional<ParameterRow> reread(ProjectConfig config, ProjectState state, String className,
                                                 String name, ValueGrammar grammar) {
        return find(config, state, className, name, grammar).map(JavaParameter::row);
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
