package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.StudioProjectSettings.WorkspaceLayout;
import com.botmaker.studio.services.ProjectSettingsService;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Remembers how the main window was arranged: the two split-pane dividers and the open bottom tab.
 *
 * <p>The window's own geometry already survives a restart ({@code ProjectPreferences.WindowState}); this is
 * the missing half. It is saved <b>per project</b> and read back at open, because how much of the window the
 * file tree or the console deserves follows from the bot: one being read wants a wide tree, one being
 * debugged wants a tall terminal.
 *
 * <p>Written once, at teardown, rather than on every drag. A divider's position changes continuously while
 * it is dragged and again whenever the window is resized, so listening would mean rewriting a file in the
 * user's <em>versioned</em> project on every frame of a drag; {@link #save()} on the way out costs one write
 * per session and picks up exactly the same final positions.
 */
final class WorkspaceLayoutStore {

    private final ProjectSettingsService settings;
    private final SplitPane explorerSplit;
    private final SplitPane bottomSplit;
    private final TabPane bottomTabPane;
    private final Map<BottomTab, Tab> tabs;
    private final CenterTabs centerTabs;
    private final Path projectDir;

    WorkspaceLayoutStore(ProjectSettingsService settings, SplitPane explorerSplit, SplitPane bottomSplit,
                         TabPane bottomTabPane, Map<BottomTab, Tab> tabs, CenterTabs centerTabs, Path projectDir) {
        this.settings = settings;
        this.explorerSplit = explorerSplit;
        this.bottomSplit = bottomSplit;
        this.bottomTabPane = bottomTabPane;
        this.tabs = tabs;
        this.centerTabs = centerTabs;
        this.projectDir = projectDir.toAbsolutePath().normalize();
    }

    /**
     * Applies the remembered layout over the defaults each pane was built with. A project that has never
     * been arranged, an unusable divider and a tab this Studio doesn't have all leave the default in place.
     */
    void restore() {
        WorkspaceLayout saved = settings.current().workspaceLayout();
        if (saved == null) return;

        // The Java files left open, relative to the project so a moved project keeps them (2026-10-13); a path
        // edited in that leads outside the project, or to anything but Java, is not opened.
        if (saved.openFiles() != null) {
            centerTabs.restore(saved.openFiles().stream().map(this::inProject).filter(Objects::nonNull).toList(),
                    saved.shownFile() == null ? null : inProject(saved.shownFile()));
        }

        explorerSplit.setDividerPositions(saved.explorerDividerOr(explorerSplit.getDividerPositions()[0]));
        bottomSplit.setDividerPositions(saved.bottomDividerOr(bottomSplit.getDividerPositions()[0]));

        Tab tab = tabs.get(BottomTab.named(saved.bottomTab()));
        if (tab != null) bottomTabPane.getSelectionModel().select(tab);
    }

    /** Writes the layout as it stands. Called from {@code UIManager.dispose()}; idempotent and best-effort. */
    void save() {
        settings.saveNow(settings.current().withWorkspaceLayout(snapshot()));
    }

    /** The current arrangement. Divider positions are sanitised by {@link WorkspaceLayout} itself. */
    private WorkspaceLayout snapshot() {
        Tab selected = bottomTabPane.getSelectionModel().getSelectedItem();
        String tabKey = tabs.entrySet().stream()
                .filter(e -> e.getValue() == selected)
                .map(e -> e.getKey().key())
                .findFirst().orElse(null);
        // The Versions tab saves its own divider as it moves; keep whatever it last wrote.
        WorkspaceLayout saved = settings.current().workspaceLayout();
        Path shown = centerTabs.shownJavaFile();
        return new WorkspaceLayout(dividerOf(explorerSplit), dividerOf(bottomSplit), tabKey,
                saved == null ? null : saved.publishDivider(),
                centerTabs.javaFiles().stream().filter(file -> file.startsWith(projectDir)).map(this::relative)
                        .toList(),
                shown == null ? null : relative(shown));
    }

    /** A saved {@code relative} path resolved in the project, or null when it leaves it or is not a Java file. */
    private Path inProject(String relative) {
        try {
            Path at = projectDir.resolve(relative).normalize();
            return at.startsWith(projectDir) && at.toString().endsWith(".java") ? at : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** {@code file} as the settings keep it: relative to the project, with forward slashes. */
    private String relative(Path file) {
        return projectDir.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** A split's first divider, or {@code null} if it has none (nothing was ever laid out). */
    private static Double dividerOf(SplitPane split) {
        double[] positions = split.getDividerPositions();
        return positions.length == 0 ? null : positions[0];
    }
}
