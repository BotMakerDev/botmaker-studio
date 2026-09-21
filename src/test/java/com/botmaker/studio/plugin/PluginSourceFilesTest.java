package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.source.PluginSource;
import com.botmaker.studio.project.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file a plugin gives a bot, landing once and never again.
 *
 * <p>Two properties carry the whole design and both are asserted here: <b>the package it declares is the
 * package it is written into</b> (so it compiles the moment it lands, before any editor has opened it), and
 * <b>an existing file is never written over</b> (so a re-install, a reload or the next project open cannot
 * throw away what the user has drawn).
 */
class PluginSourceFilesTest {

    /** A plugin that ships one file, and nothing else this test needs. */
    private record Shipping(String id, List<PluginSource> sources) implements StudioPlugin {
        @Override public List<PluginSource> pluginSources() { return sources; }
    }

    private static final String SOURCE = """
            package ${package};

            import com.botmaker.plugin.basics.managed.Managed;

            /** The plugin's values for this bot. */
            public final class Sdk {

                @Managed("flow")
                public static String flow() {
                    return "Collect";
                }
            }
            """;

    private static StudioPlugin sdk() {
        return new Shipping("com.botmaker.sdk", List.of(new PluginSource("Sdk", SOURCE)));
    }

    private static ProjectConfig configIn(Path root) {
        return ProjectConfig.forProject("MyBot", root);
    }

    private static Path fileIn(ProjectConfig config) {
        return config.mainPackageDir().resolve("plugins").resolve("sdk").resolve("Sdk.java");
    }

    @Test
    void theFileLandsInThePluginsPackageAndDeclaresIt(@TempDir Path root) throws IOException {
        ProjectConfig config = configIn(root);

        List<Path> written = PluginSourceFiles.install(config, List.of(sdk()));

        Path file = fileIn(config);
        assertEquals(List.of(file), written);
        assertTrue(Files.isRegularFile(file), file.toString());
        // The package it declares is the package the path spells — the file compiles where it landed.
        assertTrue(Files.readString(file)
                .startsWith("package " + config.mainPackage() + ".plugins.sdk;"), Files.readString(file));
        assertFalse(Files.readString(file).contains(PluginSource.PACKAGE));
    }

    @Test
    void aFileThatIsAlreadyThereIsNeverWrittenOver(@TempDir Path root) throws IOException {
        ProjectConfig config = configIn(root);
        PluginSourceFiles.install(config, List.of(sdk()));

        Path file = fileIn(config);
        String edited = Files.readString(file).replace("\"Collect\"", "\"Battle\"")
                + "\n// and a comment the user added\n";
        Files.writeString(file, edited);

        // Every later bind runs this again: a project open, a Reload Plugins, a re-install.
        assertEquals(List.of(), PluginSourceFiles.install(config, List.of(sdk())));
        assertEquals(List.of(), PluginSourceFiles.install(config, List.of(sdk())));
        assertEquals(edited, Files.readString(file));
    }

    @Test
    void aPluginThatShipsNothingWritesNothing(@TempDir Path root) {
        ProjectConfig config = configIn(root);

        assertEquals(List.of(), PluginSourceFiles.install(config,
                List.of(new Shipping("com.example.quiet", List.of()))));
        assertFalse(Files.isDirectory(config.mainPackageDir().resolve("plugins")));
    }

    /** A plugin that throws costs only its own file: the project still opens, and its neighbours land. */
    @Test
    void aThrowingPluginCostsOnlyItself(@TempDir Path root) {
        ProjectConfig config = configIn(root);
        StudioPlugin broken = new StudioPlugin() {
            @Override public String id() { return "com.example.broken"; }

            @Override public List<PluginSource> pluginSources() {
                throw new IllegalStateException("no");
            }
        };

        assertEquals(List.of(fileIn(config)), PluginSourceFiles.install(config, List.of(broken, sdk())));
    }

    @Test
    void thePackageIsTheBotsOwnThenPluginsThenTheIdsLastSegment() {
        assertEquals("com.mybot.plugins.sdk", PluginSourceFiles.packageOf("com.mybot", "sdk"));
        // A bot with no package of its own puts them at the top level, which still compiles.
        assertEquals("plugins.sdk", PluginSourceFiles.packageOf("", "sdk"));
    }

    @Test
    void anIdThatIsNotAnIdentifierStillMakesAPackageName() {
        assertEquals("sdk", PluginSourceFiles.segmentOf("com.botmaker.sdk"));
        assertEquals("discord", PluginSourceFiles.segmentOf("discord"));
        assertEquals("my_plugin", PluginSourceFiles.segmentOf("com.acme.My-Plugin"));
        assertEquals("_2fa", PluginSourceFiles.segmentOf("com.acme.2fa"));
        assertEquals("", PluginSourceFiles.segmentOf(""));
    }
}
