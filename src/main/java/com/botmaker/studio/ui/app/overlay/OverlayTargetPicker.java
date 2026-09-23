package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.project.managed.MethodReferences;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.ProjectSettingsService;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.botmaker.studio.ui.app.overlay.OverlayStyles.dimLabel;
import static com.botmaker.studio.ui.app.overlay.OverlayStyles.label;

/**
 * The two pickers that answer <em>where does the next block go</em>: the activity (which file the overlay
 * authors into) and the method within it (whose statements are the only ones the tree renders).
 *
 * <p>It owns the combos and the naming rules behind them — which targets exist, which one to open on, and how a
 * method is labelled — but it does not own the editor: picking an activity reports the resolved
 * {@link Path} through {@link Callbacks#onActivityFile}, and the coordinator does the file switch and the
 * cursor re-homing. Nothing here touches the cursor or the block tree.
 */
final class OverlayTargetPicker {

    /**
     * What the picker reports back.
     *
     * @param onActivityFile a target resolved to an existing file; the caller switches the editor to it
     * @param onMethod       a method label the user picked; the caller re-scopes the tree and re-homes the caret
     * @param onStatus       a one-line message for the HUD's status line — the picker never opens a dialog
     */
    record Callbacks(Consumer<Path> onActivityFile, Consumer<String> onMethod, Consumer<String> onStatus) {}

    /** Caption separating the flow's activities from the scaffold hooks; disabled in the list, never selectable. */
    private static final String SCAFFOLD_HEADER = "— scaffolds —";

    /** The two supervised hooks a pre-2026-08-29 project has as files of their own. */
    private static final List<String> HOOK_FILES = List.of("GoHome.java", "Popups.java");

    private final CodeEditorService context;
    private final ProjectSettingsService settings;
    private final Callbacks callbacks;

    /** The activity being authored into; picking one switches the editor to its file and re-homes the cursor. */
    private final ComboBox<String> activityBox = new ComboBox<>();
    /** The method the tree is scoped to; its label carries the signature, so overloads stay distinguishable. */
    private final ComboBox<String> methodBox = new ComboBox<>();
    /** Label of the method currently rendered/edited, or {@code null} to fall back to every top-level body. */
    private String selectedMethod;
    /** The method the chosen reference names ({@code body}), opened on first; {@code null} for a hook file. */
    private String preferredMethod;
    /**
     * The activity that is actually open. A failed pick reverts the combo to this rather than leaving it naming
     * a file the overlay never opened — the combo is the only thing telling the user where their blocks land.
     */
    private String openTarget;

    OverlayTargetPicker(CodeEditorService context, ProjectSettingsService settings, Callbacks callbacks) {
        this.context = context;
        this.settings = settings;
        this.callbacks = callbacks;
    }

    // ── target activity ─────────────────────────────────────────────────────────────────────────────────

