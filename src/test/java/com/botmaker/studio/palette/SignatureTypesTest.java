package com.botmaker.studio.palette;

import com.botmaker.studio.plugin.grammar.ValueTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A chooser's type as a signature type, and back — and how a person reads one. */
class SignatureTypesTest {

    @Test
    void aCatalogueTypeStaysDescribed() {
        SignatureType whole = SignatureTypes.of(int.class);
        assertEquals(new SignatureType.Described(BotType.Choice.of(BotType.WHOLE_NUMBER)), whole);
        assertEquals(new SignatureType.Described(BotType.Choice.listOf(BotType.TEXT)),
                SignatureTypes.of(ValueTypes.listOf(String.class)));
        assertTrue(SignatureTypes.of(void.class).isVoid());
    }

    @Test
    void anythingElseIsTypedAndReadsAsJava() {
        SignatureType nested = SignatureTypes.of(ValueTypes.mapOf(String.class, ValueTypes.listOf(int.class)));
        assertInstanceOf(SignatureType.Typed.class, nested);
        assertEquals("Map<String, List<Integer>>", nested.sourceName());
        assertEquals("null", SignatureTypes.of(new ValueTypes.BotClass("com.bot.Pair", List.of())).defaultText());
    }

    @Test
    void theChooserOpensOnWhatTheSignatureSays() {
        assertEquals(int.class, SignatureTypes.typeOf(SignatureTypes.of(int.class)));
        assertEquals(ValueTypes.listOf(String.class),
                SignatureTypes.typeOf(new SignatureType.Described(BotType.Choice.listOf(BotType.TEXT))));
        assertEquals(new ValueTypes.Unknown("Outcome"), SignatureTypes.typeOf(SignatureType.kept("Outcome")));
    }

    @Test
    void aVariableIsNamedAfterItsTypeNeverAKeyword() {
        assertEquals("number", TypeNames.variableName(int.class));
        assertEquals("pair", TypeNames.variableName(new ValueTypes.BotClass("com.bot.Pair", List.of())));
        assertEquals("strings", TypeNames.variableName(ValueTypes.listOf(String.class)));
    }
}
