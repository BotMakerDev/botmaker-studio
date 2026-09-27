package com.botmaker.studio.services;

import com.botmaker.studio.project.params.ParameterRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the Parameters dialog's rail shows, and which parameters each row holds — the parts of that dialog that
 * are a decision rather than a widget, kept free of JavaFX so they can be tested headlessly.
 *
 * <p><b>A section is a class, and its categories are inside it (2026-09-27).</b> The rail is <i>All</i>, then
 * one heading per class that declares {@code @Param} fields — the section, which is what the bot spells in
 * front of a name — and under it <i>General</i> and each category that class's fields use. It was one flat
 * list of categories until then, merged across classes, so "Timing" in two classes was one row holding both
 * and a category's row could not say where a new parameter in it would be written. An activity with
 * parameters keeps them in its own class ({@code Collect}), so its section is its settings.
 *
 * <p><b>A category is free text (2026-09-22).</b> The categories under a section are the ones its fields
 * use, plus any the user has just created in this window and not yet filed anything under ({@code added}):
 * a category exists because something is filed under it, and one made a moment ago has to exist long enough
 * to be filled. Nothing is ever unreachable — a field with no category is under its section's
 * {@link ParameterRow#GENERAL}.
 */
public final class VariableRailModel {

    /** The row that holds everything. Not a tag: no variable carries it and nothing may declare it. */
    public static final String ALL = "All variables";

    private VariableRailModel() {}

    /** One parameter and the class (section) that declares it. */
    public record Filed(String section, ParameterRow row) {}

    /** A rail entry: either a section heading or a selectable tag. */
    public sealed interface Row permits Heading, TagRow {}

    /** A non-selectable section heading: the class. */
    public record Heading(String text) implements Row {}

    /**
     * A selectable bucket: {@link #ALL} (with no section), or a section's {@link ParameterRow#GENERAL} or one
     * of its categories.
     */
    public record TagRow(String section, String tag, int count) implements Row {}

    /** The categories {@code rows} use, in declaration order, deduplicated the way the rail compares them. */
    public static List<String> categoriesOf(List<ParameterRow> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (ParameterRow row : rows) add(out, row.category());
        return List.copyOf(out);
    }

    /**
     * The categories under {@code section}: the ones its fields use, in declaration order, then the ones just
     * {@code added} there that none of them uses yet.
     */
    public static List<String> categoriesIn(List<Filed> filed, String section, List<String> added) {
        List<String> out = new ArrayList<>(categoriesOf(inSection(filed, section)));
        if (added != null) for (String category : added) add(out, category);
        return List.copyOf(out);
    }

    /**
     * The rail: All, then per section in {@code sections}' order a heading, General and its categories. Every
     * section's General is present even with nothing in it — it is where a new parameter lands before anyone
     * files it, and a bucket you cannot select is a bucket you cannot put anything in.
     */
    public static List<Row> rowsOf(List<Filed> filed, List<String> sections, Map<String, List<String>> added) {
        List<Filed> all = filed == null ? List.of() : filed;
        List<Row> out = new ArrayList<>();
        out.add(new TagRow(null, ALL, all.size()));
        for (String section : sections) {
            out.add(new Heading(section));
            out.add(new TagRow(section, ParameterRow.GENERAL, rowsIn(all, section, ParameterRow.GENERAL).size()));
            for (String category : categoriesIn(all, section, added == null ? null : added.get(section))) {
                out.add(new TagRow(section, category, rowsIn(all, section, category).size()));
            }
        }
        return List.copyOf(out);
    }

    /**
     * What the row ({@code section}, {@code tag}) holds, in declaration order: everything for {@link #ALL};
     * otherwise the section's fields with no category for {@link ParameterRow#GENERAL}, or those filed under
     * {@code tag}, matched the way a user reads it.
     */
    public static List<Filed> rowsIn(List<Filed> filed, String section, String tag) {
        if (filed == null || filed.isEmpty()) return List.of();
        if (tag == null || ALL.equals(tag)) return List.copyOf(filed);
        return filed.stream()
                .filter(entry -> entry.section().equals(section))
                .filter(entry -> ParameterRow.GENERAL.equals(tag) ? entry.row().category().isBlank()
                        : entry.row().category().strip().equalsIgnoreCase(tag))
                .toList();
    }

    private static List<ParameterRow> inSection(List<Filed> filed, String section) {
        if (filed == null) return List.of();
        return filed.stream().filter(entry -> entry.section().equals(section)).map(Filed::row).toList();
    }

    private static void add(List<String> out, String category) {
        if (category == null || category.isBlank()) return;
        String trimmed = category.strip();
        if (out.stream().noneMatch(seen -> seen.equalsIgnoreCase(trimmed))) out.add(trimmed);
    }
}
