package com.botmaker.studio.project.managed;

import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.MigrationSteps.Choice;
import com.botmaker.studio.project.managed.MigrationSteps.Step;
import com.botmaker.studio.project.managed.MigrationTypes.Area;
import com.botmaker.studio.project.managed.MigrationTypes.Frame;
import com.botmaker.studio.project.managed.MigrationTypes.OldArea;
import com.botmaker.studio.project.managed.MigrationTypes.OldFrame;
import com.botmaker.studio.project.managed.MigrationTypes.OldShot;
import com.botmaker.studio.project.managed.MigrationTypes.OldTint;
import com.botmaker.studio.project.managed.MigrationTypes.Shot;
import com.botmaker.studio.project.managed.MigrationTypes.Tint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** set_migration's lines over the window's seeded steps: what they change, and what they refuse. */
class MigrationMappingTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(MigrationTypes.types(), List.of());

    private static MigrationMapping.Asked steps(Class<?> from, Class<?> to, String mapping) {
        return MigrationMapping.steps(GRAMMAR, List.of(), from, to, mapping);
    }

    private static String refused(Class<?> from, Class<?> to, String mapping) {
        MigrationMapping.Asked asked = steps(from, to, mapping);
        assertTrue(asked.steps().isEmpty(), asked.toString());
        return asked.problem();
    }

    @Test
    void noLinesTakeTheFirstGuessAndALineChangesOnePart() {
        MigrationMapping.Asked guessed = steps(OldShot.class, Shot.class, "");
        assertNull(guessed.problem());
        Step.Calls step = assertInstanceOf(Step.Calls.class, guessed.steps().getFirst());
        assertEquals("false", assertInstanceOf(Choice.Fixed.class, step.choices().get(2)).value().source());

        Step.Calls grey = assertInstanceOf(Step.Calls.class, steps(OldShot.class, Shot.class,
                "grey: fixed true\nthreshold -> from threshold").steps().getFirst());
        assertEquals(new Choice.From(0), grey.choices().get(0));
        assertEquals(new Choice.From(1), grey.choices().get(1));
        assertEquals("true", assertInstanceOf(Choice.Fixed.class, grey.choices().get(2)).value().source());

        // A value may hold a separator or ';' of its own.
        Step.Calls file = assertInstanceOf(Step.Calls.class, steps(OldShot.class, Shot.class,
                "file -> fixed \"a:b;c.png\"").steps().getFirst());
        assertEquals("\"a:b;c.png\"", assertInstanceOf(Choice.Fixed.class, file.choices().get(0)).value().source());
    }

    @Test
    void aPairThePartsGoThroughIsAStepOfItsOwnNamedByItsType() {
        MigrationMapping.Asked asked = steps(OldFrame.class, Frame.class, "Area.w: fixed 5");
        assertNull(asked.problem());
        assertEquals(List.of(OldFrame.class, OldArea.class), asked.steps().stream().map(Step::from).toList());
        Step.Calls area = assertInstanceOf(Step.Calls.class, asked.steps().get(1));
        assertEquals(Area.class, area.to());
        assertEquals("5", assertInstanceOf(Choice.Fixed.class, area.choices().get(2)).value().source());
    }

    @Test
    void anEnumsArmsChangeByConstant() {
        Step.Arms arms = assertInstanceOf(Step.Arms.class, steps(OldTint.class, Tint.class, "RED → NAVY").steps()
                .getFirst());
        assertEquals(Map.of("RED", "NAVY", "BLUE", "NAVY"), arms.arms());
        assertTrue(refused(OldTint.class, Tint.class, "RED: PINK").contains("RED cannot become PINK; it can become "
                + "CRIMSON, NAVY."));
        assertTrue(refused(OldTint.class, Tint.class, "GREEN: NAVY").contains("OldTint has no constant GREEN"));
    }

    @Test
    void aWrongLineRefusesAndSaysWhatWouldDo() {
        assertTrue(refused(OldShot.class, Shot.class, "nope: fixed 1").contains("the new type has no part nope; its "
                + "parts: file, threshold, grey."));
        assertTrue(refused(OldShot.class, Shot.class, "grey: from file").contains("grey (boolean) cannot be from "
                + "file (file (String))."));
        assertTrue(refused(OldShot.class, Shot.class, "file: from nothing").contains("the old type has no part "
                + "nothing"));
        assertTrue(refused(OldShot.class, Shot.class, "threshold: fixed \"high\"").contains("is not a int"));
        assertTrue(refused(OldShot.class, Shot.class, "grey: maybe").contains("write from <part>"));
        assertTrue(refused(OldShot.class, Shot.class, "just words").startsWith("\"just words\" is not a line"));
        assertTrue(refused(OldShot.class, Shot.class, "Area.w: fixed 1").startsWith("Area.w: fixed 1: no pair"));
        assertTrue(refused(OldTint.class, Shot.class, "").startsWith("OldTint → Shot: An enum migrates only"));
    }

    @Test
    void aDraftListsEachValueBeforeAndAfter() {
        MigrationSteps.Draft draft = new MigrationSteps.Draft(new Refactor.Refused("It would not compile."),
                new ManagedMigrations.Report(List.of(), List.of("GOLD"), List.of("ORE: no factory")),
                List.of(new MigrationSteps.Row("ORE", "new OldShot(\"ore.png\", 80)",
                        "new Shot(\"ore.png\", 80, false)")));
        assertEquals("""
                Refused: It would not compile.
                1 value, as it is → as copied:
                - ORE: new OldShot("ore.png", 80) → new Shot("ore.png", 80, false)
                Kept as you made them: GOLD.
                Not migrated: ORE: no factory.""", MigrationSummary.draft(draft));
    }
}
