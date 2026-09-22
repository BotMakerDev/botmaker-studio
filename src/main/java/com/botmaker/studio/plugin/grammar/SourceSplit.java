package com.botmaker.studio.plugin.grammar;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Taking a written call back apart: the depth-zero comma split every container and every component type
 * needs.
 *
 * <p><b>Not a parser, deliberately.</b> What this reads is what the grammar wrote — one expression per
 * part — and {@link JavaExpressions} is the real parser for anything whose meaning is asked. The split exists
 * so the <em>round trip</em> is decidable without one, which is true only because the writer's output is the
 * reader's whole input domain.
 *
 * <p>Brackets and quotes are tracked so a part containing a comma is one part: a string, a
 * {@code new Color(255, 0, 0)}, a nested {@code List.of(a, b)}. An unbalanced source answers empty rather
 * than a wrong split — a partial reading is not a reading.
 */
final class SourceSplit {

    private SourceSplit() {
    }

    /**
     * The arguments of a call to {@code factory}, or empty when {@code source} is not one.
     *
     * <p>Every dotted suffix of the name is accepted — {@code java.util.List.of(…)} is what a generated
     * file carries and {@code List.of(…)} is what a user's own file does — down to {@code Owner.factory}.
     * {@code bare} also accepts the method name alone, which a static import writes; a caller that does not
     * already know which type it is reading refuses it, because {@code of(…)} is every factory at once.
     */
    static Optional<List<String>> arguments(String source, String factory, boolean bare) {
        if (source == null || factory == null) return Optional.empty();
        String trimmed = source.strip();
        if (!trimmed.endsWith(")")) return Optional.empty();
        for (String candidate : suffixes(factory, bare)) {
            if (trimmed.startsWith(candidate + "(")) {
                return parts(trimmed.substring(candidate.length() + 1, trimmed.length() - 1));
            }
        }
        return Optional.empty();
    }

    /** {@link #arguments(String, String, boolean)}, with the bare method name accepted. */
    static Optional<List<String>> arguments(String source, String factory) {
        return arguments(source, factory, true);
    }

    /**
     * The arguments of {@code new Type(…)}, or empty when {@code source} is not that — accepting every
     * dotted suffix of the type's name, and never the bare constructor, since {@code new} always names one.
     */
    static Optional<List<String>> constructorArguments(String source, String type) {
        if (source == null || type == null) return Optional.empty();
        String trimmed = source.strip();
        if (!trimmed.startsWith("new") || trimmed.length() < 4 || !Character.isWhitespace(trimmed.charAt(3))) {
            return Optional.empty();
        }
        return arguments(trimmed.substring(3), type, true);
    }

    /**
     * {@code name}'s dotted suffixes, longest first: {@code java.util.Map.ofEntries}, then
     * {@code util.Map.ofEntries}, {@code Map.ofEntries}, and {@code ofEntries} only when {@code bare}.
     */
    private static List<String> suffixes(String name, boolean bare) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (true) {
            String candidate = name.substring(start);
            if (candidate.indexOf('.') >= 0 || bare) out.add(candidate);
            int dot = name.indexOf('.', start);
            if (dot < 0) break;
            start = dot + 1;
        }
        return out;
    }

    /** {@code inner} split on its depth-zero commas, or empty when it does not balance. */
    static Optional<List<String>> parts(String inner) {
        if (inner == null) return Optional.empty();
        if (inner.isBlank()) return Optional.of(List.of());

        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            boolean escaped = i > 0 && inner.charAt(i - 1) == '\\';
            if (c == '"' && !inChar && !escaped) inString = !inString;
            else if (c == '\'' && !inString && !escaped) inChar = !inChar;
            // Angle brackets are deliberately not tracked: a diamond balances nothing and needs nothing, and
            // an explicit type witness is something no writer here emits. A lone `>` from tracking them would
            // empty an answer that is fine.
            else if (!inString && !inChar && (c == '(' || c == '[' || c == '{')) depth++;
            else if (!inString && !inChar && (c == ')' || c == ']' || c == '}')) depth--;
            else if (!inString && !inChar && c == ',' && depth == 0) {
                parts.add(part.toString().strip());
                part.setLength(0);
                continue;
            }
            if (depth < 0) return Optional.empty();
            part.append(c);
        }
        if (depth != 0 || inString || inChar) return Optional.empty();
        parts.add(part.toString().strip());
        return Optional.of(List.copyOf(parts));
    }
}
