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
 * <p><b>Dev mode is the one exception (2026-10-06)</b>: a project whose settings turn it on
 * ({@code StudioProjectSettings.devMode}) binds its dev builds, and they are reported as {@link Split#dev}
 * so the window can say so. It exists because a release was the only way to try a plugin change, and a
 * JitPack build that fails costs a tag. Publish still refuses the pins.
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
            + "version in Project ▸ Plugins & Libraries ▸ Installed, or turn on Dev mode there for this project";

    private ReleasedPlugins() {}

    /**
     * What the loader may open, the plugins left off it, and the dev-build plugin jars dev mode let through
     * (classpath entries, a subset of {@code loadable}; {@link #describe} names one).
     */
    public record Split(List<String> loadable, List<PluginLoader.PluginFailure> refused, List<String> dev) {
        public Split {
            loadable = List.copyOf(loadable);
            refused = List.copyOf(refused);
            dev = List.copyOf(dev);
        }
    }

    /** {@link #split(List, boolean, Predicate)} over the jars' own service files. */
    public static Split split(List<String> resolvedClasspath, boolean devMode) {
        return split(resolvedClasspath, devMode, MavenService::declaresPlugin);
    }

    /**
     * Leaves every dev-build plugin jar out of {@code resolvedClasspath}, unless {@code devMode}.
     *
     * @param declaresPlugin whether a jar carries the {@code StudioPlugin} service entry; asked of dev-build
     *                       jars only, so a released classpath opens no jar here
     */
    public static Split split(List<String> resolvedClasspath, boolean devMode, Predicate<Path> declaresPlugin) {
        List<String> loadable = new ArrayList<>();
        List<PluginLoader.PluginFailure> refused = new ArrayList<>();
        List<String> dev = new ArrayList<>();
        for (String entry : resolvedClasspath) {
            Path jar = Path.of(entry);
            String version = versionOf(jar);
            if (!isDevVersion(version) || !declaresPlugin.test(jar)) {
                loadable.add(entry);
            } else if (devMode) {
                loadable.add(entry);
                dev.add(entry);
            } else {
                refused.add(new PluginLoader.PluginFailure(describe(entry), new IllegalStateException(REFUSAL)));
            }
        }
        return new Split(loadable, refused, dev);
    }

    /** A resolved jar as {@code <artifact> <version>}, read off the Maven repository layout. */
    public static String describe(String entry) {
        Path jar = Path.of(entry);
        return artifactOf(jar) + " " + versionOf(jar);
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
