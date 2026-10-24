package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.app.StartingPoints.Fetch;
import com.botmaker.studio.ui.app.StartingPoints.Kind;
import com.botmaker.studio.ui.app.StartingPoints.StartChoice;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * New Project's rules, apart from the window that shows them (2026-10-24): when the name's rule is shown, the name
 * a picked bot fills in, and the one-line texts about the catalog and the pick.
 */
final class NewProjectForm {

    private static final DateTimeFormatter KEPT_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ROOT);

    private NewProjectForm() {}

    /**
     * The sentence under the name field: {@code problem}'s ({@code ProjectCreator.nameProblem}) for a name that
     * breaks a rule, nothing for an empty field or a valid name — the rules are not shown until one is broken.
     */
    static Optional<String> nameError(String typed, Function<String, String> problem) {
        if (typed == null || typed.isEmpty()) return Optional.empty();
        return Optional.ofNullable(problem.apply(typed));
    }

    /**
     * The name picking {@code picked} fills in: the bot's, when the user has typed none ({@code typedByUser}
     * false). A name the user typed is never replaced, and Blank fills nothing.
     *
     * <p>A repository's name may hold a dash or a dot ({@code firestone-bot}), which a project name may not, so
     * its parts are joined capitalised ({@code FirestoneBot}); leading digits go, and it is cut to the 50
     * characters a name may have (my call: a filled-in name the field then refuses would be no help).
     */
    static Optional<String> prefill(boolean typedByUser, StartChoice picked) {
        if (typedByUser || picked == null || picked.entry() == null) return Optional.empty();
        StringBuilder name = new StringBuilder();
        for (String part : picked.entry().name().split("[^A-Za-z0-9]+")) {
            if (part.isEmpty()) continue;
            name.append(Character.toUpperCase(part.charAt(0))).append(part, 1, part.length());
        }
        while (!name.isEmpty() && !Character.isLetter(name.charAt(0))) name.deleteCharAt(0);
        if (name.isEmpty()) return Optional.empty();
        name.setCharAt(0, Character.toUpperCase(name.charAt(0)));
        if (name.length() > 50) name.setLength(50);
        return Optional.of(name.toString());
    }

    /**
     * The line above the cards: what the catalog's answer is. Nothing once GitHub answered; offline, the day the
     * kept catalog was read in {@code zone}, or that there are no bots to show at all.
     */
    static String catalogLine(Fetch fetch, Optional<Instant> keptAt, boolean anyKept, ZoneId zone) {
        return switch (fetch) {
            case PENDING -> "Checking for new bots…";
            case ANSWERED -> "";
            case FAILED -> anyKept && keptAt.isPresent()
                    ? "Offline — showing the bots from " + KEPT_DAY.format(keptAt.get().atZone(zone))
                    : "The bots could not be loaded — Blank only.";
        };
    }

    /** The muted line in place of bot cards when the catalog answered and none is shown. */
    static String emptyLine(boolean communityShown) {
        return communityShown ? "No bots listed yet."
                : "No official bots yet — show community bots to see the rest.";
    }

    /**
     * The line under the cards for {@code picked}: a bot's trust warning, the same sentence Browse Bots' Install
     * shows; for a kept bot not yet confirmed, why Create waits.
     */
    static String startNote(StartChoice picked, Fetch fetch) {
        if (picked == null || picked.entry() == null) return "";
        if (picked.kind() == Kind.REMEMBERED) {
            return fetch == Fetch.FAILED
                    ? picked.displayName() + " can be started from once the catalog can be reached."
                    : "Checking that " + picked.displayName() + " is still listed…";
        }
        return picked.entry().trustWarning();
    }
}
