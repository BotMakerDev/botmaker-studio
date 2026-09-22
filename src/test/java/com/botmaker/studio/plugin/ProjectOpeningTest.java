package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Telling the incoming plugins <em>which</em> project they are now serving — the mirror of
 * {@link ProjectClosingTest}.
 *
 * <p>The failure this guards against is not a leak but an empty window. A plugin is constructed once by
 * {@code ServiceLoader} and then serves whatever is bound to it, so a plugin never told which project it has
 * cannot read that project's own files at all — and a plugin that answers nothing looks exactly like a
 * plugin with nothing to contribute. Nothing about it fails to compile.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class ProjectOpeningTest {

    /** Enough of a {@link StudioServices} to be recognised again; nothing here reads it. */
    private static final StudioServices A_PROJECT = new HostServices(null, null);

    /** A plugin that writes down which project it was handed. */
    private static final class Fake implements StudioPlugin {
        private final String id;
        private final List<String> log;
        private StudioServices held;

        Fake(String id, List<String> log) {
            this.id = id;
            this.log = log;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void projectOpened(StudioServices services) {
            held = services;
            log.add(id);
        }
    }

    /** A bug in somebody else's jar, met on the way into a project. */
    private record Broken(String id) implements StudioPlugin {
        @Override
        public void projectOpened(StudioServices services) {
            throw new IllegalStateException("no");
        }
    }

    @Test
    void every_plugin_about_to_serve_the_project_is_handed_it() {
        List<String> log = new ArrayList<>();
        Fake first = new Fake("first", log);
        Fake second = new Fake("second", log);

        PluginHost.openIncoming(List.of(first, second), A_PROJECT);

        assertEquals(List.of("first", "second"), log);
        assertSame(A_PROJECT, first.held);
        assertSame(A_PROJECT, second.held);
    }

    /**
     * One plugin failing on the way in must not cost the next one its project, and must not stop the project
     * from opening. A plugin that refuses simply has no project — its data surfaces then answer nothing,
     * which is what an uninstalled plugin's do anyway.
     */
    @Test
    void a_plugin_that_throws_does_not_stop_the_others() {
        List<String> log = new ArrayList<>();
        Fake after = new Fake("after", log);

        PluginHost.openIncoming(List.of(new Broken("broken"), after), A_PROJECT);

        assertEquals(List.of("after"), log);
        assertSame(A_PROJECT, after.held);
    }

    /**
     * A plugin compiled against an earlier contract implements neither half of the lifecycle, which is the
     * ordinary case — every contribution surface returns data and needs none.
     */
    @Test
    void a_plugin_that_never_heard_of_it_is_unaffected() {
        StudioPlugin older = () -> "older";

        PluginHost.openIncoming(List.of(older), A_PROJECT);
    }

    /**
     * Nobody is told when there is no project — an unbind. That is not an omission: every plugin that was
     * serving one has just had {@code projectClosing()}, which is the statement that it is over, and naming
     * a project that does not exist would be worse than naming none.
     */
    @Test
    void nobody_is_told_when_there_is_no_project() {
        List<String> log = new ArrayList<>();

        PluginHost.openIncoming(List.of(new Fake("first", log)), null);

        assertEquals(List.of(), log);
    }
}
