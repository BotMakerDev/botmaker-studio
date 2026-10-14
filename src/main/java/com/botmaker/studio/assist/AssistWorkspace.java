package com.botmaker.studio.assist;

import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.parser.guard.RefusalJournal;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.managed.ManagedConstants;
import com.botmaker.studio.suggestions.ProjectAnalyzer;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything an assistant's edits need to know about one of the bot's files, copied out of {@link ProjectState} once so
 * that a turn never reads the live editor state again.
 *
 * <p><b>Why a copy.</b> The canvas re-parses on every accepted edit, and the block registry it publishes lags
 * the source by one {@code runLater}. A turn that mixed the live registry with its own edits would hand
 * {@code CodeEditor} a node from one tree and a compilation unit from another. So each step of a turn builds
 * its own {@link ProjectState} from this record and its working copy of the source ({@link #stage}), and the
 * live state is touched exactly once, when the turn commits.
 *
 * @param index   the library index a staged {@link ProjectAnalyzer} reads, or {@code null} in a test
 * @param journal where a refused rewrite is recorded, or {@code null} for the cache directory
 * @param grammar   the value grammar a slot's value is read and written with
 * @param constants the bot's managed constants, which a slot may be set to by name ({@code Pictures.ORE})
 * @param others    the bot's other files as they were when the turn began, read only: an insert that names
 *                  another class of the bot ({@code Bot.runFlow(Sdk.class)}) looks for it among them
 */
public record AssistWorkspace(ProjectConfig config,
                              Path file,
                              List<String> classpath,
                              Path sourceRoot,
                              ProjectTemplate template,
                              StudioProjectSettings settings,
                              TypeSummaryManager index,
                              RefusalJournal journal,
                              ValueGrammar grammar,
                              List<ManagedConstants.Constant> constants,
                              List<ProjectFile> others) {

    public AssistWorkspace {
        classpath = classpath == null ? List.of() : List.copyOf(classpath);
        if (grammar == null) grammar = PluginHost.grammar();
        constants = constants == null ? List.of() : List.copyOf(constants);
        others = others == null ? List.of() : List.copyOf(others);
    }

    /** A workspace that knows none of the bot's other files. */
    public AssistWorkspace(ProjectConfig config, Path file, List<String> classpath, Path sourceRoot,
                           ProjectTemplate template, StudioProjectSettings settings, TypeSummaryManager index,
                           RefusalJournal journal, ValueGrammar grammar, List<ManagedConstants.Constant> constants) {
        this(config, file, classpath, sourceRoot, template, settings, index, journal, grammar, constants, List.of());
    }

    /** {@code file} of the open project. FX thread, as {@link ProjectState} is. */
    public static AssistWorkspace of(ProjectConfig config, ProjectState state, TypeSummaryManager index, Path file) {
        // Copied, not shared: the live files change as the canvas re-parses.
        List<ProjectFile> others = state.getAllFiles().stream()
                .filter(each -> !each.getPath().equals(file))
                .map(each -> new ProjectFile(each.getPath(), each.getContent()))
                .toList();
        return new AssistWorkspace(config, file, state.getResolvedClasspath(), state.getSourcePath(),
                state.getTemplate(), state.getSettings(), index, null, PluginHost.grammar(),
                ManagedConstants.scan(config, state), others);
    }

    /** The constants as the grammar reads them. */
    ManagedConstants.Lookup lookup() {
        return new ManagedConstants.Lookup(constants, grammar);
    }

    /** A fresh {@link ProjectState} holding {@code source} as this workspace's file — one step of a turn. */
    ProjectState stage(String source) {
        ProjectState staged = new ProjectState();
        others.forEach(each -> staged.addFile(new ProjectFile(each.getPath(), each.getContent())));
        staged.addFile(new ProjectFile(file, source));
        staged.setActiveFile(file);
        staged.setSourcePath(sourceRoot);
        staged.setResolvedClasspath(classpath);
        staged.setTemplate(template);
        if (settings != null) staged.setSettings(settings);
        staged.setCurrentCode(source);
        return staged;
    }

    /** The analyzer a staged edit seeds its arguments with. */
    ProjectAnalyzer analyzer(ProjectState staged) {
        return new ProjectAnalyzer(index, staged);
    }
}
