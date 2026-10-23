package com.botmaker.studio.sharing;

import com.botmaker.studio.project.UserLibrary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything a publish is asked to do, fixed when Publish is pressed.
 *
 * <p>Fixed on purpose: a retry resumes a publish half done, so the release it cuts and the listing it sets must
 * be the ones the steps already done were for — not whatever the form says after the author kept typing.
 *
 * @param botName      the project's name
 * @param repoName     the repository under the signed-in account
 * @param description  the repository's description, which is what Browse Bots shows
 * @param tags         added to the repository's topics, which the catalog shows as the bot's tags
 * @param listed       whether the bot is listed in the catalog at all (its pull request)
 */
public record PublishRequest(Path projectDir, String botName, String repoName, String description, String version,
                             List<String> tags, boolean listed) {

    public PublishRequest {
        botName = botName == null ? "" : botName.trim();
        repoName = repoName == null ? "" : repoName.trim();
        description = description == null ? "" : description.trim();
        version = version == null ? "" : version.trim();
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /**
     * How this bot would read in Browse Bots once listed, for the sheet's preview: named by its repository, as
     * the catalog names a bot, with its tags as topics.
     */
    public GalleryEntry preview(String owner) {
        return new GalleryEntry(repoName, owner, repoName, description, BotPublisher.topics(List.of(), tags));
    }

    /**
     * The catalog's plugins among {@code declared}, by coordinate: the catalog lists a bot only when its pom
     * declares at least one (its check's rule 6), so Publish waits while this is empty and the catalog was read.
     */
    public static List<String> catalogPlugins(List<UserLibrary> declared, List<PluginRegistry.Plugin> listed) {
        Set<String> coordinates = new HashSet<>();
        for (PluginRegistry.Plugin plugin : listed) coordinates.add(plugin.coordinate().toLowerCase(Locale.ROOT));
        return declared.stream().map(UserLibrary::groupArtifact)
                .filter(c -> coordinates.contains(c.toLowerCase(Locale.ROOT))).toList();
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
            if (version.toUpperCase(Locale.ROOT).contains("SNAPSHOT") || version.contains("${")) {
                out.add(library.groupArtifact() + ":" + version);
            }
        }
        return List.copyOf(out);
    }

    /**
     * {@code ${name}} replaced from {@code properties}; a property nobody defines is left as written.
     *
     * <p><b>At most {@value #MAX_SUBSTITUTIONS} replacements.</b> The scan goes on inside a replaced value, so a
     * self-referencing property ({@code <a>x${a}</a>}) grew the text without end.
     * Since 2026-10-12 the pom is a template's, fetched from its repository by New Project, so that was any
     * gallery author's way to exhaust Studio's memory.
     */
    static String interpolate(String text, Map<String, String> properties) {
        if (text == null) return "";
        String out = text;
        int replaced = 0;
        for (int start = out.indexOf("${"); start >= 0 && replaced < MAX_SUBSTITUTIONS;
             start = out.indexOf("${", start + 1)) {
            int end = out.indexOf('}', start);
            if (end < 0) break;
            String value = properties.get(out.substring(start + 2, end));
            if (value != null) {
                out = out.substring(0, start) + value + out.substring(end + 1);
                replaced++;
            }
        }
        return out;
    }

    /** Ample for a version: a pom pin names one property. */
    static final int MAX_SUBSTITUTIONS = 32;
}
