package com.botmaker.studio.services;

import com.botmaker.plugin.api.ParameterGroup;
import com.botmaker.plugin.api.ParameterRow;
import com.botmaker.studio.project.activity.ValueWire;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Parameters dialog's rail, which is a decision rather than a widget: which buckets exist, what each holds,
 * and — the one that matters — that no parameter can end up in none of them.
 *
 * <p>The categories came from the picture library's {@code TagCatalog} until 2026-09-02 and are declared on a
 * {@link ParameterGroup} now, so the fixture is a list of plugin sections rather than an activity list. The
 * two headings the rail used to draw over the tags — <i>Activity categories</i>, <i>Custom categories</i> —
 * went with that split: a category has one origin now, the plugin that owns the section.
 *
 * <p>The fixture is {@link ParameterRow}s since 2026-09-10, which is what every reader of this model hands it
 * now that the Runner renders rows too.
 */
class VariableRailModelTest {

    /** Two sections, as two plugins would declare them — the merge is the thing under test. */
    private static List<ParameterGroup> groups() {
        return List.of(
                ParameterGroup.of(ParameterGroup.DEFAULT_ID, "Parameters", List.of("Mining", "Fishing")),
                ParameterGroup.of("discord", "DiscordParameters", List.of("Timing")));
    }

    private static List<String> categories() {
        return VariableRailModel.categoriesOf(groups());
    }

    /** Keyed by the persisted id, which is what a type <em>is</em> since the vocabulary opened. */
    private static ParameterRow row(String name, String typeId, String category) {
        return ParameterRow.named(name, ValueWire.one(typeId)).category(category).build();
    }

    private static List<ParameterRow> rows() {
        return List.of(
                row("RETRIES", "WHOLE_NUMBER", "Mining"),
                row("ORE", "TEXT", "Mining"),
                row("BAIT", "TEXT", "Fishing"),
                row("DEBUG", "YES_NO", ""),
                row("GAP", "DURATION", "Timing"));
    }

    @Test
    void theRailIsAllThenCategoriesThenEachDeclaredCategory() {
        List<VariableRailModel.Row> rail = VariableRailModel.rowsOf(rows(), categories());

        assertEquals(List.of("All variables (5)", "#Categories", "General (1)",
                        "Mining (2)", "Fishing (1)", "Timing (1)"),
                rail.stream().map(VariableRailModelTest::render).toList());
    }

    @Test
    void theCategoriesOfSeveralSectionsMergeInSectionOrderWithoutDuplicates() {
        // Two plugins may both call a category "Timing"; the rail is one list, so it must be listed once.
        List<ParameterGroup> overlapping = List.of(
                ParameterGroup.of(ParameterGroup.DEFAULT_ID, "Parameters", List.of("Timing", "Vision")),
                ParameterGroup.of("discord", "DiscordParameters", List.of("timing", "Webhooks")));

        assertEquals(List.of("Timing", "Vision", "Webhooks"), VariableRailModel.categoriesOf(overlapping),
                "first spelling wins, and it wins case-insensitively");
    }

    /** Both computed rows exist even with nothing in them: a bucket you cannot select is one you cannot fill. */
    @Test
    void allAndGeneralAreOfferedByAnEmptyProject() {
        List<VariableRailModel.Row> rail = VariableRailModel.rowsOf(List.of(), List.of());

        assertEquals(List.of("All variables (0)", "#Categories", "General (0)"),
                rail.stream().map(VariableRailModelTest::render).toList());
    }

    /**
     * The forward-and-backward compatibility case: a parameter carries a category nothing declares any more —
     * an older project's activity name, or a category the plugin dropped. It must still have a home, or a
     * value would be invisible in the one dialog that edits it while still being read by the bot.
     */
    @Test
    void aVariableFiledUnderAVanishedCategoryIsListedUnderGeneral() {
        List<ParameterRow> rows = List.of(row("ORE", "TEXT", "Smelting"));

        List<ParameterRow> general = VariableRailModel.rowsIn(rows, ParameterRow.GENERAL, categories());

        assertEquals(List.of("ORE"), general.stream().map(ParameterRow::name).toList());
        assertEquals(1, VariableRailModel.rowsIn(rows, VariableRailModel.ALL, categories()).size());
    }

    @Test
    void everyVariableIsReachableFromExactlyOneTagRow() {
        List<ParameterRow> rows = rows();
        List<String> categories = categories();

        for (ParameterRow v : rows) {
            long homes = VariableRailModel.rowsOf(rows, categories).stream()
                    .filter(r -> r instanceof VariableRailModel.TagRow t && !t.tag().equals(VariableRailModel.ALL))
                    .map(r -> ((VariableRailModel.TagRow) r).tag())
                    .filter(tag -> VariableRailModel.rowsIn(rows, tag, categories).contains(v))
                    .count();
            assertEquals(1, homes, v.name() + " should be listed under exactly one category");
        }
    }

    @Test
    void aCategoryIsMatchedHoweverItIsSpelled() {
        List<ParameterRow> rows = List.of(row("RETRIES", "WHOLE_NUMBER", "mining"));

        assertTrue(VariableRailModel.rowsIn(rows, "Mining", categories()).contains(rows.getFirst()),
                "a category read out of the file is matched the way a user reads it");
        assertTrue(VariableRailModel.isDeclared(categories(), "MINING"),
                "and the declared-ness test agrees with the filter, or a variable would be in two rows");
    }

    private static String render(VariableRailModel.Row row) {
        return switch (row) {
            case VariableRailModel.Heading heading -> "#" + heading.text();
            case VariableRailModel.TagRow tag -> tag.tag() + " (" + tag.count() + ")";
        };
    }
}
