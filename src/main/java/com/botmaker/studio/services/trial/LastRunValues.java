package com.botmaker.studio.services.trial;

import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.services.debug.DebugSnapshot;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each of the bot's locals held when a debug session last paused in its method — ▶ Try's first source for an
 * earlier local ({@link TrialPlan.Source#LAST_RUN}).
 *
 * <p>Read from the {@link DebugSnapshot}s the Debug tab is shown, each bot frame of each, the newest winning; kept
 * after the session ends, since "the last run" is the point. A value is the snapshot's text, which is what JDI could
 * read without running the bot's code: a primitive, a string, an enum's constant. It is offered only when the grammar
 * reads that text back as the local's type, so an object shown as {@code Match #41} never is, nor a string the
 * snapshot cut short.
 */
public final class LastRunValues {

    /** {@code className#method} → local → its value's text. */
    private final Map<String, Map<String, String>> byMethod = new ConcurrentHashMap<>();

    /** Keeps every bot frame's locals in {@code snapshot}. */
    public void remember(DebugSnapshot snapshot) {
        if (snapshot == null) return;
        for (DebugSnapshot.Frame frame : snapshot.frames()) {
            if (!frame.inBot()) continue;
            Map<String, String> locals = byMethod.computeIfAbsent(frame.className() + "#" + frame.method(),
                    k -> new ConcurrentHashMap<>());
            for (DebugSnapshot.Variable v : frame.variables()) {
                if (!"this".equals(v.name()) && v.value() != null) locals.put(v.name(), v.value());
            }
        }
    }

    /** The text {@code local} of {@code className.method} last showed; empty when it never paused there. */
    public Optional<String> text(String className, String method, String local) {
        Map<String, String> locals = byMethod.get(className + "#" + method);
        return locals == null ? Optional.empty() : Optional.ofNullable(locals.get(local));
    }

    /** These values as a plan reads them: each written by {@code grammar} as its local's type, when it reads. */
    public TrialPlan.LastRun reader(ValueGrammar grammar) {
        return (className, method, local, typeName) -> text(className, method, local)
                .filter(text -> !text.endsWith("…\""))
                .flatMap(text -> grammar.named(typeName).flatMap(type -> read(grammar, type, text)));
    }

    private static Optional<JavaValue> read(ValueGrammar grammar, Type type, String text) {
        return grammar.valueOf(type, text).flatMap(value -> grammar.initializer(type, value));
    }
}