    /** The picker naming the activity every insert goes into, plus a nudge when the project has none. */
    HBox activityRow() {
        List<String> items = targetNames();
        activityBox.getItems().setAll(items);
        activityBox.setTooltip(new Tooltip("The activity that new and recorded blocks are inserted into"));
        activityBox.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                boolean header = SCAFFOLD_HEADER.equals(item);
                setDisable(header);   // a caption, not a choice
                setStyle(header ? "-fx-font-style: italic; -fx-opacity: 0.7;" : "");
            }
        });
        HBox row = new HBox(6, label("Activity:"), activityBox);
        activityBox.setDisable(items.isEmpty());
        if (activityNames().isEmpty()) {
            activityBox.setPromptText("none yet");
            row.getChildren().add(dimLabel("add one in 🔀 Activity Flow on the toolbar"));
        }
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Selects the target the overlay should open on and reports its file, then arms the change handler. The
     * handler is installed <em>after</em> the initial value so the one explicit call here is the only one —
     * {@code ComboBox.setValue}'s action-firing behaviour is not something to have two code paths depend on.
     */
    void selectInitialTarget() {
        String initial = preferredTarget();
        if (initial != null) {
            activityBox.setValue(initial);
            selectActivity(initial);
        }
        activityBox.setOnAction(e -> selectActivity(activityBox.getValue()));
    }

    /** Whether the project has anywhere to author into — the recorder's availability turns on this. */
    boolean hasTargets() {
        return !targetNames().isEmpty();
    }

    /**
     * The methods this bot's {@code @Managed} values reference — {@code Collect::body} in the flow — read out
     * of its own source ({@link MethodReferences}).
     *
     * <p>It was the {@code Activities.define("…")} calls until SDK 2.0 deleted {@code define}, and the flow's
     * list out of {@code activities.json} before 2026-09-11. A method reference is what a flow names now, so
     * it is what the picker offers: choosing one opens the file declaring the class and scopes the tree to
     * the method.
     */
    private List<String> activityNames() {
        return MethodReferences.scan(context.getConfig(), context.getState()).stream()
                .map(MethodReferences.Target::label).toList();
    }

    /**
     * Everything the overlay can author into: the activities, then the two supervised hooks
     * ({@code GoHome}, {@code Popups}) if this project has them as files. They are as much a place for blocks
     * as any activity — {@code Popups} in particular is where the popup-dismissal steps belong — but nothing
     * defines them, so a list built from the {@code define} calls alone could never reach them.
     *
     * <p>Only a project created before 2026-08-29 has them: a new one carries {@code goHome} and
     * {@code dismissPopups} as methods of its own entry point, and both are ordinary user code the overlay
     * reaches through the file the user has open. The list stays for the projects that do have the files.
     */
    private List<String> targetNames() {
        List<String> out = new ArrayList<>(activityNames());
        List<String> hooks = hookNames();
        if (!hooks.isEmpty()) {
            out.add(SCAFFOLD_HEADER);
            out.addAll(hooks);
        }
        return out;
    }

    /** The two hook files, if this project has them. Empty for one created after they stopped being files. */
    private List<String> hookNames() {
        Path dir = context.getConfig().mainSourceFile().getParent();
        if (dir == null) return List.of();
        return HOOK_FILES.stream()
                .sorted()
                .filter(f -> Files.isRegularFile(dir.resolve(f)))
                .map(f -> f.substring(0, f.length() - ".java".length()))
                .toList();
    }

    /**
     * Refreshes the activity list after an activity was created/renamed/removed elsewhere. Leaves an
     * already-valid selection alone — creating an unrelated activity shouldn't yank the user off what they're
     * editing — but picks a default when the box was previously empty or its selection no longer exists.
     */
    void refreshActivities() {
        List<String> items = targetNames();
        activityBox.getItems().setAll(items);
        activityBox.setDisable(items.isEmpty());
        if (items.isEmpty()) {
            activityBox.setPromptText("none yet");
            return;
        }
        // A scaffold hook is a valid selection, so the still-valid test is against the whole item list —
        // otherwise creating an unrelated activity would yank a user editing Popups back into the flow.
        String current = activityBox.getValue();
        if (current == null || !items.contains(current)) {
            String next = preferredTarget();
            activityBox.setValue(next);
            selectActivity(next);
        }
    }

    /**
     * Which target to open on: the one last authored into (activity <em>or</em> scaffold hook — the last-used
     * one is remembered per project so reopening the overlay resumes where the last session stopped), else the
     * first activity the source defines, else a hook for a project that has no activities yet.
     *
     * <p><b>The flow's start node was preferred over the first activity until 2026-09-11</b>, and it is not
     * asked for any more: which node a flow starts at is the SDK plugin's graph, and the host reading that
     * file to sort a dropdown is exactly the arrangement this phase removes. What the default costs is the
     * one open where the user has never picked a target — every open after that resumes from
     * {@code lastRecordedActivity}, which is the editor's own state and is untouched.
     */
    private String preferredTarget() {
        String last = settings.current().lastRecordedActivity();
        if (last != null && !SCAFFOLD_HEADER.equals(last) && targetNames().contains(last)) return last;
        List<String> names = activityNames();
        if (names.isEmpty()) {
            List<String> hooks = hookNames();
            return hooks.isEmpty() ? null : hooks.get(0);
        }
        return names.get(0);
    }

    /**
     * Resolves the picked target to a file and reports it.
     *
     * <p>For a method reference that means <b>finding</b> the file declaring its class
     * ({@link MethodReferences}), because nothing writes a user's sources and so nothing knows where it is;
     * the method it names is then the one the tree opens scoped to. For a scaffold hook it is
     * {@code <name>.java} beside the main source — {@link #targetNames} offers both, so this resolves both.
     *
     * <p>A reference whose class is not in the bot's sources — a library's method, a class not written yet —
     * has nothing to open. The combo reverts to whatever is actually open and the status line names the
     * class, rather than offering to create a file.
     */
    private void selectActivity(String name) {
        if (name == null || SCAFFOLD_HEADER.equals(name)) return;
        MethodReferences.Target target = MethodReferences.find(context.getConfig(), context.getState(), name);
        Path file = target == null ? null : target.file();
        if (target == null) {
            Path pkg = context.getConfig().mainSourceFile().getParent();
            if (pkg != null && Files.isRegularFile(pkg.resolve(name + ".java"))) {
                file = pkg.resolve(name + ".java");
            }
        }
        if (file == null || !Files.isRegularFile(file)) {
            activityBox.setValue(openTarget);
            callbacks.onStatus().accept(target != null
                    ? name + " — no class " + target.simpleClassName() + " in this bot's sources to open"
                    : name + " is not in this bot's sources");
            return;
        }
        openTarget = name;
        selectedMethod = null;   // a different file — the previous selection doesn't apply here
        preferredMethod = target == null ? null : target.method();
        callbacks.onActivityFile().accept(file);
        settings.update(settings.current().withLastRecordedActivity(name));
    }

    // ── target method ───────────────────────────────────────────────────────────────────────────────────

    /** The picker naming the method whose statements are the only ones rendered/edited. */
    HBox methodRow() {
        methodBox.setTooltip(new Tooltip("Show only this method's blocks — new/recorded blocks land here too"));
        methodBox.setOnAction(e -> {
            String picked = methodBox.getValue();
            if (picked == null || picked.equals(selectedMethod)) return;
            selectedMethod = picked;
            callbacks.onMethod().accept(picked);
        });
        HBox row = new HBox(6, label("Method:"), methodBox);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** The label of the method the tree is scoped to, or {@code null} for every top-level body. */
    String selectedMethod() {
        return selectedMethod;
    }

    /**
     * Repopulates the method list from the current file. Leaves an already-valid selection alone (so an edit
     * elsewhere in the file doesn't yank the view away from what the user is looking at); picks the method the
     * chosen reference names ({@code body} for {@code Collect::body}), else {@code run}, else the first method,
     * when the selection is unset or no longer exists.
     *
     * @param labels every method in the file, as {@link BlockTree.Index#methodLabels()} names them
     * @return {@code true} when the selection changed and the caller must re-home the caret into it
     */
    boolean refreshMethods(List<String> labels) {
        methodBox.getItems().setAll(labels);
        boolean changed = false;
        if (selectedMethod == null || !labels.contains(selectedMethod)) {
            selectedMethod = labels.stream()
                    .filter(l -> preferredMethod != null && l.startsWith(preferredMethod + "(")).findFirst()
                    .or(() -> labels.stream().filter(l -> l.startsWith("run(")).findFirst())
                    .orElse(labels.isEmpty() ? null : labels.get(0));
            changed = true;
        }
        methodBox.setValue(selectedMethod);
        return changed;
    }
}
