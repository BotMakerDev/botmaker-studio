package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.ParameterEdit;
import com.botmaker.plugin.api.ParameterGroup;
import com.botmaker.plugin.api.ParameterRow;
import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueForm;
import com.botmaker.plugin.api.value.Visibility;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The project's parameters as <b>one list</b>: the bot's own {@code @Param} fields and the rows the loaded
 * plugins declare, in one shape, with one way to change each of them.
 *
 * <p><b>Two owners, and the difference is deliberate.</b> A {@code @Param} field is the user's — the host
 * reads it off the syntax tree and writes it back there, so adding, renaming, retyping, refiling and removing
 * are all this class's business. A plugin's row is the plugin's — an activity's enable flag, a capture target
 * — and the only thing a window may do to one is change its <em>value</em>
 * ({@code StudioPlugin.parameterEdited}). Declaring one from outside was a contract call until 2026-09-17 and
 * is not any more: a plugin declares its own rows in its own code, where a wire form buys nothing.
 *
 * <p><b>A section is a class or a plugin group, and its id says which.</b> A Java section's id is
 * {@code java:<ClassName>}; anything else is a plugin's group id, and {@code ""} is the default plugin's.
 * Two plugins may both offer a {@code timeout} and a bot may declare one beside them, so the handle
 * everything here is keyed by is the <em>pair</em> {@code (section, name)} and never the name alone.
 *
 * <p><b>Nothing is cached</b>, for the reason {@link JavaParameters} states: the source file and the plugin's
 * own file are the truth, they are cheap to read, and a second answer to "what does this bot declare" is the
 * failure the old arrangement had.
 */
public final class ParameterSurface {

    /** What a Java section's id starts with — the one string that tells the two kinds of section apart. */
    public static final String JAVA_PREFIX = "java:";

    private ParameterSurface() {}

    /**
     * One row and where it comes from.
     *
     * @param group the section id — {@code java:<ClassName>} for a field, else the plugin's group id
     * @param row   the row itself, the same shape whoever owns it
     * @param java  the field behind it, or {@code null} for a plugin's row
     */
    public record Entry(String group, ParameterRow row, JavaParameter java) {

        public boolean isJava() {
            return java != null;
        }

        /** Whether the value cell may write. A plugin's row always may; a field may when it can be read. */
        public boolean editable() {
            return java == null || java.editable();
        }

        /** Why not, for the cell's tooltip — blank when it is editable. */
        public String note() {
            return java == null ? "" : java.note();
        }

        /** True when this is the row called {@code name} in {@code group}. */
        public boolean is(String otherGroup, String name) {
            return group.equals(otherGroup) && row.name().equals(name);
        }
    }

    /** The section id of the class {@code className} — {@code java:Parameters}. */
    public static String groupOf(String className) {
        return JAVA_PREFIX + className;
    }

    /** The class a Java section id names, or {@code ""} when the id is a plugin's. */
    public static String classOf(String group) {
        return group != null && group.startsWith(JAVA_PREFIX) ? group.substring(JAVA_PREFIX.length()) : "";
    }

    public static boolean isJavaGroup(String group) {
        return !classOf(group).isEmpty();
    }

    // ---- reading ----------------------------------------------------------------------------------------

    /**
     * Every parameter of the project: the bot's fields first, class by class, then each plugin's rows.
     *
     * <p>The bot's own come first because they are the ones a person opened this window to change; a
     * plugin's section is the settings of a thing they installed.
     */
    public static List<Entry> rows(ProjectConfig config, ProjectState state, String sdkPin) {
        return rows(config, state, sdkPin, PluginHost.valueTypes());
    }

    /**
     * The same, against a given catalog — the seam a test uses, and the one place the vocabulary enters.
     *
     * <p>Every edit below takes one too, for a reason worth stating: the catalog is what turns a value into
     * the Java a field is initialised with, so a window and a test that disagreed about it would write two
     * different files from the same click.
     */
    public static List<Entry> rows(ProjectConfig config, ProjectState state, String sdkPin,
                                   ValueCatalog catalog) {
        List<Entry> out = new ArrayList<>();
        for (JavaParameter parameter : JavaParameters.scan(config, state, catalog)) {
            out.add(new Entry(groupOf(parameter.className()), parameter.row(), parameter));
        }
        for (ParameterGroup group : PluginHost.parameterGroups(sdkPin)) {
            for (ParameterRow row : PluginHost.parameterRows(group.id())) {
                out.add(new Entry(group.id(), row, null));
            }
        }
        return List.copyOf(out);
    }

