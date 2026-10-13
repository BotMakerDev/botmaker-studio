package com.botmaker.studio.assist;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The bot's notes on its game, {@value #FILE} at the project's root: what the assistant learnt exploring it — how
 * to reach a screen, a hotkey, what Escape does, how slow a screen is — so the next session starts from it rather
 * than exploring again (2026-10-13). Plain Markdown in the bot, versioned and published with it, which the bot's
 * author may edit too; the assistant writes one {@code ## section} at a time.
 *
 * <p>A section runs from its {@code ## } heading to the next one outside a code fence. A body's own {@code ## }
 * line is written {@code ### }, so it never starts a section of its own.
 */
public final class GameNotes {

    public static final String FILE = "GAME.md";
    /** The most of the notes the instructions carry at connect; read_notes has the rest. */
    static final int AT_CONNECT = 12_000;
    private static final String HEADING = "## ";
    private static final String FENCE = "```";
    private static final String HEAD = """
            # The game

            What the assistant learnt exploring the game, one section per subject. Edit freely: the assistant
            reads this at connect.
            """;
    /** Two writes at once would each keep only their own section. */
    private static final Object WRITING = new Object();

    private GameNotes() {}

    /** The whole notes, or one section's body; a sentence when there are none. */
    static String read(Path project, String section) {
        String notes = notes(project);
        if (notes.isBlank()) return "The bot has no notes on its game yet: write_notes starts " + FILE + ".";
        if (section == null || section.isBlank()) return notes;
        List<String> lines = notes.lines().toList();
        int at = find(lines, section);
        if (at < 0) {
            throw new IllegalArgumentException("No section " + section.strip() + " in " + FILE + ". It has: "
                    + String.join(", ", sections(lines)) + ".");
        }
        return String.join("\n", lines.subList(at, end(lines, at))).strip();
    }

    /**
     * Puts {@code text} as {@code section}'s body: replaced when the section is there, added at the end when not,
     * removed when {@code text} is blank.
     */
    static String write(Path project, String section, String text) {
        String name = section == null ? "" : section.strip();
        if (name.isEmpty() || name.startsWith("#") || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A section is a one-line heading without #: Hotkeys.");
        }
        synchronized (WRITING) {
            String notes = notes(project);
            List<String> lines = new ArrayList<>((notes.isBlank() ? HEAD : notes).lines().toList());
            int at = find(lines, name);
            boolean removing = text == null || text.isBlank();
            if (at < 0 && removing) throw new IllegalArgumentException("No section " + name + " to remove.");
            // A section found by another case keeps its heading as it is written.
            String heading = at < 0 ? name : lines.get(at).substring(HEADING.length()).strip();
            List<String> body = removing ? List.of() : section(heading, text);
            String done;
            if (at < 0) {
                while (!lines.isEmpty() && lines.getLast().isBlank()) lines.removeLast();
                lines.add("");
                lines.addAll(body);
                done = "Added " + name + " to " + FILE + ".";
            } else {
                lines.subList(at, end(lines, at)).clear();
                lines.addAll(at, body);
                done = (removing ? "Removed " : "Rewrote ") + name + " in " + FILE + ".";
            }
            save(project, String.join("\n", lines).strip() + "\n");
            return done;
        }
    }

    /** The notes for the instructions at connect, cut at a line: empty when there are none or they won't read. */
    static String atConnect(Path project) {
        String notes;
        try {
            notes = notes(project).strip();
        } catch (UncheckedIOException unreadable) {
            return "";
        }
        if (notes.length() <= AT_CONNECT) return notes;
        int cut = notes.lastIndexOf('\n', AT_CONNECT);
        return notes.substring(0, cut > 0 ? cut : AT_CONNECT) + "\n… cut here; read_notes reads the rest.";
    }

    private static List<String> section(String name, String text) {
        List<String> body = new ArrayList<>();
        body.add(HEADING + name);
        body.add("");
        boolean fenced = false;
        for (String line : text.strip().lines().toList()) {
            if (line.startsWith(FENCE)) fenced = !fenced;
            body.add(!fenced && line.startsWith(HEADING) ? "#" + line : line);
        }
        body.add("");
        return body;
    }

    /** Whether each line is a section's heading: {@code ## } outside a code fence. */
    private static boolean[] headings(List<String> lines) {
        boolean[] heading = new boolean[lines.size()];
        boolean fenced = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(FENCE)) fenced = !fenced;
            heading[i] = !fenced && line.startsWith(HEADING);
        }
        return heading;
    }

    private static int find(List<String> lines, String section) {
        String wanted = section.strip().toLowerCase(Locale.ROOT);
        boolean[] heading = headings(lines);
        for (int i = 0; i < lines.size(); i++) {
            if (heading[i] && lines.get(i).substring(HEADING.length()).strip().toLowerCase(Locale.ROOT)
                    .equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    /** The line after {@code at}'s section: the next heading, or the end. */
    private static int end(List<String> lines, int at) {
        boolean[] heading = headings(lines);
        for (int i = at + 1; i < lines.size(); i++) {
            if (heading[i]) return i;
        }
        return lines.size();
    }

    private static List<String> sections(List<String> lines) {
        boolean[] heading = headings(lines);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (heading[i]) names.add(lines.get(i).substring(HEADING.length()).strip());
        }
        return names;
    }

    /** The notes as text; a byte that is not UTF-8, from an editor of the author's, reads as a replacement. */
    private static String notes(Path project) {
        Path file = project.resolve(FILE);
        try {
            return Files.isRegularFile(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + FILE + ": " + e.getMessage(), e);
        }
    }

    /** Written beside and moved over, so a reader never sees half a file. */
    private static void save(Path project, String notes) {
        Path file = project.resolve(FILE);
        try {
            Path next = Files.createTempFile(project, ".GAME", ".md");
            Files.writeString(next, notes, StandardCharsets.UTF_8);
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + FILE + ": " + e.getMessage(), e);
        }
    }
}
