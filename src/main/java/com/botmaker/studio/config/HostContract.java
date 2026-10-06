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
 * {@code botmaker.contract.tag}, which is the pom's contract pin): on a tag, the released contract the
 * release commit pinned (umbrella {@code docs/refactor/43-real-versions.md}).
 *
 * <p><b>A dev build writes a released tag too (2026-10-03)</b>: its pin is a {@code -SNAPSHOT}, so it gets
 * {@link MavenService#CONTRACT_FALLBACK_VERSION}, which the release moves with every contract tag. It wrote a
 * snapshot until then, a pin only this machine resolved and Publish refused; Studio works with released
 * versions only — outside dev mode, which gives a project {@link #devVersion()} (2026-10-06).
 */
public final class HostContract {

    public static final String GROUP_ID = "com.github.BotMakerDev";
    public static final String ARTIFACT_ID = "botmaker-studio-api";

    /** The baked {@code contract.tag} as read, or {@code null}: a SNAPSHOT on a dev build. */
    private static final String TAG = read();

    private static final String VERSION = orReleased(TAG);

    private HostContract() {}

    /** The contract tag a project is given, never blank and never a SNAPSHOT. */
    public static String version() {
        return VERSION;
    }

    /**
     * The contract a project in dev mode is given (2026-10-06): a dev build's own {@code -SNAPSHOT}, so a bot
     * compiles against contract members not released yet — the contract the Studio running it actually has.
     * A released Studio's tag is a release, so it is {@link #version()} there.
     */
    public static String devVersion() {
        return devVersion(TAG);
    }

    private static String read() {
        try (InputStream in = HostContract.class.getResourceAsStream("/botmaker/host.properties")) {
            if (in == null) return null;
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("contract.tag");
        } catch (IOException e) {
            return null;
        }
    }

    /** A SNAPSHOT tag as it is; anything else as {@link #orReleased} reads it. */
    static String devVersion(String tag) {
        if (tag != null && !tag.contains("${") && tag.trim().toUpperCase(Locale.ROOT).endsWith("-SNAPSHOT")) {
            return tag.trim();
        }
        return orReleased(tag);
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
