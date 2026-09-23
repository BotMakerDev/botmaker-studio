package com.botmaker.studio.plugin.grammar;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The host's view of a {@code ComponentType.factory()}: which of the three shapes it is, and how many parts. */
class FactoryTest {

    public record Pair(int a, int b) {
        public Pair wider(int by) { return new Pair(a, b + by); }
    }

    @Test
    void aConstructorIsWrittenNewAndHasNoName() throws NoSuchMethodException {
        Factory factory = new Factory(Pair.class.getConstructor(int.class, int.class));
        assertEquals(Factory.Kind.CONSTRUCTOR, factory.kind());
        assertEquals("", factory.name());
        assertEquals(Pair.class, factory.owner());
        assertEquals(2, factory.parts());
    }

    @Test
    void aStaticMethodIsItsOwnerAndName() {
        Factory factory = Factory.method(Duration.class, "ofMillis", long.class);
        assertEquals(Factory.Kind.STATIC, factory.kind());
        assertEquals("ofMillis", factory.name());
        assertEquals("java.time.Duration.ofMillis", factory.callSource());
        assertEquals(Map.class, Factory.method(Map.class, "entry", Object.class, Object.class).owner());
    }

    @Test
    void anInstanceMethodCountsItsReceiverAsAPart() {
        Factory factory = Factory.method(Pair.class, "wider", int.class);
        assertEquals(Factory.Kind.RECEIVER, factory.kind());
        assertEquals(2, factory.parts());
    }

    @Test
    void aVarargsFactoryFitsAnyCountFromItsFixedParts() {
        Factory list = Factory.method(List.class, "of", Object[].class);
        assertTrue(list.varargs());
        assertTrue(list.fits(0));
        assertTrue(list.fits(5));
        Factory entry = Factory.method(Map.class, "entry", Object.class, Object.class);
        assertTrue(entry.fits(2));
        assertFalse(entry.fits(3));
    }
}
