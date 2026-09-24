package com.botmaker.studio.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The plugin contract as a <em>project</em> depends on it: the coordinate Studio writes into a pom that has
 * none, the first time it writes an annotation the contract declares ({@code @Param}).
 *
 * <p><b>The version is a tag baked at build time</b> ({@code botmaker/host.properties}, filtered from
 * {@code botmaker.contract.tag}). It is not read off Studio's own classpath: a release build installs the
 * contract from source at {@code 0.0.0-SNAPSHOT}, which names nothing a user's project could resolve. A
 * dev build keeps {@code 0.0.0-SNAPSHOT}, which resolves from {@code ~/.m2} — the same property every
 * local SDK build relies on.
 */
public final class HostContract {

    public static final String GROUP_ID = "com.github.LiQiyeDev";
    public static final String ARTIFACT_ID = "botmaker-studio-api";

    /** What an unfiltered resource, or none, answers. */
    static final String DEV_VERSION = "0.0.0-SNAPSHOT";

    private static final String VERSION = read();

    private HostContract() {}

    /** The contract tag a project is given, never blank. */
    public static String version() {
        return VERSION;
    }

    private static String read() {
        try (InputStream in = HostContract.class.getResourceAsStream("/botmaker/host.properties")) {
            if (in == null) return DEV_VERSION;
            Properties properties = new Properties();
            properties.load(in);
            return orDev(properties.getProperty("contract.tag"));
        } catch (IOException e) {
            return DEV_VERSION;
        }
    }

    /** A blank or still-unfiltered value is the dev version. */
    static String orDev(String tag) {
        if (tag == null || tag.isBlank() || tag.contains("${")) return DEV_VERSION;
        return tag.trim();
    }
}
