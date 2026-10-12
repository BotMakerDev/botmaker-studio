package com.botmaker.studio.sharing;

import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.project.launch.SupportedTargets;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a publish is asked to do, fixed when Publish is pressed.
 *
 * <p>Fixed on purpose: a retry resumes a publish half done, so the release it cuts and the entry it lists must
 * be the ones the steps already done were for — not whatever the form says after the author kept typing.
 *
 * @param botName      the project's name, which is the entry's {@code name}
 * @param repoName     the repository under the signed-in account
 * @param tags         the entry's tags, {@code template} included for a template
 * @param listed       whether the bot is listed in the gallery at all
 */
public record PublishRequest(Path projectDir, String botName, String repoName, String description, String version,
                             List<String> tags, SupportedTargets launchTargets, boolean listed) {

    /** The entry schema this Studio writes. The gallery's gate refuses a newer one than it knows. */
    public static final int SCHEMA_VERSION = 2;

    public PublishRequest {
        botName = botName == null ? "" : botName.trim();
        repoName = repoName == null ? "" : repoName.trim();
        description = description == null ? "" : description.trim();
        version = version == null ? "" : version.trim();
        tags = tags == null ? List.of() : List.copyOf(tags);
        launchTargets = launchTargets == null ? SupportedTargets.any() : launchTargets;
    }

    public boolean isTemplate() {
        return tags.stream().anyMatch(GalleryEntry.TEMPLATE_TAG::equalsIgnoreCase);
    }

    /**
     * The entry file's fields, in the order {@code botmaker-cli}'s {@code GalleryEntry} writes them, so a
     * publish from Studio and one from {@code botmaker bot publish} diff as the same file.
     *
     * <p>{@code launchTargets} goes in as wire ids, and is left out when the author declared none: an absent
     * field reads as "the author never said" in every Studio, which is the truth. No {@code requires} since
     * 2026-10-12: what the pom declares is read off the installed release's pom ({@link TemplatePlugins}).
     */
    public Map<String, Object> entry(String owner) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("schemaVersion", SCHEMA_VERSION);
        entry.put("name", botName);
        entry.put("owner", owner);
        entry.put("repo", repoName);
        entry.put("description", description);
        entry.put("tags", tags);
        if (launchTargets.declared()) entry.put("launchTargets", launchTargets.ids());
        return entry;
    }

    /** How this bot would read in the gallery once listed, for the dialog's preview. */
    public GalleryEntry preview(String owner, GalleryTier tier, String vettedVersion) {
        return new GalleryEntry(botName, owner, repoName, description, tags, launchTargets, tier, vettedVersion);
    }

    /**
     * The dependencies a stranger installing this bot could not resolve, as {@code group:artifact:version}: a
     * {@code -SNAPSHOT} (a build that lives in this machine's {@code ~/.m2} — every local {@code mvn install} of
     * ours writes one) or a {@code ${property}} nobody defines. Publish refuses
     * while this is not empty (2026-10-01): the release would compile here and nowhere else. A version left to
     * dependency management (blank) is the pom's business and not listed.
     */
    public static List<String> unreleasedPins(List<UserLibrary> declared, Map<String, String> properties) {
        List<String> out = new ArrayList<>();
        for (UserLibrary library : declared) {
            if (library.version().isBlank()) continue;
            String version = interpolate(library.version(), properties);
            if (version.toUpperCase(java.util.Locale.ROOT).contains("SNAPSHOT") || version.contains("${")) {
                out.add(library.groupArtifact() + ":" + version);
            }
        }
        return List.copyOf(out);
    }

    /** {@code ${name}} replaced from {@code properties}; a property nobody defines is left as written. */
    static String interpolate(String text, Map<String, String> properties) {
        if (text == null) return "";
        String out = text;
        for (int start = out.indexOf("${"); start >= 0; start = out.indexOf("${", start + 1)) {
            int end = out.indexOf('}', start);
            if (end < 0) break;
            String value = properties.get(out.substring(start + 2, end));
            if (value != null) {
                out = out.substring(0, start) + value + out.substring(end + 1);
            }
        }
        return out;
    }
}
