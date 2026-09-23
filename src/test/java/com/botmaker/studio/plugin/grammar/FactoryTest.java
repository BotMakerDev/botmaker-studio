package com.botmaker.studio.plugin.grammar;

import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
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
        assertEquals(Duration.class, factory.owner());
        assertEquals(Map.class, Factory.method(Map.class, "entry", Object.class, Object.class).owner());
    }

    private static Expression parse(String source) {
        return SourceNode.parse(source).orElseThrow().node();
    }

    @Test
    void aStaticCallMatchesItsOwnerWrittenAnyWay() {
        Factory millis = Factory.method(Duration.class, "ofMillis", long.class);
        assertTrue(millis.matches((MethodInvocation) parse("Duration.ofMillis(3)"), false));
        assertTrue(millis.matches((MethodInvocation) parse("java.time.Duration.ofMillis(3)"), false));
        assertFalse(millis.matches((MethodInvocation) parse("ofMillis(3)"), false));
        assertTrue(millis.matches((MethodInvocation) parse("ofMillis(3)"), true), "a static import");
        assertFalse(millis.matches((MethodInvocation) parse("Other.ofMillis(3)"), true));
        assertFalse(millis.matches((MethodInvocation) parse("Duration.ofMillis(3, 4)"), false));
    }

    @Test
    void aConstructorMatchesItsClassAndCount() throws NoSuchMethodException {
        Factory pair = new Factory(Pair.class.getConstructor(int.class, int.class));
        assertTrue(pair.matches((ClassInstanceCreation) parse("new Pair(1, 2)")));
        assertTrue(pair.matches((ClassInstanceCreation) parse("new " + JavaNames.canonical(Pair.class) + "(1, 2)")));
        assertFalse(pair.matches((ClassInstanceCreation) parse("new Pair(1)")));
        assertFalse(pair.matches((ClassInstanceCreation) parse("new Pair(1, 2) {}")),
                "an anonymous subclass is not the record");
    }

    @Test
    void aReceiverCallMatchesByNameAndCountOnAnyReceiver() {
        Factory wider = Factory.method(Pair.class, "wider", int.class);
        assertTrue(wider.matches((MethodInvocation) parse("x.wider(3)"), false));
        assertFalse(wider.matches((MethodInvocation) parse("wider(3)"), true), "a receiver call needs a receiver");
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
