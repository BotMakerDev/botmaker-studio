package com.botmaker.studio.services.debug;

import com.botmaker.studio.services.debug.DebugSnapshot.Variable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A paused value in one short line, for the chip the canvas shows beside a variable (2026-09-27).
 *
 * <p>Read off <b>field names</b>, never a class: Studio names no plugin's type, and a vision result is whatever
 * has {@code found} beside a {@code location}, {@code bounds} or {@code centroid}. So a {@code MatchResult} reads
 * "found at 120, 340 · 93%", a {@code TextMatch} "“Start” at 10, 20 · 87%", a miss "not found", and anything
 * else its first few fields. The full tree is {@link #tree}, for the chip's tooltip.
 */
public final class ValueSummary {

    /** Fields shown in the fallback {@code Type{a=1, b=2, c=3, …}}. */
    static final int FIELDS_SHOWN = 3;

    private ValueSummary() {}

    public static String of(Variable v) {
        if (v.children().isEmpty() || v.size() >= 0) return v.value();
        Map<String, Variable> fields = new LinkedHashMap<>();
        for (Variable child : v.children()) fields.put(child.name(), child);

        Variable found = fields.get("found");
        if (found != null) return result(fields, "true".equals(found.value()));
        if (fields.size() == 1) {
            Variable only = fields.values().iterator().next();
            if (only.size() == 0) return "empty";
            if (only.size() > 0) return only.size() + (typeOf(v).endsWith("Matches") ? " found" : " items");
        }
        String shown = v.children().stream().limit(FIELDS_SHOWN)
                .map(c -> c.name() + "=" + c.value()).collect(Collectors.joining(", "));
        return typeOf(v) + "{" + shown + (v.children().size() > FIELDS_SHOWN ? ", …" : "") + "}";
    }

    /** A vision result: whether it was found, where, and how sure. */
    private static String result(Map<String, Variable> fields, boolean found) {
        if (!found) return "not found";
        Variable text = fields.get("text");
        boolean quoted = text != null && text.value().startsWith("\"");
        StringBuilder out = new StringBuilder(quoted
                ? "“" + text.value().substring(1, text.value().length() - 1) + "”" : "found");
        String at = at(fields);
        if (at != null) out.append(" at ").append(at);
        Double confidence = number(fields.get("confidence"));
        Double pixels = number(fields.get("pixelCount"));
        if (confidence != null) {
            // A matcher's score is 0..1, an OCR engine's 0..100: either way the chip says a percentage.
            out.append(" · ").append(Math.round(confidence <= 1 ? confidence * 100 : confidence)).append('%');
        } else if (pixels != null) {
            out.append(" · ").append(Math.round(pixels)).append(" px");
        }
        return out.toString();
    }

    /** "x, y" of the first of {@code location}, {@code bounds}, {@code centroid} that has both. */
    private static String at(Map<String, Variable> fields) {
        for (String name : List.of("location", "bounds", "centroid")) {
            Variable place = fields.get(name);
            if (place == null) continue;
            String x = null, y = null;
            for (Variable c : place.children()) {
                if (c.name().equals("x")) x = c.value();
                if (c.name().equals("y")) y = c.value();
            }
            if (x != null && y != null) return x + ", " + y;
        }
        return null;
    }

    private static Double number(Variable v) {
        if (v == null) return null;
        try {
            return Double.parseDouble(v.value());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** The runtime class an object reads as ({@code MatchResult #12}), else the declared type. */
    private static String typeOf(Variable v) {
        int id = v.value().indexOf(" #");
        return id > 0 ? v.value().substring(0, id) : v.type();
    }

    /** Every level that was read, one line each, indented two spaces a level. */
    public static String tree(Variable v) {
        StringBuilder out = new StringBuilder();
        tree(v, 0, out);
        return out.toString();
    }

    private static void tree(Variable v, int depth, StringBuilder out) {
        if (!out.isEmpty()) out.append('\n');
        out.append("  ".repeat(depth)).append(v.name()).append(" = ").append(v.value());
        for (Variable child : v.children()) tree(child, depth + 1, out);
    }
}
