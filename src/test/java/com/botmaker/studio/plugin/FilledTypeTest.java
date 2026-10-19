package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.value.DeclaredCallType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.host.FilledTypes;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the host declares for a {@code @Fills} method — a fresh value written as the call, nothing to edit — and
 * the declaration an editor asks for through {@code ValueContext.declared}.
 */
class FilledTypeTest {

    @Test
    void a_filled_type_starts_as_its_call() throws NoSuchMethodException {
        FilledType<?> today = FilledType.of(new FilledTypes.Filled(LocalDate.class,
                LocalDate.class.getMethod("now"), "Today"));
        assertNull(today.fresh());
        assertEquals("now", today.freshCall().getName());

        ValueGrammar grammar = ValueGrammar.of(List.of(today), List.of());
        assertEquals("LocalDate.now()", grammar.freshSpelling(LocalDate.class).map(JavaValue::source).orElseThrow());
    }

    @Test
    void the_tooltip_says_the_java_then_what_the_bot_does() {
        assertEquals("Today: the bot works this out while it runs, so there is nothing to set here.",
                FilledType.help("Today", ""));
        assertTrue(FilledType.help("Today", "Clock.today()").startsWith("Clock.today()\n\n"));
    }

    @Test
    void an_editor_is_given_the_declaration_of_the_class_it_asks_for() {
        DeclaredCallType<LocalTime> time = PluginType.value(LocalTime.class).fresh(() -> LocalTime.MIDNIGHT)
                .editor(() -> ctx -> null)
                .writtenAs(LocalTime::of, LocalTime::getHour, LocalTime::getMinute);
        ValueGrammar grammar = ValueGrammar.of(List.of(time), List.of());
        assertSame(time, grammar.declared(LocalTime.class).orElseThrow());
        assertTrue(grammar.declared(LocalDate.class).isEmpty(), "nothing declares it");
    }
}
