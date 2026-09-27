package com.botmaker.studio.services;

import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.studio.services.VariableRailModel.Filed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Parameters dialog's rail, which is a decision rather than a widget: which buckets exist, what each holds,
 * and — the one that matters — that no parameter can end up in none of them.
 *
 * <p>Since 2026-09-27 the rail is grouped by section, the class that declares the field, with that class's
 * categories inside it; the categories came from the rows themselves since 2026-09-22, a
 * {@code @Param(category = …)} being free text.
 */
class VariableRailModelTest {

    private static Filed row(String section, String name, String category) {
        return new Filed(section, ParameterRow.named(name, "int").category(category).build());
    }

    private static List<Filed> rows() {
        return List.of(
                row("Parameters", "RETRIES", "Mining"),
                row("Parameters", "ORE", "Mining"),
                row("Parameters", "DEBUG", ""),
                row("Collect", "BAIT", "Timing"),
                row("Parameters", "GAP", "Timing"));
    }

    @Test
    void theRailIsAllThenEachSectionWithItsGeneralAndCategories() {
        List<VariableRailModel.Row> rail =
                VariableRailModel.rowsOf(rows(), List.of("Parameters", "Collect"), Map.of());

        assertEquals(List.of("All variables (5)",
                        "#Parameters", "Parameters/General (1)", "Parameters/Mining (2)", "Parameters/Timing (1)",
                        "#Collect", "Collect/General (0)", "Collect/Timing (1)"),
                rail.stream().map(VariableRailModelTest::render).toList(),
                "a category two classes use is one row in each, holding that class's fields");
    }

    /** A category made in the window exists, empty, in the section it was made in, until something fills it. */
    @Test
    void aCategoryJustCreatedIsListedInItsSectionEmpty() {
        List<VariableRailModel.Row> rail = VariableRailModel.rowsOf(rows(), List.of("Parameters", "Collect"),
                Map.of("Collect", List.of("Vision", "timing")));

        assertEquals(List.of("All variables (5)",
                        "#Parameters", "Parameters/General (1)", "Parameters/Mining (2)", "Parameters/Timing (1)",
                        "#Collect", "Collect/General (0)", "Collect/Timing (1)", "Collect/Vision (0)"),
                rail.stream().map(VariableRailModelTest::render).toList(),
                "a new one the section already uses, however it is spelled, is not listed twice");
    }

    /** Both kinds of section row exist with nothing in them: a bucket you cannot select is one you cannot fill. */
    @Test
    void anEmptyProjectOffersAllAndItsOneSectionsGeneral() {
        assertEquals(List.of("All variables (0)", "#Parameters", "Parameters/General (0)"),
                VariableRailModel.rowsOf(List.of(), List.of("Parameters"), Map.of()).stream()
                        .map(VariableRailModelTest::render).toList());
    }

    @Test
    void everyParameterIsReachableFromExactlyOneSectionRow() {
        List<Filed> rows = rows();
        List<VariableRailModel.Row> rail = VariableRailModel.rowsOf(rows, List.of("Parameters", "Collect"), Map.of());

        for (Filed entry : rows) {
            long homes = rail.stream()
                    .filter(r -> r instanceof VariableRailModel.TagRow t && t.section() != null)
                    .map(VariableRailModel.TagRow.class::cast)
                    .filter(t -> VariableRailModel.rowsIn(rows, t.section(), t.tag()).contains(entry))
                    .count();
            assertEquals(1, homes, entry.row().name() + " should be listed under exactly one row");
        }
    }

    @Test
    void aCategoryIsMatchedHoweverItIsSpelled() {
        List<Filed> rows = List.of(row("Parameters", "RETRIES", "mining"));

        assertEquals(rows, VariableRailModel.rowsIn(rows, "Parameters", "Mining"),
                "a category read out of the file is matched the way a user reads it");
        assertEquals(List.of("Timing", "Vision"), VariableRailModel.categoriesOf(List.of(
                        ParameterRow.named("A", "int").category("Timing").build(),
                        ParameterRow.named("B", "int").category("Vision").build(),
                        ParameterRow.named("C", "int").category("timing").build())),
                "first spelling wins, case-insensitively");
    }

    private static String render(VariableRailModel.Row row) {
        return switch (row) {
            case VariableRailModel.Heading heading -> "#" + heading.text();
            case VariableRailModel.TagRow tag -> (tag.section() == null ? "" : tag.section() + "/")
                    + tag.tag() + " (" + tag.count() + ")";
        };
    }
}
