package com.botmaker.studio.ui.app.overlay;

import com.botmaker.plugin.api.overlay.ProbeResult;
import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.ui.app.trial.TrialMenu;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.BranchingBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.core.render.ReadOnlyDecorator;
import com.botmaker.studio.project.InsertionCursor;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.validation.BlockValidator;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.ASTNode;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The panel's <b>script</b>: the scrolling list of one-line rows that <em>is</em> the overlay's view of the
 * method being edited. It takes whatever height the panel leaves it.
 *
 * <p>It renders {@link BlockTree#flatten} and nothing else — it holds no cursor, no tree and no editor. What a
 * row <em>does</em> is a callback the coordinator supplies ({@link Callbacks}), so the view can be reasoned
 * about (and the row model tested) without an editor session behind it.
 *
 * <p>Everything the row shows beyond the code text is there because its absence was silent: an unfilled slot
 * ({@code ⚠ missing value}) before any compile, a compile error the main editor shows on the block but the HUD
 * never did, a lock on generated scaffolding that previously read as "delete is broken here", and the full
 * source text as a tooltip because a row is truncated at 70 characters with no other way to see the rest.
 *
 * <p><b>One meaning per control</b> (2026-10-06). ▲▼ move the caret, always; moving a block is the row's ⋮ menu
 * or Alt+↑/↓. The focused row used to carry ▲▼ buttons that moved the <em>block</em>, beside the step bar's ▲▼
 * that moved the caret. A row's look is CSS classes in {@code blocks.css} ({@code overlay-row…}).
 */
final class OverlayTreeView {

    /** What the coordinator does when a row is used. */
    record Callbacks(Consumer<InsertionCursor> onFocus,
                     RowAction onDelete,
                     Consumer<MethodInvocationBlock> onConfig,
                     Move onMove,
                     Consumer<StatementBlock> onToggleFold) {

        /** Something done to one row, addressed the way an edit needs it: the block, its body and its slot. */
        @FunctionalInterface
        interface RowAction {
            void run(StatementBlock stmt, BodyBlock body, int index);
        }

        /** Reorder within the row's own body: {@code delta} is -1 for up, +1 for down. */
        @FunctionalInterface
        interface Move {
            void move(StatementBlock stmt, BodyBlock body, int index, int delta);
        }
    }

    /** Longest row text before truncation; the full text stays reachable as the row's tooltip. */
    private static final int MAX_LABEL_CHARS = 70;

    private final Callbacks callbacks;

    /**
     * The editor session, held for one reason: a block's declared {@link ComponentSpec} is built against it.
     *
     * <p>Also where the audience comes from, read per row rather than captured — Reader mode is a toggle a
     * user flips while the HUD is up, and a captured audience would keep drawing a bot's author's view of it.
     */
    private final CodeEditorService context;

    private final VBox rows = new VBox(2);
    private final ScrollPane scroll = new ScrollPane(rows);

    /** Compile diagnostics, so a broken block is marked here as well as in the main editor. May be null. */
    private DiagnosticsManager diagnostics;

    /** The last probe answer for a statement, or null — what a row's marker shows when it is drawn. */
    private Function<StatementBlock, ProbeResult> probed = stmt -> null;
    /** Each drawn statement row's probe marker, so an answer updates one label rather than the list. */
    private final Map<StatementBlock, Label> markers = new IdentityHashMap<>();

    OverlayTreeView(CodeEditorService context, Callbacks callbacks) {
        this.context = context;
        this.callbacks = callbacks;

        rows.setPadding(new Insets(6));
        rows.getStyleClass().add("overlay-script-rows");
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("overlay-script");
        scroll.setMinHeight(120);
        VBox.setVgrow(scroll, Priority.ALWAYS);
    }

    /** The node to put in the panel; it grows to the height the panel leaves. */
    ScrollPane node() {
        return scroll;
    }

    void setDiagnostics(DiagnosticsManager diagnostics) {
        this.diagnostics = diagnostics;
    }

    /** Where a drawn row reads its last probe answer from. */
    void setProbes(Function<StatementBlock, ProbeResult> probed) {
        this.probed = probed == null ? stmt -> null : probed;
    }

    /**
     * Shows {@code result} on {@code stmt}'s row, if it is drawn: {@code ✓}, {@code ✗} or {@code ?}, with the
     * probe's line as the marker's tooltip. Null clears it.
     */
    void showProbe(StatementBlock stmt, ProbeResult result) {
        Label marker = markers.get(stmt);
        if (marker != null) paint(marker, result);
    }

    private static void paint(Label marker, ProbeResult result) {
        marker.getStyleClass().removeAll("overlay-probe-found", "overlay-probe-missing", "overlay-probe-unknown");
        boolean shown = result != null;
        marker.setVisible(shown);
        marker.setManaged(shown);
        if (!shown) {
            marker.setTooltip(null);
            return;
        }
        switch (result.state()) {
            case FOUND -> {
                marker.setText("✓");
                marker.getStyleClass().add("overlay-probe-found");
            }
            case MISSING -> {
                marker.setText("✗");
                marker.getStyleClass().add("overlay-probe-missing");
            }
            case UNKNOWN -> {
                marker.setText("?");
                marker.getStyleClass().add("overlay-probe-unknown");
            }
        }
        String line = result.text().isBlank() ? result.state().displayName() : result.text();
        Tooltip tip = marker.getTooltip();
        if (tip == null) marker.setTooltip(new Tooltip(line));
        else tip.setText(line);
    }

    /** Replaces the list with a single message — "No open file.", "Program is empty.". */
    void showMessage(String message) {
        rows.getChildren().setAll(OverlayStyles.dimLabel(message));
    }

    /**
     * Draws {@code bodies} (each a render root) against {@code cursor}, replacing whatever was there.
     * {@code collapsed} answers which statements are folded shut — see {@link BlockTree#flatten}.
     */
    void render(List<BodyBlock> bodies, InsertionCursor cursor, Predicate<StatementBlock> collapsed) {
        rows.getChildren().clear();
        markers.clear();
        for (BodyBlock body : bodies) {
            List<BlockTree.Row> flat = BlockTree.flatten(body, 0, collapsed);
            for (int i = 0; i < flat.size(); i++) {
                BlockTree.Row row = flat.get(i);
                // A caret sitting *before* a body's first statement owns no row, so it gets one of its own —
                // otherwise the HUD shows a tree with no focus anywhere and looks like it lost the cursor. A
                // caption for that same body already carries the highlight, so it doesn't need a second marker.
                if (cursor != null && cursor.index() < 0 && cursor.body() == row.body()
                        && row.kind() == BlockTree.Kind.STATEMENT && row.index() == 0) {
                    rows.getChildren().add(caretRow(row.depth()));
                }
                rows.getChildren().add(rowNode(row, cursor));
            }
        }
    }

    /** The caret's own row, drawn when it sits before a body's first statement. */
    private static HBox caretRow(int depth) {
        Label text = new Label("▸ next block goes here");
        text.getStyleClass().add("overlay-caret-text");
        HBox node = new HBox(6, indent(depth), text);
        node.setPadding(new Insets(3, 6, 3, 6));
        node.getStyleClass().addAll("overlay-row", "overlay-row-focused");
        return node;
    }

    private HBox rowNode(BlockTree.Row row, InsertionCursor cursor) {
        return switch (row.kind()) {
            case STATEMENT -> statementRow(row, cursor);
            case CAPTION -> captionRow(row, cursor);
            case EMPTY -> emptyRow(row, cursor);
        };
    }

    private HBox statementRow(BlockTree.Row row, InsertionCursor cursor) {
        StatementBlock stmt = row.stmt();
        boolean incomplete = BlockValidator.hasEmptySlot(stmt);
        boolean broken = diagnostics != null && diagnostics.hasError(stmt);
        boolean locked = stmt.isReadOnly();
        boolean focused = focused(row, cursor);

        // A collapsed row ends in "…" as well as carrying the ▸: a fold is the one case where the HUD is
        // deliberately not showing the whole program, and that has to be visible in the row, not just in a
        // control the eye skips.
        String suffix = incomplete ? "   ⚠ missing value" : (broken ? "   ✖ error" : "");
        if (row.fold() == BlockTree.Fold.COLLAPSED) suffix += "   …";
        Label text = new Label((locked ? "🔒 " : "") + compactLabel(stmt) + suffix);
        // An empty slot or a compile error shows red before the user has to go looking for it; scaffolding is
        // dimmed, because "why is there no ⋮ on this row" was the only signal that it wasn't the user's to edit.
        text.getStyleClass().add("overlay-row-text");
        if (incomplete || broken) text.getStyleClass().add("overlay-row-text-broken");
        else if (locked) text.getStyleClass().add("overlay-row-text-locked");

        HBox node = shell(row, focused);
        if (row.fold() != BlockTree.Fold.NONE) node.getChildren().add(foldToggle(row));
        // The probe's gutter: hidden until the row's call has an answer, so a row with no probe takes no room.
        Label marker = new Label();
        marker.getStyleClass().add("overlay-probe");
        paint(marker, probed.apply(stmt));
        markers.put(stmt, marker);
        node.getChildren().add(marker);

        // A block that declares a render schema is drawn from it — the same components the canvas draws, on
        // one line. One that declares none falls back to its source text, which is what every row was until
        // a block first declared a spec, and what most rows still are. The fallback is not a degraded mode:
        // the text is also this row's tooltip either way.
        List<Node> declared = compactNodes(stmt, locked);
        if (declared.isEmpty()) node.getChildren().add(text);
        else node.getChildren().addAll(declared);
        Tooltip.install(node, new Tooltip(rowTooltip(stmt, locked, broken)));
        node.setOnMouseClicked(e -> callbacks.onFocus().accept(new InsertionCursor(row.body(), row.index())));
        node.setPickOnBounds(true);

        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        node.getChildren().add(spring);

        // One ⋮ per row for everything done to the block, so a row is never a strip of glyph buttons. Generated
        // scaffolding is not the user's to change, so a read-only row's ⋮ only tries it, which edits nothing.
        MenuButton menu = rowMenu(stmt, row, locked);
        if (menu != null) node.getChildren().add(menu);
        return node;
    }

    /** The row's ⋮: try the block, configure a call's arguments, move it, delete it; null when it holds nothing. */
    private MenuButton rowMenu(StatementBlock stmt, BlockTree.Row row, boolean locked) {
        MenuButton menu = new MenuButton("⋮");
        menu.getStyleClass().add("overlay-row-menu");
        menu.setTooltip(new Tooltip("What to do with this block"));
        TrialMenu.item(stmt, context.getEventBus(),
                () -> menu.getScene() == null ? null : menu.getScene().getWindow()).ifPresent(trial -> {
            menu.getItems().add(trial);
            if (!locked) menu.getItems().add(new SeparatorMenuItem());
        });
        if (locked) return menu.getItems().isEmpty() ? null : menu;
        if (stmt instanceof MethodInvocationBlock mib) {
            MenuItem config = new MenuItem("⚙ Configure arguments (Enter)");
            config.setOnAction(e -> callbacks.onConfig().accept(mib));
            menu.getItems().addAll(config, new SeparatorMenuItem());
        }
        MenuItem up = new MenuItem("Move up (Alt+↑)");
        up.setOnAction(e -> callbacks.onMove().move(stmt, row.body(), row.index(), -1));
        MenuItem down = new MenuItem("Move down (Alt+↓)");
        down.setOnAction(e -> callbacks.onMove().move(stmt, row.body(), row.index(), +1));
        MenuItem remove = new MenuItem("✕ Delete (Del)");
        remove.setOnAction(e -> callbacks.onDelete().run(stmt, row.body(), row.index()));
        menu.getItems().addAll(up, down, new SeparatorMenuItem(), remove);
        return menu;
    }

    /**
     * {@code stmt}'s components at HUD density, kept across a re-parse whenever the block itself was.
     *
     * <p>The canvas has had this since block reuse landed — a surviving block keeps its {@code uiNode}, so a
     * half-typed field survives an edit elsewhere. The HUD rebuilt its widgets on every pulse regardless,
     * which is the same loss on the surface a user is most often editing <em>through</em>: the overlay is the
     * only place a bot can be authored without leaving the game.
     *
     * <p><b>Only the declared components are cached, never the row.</b> The row's indent, fold arrow, focus
     * ring and reorder buttons all read the row model, and the row model moves while the block stands still.
     *
     * <p>The lock stamp is re-applied <b>both ways</b> on every call. A surviving block's verdict cannot
     * actually have moved — {@code BlockReuse} refuses every subtree when it does — but this is the shape
     * phase 4's breakpoint bug had, and a one-way stamp beside a cache is the same trap set again.
     *
     * <p>Safe to hand back the same nodes each pulse because there is one panel at a time
     * ({@code OverlayEditor.active}) and its canvas twin is a different object: a block builds fresh
     * suppliers per {@code componentSpec} call, so the two surfaces never contend for one node's parent.
     */
    private List<Node> compactNodes(StatementBlock stmt, boolean locked) {
        List<Node> nodes;
        if (stmt instanceof AbstractCodeBlock block) {
            nodes = block.getCompactNodes();
            if (nodes == null) {
                nodes = CompactSpecRow.nodes(spec(stmt), locked, stmt instanceof BranchingBlock);
                block.setCompactNodes(nodes);
            }
        } else {
            nodes = CompactSpecRow.nodes(spec(stmt), locked, stmt instanceof BranchingBlock);
        }
        for (Node n : nodes) n.pseudoClassStateChanged(ReadOnlyDecorator.READ_ONLY, locked);
        return nodes;
    }

    /**
     * {@code stmt}'s declared schema, or an empty one — including when the block throws asking for it.
     *
     * <p>Total on purpose: a spec is built by a block against a live editor session, and a block that cannot
     * describe itself must cost the user that row's widgets rather than the whole HUD. The empty answer is
     * the text fallback, which is the same outcome as never having declared a spec.
     */
    private ComponentSpec spec(StatementBlock stmt) {
        if (stmt == null || context == null) return ComponentSpec.empty();
        try {
            ComponentSpec declared = stmt.componentSpec(context);
            return declared == null ? ComponentSpec.empty() : declared;
        } catch (RuntimeException | LinkageError e) {
            System.err.println("Block could not describe itself: " + e);
            return ComponentSpec.empty();
        }
    }

    /**
     * The ▾/▸ that hides a control-flow block's bodies. Worth having on a surface this small: one {@code if}
     * with three branches can push everything after it off the visible window, and the HUD's whole job is to
     * show where you are in the program.
     */
    private Button foldToggle(BlockTree.Row row) {
        boolean collapsed = row.fold() == BlockTree.Fold.COLLAPSED;
        Button b = OverlayStyles.iconButton(collapsed ? "▸" : "▾",
                collapsed ? "Expand this block's branches" : "Collapse this block's branches",
                () -> callbacks.onToggleFold().accept(row.stmt()));
        b.setMinWidth(22);
        b.setPadding(new Insets(0, 4, 0, 4));
        return b;
    }

    /**
     * A branch label — {@code else}, {@code case A:}, {@code otherwise}. Clickable like any row: it parks the
     * caret at the top of the branch it introduces, which is the only way to aim at an empty branch.
     */
    private HBox captionRow(BlockTree.Row row, InsertionCursor cursor) {
        Label text = new Label(row.caption());
        text.getStyleClass().addAll("overlay-row-text", "overlay-row-caption");
        HBox node = shell(row, focused(row, cursor));
        node.getChildren().add(text);
        if (row.body() != null) {
            node.setOnMouseClicked(e -> callbacks.onFocus().accept(new InsertionCursor(row.body(), -1)));
        }
        return node;
    }

    private HBox emptyRow(BlockTree.Row row, InsertionCursor cursor) {
        Label text = new Label("· (empty) ·");
        text.getStyleClass().add("overlay-row-empty");
        HBox node = shell(row, cursor != null && cursor.body() == row.body());
        node.getChildren().add(text);
        node.setOnMouseClicked(e -> callbacks.onFocus().accept(new InsertionCursor(row.body(), 0)));
        return node;
    }

    /** The row container: indent, padding, and the focus highlight. */
    private static HBox shell(BlockTree.Row row, boolean focused) {
        HBox node = new HBox(6, indent(row.depth()));
        node.setAlignment(Pos.CENTER_LEFT);
        node.setPadding(new Insets(3, 6, 3, 6));
        node.getStyleClass().add("overlay-row");
        if (focused) node.getStyleClass().add("overlay-row-focused");
        return node;
    }

    private static boolean focused(BlockTree.Row row, InsertionCursor c) {
        return c != null && c.body() == row.body() && c.index() == row.index();
    }

    private static Region indent(int depth) {
        Region r = new Region();
        r.setMinWidth(depth * 16.0);
        r.setPrefWidth(depth * 16.0);
        return r;
    }

    /** The full text a truncated row hides, plus why it can't be edited or won't compile. */
    private String rowTooltip(StatementBlock stmt, boolean locked, boolean broken) {
        StringBuilder sb = new StringBuilder(fullText(stmt));
        if (locked) sb.append("\n\n🔒 Generated code — edit it in the project's activity flow, not here.");
        if (broken) {
            for (var d : diagnostics.getDiagnosticsForBlock(stmt)) sb.append("\n\n✖ ").append(d.getMessage());
        }
        return sb.toString();
    }

    private static String fullText(CodeBlock block) {
        ASTNode n = block.getAstNode();
        return n == null ? block.getClass().getSimpleName() : n.toString().strip();
    }

    /** One-line summary of a block: the first source line of its AST node, trimmed and truncated. */
    static String compactLabel(CodeBlock block) {
        ASTNode n = block.getAstNode();
        if (n == null) return block.getClass().getSimpleName();
        String s = n.toString().strip();
        int nl = s.indexOf('\n');
        if (nl >= 0) s = s.substring(0, nl).strip();
        if (s.endsWith("{")) s = s.substring(0, s.length() - 1).strip();
        return s.length() > MAX_LABEL_CHARS ? s.substring(0, MAX_LABEL_CHARS - 3) + "…" : s;
    }
}
