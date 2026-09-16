package com.botmaker.studio.sharing;

import com.botmaker.shared.github.SemVer;
import com.botmaker.studio.project.launch.SupportedTargets;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Optional;

/**
 * One listing in the gallery's {@code catalog.json} (or, from an older gallery, its {@code index.json}): a
 * pointer to a bot's own GitHub repo. The release to install is NOT stored here, except for the one release
 * a maintainer vetted — the newest is fetched live from the author's repo
 * ({@link GitHubGallery#latestReleaseTag}) so a new release never requires editing the gallery.
 *
 * @param name        the bot's project name (PascalCase, as created in Studio) — also the install dir name
 * @param owner       GitHub repo owner (login)
 * @param repo        GitHub repo name
 * @param description short human description
 * @param tags        optional free-form tags for filtering
 * @param launchTargets the launch kinds the author declares the bot works on — so a browser can tell before
 *                      installing whether their platform is one of them. Absent in every entry written before
 *                      this field existed, which reads as {@link SupportedTargets#any()}: "the author never
 *                      said", never "works on nothing".
 * @param tier        how much somebody vouches for it. Absent reads as {@link GalleryTier#COMMUNITY}, the
 *                    safe side; {@link GitHubGallery} marks the legacy index's entries Vetted itself, because
 *                    that file holds only Vetted bots and cannot say so
 * @param vettedVersion the release tag a maintainer looked at, for a Vetted bot; blank otherwise
 * @param requires    the plugins the bot's pom declares, by plugin-registry id. Empty for every entry written
 *                    before 2026-09-16, which means "unknown", not "none"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GalleryEntry(String name, String owner, String repo, String description, List<String> tags,
                           SupportedTargets launchTargets, GalleryTier tier, String vettedVersion,
                           List<Requirement> requires) {

    /** A plugin a listed bot needs: its registry id and the version the bot's pom pins, which may be blank. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Requirement(String id, String version) {
        public Requirement {
            id = id == null ? "" : id.trim();
            version = version == null ? "" : version.trim();
        }

        /** {@code com.botmaker.sdk 1.1.6}, or the id alone when no version is pinned. */
        public String describe() {
            return version.isEmpty() ? id : id + " " + version;
        }
    }

    /**
     * The one reserved tag: an entry carrying it is a <b>starting template</b> rather than a bot to install.
     *
     * <p>A template is a published bot — same repo, same release, same install path — and that is the whole
     * design. Studio composes one starting point of its own (a blank project: a pom, an empty {@code src}
     * tree and a {@code main} that prints a line), and every richer one is somebody's published project that
     * New Project downloads and renames. So a new kind of starting point needs no Studio release, and the
     * people who write bots are the people who write the templates.
     *
     * <p>It is a value in the existing free-form {@code tags} list rather than a field of its own, which
     * costs one thing worth stating: an author is free to tag an ordinary bot {@code "template"} and it will
     * be offered as one. That is a curation problem in a curated index, not a correctness one — the entry is
     * still a real project that unpacks and compiles.
     */
    public static final String TEMPLATE_TAG = "template";

    @JsonCreator
    public GalleryEntry {
        name = name == null ? "" : name.trim();
        owner = owner == null ? "" : owner.trim();
        repo = repo == null ? "" : repo.trim();
        description = description == null ? "" : description;
        tags = tags == null ? List.of() : List.copyOf(tags);
        launchTargets = launchTargets == null ? SupportedTargets.any() : launchTargets;
        tier = tier == null ? GalleryTier.COMMUNITY : tier;
        vettedVersion = vettedVersion == null ? "" : vettedVersion.trim();
        requires = requires == null ? List.of() : List.copyOf(requires);
    }

    /** An entry as a legacy index spells it: no tier, no vetted release, no requirements. */
    public GalleryEntry(String name, String owner, String repo, String description, List<String> tags,
                        SupportedTargets launchTargets) {
        this(name, owner, repo, description, tags, launchTargets, null, null, null);
    }

    /**
     * The starting templates among {@code entries}, as New Project lists them: Vetted first, then by name,
     * and Community ones only when asked for.
     *
     * <p>Sorted rather than left in the catalog's order because the first row is the one preselected, and it
     * must be the same row on every launch.
     */
    public static List<GalleryEntry> templates(List<GalleryEntry> entries, boolean includeCommunity) {
        return entries.stream()
                .filter(GalleryEntry::isTemplate)
                .filter(e -> includeCommunity || e.isVetted())
                .sorted(java.util.Comparator.comparing((GalleryEntry e) -> !e.isVetted())
                        .thenComparing(GalleryEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public GalleryEntry withTier(GalleryTier newTier) {
        return new GalleryEntry(name, owner, repo, description, tags, launchTargets, newTier, vettedVersion,
                requires);
    }

    public boolean isVetted() {
        return tier == GalleryTier.VETTED;
    }

    /**
     * The release to install: the vetted one for a Vetted bot that names it, else {@code latest}.
     *
     * <p>A Vetted bot from the legacy index names no release, so it installs the newest, which is exactly what
     * every Studio before tiers did.
     */
    public String installTag(String latest) {
        return isVetted() && !vettedVersion.isEmpty() ? vettedVersion : (latest == null ? "" : latest);
    }

    /**
     * The release an installed copy of this bot should move to, if any.
     *
     * <p>A Vetted bot offers its vetted release and never a lower one: somebody who installed a newer release
     * of their own accord is not "updated" backwards. Everything else, including a bot no longer listed at all
     * ({@code listing} null), offers the newest release. Pure, so the rule is tested without a network.
     *
     * @param listing   the bot's current listing, or {@code null} when the catalog does not name it
     * @param installed the tag the project was installed at
     * @param latest    the repo's newest release tag, blank when unknown
     */
    public static Optional<String> updateTarget(GalleryEntry listing, String installed, String latest) {
        String target = listing == null ? (latest == null ? "" : latest) : listing.installTag(latest);
        if (target.isBlank() || target.equals(installed)) return Optional.empty();
        if (listing != null && listing.isVetted() && !listing.vettedVersion.isEmpty()
                && SemVer.isValid(installed) && SemVer.compare(target, installed) < 0) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    public String htmlUrl() {
        return "https://github.com/" + owner + "/" + repo;
    }

    /** {@code owner/repo}, the stable identity of a bot. */
    public String slug() {
        return owner + "/" + repo;
    }

    /** True when this entry is a starting template — see {@link #TEMPLATE_TAG}. */
    public boolean isTemplate() {
        return tags.stream().anyMatch(TEMPLATE_TAG::equalsIgnoreCase);
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
