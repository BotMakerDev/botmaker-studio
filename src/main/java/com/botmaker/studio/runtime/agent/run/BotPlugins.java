package com.botmaker.studio.runtime.agent.run;

import com.botmaker.plugin.host.PluginPoms;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * The plugins declared on the bot's classpath, found by plugin-host's own rule ({@link PluginPoms}: one jar, one
 * declaring pom), each handed its channel before the bot's {@code main}. Built on the bot's own loader, so a
 * plugin's bot half is the one the bot runs.
 */
final class BotPlugins {

    private BotPlugins() {}

    static void start(AgentRun run) {
        ClassLoader bot = ClassLoader.getSystemClassLoader();
        Set<String> handed = new HashSet<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            PluginPoms.Found found;
            try {
                found = PluginPoms.find(Path.of(entry));
            } catch (InvalidPathException | SecurityException e) {
                continue;
            } catch (RuntimeException | LinkageError e) {
                // One unreadable entry costs its own plugin, never the run's log and the other plugins.
                System.err.println("[BotMaker] could not read " + entry + " for a plugin: " + e);
                continue;
            }
            found.problems().forEach(problem -> System.err.println("[BotMaker] " + problem));
            for (PluginPoms.PluginPom pom : found.poms()) {
                String plugin = pom.coordinate().toString();
                if (!handed.add(plugin)) continue;
                try {
                    PluginChannel.hand(run, plugin, pom.pluginClass(), bot);
                } catch (NoClassDefFoundError older) {
                    // The bot's contract has no run channel: the plugin runs as it would with no host.
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    System.err.println("[BotMaker] " + plugin + " could not start its run: " + e);
                }
            }
        }
    }
}
