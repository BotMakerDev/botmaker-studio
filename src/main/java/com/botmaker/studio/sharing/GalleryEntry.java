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
 * @param pluginsRank the lowest rank among {@code plugins} as the catalog ranks them — community for an id that
 *                    names no row — and {@link PublisherRank#ADMIN} for a bot that uses none: a bot runs its
 *                    plugins' code too, so it is trusted no further than they are ({@link #trust})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GalleryEntry(String name, String owner, String repo, String description, List<String> tags,
                           String basedOn, String tag, PublisherRank rank, int stars,
                           List<PublishedBotPlugins.Pin> plugins, PublisherRank pluginsRank) {

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
        pluginsRank = pluginsRank == null ? PublisherRank.COMMUNITY : pluginsRank;
    }

    /** A bot of its own the catalog did not list — one being published, a test's: community, no tag. */
    public GalleryEntry(String name, String owner, String repo, String description, List<String> tags) {
        this(name, owner, repo, description, tags, "", "", PublisherRank.COMMUNITY, 0, List.of(),
                PublisherRank.ADMIN);
    }

    /** This listing, read as a fork of {@code slug}, or as nothing's when it is blank. */
    public GalleryEntry withBasedOn(String slug) {
        return new GalleryEntry(name, owner, repo, description, tags, slug, tag, rank, stars, plugins, pluginsRank);
    }

    /** How far this bot is trusted: its publisher's rank, or its plugins' when one of theirs is lower. */
    public PublisherRank trust() {
        return PublisherRank.lower(rank, pluginsRank);
    }

    /** "based on alice/miner", or blank for a bot of its own. */
    public String basedOnLine() {
        return basedOn.isBlank() ? "" : "based on " + basedOn;
    }

    /**
     * The order every list of bots is shown in (2026-10-23): official first, then trusted, then community
     * ({@link #trust}), the most starred first within a rank, then by name.
     */
    public static final Comparator<GalleryEntry> RANKED = Comparator.comparing(GalleryEntry::trust)
            .thenComparing(Comparator.comparingInt(GalleryEntry::stars).reversed())
            .thenComparing(GalleryEntry::name, String.CASE_INSENSITIVE_ORDER);

    /**
     * The bots among {@code entries} New Project offers to start from, {@link #RANKED}.
     *
     * <p>Sorted rather than left in the catalog's order so the list reads the same on every launch.
     */
    public static List<GalleryEntry> startingPoints(List<GalleryEntry> entries) {
        return entries.stream().sorted(RANKED).toList();
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
     * {@code create_project} — by {@link #trust} (2026-10-23): nothing for an official bot, who published it for
     * a trusted one, and for a community one that nobody reviewed its code. Only a community bot is confirmed
     * before it is installed ({@link #asksFirst}).
     */
    public String trustWarning() {
        return switch (trust()) {
            case ADMIN -> "";
            case TRUSTED -> "By " + owner + ", a vetted developer.";
            case COMMUNITY -> rank == PublisherRank.COMMUNITY
                    ? "“" + name + "” by " + owner + " is a bot that can control your mouse, keyboard and screen. "
                    + "It was listed automatically, and nobody reviewed its code. Only start from bots by authors "
                    + "you trust."
                    : "“" + name + "” by " + owner + " uses a community plugin, which can control your mouse, "
                    + "keyboard and screen. It was listed automatically, and nobody reviewed its code. Only start "
                    + "from bots whose plugins are by authors you trust.";
        };
    }

    /** Whether starting from this bot is confirmed first: a community bot, or one using a community plugin. */
    public boolean asksFirst() {
        return trust() == PublisherRank.COMMUNITY;
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
