package com.botmaker.studio.config;

import com.botmaker.studio.services.MavenService;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * The plugin contract as a <em>project</em> depends on it: the coordinate Studio writes into a pom that has
 * none, the first time it writes an annotation the contract declares ({@code @Param}).
 *
 * <p><b>The version is a tag baked at build time</b> ({@code botmaker/host.properties}, filtered from
 * {@code botmaker.contract.tag}). It is not read off Studio's own classpath: a release build installs the
 * contract from source at {@code 0.0.0-SNAPSHOT}, which names nothing a user's project could resolve.
 *
 * <p><b>A dev build writes a released tag too (2026-10-03)</b>: {@link MavenService#CONTRACT_FALLBACK_VERSION},
 * which the release moves with every contract tag. It wrote {@code 0.0.0-SNAPSHOT} until then, a pin only
 * this machine resolved and Publish refused; Studio works with released versions only.
 */
public final class HostContract {

    public static final String GROUP_ID = "com.github.BotMakerDev";
    public static final String ARTIFACT_ID = "botmaker-studio-api";

    private static final String VERSION = read();

    private HostContract() {}

    /** The contract tag a project is given, never blank and never a SNAPSHOT. */
    public static String version() {
        return VERSION;
    }

    private static String read() {
        try (InputStream in = HostContract.class.getResourceAsStream("/botmaker/host.properties")) {
            if (in == null) return MavenService.CONTRACT_FALLBACK_VERSION;
            Properties properties = new Properties();
            properties.load(in);
            return orReleased(properties.getProperty("contract.tag"));
        } catch (IOException e) {
            return MavenService.CONTRACT_FALLBACK_VERSION;
        }
    }

    /** A blank, still-unfiltered or SNAPSHOT value is the released fallback. */
    static String orReleased(String tag) {
        if (tag == null || tag.isBlank() || tag.contains("${")
                || tag.toUpperCase(Locale.ROOT).endsWith("-SNAPSHOT")) {
            return MavenService.CONTRACT_FALLBACK_VERSION;
        }
        return tag.trim();
    }
}
