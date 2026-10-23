package com.botmaker.studio.sharing;

import com.botmaker.shared.github.SemVer;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One bot Browse Bots lists: a row of {@code catalog.json} ({@link CatalogIndex}, 2026-10-23), a pointer to a
 * bot's own GitHub repo at the release the catalog's check passed.
 *
 * @param name        the repository's name — also the install dir name
 * @param owner       GitHub repo owner (login)
 * @param repo        GitHub repo name
 * @param description short human description
 * @param tags        optional free-form tags for filtering
 * @param basedOn     {@code owner/repo} of the bot this one is a fork of and still follows (the catalog's
 *                    {@code forkOf}), blank for a bot of its own or a fork that stopped following
 * @param tag         the release Install, New Project and <i>Get vX.Y</i> go to — never a newer one, which no
 *                    check has passed; blank for a row that is not the catalog's
 * @param rank        who published it
 * @param stars       GitHub's star count when the catalog was built
 * @param plugins     the catalog plugins the bot's pom at {@code tag} declares, with the versions it pins; an id
 *                    that names no plugin row is kept and matches nothing
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GalleryEntry(String name, String owner, String repo, String description, List<String> tags,
                           String basedOn, String tag, PublisherRank rank, int stars,
                           List<PublishedBotPlugins.Pin> plugins) {

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
        tag = tag == null ? "" : tag.trim();
        rank = rank == null ? PublisherRank.COMMUNITY : rank;
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
    }

    /** A bot of its own the catalog did not list — one being published, a test's: community, no tag. */
    public GalleryEntry(String name, String owner, String repo, String description, List<String> tags) {
        this(name, owner, repo, description, tags, "", "", PublisherRank.COMMUNITY, 0, List.of());
    }

    /** This listing, read as a fork of {@code slug}, or as nothing's when it is blank. */
    public GalleryEntry withBasedOn(String slug) {
        return new GalleryEntry(name, owner, repo, description, tags, slug, tag, rank, stars, plugins);
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
     * The release an installed copy should move to: the listed one when it is not the one installed. Pure, so
     * the rule is tested without a network.
     *
     * @param installed the tag the project was installed at
     * @param latest    the catalog's tag for the bot, blank when it is not listed
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
