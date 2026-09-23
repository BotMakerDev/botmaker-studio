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
 * Per-project editor settings, persisted as {@code settings.json} under the project's
 * {@code src/main/resources}. It is the only project file the editor itself both writes and versions —
 * {@code activities.json} was the other, and that one is the SDK plugin's since 2026-09-11.
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
 * @param lastRecordedActivity the activity the overlay editor last authored into, preselected the next time it
 *                            opens so a recording session resumes where the previous one left off. {@code null}
 *                            until the overlay has been used, and ignored once the activity is gone
 *                            (backward-compatible; absent → null)
 * @param overlayState        where the overlay HUD sat and how tall its tree was, restored the next time it
 *                            opens (backward-compatible; absent → null)
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
 *                            {@code lastRecordedActivity} naming a deleted activity; see
 *                            {@code EditorContest} (backward-compatible; absent → empty)
 * @param preferredRecorders  which plugin writes down a gesture two plugins both record:
 *                            {@code Gesture name → pluginId}. The same verdict shape as {@code preferredEditors},
 *                            answered from the HUD's <i>Record with</i> menu; a verdict naming an uninstalled
 *                            plugin is inert (backward-compatible; absent → empty)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StudioProjectSettings(List<String> knownWindowTitles, Map<String, String> favoriteOverloads,
                                    Map<String, List<String>> favoriteMethods,
                                    ProjectTemplate template, String lastRecordedActivity,
                                    OverlayState overlayState, WorkspaceLayout workspaceLayout,
                                    List<String> hiddenToolbarGroups,
                                    Map<String, String> preferredEditors,
                                    Map<String, String> preferredRecorders) {

    /**
     * The overlay editor HUD's remembered layout: its top-left corner on screen and how many tree rows it
     * shows before scrolling.
     *
     * <p>Persisted because the HUD is dragged out of the way of whatever the user is doing in the game, and
     * that placement is a property of the <em>project</em> — the same game, the same UI, the same corner that
     * is safe to cover. Re-dragging it on every open (and re-growing a tree that reopened at 8 rows) is the
     * kind of friction that is invisible in a single session and constant across many. A position off every
     * attached screen is discarded on restore rather than trusted; monitors come and go.
     */
    public record OverlayState(int x, int y, int visibleLines) {}

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
     */
    public record WorkspaceLayout(Double explorerDivider, Double bottomDivider, String bottomTab) {

        /** Dividers this close to an edge have hidden a pane, so they are treated as never saved. */
        private static final double EDGE = 0.02;

        public WorkspaceLayout {
            explorerDivider = usable(explorerDivider);
            bottomDivider = usable(bottomDivider);
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
        preferredRecorders = preferredRecorders == null ? Map.of() : Map.copyOf(preferredRecorders);
    }

    private static Map<String, List<String>> deepCopy(Map<String, List<String>> src) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        src.forEach((k, v) -> out.put(k, v == null ? List.of() : List.copyOf(v)));
        return Map.copyOf(out);
    }

    /** A fresh project's settings — nothing remembered yet, and no template recorded until one is chosen. */
    public static StudioProjectSettings empty() {
        return new StudioProjectSettings(List.of(), Map.of(), Map.of(), null, null, null, null, List.of(),
                Map.of(), Map.of());
    }

    // withTargets, withDefaultIndex, withKnownWindowTitles and withReferenceResolution are deleted
    // (2026-08-31 / 2026-09-01). Their callers were the targets dialog and the capture resolution row, both
    // of which are the SDK plugin's now — so the editor has no way left to change a capture setting it does
    // not own, which is the point rather than a side effect.

    /** This settings with the originating template recorded ({@code null} clears it). */
    public StudioProjectSettings withTemplate(ProjectTemplate template) {
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                lastRecordedActivity, overlayState, workspaceLayout, hiddenToolbarGroups, preferredEditors,
                preferredRecorders);
    }

    /** This settings with the overlay editor's last authored activity recorded ({@code null} clears it). */
    public StudioProjectSettings withLastRecordedActivity(String activityName) {
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                activityName, overlayState, workspaceLayout, hiddenToolbarGroups, preferredEditors,
                preferredRecorders);
    }

    /** This settings with the overlay HUD's remembered layout replaced ({@code null} clears it). */
    public StudioProjectSettings withOverlayState(OverlayState state) {
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                lastRecordedActivity, state, workspaceLayout, hiddenToolbarGroups, preferredEditors,
                preferredRecorders);
    }

    /** This settings with the main window's remembered layout replaced ({@code null} clears it). */
    public StudioProjectSettings withWorkspaceLayout(WorkspaceLayout layout) {
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                lastRecordedActivity, overlayState, layout, hiddenToolbarGroups, preferredEditors,
                preferredRecorders);
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
                lastRecordedActivity, overlayState, workspaceLayout,
                groupNames == null ? List.of() : List.copyOf(groupNames), preferredEditors, preferredRecorders);
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
                lastRecordedActivity, overlayState, workspaceLayout, hiddenToolbarGroups, next, preferredRecorders);
    }

    /** The chosen plugin id for {@code typeName}, or {@code null} when the user has not been asked. */
    @JsonIgnore
    public String preferredEditorFor(String typeName) {
        return preferredEditors.get(typeName);
    }

    /**
     * This settings with {@code gesture}'s recorder set to {@code pluginId} ({@code null} clears it, which
     * leaves the gesture to the highest-ranked writer).
     *
     * <p>Keyed on the gesture's enum <em>name</em>, for the reason {@link #withHiddenToolbarGroups} takes names:
     * this record is Jackson's and names no contract type.
     */
    public StudioProjectSettings withPreferredRecorder(String gesture, String pluginId) {
        Map<String, String> next = new LinkedHashMap<>(preferredRecorders);
        if (pluginId == null) next.remove(gesture);
        else next.put(gesture, pluginId);
        return new StudioProjectSettings(knownWindowTitles, favoriteOverloads, favoriteMethods, template,
                lastRecordedActivity, overlayState, workspaceLayout, hiddenToolbarGroups, preferredEditors, next);
    }

    /** The chosen plugin id for {@code gesture} (an enum name), or {@code null} when none was chosen. */
    @JsonIgnore
    public String preferredRecorderFor(String gesture) {
        return preferredRecorders.get(gesture);
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
                lastRecordedActivity, overlayState, workspaceLayout, hiddenToolbarGroups, preferredEditors,
                preferredRecorders);
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
                lastRecordedActivity, overlayState, workspaceLayout, hiddenToolbarGroups, preferredEditors,
                preferredRecorders);
    }

    /** The favorite method names for {@code className} (preference order), or an empty list if none. */
    @JsonIgnore
    public List<String> favoriteMethodsFor(String className) {
        return favoriteMethods.getOrDefault(className, List.of());
    }

    /** Reads {@code settings.json} from {@code resourcesDir}; returns {@link #empty()} if absent/invalid. */
    public static StudioProjectSettings read(Path resourcesDir) {
        Path file = resourcesDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return empty();
        try {
            return MAPPER.readValue(file.toFile(), StudioProjectSettings.class);
        } catch (Exception e) {
            System.err.println("Failed to read " + FILE_NAME + " in " + resourcesDir + ": " + e.getMessage());
            return empty();
        }
    }

    /**
     * Writes (overwrites) {@code settings.json} into {@code resourcesDir}, creating it if needed, stamped with
     * the file's {@code schemaVersion} — see {@link SchemaFile#stamped}.
     *
     * <p>It writes one file and no longer merges a second. The {@code Authoring.writeCapture} call that used
     * to sit here re-read {@code capture.json} to replace the one component the editor still owned, precisely
     * because the plugin writes the rest of that file while the editor is not looking. With no component left
     * to own, the merge and the reason for it both go.
     */
    public void write(Path resourcesDir) throws IOException {
        Files.createDirectories(resourcesDir);
        ObjectNode body = MAPPER.valueToTree(this);
        MAPPER.writeValue(resourcesDir.resolve(FILE_NAME).toFile(), SchemaFile.SETTINGS.stamped(body));
    }
}
