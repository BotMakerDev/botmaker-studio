package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.ParameterGroup;
import com.botmaker.plugin.api.ParameterRow;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.params.JavaParameters;
import com.botmaker.studio.project.params.ParameterSurface;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;

import java.util.ArrayList;
import java.util.List;

/**
 * The project's parameters as the <em>code editor</em> needs them: which ones may fill a slot of a given
 * type, and what class a bot spells in front of one.
 *
 * <p><b>It asks the plugins, where {@code ProjectAnalyzer} used to read a file (2026-09-11).</b> The
 * expression menu and the variable picker answered out of {@code activities.json}, parsed by the host and
 * cached in {@code ProjectState} — one plugin's store, held in a copy that plugin's own writes could not
 * refresh, and unavailable to any second plugin. Every answer here comes from
 * {@link PluginHost#parameterGroups} and {@link PluginHost#parameterRows}, so a parameter reaches the menus
 * on the same terms whoever declared it.
 *
 * <p><b>Nothing is cached.</b> That is the parameter surface's own rule — the owner's file is the truth and
 * only the owner can read it — and it is why these are static functions over a {@link ProjectConfig} rather
 * than a service with state. The lists are small and a menu is built when it is opened.
 *
 * <p><b>Visibility is not consulted.</b> {@link ParameterRow#isPublic()} says who may change a value while
 * the bot is being <em>run</em>; this list is about what the bot's own code may read, which is every
 * parameter its plugin declares.
 */
public final class HostParameters {

    private HostParameters() {}

    /** One parameter and the class a bot spells in front of its name — {@code Parameters.REST}. */
    public record Parameter(String qualifier, ParameterRow row) {

        /** What a menu shows: the prose label when the author gave one, else the name and its type. */
        public String menuLabel() {
            return row.name().equals(row.displayLabel())
                    ? row.name() + " (" + row.form().sourceName() + ")"
                    : row.displayLabel() + " — " + row.name();
        }
    }

    /**
     * Every declared parameter — the bot's own {@code @Param} fields first, then each plugin's rows.
     *
     * <p>{@link ParameterSurface} is asked rather than the plugins directly, because since 2026-09-17 a user
     * parameter is a field in the bot's own Java: a menu that asked only the plugins would offer none of the
     * parameters the person actually wrote.
     */
    public static List<Parameter> all(ProjectConfig config, ProjectState state) {
        List<Parameter> out = new ArrayList<>();
        for (ParameterSurface.Entry entry : ParameterSurface.rows(config, state, pin(config))) {
            out.add(new Parameter(qualifierOf(entry, pin(config)), entry.row()));
        }
        return List.copyOf(out);
    }

    /** The class a bot spells in front of this row — the declaring class, or the group's generated one. */
    private static String qualifierOf(ParameterSurface.Entry entry, String sdkPin) {
        if (entry.isJava()) return entry.java().className();
        ParameterGroup group = PluginHost.parameterGroup(sdkPin, entry.group());
        return group == null ? "" : group.className();
    }

    /**
     * The parameters a slot of {@code requiredType} may be filled from, using
     * {@link ProjectAnalyzer#isCompatible} so the answer cannot differ from the one every other menu gives.
     */
    public static List<Parameter> compatibleWith(ProjectConfig config, ProjectState state,
                                                 ResolvedType requiredType) {
        return all(config, state).stream()
                .filter(p -> ProjectAnalyzer.isCompatible(ValueWire.resolvedType(p.row().form()), requiredType))
                .toList();
    }

    /**
     * Whether {@code qualifier} is the class one of the declared groups generates — that is, whether
     * {@code <qualifier>.<something>} in the user's source is a reference to a parameter at all.
     *
     * <p>It replaced a two-constant {@code VariableHolder} enum naming {@code Activities} and
     * {@code Parameters}. Those are one plugin's class names, so a second plugin's parameters could never
     * have been recognised in a slot; and a host that writes a plugin's class name down is the back door the
     * platform exists to close. A project with no plugins recognises nothing, which is correct — nothing has
     * declared a parameter, so no reference to one can be resolved.
     */
    public static boolean isQualifier(ProjectConfig config, ProjectState state, String qualifier) {
        if (qualifier == null || qualifier.isBlank()) return false;
        if (JavaParameters.isQualifier(config, state, qualifier)) return true;
        for (ParameterGroup group : PluginHost.parameterGroups(pin(config))) {
            if (group.className().equals(qualifier)) return true;
        }
        return false;
    }

    /**
     * The class to write in front of {@code name}, or {@code null} when no plugin declares a parameter by
     * that name.
     *
     * <p>Null rather than a guess: the only callers pass a name they got from {@link #all}, so an answer is
     * always there in practice, and inventing a qualifier for a name nobody claims would write a reference to
     * a class that does not exist.
     */
    public static String qualifierOf(ProjectConfig config, ProjectState state, String name) {
        if (name == null || name.isBlank()) return null;
        for (Parameter parameter : all(config, state)) {
            if (parameter.row().name().equals(name)) return parameter.qualifier();
        }
        return null;
    }

    /**
     * The version the project's pom pins, which every data surface is asked with, or {@code null} for a
     * project that names no SDK — an ordinary state since a blank project pins no plugin at all.
     */
    private static String pin(ProjectConfig config) {
        if (config == null) return null;
        try {
            return MavenService.readSdkVersion(config.projectPath()).orElse(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
