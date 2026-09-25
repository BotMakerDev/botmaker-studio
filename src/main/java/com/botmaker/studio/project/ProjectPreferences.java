package com.botmaker.studio.project;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.botmaker.studio.config.Constants.PROJECTS_ROOT;

/**
 * User preferences for project management (last opened, recent projects).
 * Persisted as JSON in the projects root directory.
 * Renamed from the old ProjectConfig to avoid clash with the new ProjectConfig.
 *
 * <p>A project is remembered by its <b>directory</b> since 2026-09-18, because one may live outside the
 * default root. The name is still written beside it: a file this build wrote stays readable by an older
 * Studio, which ignores {@code path} and {@code lastOpenedPath} and finds a default-root project by name as it
 * always did. A file an older Studio wrote has no path, and a missing path means the default root.
 */
public class ProjectPreferences {

    private static final Path CONFIG_FILE = PROJECTS_ROOT.resolve("botmaker-config.json");
    /** How many projects the MRU keeps. */
    private static final int MAX_RECENT_PROJECTS = 10;
    /** Same depth as the project MRU — long enough to cover a working set, short enough to stay scannable. */
    private static final int MAX_RECENT_LAUNCH_TARGETS = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private String lastOpenedProject;
    /** The last project's directory; {@code null} in a file an older Studio wrote. */
    private String lastOpenedPath;
    private List<ProjectEntry> recentProjects = new ArrayList<>();
    /**
     * Launch-target specs the user has picked before, newest first — the "Recently used" list in
     * {@code LaunchTargetDialog}. Global rather than per-project on purpose: the whole point is that a game
     * chosen once is re-selectable from the <em>next</em> project without walking the library picker again.
     */
    private List<String> recentLaunchTargets = new ArrayList<>();
    private Integer captureScreenIndex;
    private WindowState windowState;
    /**
     * Where each secondary window was left, keyed by the stable id its {@code ui/app/StudioWindow} was built
     * with. Separate from {@link #windowState} because they answer different questions: that one is "where is
     * BotMaker", this one is "how big did I make the flow canvas last time". Unknown keys are simply absent,
     * so a dialog that has never been opened falls back to the size its caller asked for.
     */
    private Map<String, WindowState> dialogWindows = new LinkedHashMap<>();
    // The Remote Pilot's pairing token and last bind port lived here until 2026-08-30, when the pilot became
    // the SDK plugin's feature. A plugin's state is the plugin's to keep, and the editor has no reason to know
    // a pilot token exists — they are in the plugin's own java.util.prefs node now. An older preferences file
    // still carrying the two keys reads fine: this class ignores unknown properties.
    /** True once the user ticked "don't show again" on the Wayland → X11 notice. */
    private boolean hideWaylandNotice;
    /** How the project list is sorted, by {@code ProjectSelectionScreen.SortMode} name. Null = the default. */
    private String projectSortMode;
    // The Assistant tab's model choice (`assistant`) lived here for a day, until 2026-09-25: the tab runs AI
    // CLIs now, each with its own login. An older file still carrying the key reads fine (unknown properties).
    /**
     * Statement-menu entries the user pinned to the top, by palette id, in pin order. Global rather than
     * per-project, like {@link #recentLaunchTargets}: a block reached for in one bot is reached for in the next.
     */
    private List<String> pinnedStatements = new ArrayList<>();

    public ProjectPreferences() {}

    // --- Accessors ---

    public String getLastOpenedProject() { return lastOpenedProject; }
    public void setLastOpenedProject(String name) { this.lastOpenedProject = name; }
    public String getLastOpenedPath() { return lastOpenedPath; }
    public void setLastOpenedPath(String path) { this.lastOpenedPath = path; }
    public List<ProjectEntry> getRecentProjects() { return recentProjects; }
    public List<String> getRecentLaunchTargets() { return recentLaunchTargets; }
    public void setRecentLaunchTargets(List<String> specs) {
        this.recentLaunchTargets = specs == null ? new ArrayList<>() : new ArrayList<>(specs);
    }
    public Integer getCaptureScreenIndex() { return captureScreenIndex; }
    public void setCaptureScreenIndex(Integer index) { this.captureScreenIndex = index; }
    public WindowState getWindowState() { return windowState; }
    public void setWindowState(WindowState windowState) { this.windowState = windowState; }
    public Map<String, WindowState> getDialogWindows() { return dialogWindows; }
    public void setDialogWindows(Map<String, WindowState> states) {
        this.dialogWindows = states == null ? new LinkedHashMap<>() : new LinkedHashMap<>(states);
    }
    public boolean isHideWaylandNotice() { return hideWaylandNotice; }
    public void setHideWaylandNotice(boolean hide) { this.hideWaylandNotice = hide; }
    public String getProjectSortMode() { return projectSortMode; }
    public void setProjectSortMode(String mode) { this.projectSortMode = mode; }
    public List<String> getPinnedStatements() { return pinnedStatements; }
    public void setPinnedStatements(List<String> ids) {
        this.pinnedStatements = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
    }