    /**
     * The sections to draw, in the order of {@link #rows}: one per class that declares a field, then one per
     * plugin group, then one per group the project's rows name that no loaded plugin claims.
     *
     * <p>A class with no fields left still gets its section for as long as the window is open — that is the
     * caller's doing, not this method's: what is listed here is what the project currently declares.
     */
    public static List<ParameterGroup> sections(ProjectConfig config, ProjectState state, String sdkPin) {
        List<ParameterGroup> groups = new ArrayList<>();
        for (String className : JavaParameters.classes(config, state)) {
            groups.add(ParameterGroup.of(groupOf(className), className));
        }
        groups.addAll(PluginHost.parameterGroups(sdkPin));
        Set<String> ids = new LinkedHashSet<>(groups.stream().map(ParameterGroup::id).toList());
        for (Entry entry : rows(config, state, sdkPin)) {
            if (ids.add(entry.group())) {
                groups.add(ParameterGroup.of(entry.group(),
                        entry.group().isEmpty() ? "Parameters" : entry.group()));
            }
        }
        return List.copyOf(groups);
    }

    /**
     * Every category the window may file a parameter under: the ones the plugins declare, plus every string
     * the bot's own fields actually use.
     *
     * <p>A {@code @Param}'s category is free text — the six the SDK used to declare were a vocabulary, and a
     * bot's author names their own — so the only way to know one exists is that something is filed under it.
     */
    public static List<String> categories(ProjectConfig config, ProjectState state, String sdkPin) {
        return categories(config, state, sdkPin, PluginHost.valueTypes());
    }

    /** The same, against a given catalog. */
    public static List<String> categories(ProjectConfig config, ProjectState state, String sdkPin,
                                          ValueCatalog catalog) {
        Set<String> out = new LinkedHashSet<>();
        for (ParameterGroup group : PluginHost.parameterGroups(sdkPin)) out.addAll(group.categories());
        for (Entry entry : rows(config, state, sdkPin, catalog)) {
            if (!entry.row().category().isBlank()) out.add(entry.row().category());
        }
        return List.copyOf(out);
    }

    // ---- changing ---------------------------------------------------------------------------------------

    /**
     * Sets a row's value and answers the row as it now stands, or empty when nothing was stored.
     *
     * <p>A plugin's row goes back to its plugin, which may store something other than what was typed — a
     * clamp, a canonical spelling, a value pruned to the choices still on offer — and that is what comes
     * back. A field is rewritten in place, and its initialiser is re-read afterwards for the same reason.
     */
    public static Optional<ParameterRow> setValue(ProjectConfig config, ProjectState state, Entry entry,
                                                  String value) {
        return setValue(config, state, entry, value, PluginHost.valueTypes());
    }

    /** The same, against a given catalog. */
    public static Optional<ParameterRow> setValue(ProjectConfig config, ProjectState state, Entry entry,
                                                  String value, ValueCatalog catalog) {
        if (entry == null) return Optional.empty();
        if (!entry.isJava()) {
            return PluginHost.parameterEdited(new ParameterEdit(entry.group(), entry.row().name(), value));
        }
        if (!entry.editable()) return Optional.empty();
        JavaParameters.setValue(config, state, entry.java(), value);
        return reread(config, state, entry.java().className(), entry.row().name(), catalog);
    }

    /**
     * Applies {@code wanted} to the field {@code entry} names — name, type, category, note, visibility,
     * bounds, choices and value, each through the edit that owns it.
     *
     * <p><b>Only what differs is written, and the order matters.</b> The name first, because everything
     * after it is found by name; the type next, because retyping resets the value; the annotation members
     * together, because they are one annotation; the value last, because everything before it changes what a
     * value may be. A plugin's row is refused outright — the host does not declare one any more.
     */
    public static Optional<ParameterRow> declare(ProjectConfig config, ProjectState state, Entry entry,
                                                 ParameterRow wanted) {
        return declare(config, state, entry, wanted, PluginHost.valueTypes());
    }

