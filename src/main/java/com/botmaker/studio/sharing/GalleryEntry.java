package com.botmaker.studio.sharing;

import com.botmaker.shared.github.SemVer;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One bot Browse Bots lists, read off GitHub's topic search ({@link GitHubGallery#browse}) since 2026-10-23: a
 * pointer to a bot's own GitHub repo. The release to install is NOT stored here — the newest is fetched live from
 * the author's repo ({@link GitHubGallery#latestReleaseTag}) so a new release never requires editing the gallery.
 *
 * @param name        the bot's project name (PascalCase, as created in Studio) — also the install dir name
 * @param owner       GitHub repo owner (login)
 * @param repo        GitHub repo name
 * @param description short human description
 * @param tags        optional free-form tags for filtering
 * @param basedOn     {@code owner/repo} of the bot this one is a fork of and still follows, blank for a bot of
 *                    its own or a fork that stopped following (2026-10-23)
 *
 * <p>Fields an older catalog still carries are ignored: {@code requires} since 2026-10-12 (a bot's plugins are read
 * off the pom of the release that is installed, {@link PublishedBotPlugins}); {@code launchTargets}, {@code tier}
 * and {@code vettedVersion} since 2026-10-23 (what a bot launches is the SDK's business, and no listing is vouched
 * for above another: every bot installs its newest release).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GalleryEntry(String name, String owner, String repo, String description, List<String> tags,
                           String basedOn) {

    // TEMPLATE_TAG went on 2026-10-23, with the template concept: every published bot is a starting point, and
    // New Project clones it linked to its author as Browse Bots' Install does. An entry still tagged "template"
    // reads as an ordinary bot with that tag.

    @JsonCreator
    public GalleryEntry {
        name = name == null ? "" : name.trim();
        owner = owner == null ? "" : owner.trim();
        repo = repo == null ? "" : repo.trim();
        description = description == null ? "" : description;
        tags = tags == null ? List.of() : List.copyOf(tags);
        basedOn = basedOn == null ? "" : basedOn.trim();
    }

    /** A bot of its own, based on nothing. */
    public GalleryEntry(String name, String owner, String repo, String description, List<String> tags) {
        this(name, owner, repo, description, tags, "");
    }

    /** This listing, read as a fork of {@code slug}, or as nothing's when it is blank. */
    public GalleryEntry withBasedOn(String slug) {
        return new GalleryEntry(name, owner, repo, description, tags, slug);
    }

    /** "based on alice/miner", or blank for a bot of its own. */
    public String basedOnLine() {
        return basedOn.isBlank() ? "" : "based on " + basedOn;
    }

    /**
     * The bots among {@code entries} New Project offers to start from, by name.
     *
     * <p>Sorted rather than left in the catalog's order so the list reads the same on every launch.
     */
    public static List<GalleryEntry> startingPoints(List<GalleryEntry> entries) {
        return entries.stream()
                .sorted(Comparator.comparing(GalleryEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * The release an installed copy should move to: the repo's newest when it is not the one installed. Pure, so
     * the rule is tested without a network.
     *
     * @param installed the tag the project was installed at
     * @param latest    the repo's newest release tag, blank when unknown
     */
    public static Optional<String> updateTarget(String installed, String latest) {
        String target = latest == null ? "" : latest;
        if (target.isBlank() || target.equals(installed)) return Optional.empty();
        // Never backwards: a newest release older than the one installed is a release deleted since.
        if (SemVer.isValid(installed) && SemVer.isValid(target) && SemVer.compare(target, installed) < 0) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    /**
     * What every door that starts from this bot says first — Browse Bots' Install, New Project, the assistant's
     * {@code create_project}: nobody reviews a listing's code.
     */
    public String trustWarning() {
        return "“" + name + "” by " + owner + " is a bot that can control your mouse, keyboard and screen. It was "
                + "listed automatically, and nobody reviewed its code. Only start from bots by authors you trust.";
    }

    public String htmlUrl() {
        return "https://github.com/" + owner + "/" + repo;
    }

    /** {@code owner/repo}, the stable identity of a bot. */
    public String slug() {
        return owner + "/" + repo;
    }

    /** True if the entry matches a free-text query against name / description / owner / tags. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.toLowerCase();
        if (name.toLowerCase().contains(q)) return true;
        if (description.toLowerCase().contains(q)) return true;
        if (owner.toLowerCase().contains(q)) return true;
        return tags.stream().anyMatch(t -> t.toLowerCase().contains(q));
    }
}
