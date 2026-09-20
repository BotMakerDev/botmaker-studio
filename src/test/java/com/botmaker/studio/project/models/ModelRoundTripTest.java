package com.botmaker.studio.project.models;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The law the whole design rests on: <b>the writer's output is the reader's whole input domain</b>.
 *
 * <p>Everything else in this package is machinery for this one property. A plugin hands over a record, the
 * host writes a file, the host reads the file back, and what comes out is the record that went in — with no
 * text crossing the contract in either direction and no second implementation of the grammar to keep in
 * step. A test that only checked the written source would let the two halves drift exactly the way
 * {@code ValueCodec.wireOfLiteral} drifted, which is the failure {@code 33-plugin-java.md} is a response to.
 */
class ModelRoundTripTest {

    private static final String PACKAGE = "com.example.bot.plugins.sdk";

    private static <T extends Record> T roundTrip(String simpleName, T value) {
        String source = ModelWriter.compilationUnit(PACKAGE, simpleName, value, ModelValues.CATALOG)
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) value.getClass();
        return ModelReader.read(source, simpleName, type, ModelValues.CATALOG).orElseThrow();
    }

    @Test
    void aModelSurvivesBeingWrittenAndReadBack() {
        ModelValues.Node step =
                new ModelValues.Node("tap", Duration.ofMillis(250), ModelValues.Direction.FORWARD);
        ModelValues.Flow flow = new ModelValues.Flow("main", List.of(step),
                Map.of("first", step), 7);
        assertEquals(flow, roundTrip("Flow", flow));
    }

    @Test
    void everyBuiltinComesBackAsTheTypeItsComponentIsDeclared() {
        ModelValues.Primitives value = new ModelValues.Primitives(
                true, (byte) -3, '\n', (short) -9, -42, 42L, 1.5f, -1.5, "say \"go\"日");
        assertEquals(value, roundTrip("Values", value));
    }

    @Test
    void aNonFiniteNumberSurvivesAsWell() {
        ModelValues.Primitives value = new ModelValues.Primitives(
                false, (byte) 0, 'a', (short) 0, 0, 0L, Float.NaN, Double.NEGATIVE_INFINITY, "");
        ModelValues.Primitives back = roundTrip("Values", value);
        assertTrue(Float.isNaN(back.ratio()));
        assertEquals(Double.NEGATIVE_INFINITY, back.precise());
    }

    /**
     * The reason the reader parses rather than splits on commas. A generated file is the one place a comma
     * inside a string is certain to turn up, because the strings in it are names a user typed.
     */
    @Test
    void aCommaInsideAKeyIsNotAnArgumentBoundary() {
        ModelValues.Node step = new ModelValues.Node("a, b", Duration.ZERO, ModelValues.Direction.BACK);
        ModelValues.Flow flow = new ModelValues.Flow("x", List.of(step), Map.of("k, j", step), 1);
        assertEquals(flow, roundTrip("Flow", flow));
    }

    @Test
    void aMapPastTenPairsRoundTripsLikeAnyOther() {
        ModelValues.Node step = new ModelValues.Node("tap", Duration.ofSeconds(1),
                ModelValues.Direction.FORWARD);
        Map<String, ModelValues.Node> many = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) many.put("step" + i, step);
        ModelValues.Flow flow = new ModelValues.Flow("big", List.of(), many, 1);
        assertEquals(many, roundTrip("Flow", flow).starts());
    }

    @Test
    void anEmptyContainerIsStillAReading() {
        ModelValues.Flow flow = new ModelValues.Flow("empty", List.of(), Map.of(), 0);
        assertEquals(flow, roundTrip("Flow", flow));
    }

    @Test
    void aRecursiveModelRoundTrips() {
        ModelValues.Tree tree = new ModelValues.Tree("root", List.of(
                new ModelValues.Tree("left", List.of()),
                new ModelValues.Tree("right", List.of(new ModelValues.Tree("leaf", List.of())))));
        assertEquals(tree, roundTrip("Tree", tree));
    }

    /**
     * A generated file is read-only for a reason, and this is what the role is protecting: an edit the host
     * did not write is not a reading it will guess at. The file comes back as empty rather than as a model
     * with one component the editor never chose.
     */
    @Test
    void aHandEditedInitialiserIsNotRead() {
        ModelValues.Node step = new ModelValues.Node("tap", Duration.ZERO, ModelValues.Direction.BACK);
        String source = ModelWriter.compilationUnit(PACKAGE, "Flow",
                new ModelValues.Flow("main", List.of(step), Map.of(), 1), ModelValues.CATALOG).orElseThrow();

        String edited = source.replace("\"main\"", "name()");
        assertTrue(ModelReader.read(edited, "Flow", ModelValues.Flow.class, ModelValues.CATALOG).isEmpty());

        String renamed = source.replace("java.util.List.of(", "java.util.Set.of(");
        assertTrue(ModelReader.read(renamed, "Flow", ModelValues.Flow.class, ModelValues.CATALOG).isEmpty());
    }

    @Test
    void aProjectThatNeverHadAModelReadsAsEmptyRatherThanFailing() {
        String unrelated = "package com.example.bot;\n\npublic final class Other {\n}\n";
        assertEquals(Optional.empty(),
                ModelReader.read(unrelated, "Flow", ModelValues.Flow.class, ModelValues.CATALOG));
        assertEquals(Optional.empty(), ModelReader.initializerIn(unrelated, "Flow"));
    }
}