    /**
     * Records {@code projectDir} as the project last opened and moves it to the front of the MRU. Two entries
     * are the same project when they are the same directory, not when they share a name: a template kept in
     * its own repository and a project made from it are both called whatever their folders are called.
     */
    public void recordOpened(Path projectDir) {
        Path dir = projectDir.toAbsolutePath().normalize();
        // Forget first: forgetting the last-opened project also clears "last opened", which is about to be set.
        forgetRecent(dir);
        lastOpenedProject = dir.getFileName().toString();
        lastOpenedPath = dir.toString();
        recentProjects.addFirst(new ProjectEntry(dir));
        if (recentProjects.size() > MAX_RECENT_PROJECTS) {
            recentProjects = new ArrayList<>(recentProjects.subList(0, MAX_RECENT_PROJECTS));
        }
    }

    /** Drops {@code projectDir} from the MRU, and from "last opened" when it is that one. Touches no file. */
    public void forgetRecent(Path projectDir) {
        Path dir = projectDir.toAbsolutePath().normalize();
        recentProjects.removeIf(p -> dir.equals(p.directory()));
        if (dir.equals(lastOpenedDirectory())) {
            lastOpenedProject = null;
            lastOpenedPath = null;
        }
    }

    /** The last project's directory, or {@code null}. A file without {@code lastOpenedPath} means the default root. */
    @JsonIgnore
    public Path lastOpenedDirectory() {
        if (lastOpenedPath != null && !lastOpenedPath.isBlank()) return Path.of(lastOpenedPath);
        return lastOpenedProject == null ? null : PROJECTS_ROOT.resolve(lastOpenedProject);
    }

    /**
     * Moves {@code spec} to the front of the launch-target MRU, capped at {@link #MAX_RECENT_LAUNCH_TARGETS}.
     * Mirrors {@link #addRecentProject}: remove-then-{@code addFirst}, so re-picking an old target promotes it
     * rather than duplicating it. A null/blank spec (the "Clear target" path) is not recorded — clearing is not
     * a choice worth offering back.
     */
    public void addRecentLaunchTarget(String spec) {
        if (spec == null || spec.isBlank()) return;
        String trimmed = spec.trim();
        recentLaunchTargets.removeIf(trimmed::equals);
        recentLaunchTargets.addFirst(trimmed);
        if (recentLaunchTargets.size() > MAX_RECENT_LAUNCH_TARGETS) {
            recentLaunchTargets = new ArrayList<>(recentLaunchTargets.subList(0, MAX_RECENT_LAUNCH_TARGETS));
        }
    }

    // --- Persistence ---

    public static ProjectPreferences load() {
        return read(CONFIG_FILE);
    }

    public void save() {
        write(CONFIG_FILE);
    }

    /** {@link #load()} from any file — the seam the round-trip tests use. */
    static ProjectPreferences read(Path file) {
        try {
            if (Files.exists(file)) {
                return MAPPER.readValue(file.toFile(), ProjectPreferences.class);
            }
        } catch (Exception e) {
            System.err.println("Failed to load project preferences: " + e.getMessage());
        }
        return new ProjectPreferences();
    }

