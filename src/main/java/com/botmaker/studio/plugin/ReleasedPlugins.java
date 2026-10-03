package com.botmaker.studio.plugin;

import com.botmaker.plugin.host.PluginLoader;
import com.botmaker.studio.services.MavenService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Studio loads released plugins only (2026-10-03, the maintainer's call): a plugin jar resolved at a
 * {@code -SNAPSHOT} version is left off the loader and reported, never bound.
 *
 * <p>A dev build is a jar only this machine has — {@code mvn install} put it in {@code ~/.m2} — so a bot
 * edited against it cannot be published, and the editor would describe a plugin no user of the bot will ever
 * run. Until this date Studio loaded whatever the pom pinned and only Publish refused the SNAPSHOT, which let
 * a whole session of edits pile up against a build that was then refused at the last step.
 *
 * <p><b>Only plugins are refused.</b> A SNAPSHOT library that declares no {@code StudioPlugin} stays on the
 * classpath: it is the bot's business, and Maven builds (the umbrella's {@code -Ptemplates}, CI) still use
 * SNAPSHOTs freely. The version is the Maven repository's own: the directory a resolved jar sits in.
 *
 * <p>The way back is <i>Project ▸ Plugins &amp; Libraries ▸ Installed</i>, which seeds a dev row with a
 * released version and moves it through the same checked pass as any upgrade.
 */
public final class ReleasedPlugins {

    /** The sentence a refused plugin is reported with, after its coordinate. */
    static final String REFUSAL = "a dev build, and Studio loads released versions only. Pin a released "
            + "version in Project ▸ Plugins & Libraries ▸ Installed";

    private ReleasedPlugins() {}

    /** What the loader may open, and the plugins left off it. */
    public record Split(List<String> loadable, List<PluginLoader.PluginFailure> refused) {
        public Split {
            loadable = List.copyOf(loadable);
            refused = List.copyOf(refused);
        }
    }

    /** {@link #split(List, Predicate)} over the jars' own service files. */
    public static Split split(List<String> resolvedClasspath) {
        return split(resolvedClasspath, MavenService::declaresPlugin);
    }

    /**
     * Leaves every dev-build plugin jar out of {@code resolvedClasspath}.
     *
     * @param declaresPlugin whether a jar carries the {@code StudioPlugin} service entry; asked of dev-build
     *                       jars only, so a released classpath opens no jar here
     */
    public static Split split(List<String> resolvedClasspath, Predicate<Path> declaresPlugin) {
        List<String> loadable = new ArrayList<>();
        List<PluginLoader.PluginFailure> refused = new ArrayList<>();
        for (String entry : resolvedClasspath) {
            Path jar = Path.of(entry);
            String version = versionOf(jar);
            if (isDevVersion(version) && declaresPlugin.test(jar)) {
                refused.add(new PluginLoader.PluginFailure(artifactOf(jar) + " " + version,
                        new IllegalStateException(REFUSAL)));
            } else {
                loadable.add(entry);
            }
        }
        return new Split(loadable, refused);
    }

    /** Whether {@code version} names a build only this machine has: a SNAPSHOT, or a property nobody set. */
    public static boolean isDevVersion(String version) {
        if (version == null) return false;
        return version.toUpperCase(Locale.ROOT).endsWith("-SNAPSHOT") || version.contains("${");
    }

    /** The version directory a jar sits in, in the Maven repository layout; "" for anything shallower. */
    static String versionOf(Path jar) {
        Path dir = jar.getParent();
        return dir == null || dir.getFileName() == null ? "" : dir.getFileName().toString();
    }

    private static String artifactOf(Path jar) {
        Path dir = jar.getParent() == null ? null : jar.getParent().getParent();
        return dir == null || dir.getFileName() == null ? jar.getFileName().toString() : dir.getFileName().toString();
    }
}
