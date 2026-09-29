package com.botmaker.studio.ui.app;

import com.botmaker.plugin.host.PluginLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sentence Plugins &amp; Libraries says about plugins that did not load (Manage Plugins said it until
 * 2026-09-29, inside one of five windows).
 *
 * <p><b>Why this is worth a test at all:</b> three incidents in this project's record end with the same
 * words — <i>an empty palette and one line on stderr</i>. The line was printed where nobody reads, and
 * nothing above the loader could tell <i>this project pins no plugin</i> from <i>this project pins a plugin
 * that is broken</i>. Those are the same screen and completely different problems.
 *
 * <p>The formatting is static and pure so it can be asserted with no scene, the same split as
 * {@code BlockTree} and {@code PluginRegistry.Plugin}. What a test cannot answer is where the label sits and
 * whether it is legible in both themes.
 */
class PluginFailureTextTest {

    @Test
    void everything_loading_says_nothing() {
        assertEquals("", PluginsWindow.failureText(List.of()));
    }

    @Test
    void one_failure_names_the_plugin_and_the_cause() {
        String text = PluginsWindow.failureText(List.of(new PluginLoader.PluginFailure(
                "com.example.demo.ExamplePlugin",
                new NoClassDefFoundError("com/botmaker/plugin/toolkit/AbstractStudioPlugin"))));

        assertTrue(text.startsWith("A plugin on this project's classpath did not load: "), text);
        assertTrue(text.contains("com.example.demo.ExamplePlugin"), text);
        assertTrue(text.contains("AbstractStudioPlugin"), text);
        // The project is not broken and must not be described as if it were.
        assertTrue(text.contains("the project itself is unaffected"), text);
    }

    @Test
    void two_failures_are_counted_and_both_named() {
        String text = PluginsWindow.failureText(List.of(
                new PluginLoader.PluginFailure("a.One", new IllegalStateException("no display")),
                new PluginLoader.PluginFailure("b.Two", new IllegalStateException("no display"))));

        assertTrue(text.startsWith("2 plugins on this project's classpath did not load: "), text);
        assertTrue(text.contains("a.One") && text.contains("b.Two"), text);
    }

    /** A cause with no message still says something, because a blank line is a line nobody can act on. */
    @Test
    void a_cause_with_no_message_falls_back_to_its_type() {
        String text = PluginsWindow.failureText(
                List.of(new PluginLoader.PluginFailure("a.One", new NoClassDefFoundError())));

        assertTrue(text.contains("NoClassDefFoundError"), text);
    }

    /** The canvas banner: each refused plugin as the loader says it, then each jar that did not download. */
    @Test
    void the_banner_lists_refused_plugins_then_failed_downloads() {
        List<String> lines = EditorCanvas.loadProblemLines(
                List.of(new PluginLoader.PluginFailure("a.One", new IllegalStateException("no display"))),
                List.of("g:a:v1 — not found"));

        assertEquals(List.of("a.One — no display", "could not download g:a:v1 — not found"), lines);
    }

    /**
     * A contract class the host lacks is another Studio's plugin, not a jar to put back — the SDK built on a
     * deleted contract type used to read <i>a plugin — com/…/ValueCatalog is not on the classpath</i>.
     */
    @Test
    void a_missing_contract_class_reads_as_another_studios_plugin() {
        String text = new PluginLoader.PluginFailure(null,
                new NoClassDefFoundError("com/botmaker/plugin/api/value/ValueCatalog")).describe();

        assertEquals("a plugin — built for a different Studio: it uses ValueCatalog, which this Studio has not got",
                text);
    }
}