    /** {@link #save()} to any file. */
    void write(Path file) {
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writeValue(file.toFile(), this);
        } catch (IOException e) {
            System.err.println("Failed to save project preferences: " + e.getMessage());
        }
    }

    // --- Static Convenience ---

    public static void updateLastOpened(Path projectDir) {
        ProjectPreferences prefs = load();
        prefs.recordOpened(projectDir);
        prefs.save();
    }

    /** The directory of the project last opened, or {@code null}. */
    public static Path getLastOpened() {
        return load().lastOpenedDirectory();
    }

    /** The remembered projects' directories, newest first. */
    public static List<Path> recentDirectories() {
        return load().getRecentProjects().stream().map(ProjectEntry::directory).toList();
    }

    /** Removes {@code projectDir} from the MRU. The folder itself is not touched. */
    public static void removeRecent(Path projectDir) {
        ProjectPreferences prefs = load();
        prefs.forgetRecent(projectDir);
        prefs.save();
    }

    /** Records a launch-target spec in the global MRU. Called from every path that writes {@code launch.target}. */
    public static void recordLaunchTarget(String spec) {
        ProjectPreferences prefs = load();
        prefs.addRecentLaunchTarget(spec);
        prefs.save();
    }

    /** The launch-target specs picked before, newest first; empty when none have been. */
    public static List<String> recentLaunchTargets() {
        return load().getRecentLaunchTargets();
    }

    /** Index (into {@code Screen.getScreens()}) of the screen last chosen for capture, or {@code null}. */
    public static Integer getCaptureScreen() {
        return load().getCaptureScreenIndex();
    }

    public static void updateCaptureScreen(int index) {
        ProjectPreferences prefs = load();
        prefs.setCaptureScreenIndex(index);
        prefs.save();
    }

    /** True if the user asked not to see the Wayland → X11 notice again. */
    public static boolean isWaylandNoticeHidden() {
        return load().isHideWaylandNotice();
    }

    public static void setWaylandNoticeHidden(boolean hide) {
        ProjectPreferences prefs = load();
        prefs.setHideWaylandNotice(hide);
        prefs.save();
    }

    /** The project list's sort order, or {@code null} to use the default. */
    public static String getSortMode() {
        return load().getProjectSortMode();
    }

    public static void updateSortMode(String mode) {
        ProjectPreferences prefs = load();
        prefs.setProjectSortMode(mode);
        prefs.save();
    }

    /** The persisted main-window geometry, or {@code null} if never saved. */
    public static WindowState loadWindowState() {
        return load().getWindowState();
    }

    public static void saveWindowState(WindowState state) {
        ProjectPreferences prefs = load();
        prefs.setWindowState(state);
        prefs.save();
    }

    /** Where the secondary window {@code key} was last left, or {@code null} if it has never been sized. */
    public static WindowState loadDialogState(String key) {
        // Not isUsable(): that floor (400×300) is the main window's, and several dialogs are smaller than it
        // by design. What a dialog needs is only that the numbers aren't a collapsed or unwritten window.
        WindowState state = load().getDialogWindows().get(key);
        return state != null && state.getWidth() >= 200 && state.getHeight() >= 150 ? state : null;
    }

    public static void saveDialogState(String key, WindowState state) {
        ProjectPreferences prefs = load();
        prefs.getDialogWindows().put(key, state);
        prefs.save();
    }

    // --- Inner Record ---

    /** Main-window geometry + maximized flag, so the app reopens where the user left it. */
    public static class WindowState {
        private double x;
        private double y;
        private double width;
        private double height;
        private boolean maximized;

        public WindowState() {}

        public WindowState(double x, double y, double width, double height, boolean maximized) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.maximized = maximized;
        }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }
        public double getY() { return y; }
        public void setY(double y) { this.y = y; }
        public double getWidth() { return width; }
        public void setWidth(double width) { this.width = width; }
        public double getHeight() { return height; }
        public void setHeight(double height) { this.height = height; }
        public boolean isMaximized() { return maximized; }
        public void setMaximized(boolean maximized) { this.maximized = maximized; }

        @JsonIgnore
        public boolean isUsable() { return width >= 400 && height >= 300; }
    }

    public static class ProjectEntry {
        private String name;
        /** The project's directory; {@code null} in an entry an older Studio wrote, meaning the default root. */
        private String path;
        private String lastOpened;

        public ProjectEntry() {}

        public ProjectEntry(Path directory) {
            this.name = directory.getFileName().toString();
            this.path = directory.toString();
            this.lastOpened = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getLastOpened() { return lastOpened; }
        public void setLastOpened(String lastOpened) { this.lastOpened = lastOpened; }

        /** Where this project is: its recorded path, or the default root for an entry written without one. */
        @JsonIgnore
        public Path directory() {
            Path dir = path != null && !path.isBlank() ? Path.of(path) : PROJECTS_ROOT.resolve(name);
            return dir.toAbsolutePath().normalize();
        }
    }
}
