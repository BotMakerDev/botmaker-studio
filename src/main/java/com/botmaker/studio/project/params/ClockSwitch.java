package com.botmaker.studio.project.params;

import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;

import java.lang.reflect.Type;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * The Local | UTC switch on a time-of-day parameter (feedback 3, 2026-09-27): a {@code LocalTime} — this
 * computer's clock — becomes an {@code OffsetTime} at UTC, and back.
 *
 * <p><b>The clock reading stays; it is not converted.</b> Somebody flipping 07:30 to UTC means the reset at
 * 07:30 UTC, not whatever 07:30 here is in UTC today — which would also change with daylight saving. Leaving
 * UTC drops the offset for the same reason. {@code java.time} names are the JDK's, not a plugin's, so Studio
 * may name them; whether the project can write either is still the grammar's answer.
 */
public final class ClockSwitch {

    private ClockSwitch() {
    }

    /** The other clock's type for a field declared as {@code form}, or empty when it is not a time of day. */
    public static Optional<Type> other(Type form) {
        if (form == LocalTime.class) return Optional.of(OffsetTime.class);
        if (form == OffsetTime.class) return Optional.of(LocalTime.class);
        return Optional.empty();
    }

    /**
     * {@code parameter} retyped to the other clock, holding the same hours, minutes and seconds — or empty when
     * it is no time of day or nothing was written. A value the grammar cannot read is left as the new type's
     * fresh one, which is what any retype does.
     */
    public static Optional<ParameterRow> flip(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                              ValueGrammar grammar) {
        Type to = other(parameter.form()).orElse(null);
        if (to == null) return Optional.empty();
        Optional<Object> kept = grammar.valueOf(parameter.form(), parameter.row().value()).map(ClockSwitch::flipped);
        Optional<ParameterRow> retyped = JavaParameters.declare(config, state, parameter, parameter.row(), to,
                grammar).stored();
        if (retyped.isEmpty() || kept.isEmpty()) return retyped;
        Optional<JavaValue> written = grammar.initializer(to, kept.get());
        JavaParameter now = JavaParameters.find(config, state, parameter.className(), parameter.row().name(),
                grammar).orElse(null);
        if (now == null || written.isEmpty()) return retyped;
        return JavaParameters.setValue(config, state, now, written.get(), grammar);
    }

    /** The same clock reading on the other clock. */
    static Object flipped(Object value) {
        if (value instanceof LocalTime local) return OffsetTime.of(local, ZoneOffset.UTC);
        if (value instanceof OffsetTime offset) return offset.toLocalTime();
        return value;
    }
}
