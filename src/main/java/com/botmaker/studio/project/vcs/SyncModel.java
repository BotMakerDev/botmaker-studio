package com.botmaker.studio.project.vcs;

import com.botmaker.shared.github.SemVer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a bot lives and what its main action is ({@code docs/refactor/39-versions.md} §2): the strip at the top
 * of the Versions tab — <i>This computer</i>, <i>My copy</i>, <i>Original</i> — and the one button whose label
 * names where it goes. Pure: computed from {@link Facts} read off git and the sign-in, never stored and never
 * asked of the user.
 */
public record SyncModel(Ownership ownership, Facts facts) {

    /** Who the bot belongs to, as far as sharing it goes. */
    public enum Ownership {
        /** Not signed in, or nothing to share with yet: only this computer. */
        LOCAL_ONLY,
        /** The user's own bot, not on GitHub yet. */
        OWN_NEW,
        /** The user's own bot, with {@code mine} on their account and no author above it. */
        OWN_PUBLISHED,
        /** Someone else's bot: {@code original} belongs to another account. */
        OTHERS
    }

    /** A button the strip can offer. */
    public enum Action {
        SAVE_VERSION("Save version"),
        PUBLISH("Publish…"),
        SAVE_TO_MY_COPY("Save to my copy"),
        SIGN_IN("Sign in to share…");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * What the model is computed from.
     *
     * @param login        the signed-in GitHub login, or null when signed out
     * @param mineUrl      the {@code mine} remote's URL, or null
     * @param originalUrl  the {@code original} remote's URL, or null — for a zip install not yet attached,
     *                     the URL its provenance names
     * @param unsaved      files changed since the last version
     * @param notInMyCopy  versions this computer has that {@code mine} lacks; -1 when nothing was pushed there
     * @param installedTag the release this bot was installed from, or null
     * @param availableTag a newer release of the original, or null
     */
    public record Facts(String login, String mineUrl, String originalUrl, int unsaved, int notInMyCopy,
                        String installedTag, String availableTag) {

        public boolean signedIn() {
            return login != null && !login.isBlank();
        }
    }

    /** A GitHub repository named by a remote URL. */
    public record Slug(String owner, String repo) {

        @Override
        public String toString() {
            return owner + "/" + repo;
        }
    }

    private static final Pattern GITHUB = Pattern.compile("^https://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$");

    /** {@code owner/repo} of a {@code https://github.com/…} URL, else empty. */
    public static Optional<Slug> slug(String url) {
        if (url == null) return Optional.empty();
        Matcher m = GITHUB.matcher(url.trim());
        return m.matches() ? Optional.of(new Slug(m.group(1), m.group(2))) : Optional.empty();
    }

    public static SyncModel of(Facts f) {
        return new SyncModel(ownership(f), f);
    }

    private static Ownership ownership(Facts f) {
        if (!f.signedIn()) return Ownership.LOCAL_ONLY;
        String login = f.login().toLowerCase(Locale.ROOT);
        Optional<Slug> original = slug(f.originalUrl());
        if (original.isPresent() && !original.get().owner().toLowerCase(Locale.ROOT).equals(login)) {
            return Ownership.OTHERS;
        }
        // An author who installed their own bot has its repository as the original: it is their copy.
        return f.mineUrl() != null || original.isPresent() ? Ownership.OWN_PUBLISHED : Ownership.OWN_NEW;
    }

    /** The strip's one button. */
    public Action main() {
        return switch (ownership) {
            case LOCAL_ONLY -> Action.SAVE_VERSION;
            case OWN_NEW -> Action.PUBLISH;
            case OWN_PUBLISHED, OTHERS -> Action.SAVE_TO_MY_COPY;
        };
    }

    /** What sits beside it ({@code Get vX.Y} and {@code Suggest to author…} arrive with 4e). */
    public List<Action> also() {
        return switch (ownership) {
            case LOCAL_ONLY -> List.of(Action.SIGN_IN);
            case OWN_NEW -> List.of(Action.SAVE_TO_MY_COPY);
            case OWN_PUBLISHED -> List.of(Action.PUBLISH);
            case OTHERS -> List.of();
        };
    }

    /** "3 unsaved changes" / "Saved". */
    public String thisComputer() {
        int n = facts.unsaved();
        return n == 0 ? "Saved" : n + (n == 1 ? " unsaved change" : " unsaved changes");
    }

    /** "2 versions not in your copy" / "Up to date", or null when there is no copy to speak of. */
    public String myCopy() {
        if (mineSlug().isEmpty()) {
            return ownership == Ownership.OTHERS || ownership == Ownership.OWN_NEW ? "Not saved to your copy yet" : null;
        }
        int n = facts.notInMyCopy();
        if (n < 0) return "Not saved to your copy yet";
        return n == 0 ? "Up to date" : n + (n == 1 ? " version not in your copy" : " versions not in your copy");
    }

    /** "v1.4 available" / "Latest (v1.3)", or null when the bot has no original. */
    public String original() {
        if (facts.originalUrl() == null) return null;
        if (facts.availableTag() != null) return facts.availableTag() + " available";
        return facts.installedTag() == null ? "Latest" : "Latest (" + facts.installedTag() + ")";
    }

    public Optional<Slug> mineSlug() {
        return facts.mineUrl() == null && ownership == Ownership.OWN_PUBLISHED
                ? slug(facts.originalUrl()) : slug(facts.mineUrl());
    }

    public Optional<Slug> originalSlug() {
        return slug(facts.originalUrl());
    }

    /**
     * The branch of {@code mine} this computer's {@code branch} is pushed to. The user's own repository takes
     * it as it is; a fork keeps its {@code main} tracking the author and carries the user's line beside it,
     * as {@code studio/<branch>} — pushing over the fork's {@code main} would be refused the day the author
     * moved on.
     */
    public String remoteBranch(String branch) {
        return ownership == Ownership.OTHERS ? "studio/" + branch : branch;
    }

    /** The newest release among {@code tags} after {@code installed}, or null when none is newer. */
    public static String newest(List<String> tags, String installed) {
        String best = null;
        for (String tag : tags) {
            if (!SemVer.isValid(tag) || !SemVer.isGreater(tag, installed)) continue;
            if (best == null || SemVer.compare(tag, best) > 0) best = tag;
        }
        return best;
    }
}
