package com.botmaker.studio.project.models;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A model instance written as the source a bot compiles. */
class ModelWriterTest {

    private static final ModelValues.Node STEP =
            new ModelValues.Node("tap", Duration.ofMillis(250), ModelValues.Direction.FORWARD);

    private static String initializer(Record value) {
        return ModelWriter.initializer(new ModelForm.Declared(value.getClass()), value, ModelValues.CATALOG)
                .orElseThrow();
    }

    @Test
    void aRecordIsItsCanonicalConstructorFullyQualified() {
        assertEquals("new " + ModelValues.Node.class.getCanonicalName()
                        + "(\"tap\", java.time.Duration.ofMillis(250L), "
                        + ModelValues.Direction.class.getCanonicalName() + ".FORWARD)",
                initializer(STEP));
    }

    @Test
    void aContainerIsWrittenWithTheFactoryItDeclared() {
        ModelValues.Flow flow = new ModelValues.Flow("main", List.of(STEP), Map.of("first", STEP), 2);
        String source = initializer(flow);
        assertTrue(source.contains("java.util.List.of(new "), source);
        assertTrue(source.contains("java.util.Map.ofEntries(java.util.Map.entry(\"first\", new "), source);
    }

    /**
     * A map of ten pairs is the case {@code Map.of} cannot take, and the reason {@code 32} settled on
     * {@code ofEntries} for every map rather than for the big ones: one syntax rule, and the cliff never
     * exists.
     */
    @Test
    void aMapPastTenPairsIsWrittenTheSameWayAsAMapOfOne() {
        Map<String, ModelValues.Node> many = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 12; i++) many.put("step" + i, STEP);
        String source = initializer(new ModelValues.Flow("main", List.of(), many, 1));
        assertEquals(12, source.split("java\\.util\\.Map\\.entry\\(", -1).length - 1, source);
    }

    /**
     * Every builtin in argument position at once. The suffixes and casts are the whole point: without them
     * this source names the right values and does not compile, which is the failure mode the first stated
     * requirement is about.
     */
    @Test
    void eachBuiltinIsWrittenAsTheLiteralThatCompilesInArgumentPosition() {
        String source = initializer(new ModelValues.Primitives(
                true, (byte) 3, 'x', (short) 9, 42, 42L, 1.5f, 1.5, "hi"));
        assertEquals("new " + ModelValues.Primitives.class.getCanonicalName()
                + "(true, (byte) 3, 'x', (short) 9, 42, 42L, 1.5f, 1.5, \"hi\")", source);
    }

    @Test
    void aStringIsEscapedAndAnythingOutsideAsciiIsWrittenAsAnEscape() {
        String source = initializer(new ModelValues.Node("say \"go\"\n日", Duration.ZERO,
                ModelValues.Direction.BACK));
        assertTrue(source.contains("\"say \\\"go\\\"\\n\\u65e5\""), source);
    }

    @Test
    void aNonFiniteNumberIsWrittenAsTheConstantThatNamesIt() {
        String source = initializer(new ModelValues.Primitives(
                false, (byte) 0, 'a', (short) 0, 0, 0L, Float.NaN, Double.POSITIVE_INFINITY, ""));
        assertTrue(source.contains("Float.NaN"), source);
        assertTrue(source.contains("Double.POSITIVE_INFINITY"), source);
    }

    /**
     * A legal type says nothing about what an instance of it holds, so {@code null} is the one refusal the
     * writer still has to make — and it refuses the whole value rather than writing the rest of it.
     */
    @Test
    void aNullComponentRefusesTheWholeModel() {
        ModelValues.Node broken = new ModelValues.Node(null, Duration.ZERO, ModelValues.Direction.BACK);
        assertTrue(ModelWriter.initializer(new ModelForm.Declared(ModelValues.Node.class), broken,
                ModelValues.CATALOG).isEmpty());
        assertTrue(ModelWriter.compilationUnit("com.example.bot.plugins.sdk", "Flow",
                new ModelValues.Flow("main", List.of(broken), Map.of(), 1), ModelValues.CATALOG).isEmpty());
    }

    @Test
    void theFileHoldsOneConstantOfThePluginsOwnTypeAndImportsNothing() {
        String source = ModelWriter.compilationUnit("com.example.bot.plugins.sdk", "Flow",
                new ModelValues.Flow("main", List.of(STEP), Map.of(), 1), ModelValues.CATALOG).orElseThrow();

        assertTrue(source.startsWith("package com.example.bot.plugins.sdk;\n"), source);
        assertFalse(source.contains("\nimport "), source);
        assertTrue(source.contains("public final class Flow {"), source);
        assertTrue(source.contains("public static final " + ModelValues.Flow.class.getCanonicalName()
                + " " + ModelWriter.FIELD + " = new "), source);
        assertTrue(source.contains("private Flow() {"), source);
        assertTrue(source.contains("Generated by BotMaker"), source);
    }

    @Test
    void aModelTheGrammarRefusesIsNeverWritten() {
        assertTrue(ModelWriter.compilationUnit("com.example.bot.plugins.sdk", "Broken",
                new ModelValues.HasAnInterface("x", () -> {
                }), ModelValues.CATALOG).isEmpty());
    }
}
