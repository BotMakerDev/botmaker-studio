package com.botmaker.studio.ui.app;

import com.botmaker.studio.blocks.func.MethodDeclarationBlock;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.nav.LibrarySource;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.render.theme.CanvasZoom;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Go to Definition on a library member (2026-09-27): the whole class, drawn as blocks the way the bot's own
 * code is, every one locked, and scrolled to the member with it highlighted. It replaced a text window
 * ({@code LibrarySourceWindow}, 2026-09-26) because the point of opening a library is to see how the thing the
 * bot calls is put together, and the canvas is the one way this program shows that.
 *
 * <p>Nothing here is the project's. The class is parsed without bindings (it compiles against its own library,
 * not the bot), drawn by {@link BlockConverter#convertReadOnly} into a block map of its own — never the open
 * file's — and the blocks, being read-only, offer no edit. Where the library shipped no sources the text is an
 * outline of its signatures ({@link LibrarySource.Origin#OUTLINE}): every function then says so where its body
 * would be, rather than drawing an empty body that reads as a function that does nothing.
 */
final class LibraryClassView {

    /** Said where an outlined function's body would be. */
    static final String NO_SOURCE = "No source for this library — signature only";

    private LibraryClassView() {}

    /** What was drawn: the root block, every block by its node, and the block to land on (null for the top). */
    record Drawn(AbstractCodeBlock root, Map<ASTNode, CodeBlock> blocks, CodeBlock target, List<String> problems) {}

    /**
     * {@code view} as blocks: the type the member is declared in — a nested one drawn on its own, since the
     * canvas draws one type per file — and the block of the declaration on {@code view.line()}.
     */
    static Drawn draw(LibrarySource.View view, BlockConverter converter, BlockDragAndDropManager manager) {
        CompilationUnit cu = SourceParser.parse(view.text());
        int offset = view.line() > 0 ? cu.getPosition(view.line(), 0) : -1;
        AbstractTypeDeclaration type = typeAt(cu, offset);
        Map<ASTNode, CodeBlock> blocks = new HashMap<>();
        // A manager of its own: every block here is locked, so it wires no drag and no drop — a body still asks
        // it for the plain gaps between statements.
        BlockConverter.ConvertResult result = converter.convertReadOnly(cu, view.text(), type, blocks, manager);
        CodeBlock target = view.line() > 0 ? declaredOn(cu, view.line(), blocks).orElse(null) : null;
        boolean outline = view.origin() == LibrarySource.Origin.OUTLINE;
        for (CodeBlock block : blocks.values()) {
            if (!(block instanceof MethodDeclarationBlock method)) continue;
            if (outline) {
                // An outline has no bodies at all; the `{}` the parser recovers for a `default` method is its own
                // repair of `;`, not code anybody wrote.
                method.setBody(null);
                method.setMissingBody(NO_SOURCE);
            }
            // Folded, but for the member asked for: a long class opens as a list of signatures, each body built
            // the moment it is opened.
            method.collapseHere(method != target);
        }
        return new Drawn(result.root(), blocks, target, result.problems());
    }

    /**
     * The innermost type whose source covers {@code offset}, or the file's first type — the one the canvas
     * draws for a member of {@code Map.Entry} is {@code Entry}, because {@code Map}'s block has no row for a
     * nested class.
     */
    static AbstractTypeDeclaration typeAt(CompilationUnit cu, int offset) {
        if (cu.types().isEmpty()) return null;
        AbstractTypeDeclaration found = (AbstractTypeDeclaration) cu.types().getFirst();
        if (offset < 0) return found;
        List<?> members = cu.types();
        boolean deeper = true;
        while (deeper) {
            deeper = false;
            for (Object member : members) {
                if (member instanceof AbstractTypeDeclaration t && t.getStartPosition() <= offset
                        && offset < t.getStartPosition() + t.getLength()) {
                    found = t;
                    members = t.bodyDeclarations();
                    deeper = true;
                    break;
                }
            }
        }
        return found;
    }

    /** The block of the declaration whose name is on 1-based {@code line}. */
    static Optional<CodeBlock> declaredOn(CompilationUnit cu, int line, Map<ASTNode, CodeBlock> blocks) {
        for (Map.Entry<ASTNode, CodeBlock> entry : blocks.entrySet()) {
            ASTNode name = nameOf(entry.getKey());
            if (name != null && cu.getLineNumber(name.getStartPosition()) == line) return Optional.of(entry.getValue());
        }
        return Optional.empty();
    }

    private static ASTNode nameOf(ASTNode declaration) {
        return switch (declaration) {
            case MethodDeclaration m -> m.getName();
            case AbstractTypeDeclaration t -> t.getName();
            case FieldDeclaration f when !f.fragments().isEmpty() ->
                    ((VariableDeclarationFragment) f.fragments().getFirst()).getName();
            case BodyDeclaration ignored -> null;
            default -> null;
        };
    }

    /** The tab's content: a banner saying where the class came from, above the locked blocks. FX thread. */
    static Node build(LibrarySource.View view, BlockConverter converter, CodeEditorService context) {
        Drawn drawn = draw(view, converter, new BlockDragAndDropManager(context.getEventBus()));
        Label banner = new Label(banner(view, drawn.problems()));
        banner.setWrapText(true);
        banner.getStyleClass().add("library-source-banner");

        VBox blocks = new VBox(10);
        blocks.getStyleClass().addAll("blocks-canvas", "library-canvas");
        blocks.setPadding(new Insets(20));
        EditorCanvas.followBlockStyle(blocks);
        EditorCanvas.followBlockFont(blocks);
        if (drawn.root() != null) blocks.getChildren().add(drawn.root().getUINode(context));
        else blocks.getChildren().add(new Label("This class could not be drawn."));

        ZoomPane zoom = new ZoomPane(blocks);
        zoom.zoomProperty().bind(CanvasZoom.factorProperty());
        ScrollPane scroll = new ScrollPane(zoom);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("code-scroll-pane");

        BorderPane pane = new BorderPane(scroll);
        pane.setTop(banner);
        if (drawn.target() != null) {
            // Once the tab has laid the blocks out: before that the target has no position to scroll to.
            scroll.sceneProperty().addListener((obs, was, now) -> {
                if (now != null) Platform.runLater(() -> land(scroll, zoom, drawn.target()));
            });
        }
        return pane;
    }

    private static void land(ScrollPane scroll, ZoomPane zoom, CodeBlock target) {
        target.highlight();
        Node node = target.getUINode();
        if (node == null || node.getScene() == null) return;
        scroll.applyCss();
        scroll.layout();
        Bounds bounds = zoom.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        double contentH = zoom.getHeight();
        double viewportH = scroll.getViewportBounds().getHeight();
        if (bounds != null && contentH > viewportH) {
            scroll.setVvalue(Math.max(0, Math.min(1, (bounds.getMinY() - 20) / (contentH - viewportH))));
        }
    }

    static String banner(LibrarySource.View view, List<String> problems) {
        String from = view.from() == null ? "the Java runtime" : fileName(view.from());
        String said = switch (view.origin()) {
            case SOURCES -> "Library code, read-only. From " + from + ".";
            case OUTLINE -> "Library outline, read-only. No sources were found for " + from
                    + ", so its functions show their signatures without their code.";
        };
        return problems.isEmpty() ? said : said + " Not drawn: " + String.join("; ", problems) + ".";
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }
}
