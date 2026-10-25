package com.botmaker.studio.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bot's name and description, the pom's own {@code <name>} and {@code <description>} (2026-10-25, plan phase 4):
 * what My bots, the Run window and Project Settings show. Read and written as text, so a save changes those two
 * elements and nothing else in the file: the pom is the author's too, and a rewrite through Maven's model would
 * reflow it.
 */
public final class PomInfo {

    /**
     * An element's open, close or empty tag, in document order; a comment, a CDATA section or a processing
     * instruction is matched whole so nothing inside it counts as a tag. An attribute value may hold a {@code >}.
     */
    private static final Pattern TAG = Pattern.compile(
            "<!--.*?-->|<!\\[CDATA\\[.*?]]>|<\\?.*?\\?>|<(/?)([A-Za-z][\\w.:-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*?)(/?)>",
            Pattern.DOTALL);

    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL);

    private PomInfo() {}

    /** What the pom says; each part empty when the pom has none. */
    public record Info(Optional<String> name, Optional<String> description) {}

    /**
     * One element directly under {@code <project>}: where its open tag starts, where its text sits (both ends
     * equal for an empty {@code <x/>}), where it ends, and where its line starts.
     */
    private record Element(String tag, int start, int contentStart, int contentEnd, int end, int lineStart,
                           boolean selfClosed) {}

    /** {@code projectDir}'s pom's name and description; both empty when there is no pom or it cannot be read. */
    public static Info read(Path projectDir) {
        try {
            return of(Files.readString(projectDir.resolve("pom.xml")));
        } catch (IOException e) {
            return new Info(Optional.empty(), Optional.empty());
        }
    }

    static Info of(String pom) {
        List<Element> own = projectElements(pom);
        return new Info(text(pom, own, "name"), text(pom, own, "description"));
    }

    /**
     * Writes {@code name} and {@code description} into {@code projectDir}'s pom, a blank one removing its element.
     * Nothing is written when both already read so.
     */
    public static void write(Path projectDir, String name, String description) throws IOException {
        Path pom = projectDir.resolve("pom.xml");
        String before = Files.readString(pom);
        String after = with(with(before, "name", name), "description", description);
        if (after.equals(before)) return;
        // A fresh name, made new: a cloned bot may ship a symlink under any fixed name.
        Path next = Files.createTempFile(projectDir, ".pom", ".next");
        try {
            Files.writeString(next, after);
            Files.move(next, pom, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(next);
        }
    }

    /**
     * {@code pom} with the project's {@code <tag>} holding {@code value}: replaced in place, added after the
     * project's {@code <version>} (else its {@code <artifactId>}) on a line of its own, or removed when blank.
     */
    static String with(String pom, String tag, String value) {
        String wanted = value == null ? "" : value.strip();
        List<Element> own = projectElements(pom);
        Optional<Element> found = own.stream().filter(e -> e.tag().equals(tag)).findFirst();
        if (found.isPresent()) {
            Element e = found.get();
            if (wanted.isEmpty()) return without(pom, e);
            if (textOf(pom, e).equals(wanted)) return pom;
            if (e.selfClosed()) {
                return pom.substring(0, e.start()) + "<" + tag + ">" + escape(wanted) + "</" + tag + ">"
                        + pom.substring(e.end());
            }
            return pom.substring(0, e.contentStart()) + escape(wanted) + pom.substring(e.contentEnd());
        }
        if (wanted.isEmpty()) return pom;
        Optional<Element> after = own.stream().filter(e -> e.tag().equals("version")).findFirst()
                .or(() -> own.stream().filter(e -> e.tag().equals("artifactId")).findFirst());
        if (after.isEmpty()) return pom;
        Element anchor = after.get();
        String before = pom.substring(anchor.lineStart(), anchor.start());
        String indent = before.isBlank() ? before : before.replaceAll("\\S", "");
        String line = lineBreak(pom) + indent + "<" + tag + ">" + escape(wanted) + "</" + tag + ">";
        return pom.substring(0, anchor.end()) + line + pom.substring(anchor.end());
    }

    /** {@code pom} without {@code e}: its whole line when the element is alone on it. */
    private static String without(String pom, Element e) {
        String rest = pom.substring(e.end());
        String lineEnd = rest.startsWith("\r\n") ? "\r\n" : rest.startsWith("\n") ? "\n" : null;
        boolean alone = lineEnd != null && pom.substring(e.lineStart(), e.start()).isBlank();
        return alone ? pom.substring(0, e.lineStart()) + rest.substring(lineEnd.length())
                : pom.substring(0, e.start()) + rest;
    }

    private static String lineBreak(String pom) {
        return pom.contains("\r\n") ? "\r\n" : "\n";
    }

    private static Optional<String> text(String pom, List<Element> own, String tag) {
        return own.stream().filter(e -> e.tag().equals(tag)).findFirst()
                .map(e -> textOf(pom, e)).filter(s -> !s.isEmpty());
    }

    private static String textOf(String pom, Element e) {
        String raw = pom.substring(e.contentStart(), e.contentEnd());
        Matcher cdata = CDATA.matcher(raw);
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (cdata.find()) {
            out.append(unescape(raw.substring(at, cdata.start()))).append(cdata.group(1));
            at = cdata.end();
        }
        return out.append(unescape(raw.substring(at))).toString().strip();
    }

    /** The elements directly under {@code <project>} that hold text only (no child element), in order. */
    private static List<Element> projectElements(String pom) {
        List<Element> out = new ArrayList<>();
        Matcher m = TAG.matcher(pom);
        int depth = 0;
        String open = null;
        int start = -1;
        int contentStart = -1;
        while (m.find()) {
            if (m.group(2) == null) continue;   // a comment, CDATA or <?xml ?>
            boolean close = !m.group(1).isEmpty();
            boolean empty = !m.group(4).isEmpty();
            if (empty) {
                if (depth == 1) {
                    out.add(new Element(m.group(2), m.start(), m.end(), m.end(), m.end(), lineStart(pom, m.start()),
                            true));
                } else if (depth == 2) {
                    open = null;   // a child element: not a text value
                }
                continue;
            }
            if (!close) {
                depth++;
                if (depth == 2) {
                    open = m.group(2);
                    start = m.start();
                    contentStart = m.end();
                } else {
                    open = null;
                }
            } else {
                if (depth == 2 && open != null && open.equals(m.group(2))) {
                    out.add(new Element(open, start, contentStart, m.start(), m.end(), lineStart(pom, start), false));
                }
                if (depth == 2) open = null;
                depth--;
            }
        }
        return out;
    }

    private static int lineStart(String pom, int at) {
        return pom.lastIndexOf('\n', at) + 1;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
