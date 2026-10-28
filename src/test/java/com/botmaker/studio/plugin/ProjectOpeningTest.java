package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.host.LoadedPlugin;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling the incoming plugins <em>which</em> project they are now serving — the mirror of
 * {@link ProjectClosingTest}.
 *
 * <p>The failure this guards against is not a leak but an empty window. A plugin is constructed once by the
 * loader and then serves whatever is bound to it, so a plugin never told which project it has cannot read that
 * project's own files at all — and a plugin that answers nothing looks exactly like a plugin with nothing to
 * contribute. Nothing about it fails to compile.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class ProjectOpeningTest {

    /** Enough of a {@link StudioServices} to be recognised again; nothing here reads it. */
    private static final StudioServices A_PROJECT = new HostServices(null, null);

    /** A plugin that writes down which project it was handed. */
    private static final class Fake extends TestPlugins.Fake {
        private final AtomicReference<StudioServices> handed;

        Fake(String id, List<String> log) {
            this(id, log, new AtomicReference<>());
        }

        private Fake(String id, List<String> log, AtomicReference<StudioServices> handed) {
            super(StudioPlugin.declare().onProjectOpened(services -> {
                handed.set(services);
                log.add(id);
            }));
            this.handed = handed;
        }

        StudioServices held() {
            return handed.get();
        }
    }

    /** A bug in somebody else's jar, met on the way into a project. */
    private static final class Broken extends TestPlugins.Fake {
        Broken() {
            super(StudioPlugin.declare().onProjectOpened(services -> {
                throw new IllegalStateException("no");
            }));
        }
    }

    private static LoadedPlugin loaded(String artifactId, StudioPlugin plugin) {
        return TestPlugins.loaded(artifactId, plugin);
    }

    @Test
    void every_plugin_about_to_serve_the_project_is_handed_it() {
        List<String> log = new ArrayList<>();
        Fake first = new Fake("first", log);
        Fake second = new Fake("second", log);

        PluginHost.openIncoming(List.of(loaded("first", first), loaded("second", second)), A_PROJECT);

        assertEquals(List.of("first", "second"), log);
        assertSame(A_PROJECT, first.held());
        assertSame(A_PROJECT, second.held());
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

        LoadedPlugin broken = loaded("broken", new Broken());
        PluginHost.openIncoming(List.of(broken, loaded("after", after)), A_PROJECT);

        assertEquals(List.of("after"), log);
        assertSame(A_PROJECT, after.held());
        assertTrue(PluginLogs.lines(broken.coordinate()).stream().anyMatch(line ->
                line.level() == PluginLogs.Level.ERROR && line.text().contains("opening project")));
    }

    /** A plugin that implements neither half of the lifecycle is the ordinary case. */
    @Test
    void a_plugin_that_never_heard_of_it_is_unaffected() {
        PluginHost.openIncoming(List.of(TestPlugins.loaded("older", StudioPlugin.declare())), A_PROJECT);
    }

    /**
     * Nobody is told when there is no project — an unbind. That is not an omission: every plugin that was
     * serving one has just had {@code projectClosing()}, which is the statement that it is over, and naming
     * a project that does not exist would be worse than naming none.
     */
    @Test
    void nobody_is_told_when_there_is_no_project() {
        List<String> log = new ArrayList<>();

        PluginHost.openIncoming(List.of(loaded("first", new Fake("first", log))), null);

        assertEquals(List.of(), log);
    }
}
