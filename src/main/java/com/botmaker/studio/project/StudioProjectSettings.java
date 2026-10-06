package com.botmaker.studio.project;

import com.botmaker.studio.project.migration.SchemaFile;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-project editor settings, persisted as {@code settings.json} under the project's {@code .botmaker}
 * ({@link ProjectConfig#studioRoot()}). It is the only project file the editor itself both writes and versions —
 * {@code activities.json} was the other, and that one is the SDK plugin's since 2026-09-11.
 *
 * <p><b>It lived in {@code src/main/resources} until 2026-09-26</b>, which put one machine's window layout into
 * every jar the bot was packaged as. {@link #moveOutOfResources} takes an old project's file across once, on
 * open: the same file in the same format, moved rather than read in two places.
 *
 * <p><b>Nothing about capture is in this record any more (2026-09-01).</b> It carried three components that
 * were read from and written to the SDK's {@code capture.json} through {@code Authoring} — the saved capture
 * targets, which of them is the default, and the reference resolution the pictures were captured at. All
 * three describe what a bot looks at and what its templates mean, so all three belong to the plugin that
 * captures and matches them; the plugin's own toolbar item and target manager own them now, and this file
 * neither reads nor writes {@code capture.json}. What is left here is what only the editor has an opinion
 * about: remembered window titles, the overload and method preferences the pickers surface first, the
 * template the project was created from, and where the windows sat.
 *
 * <p>The eight telescoping convenience constructors went with them. They existed because the capture triple
 * sat in the middle of the component list, so every caller that wanted a later component had to spell the
 * earlier ones; with the triple gone there is one constructor and the {@code with…} methods.
 *
 * @param knownWindowTitles   window titles seen/used before, remembered so a window can be picked as a
 *                            target without the app being currently open (backward-compatible; absent → empty)
 * @param favoriteOverloads   per-method chosen overload: {@code methodKey → signatureKey} (see
 *                            {@code ExpressionMenu}); the favorite is created by default when clicking
 *                            the method (backward-compatible; absent → empty)
 * @param favoriteMethods     per-class preferred methods: {@code className → [methodName, …]}, surfaced first in
 *                            the overlay palette and other pickers (backward-compatible; absent → empty)
 * @param template            the {@link ProjectTemplate} the project was created from, recorded at creation so
 *                            {@code FileRole}/{@code ProjectRepair} know which files are scaffolding instead of
 *                            guessing from the sources. {@code null} for projects created before this was
 *                            persisted — callers fall back to {@code ProjectRepair.looksLikeGameBot}
 *                            (backward-compatible; absent → null)
 * @param lastTarget          the overlay editor's target last authored into, as {@code com.bot.Collect#body}
 *                            ({@code OverlayTargets.Target.key()}), picked again the next time it opens.
 *                            {@code null} until the overlay has been used, and ignored once the target is gone.
 *                            It was {@code lastRecordedActivity}, an activity's name, until 2026-10-06; that
 *                            field is ignored on read (backward-compatible; absent → null)
 * @param overlayState        how wide the overlay editor's panel was, restored the next time it opens
 *                            (backward-compatible; absent → null)
 * @param workspaceLayout     where the main window's two dividers sat and which bottom tab was open, restored
 *                            the next time the project opens (backward-compatible; absent → null)
 * @param hiddenToolbarGroups the {@code ToolbarGroup} names the user has switched off, by enum name. Hidden
 *                            rather than visible on purpose: a group this project has never heard of — a
 *                            release adds one — must appear, not be absent from every project written before
 *                            it existed. A name this Studio does not know is ignored on read, and so is not
 *                            written back; see {@code ToolbarVisibility} (backward-compatible; absent → empty)
 * @param preferredEditors    which plugin's editor draws a type two plugins both claim:
 *                            {@code fully.qualified.TypeName → pluginId}. Empty is the ordinary state — a row
 *                            exists only where a user was actually shown a contest and answered it. A verdict
 *                            naming an uninstalled plugin is inert rather than an error, the same shape as
 *                            {@code lastTarget} naming a deleted method; see
 *                            {@code EditorContest} (backward-compatible; absent → empty). Its twin for
 *                            recorded gestures, {@code preferredRecorders}, left with ⏺ Record on 2026-10-06
 * @param runProperties       the system properties every run of this bot on this machine starts with,
 *                            {@code name → value} — a plugin's {@code Runs.setProperty} (2026-09-27), such as the
 *                            SDK's {@code botmaker.launch.target}. Here because this file is the checkout's own
 *                            and git-excluded, which is exactly what a fact about this computer needs: never
 *                            committed, never published (backward-compatible; absent → empty)
 * @param hiddenTraceWriters  what the Trace tab hides (2026-09-29): a writer's class binary name, which hides the
 *                            whole class, or {@code class#method}. Hidden rather than shown, for the reason
 *                            {@code hiddenToolbarGroups} is: a class the bot gains later must appear
 *                            (backward-compatible; absent → empty)
 * @param devMode             whether this project loads plugin jars at a {@code -SNAPSHOT} version, the ones
 *                            {@code mvn install} put in this machine's {@code ~/.m2} (2026-10-06). Here for the
 *                            reason {@code runProperties} is: a jar only this computer has is a fact about this
 *                            computer. Publish refuses the pins whatever this says
 *                            (backward-compatible; absent → false)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StudioProjectSettings(List<String> knownWindowTitles, Map<String, String> favoriteOverloads,
                                    Map<String, List<String>> favoriteMethods,
                                    ProjectTemplate template, String lastTarget,
                                    OverlayState overlayState, WorkspaceLayout workspaceLayout,
                                    List<String> hiddenToolbarGroups,
                                    Map<String, String> preferredEditors,
                                    Map<String, String> runProperties,
                                    List<String> hiddenTraceWriters,
                                    boolean devMode) {

    /**
     * The overlay editor panel's remembered layout: its width. Where it sits is not remembered: it docks
     * beside the window the bot watches, wherever that window is. {@code 0} is never saved, and the panel's
     * default stands.
     *
     * <p>It was the HUD's top-left corner and its tree's line count until 2026-10-06, when the floating HUD
     * became the docked panel; those fields are ignored on read.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OverlayState(int width) {}

    /**
     * The main window's remembered layout: the explorer/canvas divider, the canvas/bottom divider, and the
     * name of the bottom tab that was open. Window geometry is not here — that is the user's, not the
     * project's, and already persists through {@code ProjectPreferences.WindowState}.
     *
     * <p>Per project, because how much room the file tree or the console deserves is a property of the bot
     * being worked on: a bot being read wants a wide tree, a bot being debugged wants a tall terminal.
     *
     * <p>Each divider is {@code null} when it was never saved <em>or</em> when the saved value is unusable —
     * a divider at 0.0 or 1.0 hides a whole pane, and a settings file that has been hand-edited (or written
     * before the window was ever laid out) must not be able to open a window with no canvas in it. Callers
     * ask with {@link #explorerDividerOr}/{@link #bottomDividerOr} and get their own default back instead.
     *
     * @param explorerDivider the horizontal split's position, {@code 0..1}, or {@code null} if unusable
     * @param bottomDivider   the vertical split's position, {@code 0..1}, or {@code null} if unusable
     * @param bottomTab       the open bottom tab's key, or {@code null}; an unknown key is ignored on restore
     * @param publishDivider  the Versions tab's history/Publish split, {@code 0..1}, or {@code null}
     */
    public record WorkspaceLayout(Double explorerDivider, Double bottomDivider, String bottomTab,
                                  Double publishDivider) {

        /** Dividers this close to an edge have hidden a pane, so they are treated as never saved. */
        private static final double EDGE = 0.02;

        public WorkspaceLayout {
            explorerDivider = usable(explorerDivider);
            bottomDivider = usable(bottomDivider);
            publishDivider = usable(publishDivider);
        }

        /** The main window's three, with the Versions tab's publish divider never moved. */
        public WorkspaceLayout(Double explorerDivider, Double bottomDivider, String bottomTab) {
            this(explorerDivider, bottomDivider, bottomTab, null);
        }

        /**
         * The same layout with the Versions tab's history/publish divider at {@code position} (2026-09-26:
         * the Publish sheet is the right pane of a split, so it can be widened).
         */
        public WorkspaceLayout withPublishDivider(Double position) {
            return new WorkspaceLayout(explorerDivider, bottomDivider, bottomTab, position);
        }

        public double publishDividerOr(double fallback) {
            return publishDivider == null ? fallback : publishDivider;
        }

        private static Double usable(Double position) {
            if (position == null || !Double.isFinite(position)) return null;
            return position < EDGE || position > 1 - EDGE ? null : position;
        }

        public double explorerDividerOr(double fallback) {
            return explorerDivider == null ? fallback : explorerDivider;
        }

        public double bottomDividerOr(double fallback) {
            return bottomDivider == null ? fallback : bottomDivider;
        }
    }

    public static final String FILE_NAME = "settings.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public StudioProjectSettings {
        knownWindowTitles = knownWindowTitles == null ? List.of() : List.copyOf(knownWindowTitles);
        favoriteOverloads = favoriteOverloads == null ? Map.of() : Map.copyOf(favoriteOverloads);
        favoriteMethods = favoriteMethods == null ? Map.of() : deepCopy(favoriteMethods);
        hiddenToolbarGroups = hiddenToolbarGroups == null ? List.of() : List.copyOf(hiddenToolbarGroups);
        preferredEditors = preferredEditors == null ? Map.of() : Map.copyOf(preferredEditors);
        runProperties = runProperties == null ? Map.of() : Map.copyOf(runProperties);
        hiddenTraceWriters = hiddenTraceWriters == null ? List.of() : List.copyOf(hiddenTraceWriters);
    }

    private static Map<String, List<String>> deepCopy(Map<String, List<String>> src) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        src.forEach((k, v) -> out.put(k, v == null ? List.of() : List.copyOf(v)));
        return Map.copyOf(out);
    }

    /** A fresh project's settings — nothing remembered yet, and no template recorded until one is chosen. */
    public static StudioProjectSettings empty() {
        return new StudioProjectSettings(List.of(), Map.of(), Map.of(), null, null, null, null, List.of(),
                Map.of(), Map.of(), List.of(), false);
    }

    // withTargets, withDefaultIndex, withKnownWindowTitles and withReferenceResolution are deleted
    // (2026-08-31 / 2026-09-01). Their callers were the targets dialog and the capture resolution row, both
    // of which are the SDK plugin's now — so the editor has no way left to change a capture setting it does
    // not own, which is the point rather than a side effect.

    /** This settings with the originating template recorded ({@code null} clears it). */
    public StudioProjectSettings withTemplate(ProjectTemplate template) {
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                lastTarget, overlayState, workspaceLayout, hiddenToolbarGroups, preferredEditors,
                runProperties, hiddenTraceWriters, devMode);
    }

    /** This settings with the overlay editor's last target recorded, by its key ({@code null} clears it). */
    public StudioProjectSettings withLastTarget(String targetKey) {
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                targetKey, overlayState, workspaceLayout, hiddenToolbarGroups, preferredEditors,
                runProperties, hiddenTraceWriters, devMode);
    }

    /** This settings with the overlay HUD's remembered layout replaced ({@code null} clears it). */
    public StudioProjectSettings withOverlayState(OverlayState state) {
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                lastTarget, state, workspaceLayout, hiddenToolbarGroups, preferredEditors,
                runProperties, hiddenTraceWriters, devMode);
    }

    /** This settings with the main window's remembered layout replaced ({@code null} clears it). */
    public StudioProjectSettings withWorkspaceLayout(WorkspaceLayout layout) {
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                lastTarget, overlayState, layout, hiddenToolbarGroups, preferredEditors,
                runProperties, hiddenTraceWriters, devMode);
    }

    /**
     * This settings with the hidden toolbar groups replaced (an empty or {@code null} collection shows
     * everything).
     *
     * <p>Takes the enum <em>names</em> rather than the groups: this record is Jackson's to read and write and
     * must not name a contract type to do it, the same rule {@code VisibilityWire} keeps for the stored
     * visibility of a parameter. {@code ToolbarVisibility.wire} is the one place that spells them.
     */
    public StudioProjectSettings withHiddenToolbarGroups(Collection<String> groupNames) {
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                lastTarget, overlayState, workspaceLayout,
                groupNames == null ? List.of() : List.copyOf(groupNames), preferredEditors,
                runProperties, hiddenTraceWriters, devMode);
    }

    /** Whether {@code groupName} (a {@code ToolbarGroup} enum name) is switched off for this project. */
    @JsonIgnore
    public boolean isGroupHidden(String groupName) {
        return hiddenToolbarGroups.contains(groupName);
    }

    /**
     * This settings with {@code typeName}'s editor set to {@code pluginId} ({@code null} clears it).
     *
     * <p>Keyed on the fully qualified Java type rather than on a value-type id, which is what makes one
     * verdict serve both densities: a block's slot knows a resolved Java type and a Parameters row knows a
     * {@code ValueType} whose {@code javaName()} spells the same thing. A value-type id would be unanswerable
     * on the canvas, where there is no id — only a type.
     */
    public StudioProjectSettings withPreferredEditor(String typeName, String pluginId) {
        Map<String, String> next = new LinkedHashMap<>(preferredEditors);
        if (pluginId == null) next.remove(typeName);
        else next.put(typeName, pluginId);
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                lastTarget, overlayState, workspaceLayout, hiddenToolbarGroups, next,
                runProperties, hiddenTraceWriters, devMode);
    }

    /** The chosen plugin id for {@code typeName}, or {@code null} when the user has not been asked. */
    @JsonIgnore
    public String preferredEditorFor(String typeName) {
        return preferredEditors.get(typeName);
    }

    /** This settings with the run property {@code name} set to {@code value}; {@code null} or blank clears it. */
    public StudioProjectSettings withRunProperty(String name, String value) {
        Map<String, String> next = new LinkedHashMap<>(runProperties);
        if (value == null || value.isBlank()) next.remove(name);
        else next.put(name, value.trim());
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                lastTarget, overlayState, workspaceLayout, hiddenToolbarGroups, preferredEditors,
                next, hiddenTraceWriters, devMode);
    }

    /**
     * This settings with the Trace tab hiding the lines of {@code writers} (2026-09-29): each a class's binary
     * name, or {@code class#method}.
     */
    public StudioProjectSettings withHiddenTraceWriters(Collection<String> writers) {
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                lastTarget, overlayState, workspaceLayout, hiddenToolbarGroups, preferredEditors,
                runProperties, writers == null ? List.of() : List.copyOf(writers), devMode);
    }

    /**
     * This settings with dev mode on or off (2026-10-06): whether this project loads plugin jars at a
     * {@code -SNAPSHOT} version. See {@code ReleasedPlugins}.
     */
    public StudioProjectSettings withDevMode(boolean on) {
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                lastTarget, overlayState, workspaceLayout, hiddenToolbarGroups, preferredEditors,
                runProperties, hiddenTraceWriters, on);
    }

    /** Whether the project in {@code projectDir} is in dev mode, read from its file; false when it has none. */
    public static boolean devModeIn(Path projectDir) {
        return read(projectDir.resolve(ProjectConfig.STUDIO_DIR)).devMode();
    }

    /**
     * This settings with {@code methodKey}'s favorite overload set to {@code signatureKey} (or removed when
     * {@code signatureKey} is {@code null}). Keys are opaque strings minted by {@code ExpressionMenu}.
     */
    public StudioProjectSettings withFavoriteOverload(String methodKey, String signatureKey) {
        Map<String, String> next = new LinkedHashMap<>(favoriteOverloads);
        if (signatureKey == null) next.remove(methodKey);
        else next.put(methodKey, signatureKey);
        return new StudioProjectSettings(knownWindowTitles, next, favoriteMethods, template,
                lastTarget, overlayState, workspaceLayout, hiddenToolbarGroups, preferredEditors,
                runProperties, hiddenTraceWriters, devMode);
    }

    /** The chosen overload signature key for {@code methodKey}, or {@code null} if no favorite is set. */
    @JsonIgnore
    public String favoriteSignature(String methodKey) {
        return favoriteOverloads.get(methodKey);
    }

    /**
     * This settings with {@code className}'s favorite method list replaced (an empty/null list removes the
     * entry). Order in {@code methods} is the preference order.
     */
    public StudioProjectSettings withFavoriteMethods(String className, List<String> methods) {
        Map<String, List<String>> next = new LinkedHashMap<>(favoriteMethods);
        if (methods == null || methods.isEmpty()) next.remove(className);
        else next.put(className, List.copyOf(methods));
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, next, template,
                lastTarget, overlayState, workspaceLayout, hiddenToolbarGroups, preferredEditors,
                runProperties, hiddenTraceWriters, devMode);
    }

    /** The favorite method names for {@code className} (preference order), or an empty list if none. */
    @JsonIgnore
    public List<String> favoriteMethodsFor(String className) {
        return favoriteMethods.getOrDefault(className, List.of());
    }

    /** Reads {@code settings.json} from {@code studioDir}; returns {@link #empty()} if absent/invalid. */
    public static StudioProjectSettings read(Path studioDir) {
        Path file = studioDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return empty();
        try {
            return MAPPER.readValue(file.toFile(), StudioProjectSettings.class);
        } catch (Exception e) {
            System.err.println("Failed to read " + FILE_NAME + " in " + studioDir + ": " + e.getMessage());
            return empty();
        }
    }

    /**
     * Moves a project's {@code settings.json} from {@code src/main/resources} to {@code .botmaker}, when it is
     * still in the old place and not yet in the new one. Returns true when it moved a file.
     *
     * <p>Run before anything reads the file — {@code ProjectSchema.check}, the first thing every open does — so
     * no reader has to know there were two places. Moved, never copied: a copy left in the resources is still
     * packaged into the jar, which is the whole reason for the move. When both exist the new one is the answer
     * and the old one is left for the user; deleting a file this method did not just move is not its call.
     */
    public static boolean moveOutOfResources(ProjectConfig config) throws IOException {
        Path legacy = config.resourcesRoot().resolve(FILE_NAME);
        Path current = config.studioRoot().resolve(FILE_NAME);
        if (!Files.isRegularFile(legacy) || Files.exists(current)) return false;
        Files.createDirectories(current.getParent());
        Files.move(legacy, current);
        return true;
    }

    /**
     * Writes (overwrites) {@code settings.json} into {@code studioDir}, creating it if needed, stamped with
     * the file's {@code schemaVersion} — see {@link SchemaFile#stamped}.
     *
     * <p>It writes one file and no longer merges a second. The {@code Authoring.writeCapture} call that used
     * to sit here re-read {@code capture.json} to replace the one component the editor still owned, precisely
     * because the plugin writes the rest of that file while the editor is not looking. With no component left
     * to own, the merge and the reason for it both go.
     */
    public void write(Path studioDir) throws IOException {
        Files.createDirectories(studioDir);
        ObjectNode body = MAPPER.valueToTree(this);
        MAPPER.writeValue(studioDir.resolve(FILE_NAME).toFile(), SchemaFile.SETTINGS.stamped(body));
    }
}
