package com.botmaker.studio.services;

import com.botmaker.plugin.api.ParameterGroup;
import com.botmaker.plugin.api.ParameterRow;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Parameters dialog's tag rail shows, and which variables each row holds — the parts of that dialog that
 * are a decision rather than a widget, kept free of JavaFX so they can be tested headlessly.
 *
 * <p><b>The categories are the plugins' now, not the activity list's (2026-09-02).</b> This rail was built
 * over {@code TagCatalog} — one tag per activity in {@code activities.json}, plus the custom tags in
 * {@code templates.json} — which was right while Studio was the thing that defined activities. It no longer
 * is: activities are the SDK plugin's, so the rail was reading one plugin's file to decide the editor's own
 * headings, and renaming an activity silently renamed a category. What replaced it is a second layer
 * <em>inside</em> a section: each {@link ParameterGroup} declares the categories its own parameters may be
 * filed under, and this model is handed that list. There is nothing left to drift because there is nothing
 * left to derive.
 *
 * <p><b>Nothing is ever unreachable.</b> A variable filed under a category the group no longer declares —
 * the plugin dropped it, or an older project carries an activity name — would otherwise have no row at all.
 * Rather than inventing one, it is listed under {@link ParameterRow#GENERAL}, which is where a parameter
 * with no category lives and where this one effectively now is. The dialog's picker then shows it as
 * unfiled, so the fix is a visible choice rather than a silent relabel.
 */
public final class VariableRailModel {

    /** The row that holds everything. Not a tag: no variable carries it and nothing may declare it. */
    public static final String ALL = "All variables";

    private VariableRailModel() {}

    /** A rail entry: either a group heading or a selectable tag. */
    public sealed interface Row permits Heading, TagRow {}

    /** A non-selectable group label ("Activities", "Custom"). */
    public record Heading(String text) implements Row {}

    /** A selectable bucket, including the computed {@link #ALL} and {@link ParameterRow#GENERAL} rows. */
    public record TagRow(String tag, int count) implements Row {}

    /** Every category declared by any of {@code groups}, in group order then declaration order, deduplicated. */
    public static List<String> categoriesOf(List<ParameterGroup> groups) {
        if (groups == null || groups.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (ParameterGroup group : groups) {
            for (String category : group.categories()) {
                if (out.stream().noneMatch(seen -> seen.equalsIgnoreCase(category))) out.add(category);
            }
        }
        return List.copyOf(out);
    }

    /** Whether any of {@code declared} is {@code category}, compared the way a user reads it. */
    public static boolean isDeclared(List<String> declared, String category) {
        if (category == null || category.isBlank() || declared == null) return false;
        return declared.stream().anyMatch(d -> d.equalsIgnoreCase(category.trim()));
    }

    /**
     * The rail for the {@link ParameterRow}s the plugins hand over, over the categories {@code declared} by
     * those plugins: All, then a <i>Categories</i> heading over General and each declared category in the
     * order the plugin listed them. Both computed rows are always present — All because it is the way to see
     * everything, General because it is where a new parameter lands before anyone files it, and a bucket you
     * cannot select is a bucket you cannot put anything in.
     *
     * <p>The declared list is flat and already merged across groups. It is not split into a heading per
     * plugin, because the sections in the pane on the right already are: a rail that also grouped by plugin
     * would ask the user to hold the plugin architecture in their head twice.
     *
     * <p>There was a second pair of these taking Studio's own {@code ActivityVariable}, for as long as the
     * Runner still read one plugin's file; it went on 2026-09-10 with that window's retype.
     */
    public static List<Row> rowsOf(List<ParameterRow> rows, List<String> declared) {
        List<ParameterRow> all = rows == null ? List.of() : rows;
        List<String> categories = declared == null ? List.of() : declared;

        List<Row> out = new ArrayList<>();
        out.add(new TagRow(ALL, all.size()));
        out.add(new Heading("Categories"));
        out.add(new TagRow(ParameterRow.GENERAL, rowsIn(all, ParameterRow.GENERAL, categories).size()));
        for (String category : categories) {
            out.add(new TagRow(category, rowsIn(all, category, categories).size()));
        }
        return List.copyOf(out);
    }

    /**
     * The rows {@code tag} holds, in the order they were declared.
     *
     * <p>{@link #ALL} is everything. {@link ParameterRow#GENERAL} is everything unfiled <em>plus</em>
     * everything whose category no plugin declares — see the class note. Any other row is an exact,
     * case-insensitive match.
     */
    public static List<ParameterRow> rowsIn(List<ParameterRow> rows, String tag, List<String> declared) {
        return filter(rows, ParameterRow::category, tag, declared);
    }

    /**
     * The members of {@code items} the row {@code tag} holds, whatever a member's category is read off.
     *
     * <p>One implementation for both shapes: the rule — <i>All is everything, General is the unfiled plus
     * everything nobody declares, anything else is an exact case-insensitive match</i> — is the part that
     * must not exist twice, because the pane on the right and the counts on the left would then disagree
     * about which bucket a variable is in.
     */
    private static <T> List<T> filter(List<T> items, java.util.function.Function<T, String> categoryOf,
                                      String tag, List<String> declared) {
        if (items == null || items.isEmpty()) return List.of();
        if (tag == null || ALL.equals(tag)) return List.copyOf(items);
        if (ParameterRow.GENERAL.equals(tag)) {
            return items.stream().filter(item -> !isDeclared(declared, categoryOf.apply(item))).toList();
        }
        return items.stream().filter(item -> categoryOf.apply(item).equalsIgnoreCase(tag)).toList();
    }
}
