package com.botmaker.studio.plugin.grammar;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fourth and fifth host containers (picker 6e2): a {@code Set}, written {@code Set.of(…)}, and a
 * {@code Deque} — a stack or a queue — written {@code new ArrayDeque<>(List.of(…))}, the one container whose
 * call is a constructor.
 */
class SetAndDequeTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.empty();
    private static final Type SET_OF_TEXT = ValueTypes.of(ValueContainer.SET, List.of(String.class));
    private static final Type DEQUE_OF_COUNT = ValueTypes.of(ValueContainer.DEQUE, List.of(int.class));

    private static Optional<String> src(Optional<JavaValue> written) {
        return written.map(JavaValue::source);
    }

    @Test
    void aSetIsWrittenAsSetOfInTheOrderItHolds() {
        Set<String> value = new LinkedHashSet<>(List.of("b", "a"));
        assertEquals(Optional.of("java.util.Set.of(\"b\", \"a\")"), src(GRAMMAR.initializer(SET_OF_TEXT, value)));
    }

    @Test
    void aSetReadsBackKeepingTheWrittenOrder() {
        Object read = GRAMMAR.valueOf(SET_OF_TEXT, "Set.of(\"b\", \"a\")").orElseThrow();
        Set<?> set = assertInstanceOf(Set.class, read);
        assertEquals(List.of("b", "a"), List.copyOf(set));
    }

    @Test
    void aDequeIsANewArrayDequeOverAList() {
        Deque<Integer> value = new ArrayDeque<>(List.of(1, 2));
        assertEquals(Optional.of("new java.util.ArrayDeque<>(java.util.List.of(1, 2))"),
                src(GRAMMAR.initializer(DEQUE_OF_COUNT, value)));
    }

    @Test
    void aDequeReadsBackInItsOrder() {
        Object read = GRAMMAR.valueOf(DEQUE_OF_COUNT, "new ArrayDeque<>(List.of(1, 2))").orElseThrow();
        Deque<?> deque = assertInstanceOf(Deque.class, read);
        assertEquals(List.of(1, 2), List.copyOf(deque));
    }

    @Test
    void aDequeIsTakenApartIntoTheListItWraps() {
        List<ValueGrammar.Part> parts =
                GRAMMAR.partsOfInitializer(DEQUE_OF_COUNT, "new ArrayDeque<>(List.of(1, 2))").orElseThrow();
        assertEquals(1, parts.size());
        assertEquals(ValueTypes.listOf(int.class), parts.getFirst().form());
    }

    @Test
    void aDequeIsComposedAroundItsList() {
        JavaValue list = JavaValue.parse("List.of(3)").orElseThrow();
        assertEquals(Optional.of("new ArrayDeque<>(List.of(3))"), src(GRAMMAR.compose(DEQUE_OF_COUNT, List.of(list))));
    }

    @Test
    void bothStartEmpty() {
        assertEquals(Optional.of("java.util.Set.of()"), src(GRAMMAR.freshInitializer(SET_OF_TEXT)));
        assertEquals(Optional.of("new java.util.ArrayDeque<>(java.util.List.of())"),
                src(GRAMMAR.freshInitializer(DEQUE_OF_COUNT)));
    }

    @Test
    void anUntypedDequeIsReadAndWrittenByItsOwnSpelling() {
        Object read = GRAMMAR.valueOfAny("new ArrayDeque<>(List.of(\"x\"))").orElseThrow();
        assertTrue(read instanceof Deque<?>, "read " + read);
        assertEquals(Optional.of("new java.util.ArrayDeque<>(java.util.List.of(\"x\"))"),
                src(GRAMMAR.initializerOfAny(read)));
    }
}
