package com.botmaker.studio.sharing;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A website's <i>Open in Studio</i> link (2026-10-25): {@code botmaker://run/<owner>/<repo>}, the catalog bot Studio
 * gets and opens to run, the way its card's <i>Get</i> does. The installers register the scheme, so no {@code .bmk}
 * lands in the browser's downloads. The link names a bot, never a file or a URL to download from: anything else in it
 * is refused, and Studio opens only what the catalog lists for that repository.
 *
 * @param owner the GitHub account, as typed in the link
 * @param repo  its repository
 */
public record BotLink(String owner, String repo) {

    /** The scheme the installers register. */
    public static final String SCHEME = "botmaker";
    /** What every link starts with, without case. */
    private static final String PREFIX = SCHEME + "://run/";
    /** A GitHub account: letters, digits and single hyphens inside. */
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}");
    /** A GitHub repository name: letters, digits, '.', '-' and '_'. */
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    /**
     * The bot {@code text} links to; empty for anything else: another scheme or host, a path of other than two parts,
     * an encoded character, a query or a fragment. One trailing {@code /}, which some browsers add, is allowed.
     */
    public static Optional<BotLink> parse(String text) {
        if (text == null || !text.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) return Optional.empty();
        String path = text.substring(PREFIX.length());
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String[] parts = path.split("/", -1);
        if (parts.length != 2 || !OWNER.matcher(parts[0]).matches() || !REPO.matcher(parts[1]).matches()
                || parts[1].equals(".") || parts[1].equals("..")) {
            return Optional.empty();
        }
        return Optional.of(new BotLink(parts[0], parts[1]));
    }

    /**
     * The first of the command line's bare {@code arguments} written in the scheme, whether it parses or not, so a
     * link Studio refuses is said rather than ignored. The Linux menu entry's {@code %u} and the Windows command
     * {@code "%1"} hand a clicked link over this way.
     */
    public static Optional<String> asked(List<String> arguments) {
        return arguments.stream()
                .filter(a -> a.toLowerCase(Locale.ROOT).startsWith(SCHEME + ":"))
                .findFirst();
    }

    /** {@code owner/repo}. */
    public String slug() {
        return owner + "/" + repo;
    }

    /** Whether {@code entry} is this link's bot: the same repository, without case, as GitHub reads it. */
    public boolean names(GalleryEntry entry) {
        return entry.owner().equalsIgnoreCase(owner) && entry.repo().equalsIgnoreCase(repo);
    }

    /** The link itself. */
    @Override
    public String toString() {
        return PREFIX + slug();
    }
}
