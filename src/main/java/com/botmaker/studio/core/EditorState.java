package com.botmaker.studio.core;

import com.botmaker.core.project.ProjectState;
import org.eclipse.jdt.core.dom.ASTNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The open project's state as Studio draws it: {@link ProjectState}, plus the blocks drawn for the active file, the
 * selected block and the overlay's insertion caret.
 *
 * <p>These were {@code ProjectState}'s own until 2026-11-05. They name {@link CodeBlock}, a JavaFX node, so a state
 * holding them could not live where nothing draws; the project's state goes to {@code botmaker-core} and this stays.
 * Confined to the FX thread, as {@code ProjectState} is.
 */
public class EditorState extends ProjectState {

    // Replaced wholesale, never mutated in place: a rebuild fills a fresh map and publishes it here in one
    // assignment, so a reader iterating the previous one is never structurally modified underneath. volatile
    // because that assignment is what publishes it.
    private volatile Map<ASTNode, CodeBlock> nodeToBlockMap = Map.of();

    private CodeBlock highlightedBlock;
    private InsertionCursor insertionCursor;

    /**
     * The block registry, as an unmodifiable snapshot in its own right — the map returned here is never
     * mutated again, so it stays safe to hold and to iterate across an edit.
     */
    public Map<ASTNode, CodeBlock> getNodeToBlockMap() {
        return nodeToBlockMap;
    }

    /**
     * Publishes a freshly built registry. The caller fills its own map and hands it over complete; there is
     * deliberately no accessor that exposes the live one, because a half-filled registry is what a background
     * reader used to walk.
     */
    public void setNodeToBlockMap(Map<ASTNode, CodeBlock> map) {
        // unmodifiableMap over a copy rather than Map.copyOf: the values are nullable (an unmapped node) and
        // Map.copyOf rejects nulls.
        this.nodeToBlockMap = map != null ? Collections.unmodifiableMap(new HashMap<>(map)) : Map.of();
    }

    public void clearNodeToBlockMap() {
        this.nodeToBlockMap = Map.of();
    }

    public Optional<CodeBlock> getBlockForNode(ASTNode node) {
        return Optional.ofNullable(nodeToBlockMap.get(node));
    }

    @Override
    public Optional<String> blockClassOf(ASTNode node) {
        return getBlockForNode(node).map(block -> block.getClass().getSimpleName());
    }

    public Optional<CodeBlock> getHighlightedBlock() {
        return Optional.ofNullable(highlightedBlock);
    }

    public void setHighlightedBlock(CodeBlock block) {
        if (this.highlightedBlock != null) this.highlightedBlock.unhighlight();
        this.highlightedBlock = block;
        if (this.highlightedBlock != null) this.highlightedBlock.highlight();
    }

    public void clearHighlight() {
        setHighlightedBlock(null);
    }

    /**
     * The overlay authoring caret (where the next block is inserted), or empty when no overlay session is
     * active. Distinct from {@link #getHighlightedBlock()} (a selected block) — see {@link InsertionCursor}.
     */
    public Optional<InsertionCursor> getInsertionCursor() {
        return Optional.ofNullable(insertionCursor);
    }

    public void setInsertionCursor(InsertionCursor cursor) {
        this.insertionCursor = cursor;
    }
}
