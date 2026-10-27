package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.host.LoadedPlugin;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling the outgoing plugins that their project is over.
 *
 * <p>The failure this guards against is invisible in exactly the way a leak is: a plugin that opened a port
 * or a nested display for a project keeps holding it, the next project opens fine, and the symptom arrives
 * much later as an address already in use or a display nobody reaped. Nothing about it is a red build, so it
 * is a test.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class ProjectClosingTest {

    /** A plugin that writes down that it was told. */
    private static final class Fake extends TestPlugins.Fake {
        private final String id;
        private final List<String> log;
        private int told;

        Fake(String id, List<String> log) {
            super(StudioPlugin.declare());
            this.id = id;
            this.log = log;
        }

        @Override
        public void projectClosing() {
            told++;
            log.add(id);
        }
    }

    /** A bug in somebody else's jar, met on the way out of a project. */
    private static final class Broken extends TestPlugins.Fake {
        Broken() {
            super(StudioPlugin.declare());
        }

        @Override
        public void projectClosing() {
            throw new IllegalStateException("no");
        }
    }

    private static LoadedPlugin loaded(String artifactId, StudioPlugin plugin) {
        return TestPlugins.loaded(artifactId, plugin);
    }

    @Test
    void every_plugin_serving_the_project_is_told_once() {
        List<String> log = new ArrayList<>();
        Fake first = new Fake("first", log);
        Fake second = new Fake("second", log);

        PluginHost.closeOutgoing(List.of(loaded("first", first), loaded("second", second)));

        assertEquals(List.of("first", "second"), log);
        assertEquals(1, first.told, "a plugin told twice would release something it no longer holds");
        assertEquals(1, second.told);
    }

    /**
     * One plugin failing on the way out must not cost the next one its own release — and must not stop the
     * project that is opening. The host cannot finish releasing on the plugin's behalf, so what the thrower
     * held is leaked; what it must not take with it is everybody else's.
     */
    @Test
    void a_plugin_that_throws_does_not_stop_the_others() {
        List<String> log = new ArrayList<>();
        Fake after = new Fake("after", log);

        PluginHost.closeOutgoing(List.of(loaded("broken", new Broken()), loaded("after", after)));

        assertEquals(List.of("after"), log);
    }

    /**
     * A plugin that does not implement it at all is the ordinary case — every contribution surface returns
     * data and needs no lifecycle — so the default must be a no-op.
     */
    @Test
    void a_plugin_that_never_heard_of_it_is_unaffected() {
        PluginHost.closeOutgoing(List.of(TestPlugins.loaded("older", StudioPlugin.declare())));
    }

    /**
     * Binding and unbinding with nothing resolvable leaves the empty set bound (2026-10-27: Studio bundles no
     * plugin, and the bundled set that served a classpath that loads nothing is gone).
     */
    @Test
    void nothing_is_bound_after_a_bind_and_unbind_of_nothing() {
        PluginHost.bind(List.of(), null);
        assertTrue(PluginHost.plugins().isEmpty());
        PluginHost.unbind();
        assertTrue(PluginHost.plugins().isEmpty());
    }
}
