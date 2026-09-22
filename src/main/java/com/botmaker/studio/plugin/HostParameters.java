package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.studio.plugin.grammar.ValueForm;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.params.JavaParameter;
import com.botmaker.studio.project.params.JavaParameters;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;

import java.util.ArrayList;
import java.util.List;

/**
 * The project's parameters as the <em>code editor</em> needs them: which ones may fill a slot of a given
 * type, and what class a bot spells in front of one.
 *
 * <p><b>It reads the bot's own Java, where {@code ProjectAnalyzer} used to read a file (2026-09-11).</b> The
 * expression menu and the variable picker answered out of {@code activities.json}, parsed by the host and
 * cached in {@code ProjectState} — one plugin's store, held in a copy that plugin's own writes could not
 * refresh, and unavailable to any second plugin. It then asked the plugins, through a contract surface
 * nothing ever wrote to; since 2026-09-22 every answer comes from {@link JavaParameters}, which is the same
 * source the Parameters window draws.
 *
 * <p><b>Nothing is cached.</b> The source file is the truth and it is cheap to re-read, which is why these
 * are static functions over a {@link ProjectConfig} rather than a service with state. The lists are small
 * and a menu is built when it is opened.
 *
 * <p><b>Visibility is not consulted.</b> {@link ParameterRow#isPublic()} says who may change a value while
 * the bot is being <em>run</em>; this list is about what the bot's own code may read, which is every
 * parameter the bot declares.
 */
public final class HostParameters {

    private HostParameters() {}

    /** One parameter and the class a bot spells in front of its name — {@code Parameters.REST}. */
    public record Parameter(String qualifier, ParameterRow row, ValueForm form) {

        /** What a menu shows: the prose label when the author gave one, else the name and its type. */
        public String menuLabel() {
            return row.name().equals(row.displayLabel())
                    ? row.name() + " (" + row.typeName() + ")"
                    : row.displayLabel() + " — " + row.name();
        }
    }

    /** Every declared parameter — every {@code @Param} field the bot's own sources hold. */
    public static List<Parameter> all(ProjectConfig config, ProjectState state) {
        List<Parameter> out = new ArrayList<>();
        for (JavaParameter parameter : JavaParameters.scan(config, state)) {
            out.add(new Parameter(parameter.className(), parameter.row(), parameter.form()));
        }
        return List.copyOf(out);
    }

    /**
     * The parameters a slot of {@code requiredType} may be filled from, using
     * {@link ProjectAnalyzer#isCompatible} so the answer cannot differ from the one every other menu gives.
     */
    public static List<Parameter> compatibleWith(ProjectConfig config, ProjectState state,
                                                 ResolvedType requiredType) {
        return all(config, state).stream()
                .filter(p -> ProjectAnalyzer.isCompatible(ValueWire.resolvedType(p.form()), requiredType))
                .toList();
    }

    /**
     * Whether {@code qualifier} is a class that declares parameters — that is, whether
     * {@code <qualifier>.<something>} in the user's source is a reference to one at all.
     *
     * <p>It replaced a two-constant {@code VariableHolder} enum naming {@code Activities} and
     * {@code Parameters}: a host that writes one plugin's class names down is the back door the platform
     * exists to close. What answers it now is the bot's own source, so any class the bot declares a
     * {@code @Param} in is recognised — including one in a file a plugin shipped.
     */
    public static boolean isQualifier(ProjectConfig config, ProjectState state, String qualifier) {
        return JavaParameters.isQualifier(config, state, qualifier);
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

    // pin(ProjectConfig) stood here until 2026-09-22, reading the pom's SDK version because every parameter
    // data surface took it. There is no such surface any more: a parameter is a @Param field in the bot's
    // own Java, and what a class declares does not depend on which version of a plugin the pom names.
}
