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
import com.botmaker.studio.services.SdkSurfaceService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything an assistant's edits need to know about the open file, copied out of {@link ProjectState} once so
 * that a turn never reads the live editor state again.
 *
 * <p><b>Why a copy.</b> The canvas re-parses on every accepted edit, and the block registry it publishes lags
 * the source by one {@code runLater}. A turn that mixed the live registry with its own edits would hand
 * {@code CodeEditor} a node from one tree and a compilation unit from another. So each step of a turn builds
 * its own {@link ProjectState} from this record and its working copy of the source ({@link #stage}), and the
 * live state is touched exactly once, when the turn commits.
 *
 * @param index   the library index a staged {@link ProjectAnalyzer} reads, or {@code null} in a test
 * @param surface this project's palette curation, or {@code null} to offer everything, as a headless edit does
 * @param journal where a refused rewrite is recorded, or {@code null} for the cache directory
 * @param grammar the value grammar a slot's value is read and written with
 */
public record AssistWorkspace(ProjectConfig config,
                              Path file,
                              List<String> classpath,
                              Path sourceRoot,
                              ProjectTemplate template,
                              StudioProjectSettings settings,
                              TypeSummaryManager index,
                              SdkSurfaceService surface,
                              RefusalJournal journal,
                              ValueGrammar grammar) {

    public AssistWorkspace {
        classpath = classpath == null ? List.of() : List.copyOf(classpath);
        if (grammar == null) grammar = PluginHost.grammar();
    }

    /** The open project's active file. FX thread, as {@link ProjectState} is. */
    public static AssistWorkspace of(ProjectConfig config, ProjectState state, TypeSummaryManager index,
                                     SdkSurfaceService surface) {
        ProjectFile active = state.getActiveFile();
        return new AssistWorkspace(config, active == null ? null : active.getPath(), state.getResolvedClasspath(),
                state.getSourcePath(), state.getTemplate(), state.getSettings(), index, surface, null,
                PluginHost.grammar());
    }

    /** A fresh {@link ProjectState} holding {@code source} as this workspace's file — one step of a turn. */
    ProjectState stage(String source) {
        ProjectState staged = new ProjectState();
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
