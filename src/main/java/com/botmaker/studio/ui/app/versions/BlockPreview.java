package com.botmaker.studio.ui.app.versions;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.vcs.BlockDiff;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One side of a file, drawn as the canvas draws it — read-only, in the user's style and font — against a
 * <b>staged</b> {@link ProjectState} of its own, as {@code AssistWorkspace} stages an assistant's edit. The live
 * editor is never touched: a preview has its own state, its own event bus and its own block registry
 * ({@code docs/refactor/39-versions.md} §5). FX thread.
 */
final class BlockPreview {

    static final PseudoClass ADDED = PseudoClass.getPseudoClass("diff-added");
    static final PseudoClass REMOVED = PseudoClass.getPseudoClass("diff-removed");
    static final PseudoClass CHANGED = PseudoClass.getPseudoClass("diff-changed");

    private final Map<ASTNode, CodeBlock> registry = new HashMap<>();
    private final CodeEditorService context;
    private final List<String> problems;
    private final boolean drawn;

    /** Draws {@code source} as the file {@code file} of the project {@code live} describes. */
    BlockPreview(ProjectConfig config, ProjectState live, Path file, String source) {
        ProjectState staged = new ProjectState();
        staged.setReaderMode(true);
        staged.addFile(new ProjectFile(file, source));
        staged.setActiveFile(file);
        staged.setSourcePath(live.getSourcePath());
        staged.setResolvedClasspath(live.getResolvedClasspath());
        staged.setTemplate(live.getTemplate());
        if (live.getSettings() != null) staged.setSettings(live.getSettings());
        staged.setCurrentCode(source);

        EventBus bus = new EventBus(false);
        BlockConverter converter = new BlockConverter(config, staged);
        BlockDragAndDropManager dnd = new BlockDragAndDropManager(bus);
        BlockConverter.ConvertResult result = converter.convert(source, registry, dnd, true, false);
        staged.setNodeToBlockMap(registry);
        staged.setCompilationUnit(result.cu());
        this.context = new CodeEditorService(config, staged, bus, converter, dnd, null,
                new ProjectAnalyzer(null, staged), null, null);
        this.problems = result.problems();
        this.drawn = result.root() != null;
    }

    /** Why this side could not be drawn whole, or an empty list. */
    List<String> problems() {
        return drawn ? problems : problems.isEmpty() ? List.of("This version of the file could not be drawn.") : problems;
    }

    /**
     * The function that starts at {@code start} in this side's source, drawn, with {@code spans} marked on the
     * blocks they fall on; null when the canvas did not draw it.
     */
    Node method(int start, List<BlockDiff.Span> spans) {
        CodeBlock block = find(MethodDeclaration.class, start, -1);
        if (block == null) return null;
        Node node = block.getUINode(context);
        for (BlockDiff.Span span : spans) {
            CodeBlock marked = find(Statement.class, span.start(), span.length());
            if (marked != null && marked.getUINode() != null) {
                marked.getUINode().pseudoClassStateChanged(pseudoClass(span.mark()), true);
            }
        }
        return node;
    }

    static PseudoClass pseudoClass(BlockDiff.Mark mark) {
        return switch (mark) {
            case ADDED -> ADDED;
            case REMOVED -> REMOVED;
            case CHANGED -> CHANGED;
        };
    }

    private CodeBlock find(Class<? extends ASTNode> kind, int start, int length) {
        for (var e : registry.entrySet()) {
            ASTNode n = e.getKey();
            if (kind.isInstance(n) && n.getStartPosition() == start && (length < 0 || n.getLength() == length)) {
                return e.getValue();
            }
        }
        return null;
    }
}
