package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The gallery's copy of every listed bot's releases: assets named {@code <owner>__<repo>__<tag>.zip} on the
 * gallery repository's release tagged {@code mirror}, uploaded by its {@code mirror.yml} (2026-10-01).
 *
 * <p>The author keeps the repository, so this is read only when the author's archive cannot be had — a bot
 * whose author deleted or renamed it. The address is derived from the name, so the catalog carries nothing
 * for it and an older Studio is not affected.
 */
public final class GalleryMirror {

    /** The gallery release the copies hang off. */
    public static final String RELEASE = "mirror";
    private static final String SEPARATOR = "__";

    private GalleryMirror() {
    }

    /** {@code BotMakerDev__botmaker-gamebot__v0.2.0.zip}. {@code __} because an owner never contains one. */
    public static String assetName(String owner, String repo, String tag) {
        return owner + SEPARATOR + repo + SEPARATOR + tag + ".zip";
    }

    /** Where the copy of {@code owner/repo} at {@code tag} downloads from, whether it exists or not. */
    public static String downloadUrl(String owner, String repo, String tag) {
        return "https://github.com/" + GitHubConfig.INDEX_OWNER + "/" + GitHubConfig.INDEX_REPO
                + "/releases/download/" + RELEASE + "/" + assetName(owner, repo, tag);
    }

    /** The API address of the mirror release, whose {@code assets} say which tags have a copy. */
    public static String releaseApiUrl() {
        return GitHubConfig.API_BASE + "/repos/" + GitHubConfig.INDEX_OWNER + "/" + GitHubConfig.INDEX_REPO
                + "/releases/tags/" + RELEASE;
    }

    /**
     * The tags {@code release} holds a copy of for {@code owner/repo}, newest first. The owner and the name are
     * matched without case, as GitHub matches them; an asset of another bot is never read as one of these.
     */
    public static List<String> tagsIn(JsonNode release, String owner, String repo) {
        List<String> tags = new ArrayList<>();
        JsonNode assets = release == null ? null : release.get("assets");
        if (assets == null || !assets.isArray()) return tags;
        String prefix = (owner + SEPARATOR + repo + SEPARATOR).toLowerCase(Locale.ROOT);
        for (JsonNode asset : assets) {
            String name = asset.path("name").asText("");
            if (!name.toLowerCase(Locale.ROOT).startsWith(prefix) || !name.endsWith(".zip")) continue;
            String tag = name.substring(prefix.length(), name.length() - ".zip".length());
            if (!tag.isEmpty() && !tag.contains(SEPARATOR)) tags.add(tag);
        }
        tags.sort(Comparator.comparing(GalleryMirror::versionKey, GalleryMirror::compareKeys).reversed());
        return tags;
    }

    /** {@code v1.10.2} as {@code [1, 10, 2]}: numbers compared as numbers, so 1.10 is newer than 1.9. */
    private static List<Long> versionKey(String tag) {
        List<Long> parts = new ArrayList<>();
        for (String part : tag.replaceFirst("^[vV]", "").split("[.\\-+]")) {
            try {
                parts.add(Long.parseLong(part));
            } catch (NumberFormatException e) {
                parts.add(-1L);   // a pre-release word sorts below the release it precedes
            }
        }
        return parts;
    }

    private static int compareKeys(List<Long> a, List<Long> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            long x = i < a.size() ? a.get(i) : 0;
            long y = i < b.size() ? b.get(i) : 0;
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }
}
