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
 * @param requires     the plugins the pom declares, by registry id — see {@link #requires}
 * @param listed       whether the bot is listed in the gallery at all
 */
public record PublishRequest(Path projectDir, String botName, String repoName, String description, String version,
                             List<String> tags, SupportedTargets launchTargets,
                             List<GalleryEntry.Requirement> requires, boolean listed) {

    /** The entry schema this Studio writes. The gallery's gate refuses a newer one than it knows. */
    public static final int SCHEMA_VERSION = 2;

    public PublishRequest {
        botName = botName == null ? "" : botName.trim();
        repoName = repoName == null ? "" : repoName.trim();
        description = description == null ? "" : description.trim();
        version = version == null ? "" : version.trim();
        tags = tags == null ? List.of() : List.copyOf(tags);
        launchTargets = launchTargets == null ? SupportedTargets.any() : launchTargets;
        requires = requires == null ? List.of() : List.copyOf(requires);
    }

    public boolean isTemplate() {
        return tags.stream().anyMatch(GalleryEntry.TEMPLATE_TAG::equalsIgnoreCase);
    }

    /**
     * The entry file's fields, in the order {@code botmaker-cli}'s {@code GalleryEntry} writes them, so a
     * publish from Studio and one from {@code botmaker bot publish} diff as the same file.
     *
     * <p>{@code launchTargets} goes in as wire ids, and is left out when the author declared none: an absent
     * field reads as "the author never said" in every Studio, which is the truth. {@code requires} is left out
     * when empty for the same reason.
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
        if (!requires.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (GalleryEntry.Requirement r : requires) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("id", r.id());
                if (!r.version().isEmpty()) one.put("version", r.version());
                list.add(one);
            }
            entry.put("requires", list);
        }
        return entry;
    }

    /** How this bot would read in the gallery once listed, for the dialog's preview. */
    public GalleryEntry preview(String owner, GalleryTier tier, String vettedVersion) {
        return new GalleryEntry(botName, owner, repoName, description, tags, launchTargets, tier, vettedVersion,
                requires);
    }

    /**
     * The plugins a pom declares, named the way the plugin registry names them.
     *
     * <p>Keyed by the registry and not by a naming rule, exactly as {@code botmaker-cli}'s {@code Requirements}
     * is: what makes a dependency a plugin is having an entry there, so a plugin nobody registered is not
     * listed — a reader could not install it from Manage Plugins anyway. The version is the pom's with its
     * properties applied, since a stranger cannot resolve {@code ${botmaker.sdk.version}}.
     */
    public static List<GalleryEntry.Requirement> requires(List<UserLibrary> declared, Map<String, String> properties,
                                                          List<PluginRegistry.Plugin> registry) {
        List<GalleryEntry.Requirement> out = new ArrayList<>();
        for (UserLibrary library : declared) {
            for (PluginRegistry.Plugin plugin : registry) {
                if (!plugin.id().isEmpty() && library.groupArtifact().equals(plugin.coordinate())) {
                    out.add(new GalleryEntry.Requirement(plugin.id(), interpolate(library.version(), properties)));
                    break;
                }
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
