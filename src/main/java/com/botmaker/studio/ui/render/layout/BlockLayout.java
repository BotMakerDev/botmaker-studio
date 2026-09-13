package com.botmaker.studio.ui.render.layout;

import com.botmaker.studio.core.component.ComponentSpec;

/**
 * Main entry point for building block UIs.
 * Provides fluent API for common block layouts.
 */
public class BlockLayout {

    // Factory methods for the layouts blocks actually use. A header() can continue into a body via
    // HeaderLayoutBuilder.andBody().
    public static HeaderLayoutBuilder header() {
        return new HeaderLayoutBuilder();
    }

    public static SentenceLayoutBuilder sentence() {
        return new SentenceLayoutBuilder();
    }

    /**
     * Renders a declared {@link ComponentSpec} instead of a hand-assembled sentence.
     *
     * <p>This is the migration target for {@code createUINode}, not a replacement for the builders above: a
     * block that has not declared a spec keeps using {@link #sentence()} / {@link #header()} and renders
     * exactly as before. Both coexist for as long as the migration takes.
     *
     * @param locked whether the code this block edits is locked — take it from the block's own
     *               {@code isReadOnly()}, which is what {@code LockResolver}'s verdict was written into while
     *               the block was parsed, rather than re-deriving it here.
     */
    public static ComponentLayoutBuilder components(ComponentSpec spec, boolean locked) {
        return new ComponentLayoutBuilder(spec, locked);
    }

    /**
     * The same, for a spec that declares a {@code BODY} — rows stacked with each body between them, which is
     * every control-flow block. Use {@link #components} when the block is one sentence; the two differ only in
     * where the row breaks, and share the one pass over the components.
     */
    public static StackLayoutBuilder stack(ComponentSpec spec, boolean locked) {
        return new StackLayoutBuilder(spec, locked);
    }
}
