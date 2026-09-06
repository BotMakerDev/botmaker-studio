package com.botmaker.studio.ui.app;

import com.botmaker.plugin.host.PluginLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sentence Manage Plugins says about plugins that did not load.
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
        assertEquals("", ManagePluginsDialog.failureText(List.of()));
    }

    @Test
    void one_failure_names_the_plugin_and_the_cause() {
        String text = ManagePluginsDialog.failureText(List.of(new PluginLoader.PluginFailure(
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
        String text = ManagePluginsDialog.failureText(List.of(
                new PluginLoader.PluginFailure("a.One", new IllegalStateException("no display")),
                new PluginLoader.PluginFailure("b.Two", new IllegalStateException("no display"))));

        assertTrue(text.startsWith("2 plugins on this project's classpath did not load: "), text);
        assertTrue(text.contains("a.One") && text.contains("b.Two"), text);
    }

    /** A cause with no message still says something, because a blank line is a line nobody can act on. */
    @Test
    void a_cause_with_no_message_falls_back_to_its_type() {
        String text = ManagePluginsDialog.failureText(
                List.of(new PluginLoader.PluginFailure("a.One", new NoClassDefFoundError())));

        assertTrue(text.contains("NoClassDefFoundError"), text);
    }
}
