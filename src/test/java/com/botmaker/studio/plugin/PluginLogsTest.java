package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.PluginLog;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.host.LoadedPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayNameGeneration(ReplaceUnderscores.class)
class PluginLogsTest {

    @TempDir
    Path dir;

    @AfterEach
    void forget() {
        PluginLogs.clear();
    }

    @Test
    void a_plugins_lines_are_kept_under_its_coordinate_oldest_first() {
        LoadedPlugin plugin = TestPlugins.loaded("writer", StudioPlugin.declare());
        PluginLog log = PluginLogs.of(plugin);

        log.info("ready");
        log.warn("tailscale not found");
        log.error("capture failed", new IllegalStateException("no window"));

        List<PluginLogs.Line> lines = PluginLogs.lines(plugin.coordinate());
        assertEquals(List.of(PluginLogs.Level.INFO, PluginLogs.Level.WARN, PluginLogs.Level.ERROR),
                lines.stream().map(PluginLogs.Line::level).toList());
        assertEquals("capture failed: java.lang.IllegalStateException: no window", lines.get(2).text());
        assertTrue(PluginLogs.lines(TestPlugins.loaded("quiet", StudioPlugin.declare()).coordinate()).isEmpty());
    }

    @Test
    void only_the_newest_lines_are_kept() {
        LoadedPlugin plugin = TestPlugins.loaded("chatty", StudioPlugin.declare());
        for (int i = 0; i < PluginLogs.KEPT + 5; i++) PluginLogs.of(plugin).info("line " + i);

        List<PluginLogs.Line> lines = PluginLogs.lines(plugin.coordinate());
        assertEquals(PluginLogs.KEPT, lines.size());
        assertEquals("line 5", lines.getFirst().text());
    }

    @Test
    void a_line_no_plugin_wrote_is_kept_by_nobody() {
        PluginLogs.CALLER.warn("from Studio itself");

        assertTrue(PluginHost.plugins().stream().allMatch(p -> PluginLogs.lines(p.coordinate()).isEmpty()));
    }

    @Test
    void a_line_reads_with_its_time_and_level() {
        assertEquals("14:02:11 WARN  tailscale not found",
                new PluginLogs.Line(LocalTime.of(14, 2, 11), PluginLogs.Level.WARN, "tailscale not found").display());
    }

    @Test
    void a_jars_icon_is_read_and_a_missing_one_is_empty() throws IOException {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        Path jar = dir.resolve("plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(PluginJarFiles.ICON));
            out.write(png);
            out.closeEntry();
        }

        assertArrayEquals(png, PluginJarFiles.read(jar, PluginJarFiles.ICON).orElseThrow());
        assertTrue(PluginJarFiles.read(jar, PluginJarFiles.CHANGELOG).isEmpty());
        assertTrue(PluginJarFiles.read(dir.resolve("absent.jar"), PluginJarFiles.ICON).isEmpty());
    }
}
