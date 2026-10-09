package com.botmaker.studio.plugin.grammar;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.DeclaredCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A call declared with withers is written as its factory followed by a named link for each part that differs
 * from what the factory makes, and read back whatever order the links are in: {@code read(write(v)) == v}
 * for every combination of set and unset parts.
 */
class WitherValuesTest {

    /** A step: the factory takes its name, and every other part is a link. */
    public record Step(String name, String note, boolean home, boolean disabled, int tries) {
        public static Step of(String name) {
            return new Step(name, "", false, false, 1);
        }

        public Step described(String note) {
            return new Step(name, note, home, disabled, tries);
        }

        public Step goesHome() {
            return new Step(name, note, true, disabled, tries);
        }

        public Step off() {
            return new Step(name, note, home, true, tries);
        }

        public Step tries(int count) {
            return new Step(name, note, home, disabled, count);
        }
    }

    private static final DeclaredCall<Step> STEP = ComponentType.part(Step.class)
            .writtenAs(Step::of, Step::name)
            .with(Step::described, Step::note)
            .flag(Step::goesHome, Step::home)
            .flag(Step::off, Step::disabled)
            .with(Step::tries, Step::tries);

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(), List.of(STEP));

    private static final String OWNER = JavaNames.canonical(Step.class);

    private static Optional<String> written(Step step) {
        return GRAMMAR.initializer(Step.class, step).map(JavaValue::source);
    }

    @Test
    void everyCombinationOfLinksRoundTrips() {
        for (String note : List.of("", "Picks up ore")) {
            for (boolean home : new boolean[]{false, true}) {
                for (boolean off : new boolean[]{false, true}) {
                    for (int tries : new int[]{1, 3}) {
                        Step step = new Step("Collect", note, home, off, tries);
                        String source = written(step).orElseThrow(() -> new AssertionError(step.toString()));
                        assertEquals(Optional.of(step), GRAMMAR.valueOf(Step.class, source), source);
                        assertEquals(Optional.of(step), GRAMMAR.valueOfAny(source), source);
                    }
                }
            }
        }
    }

    @Test
    void onlyAPartTheFactoryDoesNotMakeIsALink() {
        assertEquals(Optional.of(OWNER + ".of(\"Collect\")"), written(Step.of("Collect")));
        assertEquals(Optional.of(OWNER + ".of(\"Collect\").described(\"Picks up ore\").goesHome()"),
                written(Step.of("Collect").goesHome().described("Picks up ore")),
                "links in declaration order, whatever order the value was made in");
        assertEquals(Optional.of(OWNER + ".of(\"Collect\").off().tries(3)"),
                written(Step.of("Collect").tries(3).off()));
    }

    @Test
    void linksReadInAnyOrderAndALinkLeftOutIsTheFactorys() {
        Step expected = Step.of("Collect").described("x").goesHome();
        assertEquals(Optional.of(expected), GRAMMAR.valueOf(Step.class, "Step.of(\"Collect\").goesHome().described(\"x\")"));
        assertEquals(Optional.of(Step.of("Collect")), GRAMMAR.valueOf(Step.class, "Step.of(\"Collect\")"));
        assertEquals(Optional.of(expected),
                GRAMMAR.valueOf(Step.class, "(Step.of(\"Collect\").goesHome()).described((\"x\"))"));
    }

    /** A walk the factory sends home, with a constant the type names. */
    public record Walk(String name, boolean home, String note) {
        public static final Walk PLAIN = of("Plain");

        public static Walk of(String name) {
            return new Walk(name, true, "");
        }

        public Walk goesHome() {
            return new Walk(name, true, note);
        }

        public Walk described(String note) {
            return new Walk(name, home, note);
        }
    }

    private static final ValueGrammar WALKS = ValueGrammar.of(List.of(), List.of(ComponentType.part(Walk.class)
            .writtenAs(Walk::of, Walk::name)
            .flag(Walk::goesHome, Walk::home)
            .with(Walk::described, Walk::note)
            .constants(Walk.PLAIN)));

    /**
     * A named constant is written as itself; a chain always starts at the factory, never at a constant the
     * reader would not unwind to; and a link no flag can say loses only its own part.
     */
    @Test
    void aConstantIsItselfAndALinkThatCannotBeSaidLosesOnlyItsPart() {
        String owner = JavaNames.canonical(Walk.class);
        assertEquals(Optional.of(owner + ".PLAIN"), WALKS.initializer(Walk.class, Walk.PLAIN).map(JavaValue::source));
        String described = WALKS.initializer(Walk.class, Walk.PLAIN.described("x")).map(JavaValue::source)
                .orElseThrow();
        assertEquals(owner + ".of(\"Plain\").described(\"x\")", described);
        assertEquals(Optional.of(Walk.PLAIN.described("x")), WALKS.valueOf(Walk.class, described));
        assertEquals(Optional.of(owner + ".of(\"Far\").described(\"x\")"),
                WALKS.initializer(Walk.class, new Walk("Far", false, "x")).map(JavaValue::source),
                "the factory sends it home and no flag says otherwise: only home is lost");
    }

    @Test
    void aChainNoWriterMakesStaysUnread() {
        assertTrue(GRAMMAR.valueOf(Step.class, "Step.of(\"A\").goesHome().goesHome()").isEmpty(), "set twice");
        assertTrue(GRAMMAR.valueOf(Step.class, "Step.of(\"A\").described(\"a\").described(\"b\")").isEmpty());
        assertTrue(GRAMMAR.valueOf(Step.class, "Step.of(\"A\").wanders()").isEmpty(), "no such link");
        assertTrue(GRAMMAR.valueOf(Step.class, "Step.of(\"A\").goesHome(true)").isEmpty(), "a flag takes nothing");
        assertTrue(GRAMMAR.valueOf(Step.class, "somebody.goesHome()").isEmpty(), "no factory under the links");
        assertTrue(GRAMMAR.valueOf(Step.class, "Step.of(\"A\").tries(count)").isEmpty(), "a part nothing reads");
    }
}
