package com.botmaker.studio.nav;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Find (Ctrl+F) and Find in Project (Ctrl+Shift+F), without JavaFX (2026-09-29).
 *
 * <p>A search is over the <b>source text</b>, not over what each block draws: a block shows a name, a literal
 * or a comment exactly as the source writes it, so a match in the text is a match in the block that owns that
 * offset ({@link SourceNavigation#blockAtOffset}). Searching the drawn labels instead would mean asking every
 * block kind what it renders, and would miss what a collapsed block hides.
 *
 * <p>Case-insensitive, like IntelliJ's default, and non-overlapping.
 */
public final class TextSearch {

    private TextSearch() {
    }

    /** One occurrence: where it starts, its 1-based line, and that line trimmed, for a result row. */
    public record Match(int offset, int line, String lineText) {
    }

    /** A match in one of the project's files. */
    public record FileMatch(Path file, Match match) {
    }

    /** Where {@code query} occurs in {@code text}, in order; empty for a blank query. */
    public static List<Integer> offsets(String text, String query) {
        if (text == null || query == null || query.isEmpty()) return List.of();
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        List<Integer> found = new ArrayList<>();
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            found.add(at);
        }
        return found;
    }

    /** {@link #offsets}, each with its line. */
    public static List<Match> matches(String text, String query) {
        List<Integer> offsets = offsets(text, query);
        if (offsets.isEmpty()) return List.of();
        List<Match> out = new ArrayList<>(offsets.size());
        int line = 1;
        int lineStart = 0;
        int scanned = 0;
        for (int offset : offsets) {
            for (; scanned < offset; scanned++) {
                if (text.charAt(scanned) == '\n') {
                    line++;
                    lineStart = scanned + 1;
                }
            }
            int lineEnd = text.indexOf('\n', offset);
            out.add(new Match(offset, line, text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd).strip()));
        }
        return out;
    }

    /** A file and its text, as the open project holds it. */
    public record Source(Path file, String text) {
    }

    /**
     * Every match across {@code sources}, file by file in the order given, stopping at {@code limit} — a
     * one-letter query in a large bot would otherwise build a list nobody reads.
     */
    public static List<FileMatch> inFiles(List<Source> sources, String query, int limit) {
        List<FileMatch> out = new ArrayList<>();
        for (Source source : sources) {
            for (Match match : matches(source.text(), query)) {
                if (out.size() >= limit) return out;
                out.add(new FileMatch(source.file(), match));
            }
        }
        return out;
    }
}
