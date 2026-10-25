package com.botmaker.studio.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The running application's version. In a packaged build this comes from the jar manifest's
 * {@code Implementation-Version} (injected by the shade plugin, see pom.xml); during development runs
 * (e.g. {@code mvn javafx:run}, no shaded jar) that is {@code null}, so it is the pom's own version, baked into
 * {@code botmaker/host.properties} at build time (2026-10-25: a constant, stale since 1.0.5, until then).
 */
public final class AppVersion {

    /** The pom's version as built ({@code 1.8.1-SNAPSHOT}), or {@code 0.0.0} when the file is missing. */
    private static final String POM = readPom();

    private AppVersion() {}

    /** The current version string, without any {@code v} prefix or {@code -SNAPSHOT} (e.g. {@code "1.0.0"}). */
    public static String get() {
        String fromManifest = AppVersion.class.getPackage().getImplementationVersion();
        return (fromManifest != null && !fromManifest.isBlank()) ? fromManifest : POM.replace("-SNAPSHOT", "");
    }

    /** The version a person reads: {@link #get()}, or the pom's {@code 1.8.1-SNAPSHOT} on a development run. */
    public static String label() {
        return isDevBuild() ? POM : get();
    }

    private static String readPom() {
        try (InputStream in = AppVersion.class.getResourceAsStream("/botmaker/host.properties")) {
            if (in == null) return "0.0.0";
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("studio.version", "");
            return version.isBlank() || version.startsWith("$") ? "0.0.0" : version;
        } catch (IOException e) {
            return "0.0.0";
        }
    }

    /**
     * True when running from source (e.g. {@code mvn javafx:run}) rather than a packaged build. The shade
     * plugin injects {@code Implementation-Version} into the shaded jar / jpackage app-image manifest, so its
     * absence uniquely identifies a dev run. Used to gate developer-only affordances (e.g. listing locally
     * dev-installed SDK snapshots) out of released builds.
     */
    public static boolean isDevBuild() {
        String fromManifest = AppVersion.class.getPackage().getImplementationVersion();
        return fromManifest == null || fromManifest.isBlank();
    }
}