    /** The same, against a given catalog. */
    public static Optional<ParameterRow> declare(ProjectConfig config, ProjectState state, Entry entry,
                                                 ParameterRow wanted, ValueCatalog catalog) {
        if (entry == null || wanted == null || !entry.isJava()) return Optional.empty();
        String className = entry.java().className();
        ParameterRow before = entry.row();
        String name = before.name();

        if (!wanted.name().equals(name)) {
            if (!JavaParameters.rename(config, state, entry.java(), wanted.name())) return Optional.empty();
            name = wanted.name();
        }
        JavaParameter held = find(config, state, className, name, catalog).orElse(null);
        if (held == null) return Optional.empty();

        if (!wanted.form().equals(before.form())) {
            JavaParameters.retype(config, state, held, wanted.form(), catalog);
            held = find(config, state, className, name, catalog).orElse(null);
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
        // Null-safe, because a Range component genuinely is null for "no minimum" — the record keeps the
        // absence rather than spelling it as an empty string, and an unbounded row is the ordinary case.
        if (!text(wanted.bounds().min()).equals(text(before.bounds().min()))) {
            members.put("min", text(wanted.bounds().min()));
        }
        if (!text(wanted.bounds().max()).equals(text(before.bounds().max()))) {
            members.put("max", text(wanted.bounds().max()));
        }
        if (!members.isEmpty()) {
            JavaParameters.setMembers(config, state, held, members);
            held = find(config, state, className, name, catalog).orElse(null);
            if (held == null) return Optional.empty();
        }

        if (!wanted.options().equals(before.options())) {
            JavaParameters.setOptions(config, state, held, wanted.options());
            held = find(config, state, className, name, catalog).orElse(null);
            if (held == null) return Optional.empty();
        }

        if (!wanted.value().equals(before.value()) && held.editable()) {
            JavaParameters.setValue(config, state, held, wanted.value());
        }
        return reread(config, state, className, name, catalog);
    }

    /** Removes a field's declaration. A plugin's row is refused: only its plugin may delete one. */
    public static boolean remove(ProjectConfig config, ProjectState state, Entry entry) {
        if (entry == null || !entry.isJava()) return false;
        return JavaParameters.remove(config, state, entry.java());
    }

    /**
     * Declares a new field in {@code className}, creating that class when the project has none.
     *
     * <p>Answers the row as it reads back, or empty when nothing was written — a name already taken, a type
     * whose default cannot be spelled as Java, or a class that could not be created.
     */
    public static Optional<ParameterRow> add(ProjectConfig config, ProjectState state, String className,
                                             String name, ValueForm form, String value,
                                             String category, String description) {
        return add(config, state, className, name, form, value, category, description,
                PluginHost.valueTypes());
    }

    /** The same, against a given catalog. */
    public static Optional<ParameterRow> add(ProjectConfig config, ProjectState state, String className,
                                             String name, ValueForm form, String value,
                                             String category, String description, ValueCatalog catalog) {
        if (config == null || className == null || className.isBlank()) return Optional.empty();
        if (find(config, state, className, name, catalog).isPresent()) return Optional.empty();
        if (!JavaParameters.classes(config, state).contains(className) && !createClass(config, className)) {
            return Optional.empty();
        }
        JavaParameters.add(config, state, className, name, form, value, category, description);
        return reread(config, state, className, name, catalog);
    }

    /**
     * Writes an empty {@code @Param} holder class into the bot's package, and answers whether it is there.
     *
     * <p>A file appearing in a project is a bigger event than a field appearing in a file, so this is called
     * only when a person asks for a parameter and there is nowhere to put it. An existing file is never
     * overwritten — it is somebody's work, and a class that exists is a class a field can go into.
     */
    public static boolean createClass(ProjectConfig config, String className) {
        Path file = config.mainPackageDir().resolve(className + ".java");
        if (Files.isRegularFile(file)) return true;
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
                """.formatted(config.mainPackage(), JavaParameterSource.ANNOTATION_FQN, className,
                className, className);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
            return true;
        } catch (IOException notWritten) {
            throw new UncheckedIOException("could not create " + file, notWritten);
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------------------

    /** A possibly-absent string as text — {@code null} and {@code ""} are the same answer here. */
    private static String text(String value) {
        return value == null ? "" : value;
    }

    /** The field called {@code name} in {@code className}, as the source reads <em>now</em>. */
    private static Optional<JavaParameter> find(ProjectConfig config, ProjectState state, String className,
                                                String name, ValueCatalog catalog) {
        for (JavaParameter parameter : JavaParameters.scan(config, state, catalog)) {
            if (parameter.className().equals(className) && parameter.name().equals(name)) {
                return Optional.of(parameter);
            }
        }
        return Optional.empty();
    }

    private static Optional<ParameterRow> reread(ProjectConfig config, ProjectState state, String className,
                                                 String name, ValueCatalog catalog) {
        return find(config, state, className, name, catalog).map(JavaParameter::row);
    }
}
