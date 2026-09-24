package com.botmaker.studio.plugin.record;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.record.Gesture;
import com.botmaker.plugin.api.record.RecordedValue;
import com.botmaker.plugin.host.Recordings;
import com.botmaker.studio.parser.helpers.SourceFormatter;
import com.botmaker.studio.plugin.EditorContest;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.ManagedConstants;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Writes recognised gestures down as the plugins' own calls.
 *
 * <p>For each gesture the {@link Recordings.Writer}s declared for it are tried highest rank first — or the
 * user's chosen plugin first, when two plugins record the gesture and the user picked one — and the first whose
 * every parameter fills is written. Values are spelled by the grammar, so the Java is the same
 * Java the canvas writes; a value that equals a constant of a {@code @Managed} type in the bot is written as
 * that constant ({@code Pictures.COLLECT}).
 *
 * <p>A {@link Gesture#PAUSE} directly before a pointer gesture is first offered as an {@link Gesture#AWAIT} on
 * that gesture's spot — waiting for the thing about to be clicked — and stays a pause when nothing can name it.
 */
public final class RecordingWriter {

    /**
     * One statement as a tree, and the classes it names by simple name. {@link #source()} is for showing it;
     * what is inserted is {@link #node()}.
     */
    public record Statement(org.eclipse.jdt.core.dom.Statement node, List<String> imports, String source) {

        public Statement {
            imports = List.copyOf(imports);
        }

        /** {@code Owner.method(arguments…);}, each argument's tree copied in and its imports carried. */
        static Statement call(Method method, List<JavaValue> arguments) {
            AST ast = AST.newAST(AST.getJLSLatest(), false);
            Set<String> imports = new LinkedHashSet<>();
            Class<?> owner = method.getDeclaringClass();
            String importName = JavaNames.importName(owner);
            MethodInvocation call = ast.newMethodInvocation();
            call.setExpression(ast.newName(JavaNames.simple(owner)));
            call.setName(ast.newSimpleName(method.getName()));
            @SuppressWarnings("unchecked")
            List<Expression> list = call.arguments();
            for (JavaValue argument : arguments) {
                list.add(argument.copyInto(ast));
                imports.addAll(argument.imports());
            }
            if (!importName.isEmpty()) imports.add(importName);
            ExpressionStatement statement = ast.newExpressionStatement(call);
            return new Statement(statement, List.copyOf(imports), SourceFormatter.statement(statement.toString()));
        }
    }

    /** Seconds an {@link Gesture#AWAIT} allows, per second the user actually waited, and at least. */
    private static final int AWAIT_FACTOR = 3;
    private static final int AWAIT_MIN_SECONDS = 5;

    /**
     * A gesture more than one plugin can write down, and those plugins in the order they are tried when the
     * user has not chosen: highest rank first, then load order.
     */
    public record Contest(Gesture gesture, List<EditorContest.Claim> claims) {

        public Contest {
            claims = List.copyOf(claims);
        }

        /** {@code DOUBLE_CLICK} as {@code "Double click"}, for a menu. */
        public String label() {
            String words = gesture.name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
            return Character.toUpperCase(words.charAt(0)) + words.substring(1);
        }
    }

    private final List<Recordings.Writer> writers;
    private final ValueGrammar grammar;
    private final StudioServices services;
    private final ManagedConstants.Lookup constants;
    private final Function<Gesture, String> preferred;

    public RecordingWriter(List<Recordings.Writer> writers, ValueGrammar grammar, StudioServices services,
                           List<ManagedConstants.Constant> constants) {
        this(writers, grammar, services, constants, gesture -> null);
    }

    /**
     * @param preferred the plugin id the user chose to record each gesture with, or {@code null} for none. The
     *                  chosen plugin's writers are tried first and the rest after, so a gesture the chosen
     *                  plugin cannot fill is still written by the next one.
     */
    public RecordingWriter(List<Recordings.Writer> writers, ValueGrammar grammar, StudioServices services,
                           List<ManagedConstants.Constant> constants, Function<Gesture, String> preferred) {
        this.writers = List.copyOf(writers);
        this.grammar = grammar;
        this.services = services;
        this.constants = new ManagedConstants.Lookup(constants, grammar);
        this.preferred = preferred == null ? gesture -> null : preferred;
    }

    /**
     * Every gesture {@code writers} (highest rank first within a gesture, as {@link Recordings#of} returns
     * them) offers from two or more plugins, in {@link Gesture} order. A gesture one plugin writes, however
     * many of its methods do, is no contest: there is nobody else to prefer.
     */
    public static List<Contest> contests(List<Recordings.Writer> writers) {
        Map<Gesture, Map<String, EditorContest.Claim>> byGesture = new EnumMap<>(Gesture.class);
        for (Recordings.Writer writer : writers) {
            byGesture.computeIfAbsent(writer.gesture(), g -> new LinkedHashMap<>())
                    .putIfAbsent(writer.plugin().id(),
                            new EditorContest.Claim(writer.plugin().id(), writer.plugin().displayName()));
        }
        List<Contest> out = new ArrayList<>();
        byGesture.forEach((gesture, claims) -> {
            if (claims.size() > 1) out.add(new Contest(gesture, List.copyOf(claims.values())));
        });
        return List.copyOf(out);
    }

    /** The statements for {@code gestures}, in order. A gesture nothing can write is left out. */
    public List<Statement> write(List<Gestures.Recognized> gestures) {
        List<Statement> out = new ArrayList<>();
        for (int i = 0; i < gestures.size(); i++) {
            Gestures.Recognized gesture = gestures.get(i);
            if (gesture.gesture() == Gesture.PAUSE && i + 1 < gestures.size()) {
                Optional<Statement> await = await(gesture, gestures.get(i + 1));
                if (await.isPresent()) {
                    out.add(await.get());
                    continue;
                }
            }
            write(gesture.gesture(), gesture.values(), gesture.spot()).ifPresent(out::add);
        }
        return out;
    }

    private Optional<Statement> await(Gestures.Recognized pause, Gestures.Recognized next) {
        if (next.spot() == null) return Optional.empty();
        long millis = ((Number) pause.values().getFirst()).longValue();
        int seconds = (int) Math.max(AWAIT_MIN_SECONDS, AWAIT_FACTOR * Math.ceil(millis / 1000.0));
        return write(Gesture.AWAIT, List.of(seconds), next.spot());
    }

    /**
     * The first writer for {@code gesture} that fills, written — or empty. The user's chosen plugin is tried
     * first ({@link EditorContest#ordered}, the same verdict the <i>Edit with</i> menu applies to editors),
     * then the rest by rank.
     */
    public Optional<Statement> write(Gesture gesture, List<Object> values, RecordedValue.Spot spot) {
        List<Recordings.Writer> candidates = writers.stream().filter(w -> w.gesture() == gesture).toList();
        for (Recordings.Writer writer : EditorContest.ordered(candidates, w -> w.plugin().id(),
                preferred.apply(gesture))) {
            Optional<Statement> written = fill(writer, values, spot);
            if (written.isPresent()) return written;
        }
        return Optional.empty();
    }

    private Optional<Statement> fill(Recordings.Writer writer, List<Object> values, RecordedValue.Spot spot) {
        List<JavaValue> arguments = new ArrayList<>();
        int next = 0;
        for (Recordings.Slot slot : writer.slots()) {
            Optional<List<JavaValue>> argument;
            switch (slot) {
                case Recordings.Slot.Recorded recorded -> argument = one(recorded(recorded.answer(), spot));
                case Recordings.Slot.Number number -> {
                    argument = next < values.size() ? one(number(number.type(), values.get(next++)))
                            : Optional.empty();
                }
                case Recordings.Slot.Parts parts -> {
                    int count = parts.components().size();
                    if (next + count > values.size()) return Optional.empty();
                    argument = one(built(parts, values.subList(next, next + count)));
                    next += count;
                }
                case Recordings.Slot.Text text -> {
                    argument = next < values.size() && values.get(next) instanceof String s
                            ? one(grammar.spellAny(s)) : Optional.empty();
                    next++;
                }
                case Recordings.Slot.Keys keys -> {
                    List<Object> names = keys.many() ? values.subList(Math.min(next, values.size()), values.size())
                            : next < values.size() ? List.of(values.get(next)) : List.of();
                    next += names.size();
                    argument = names.isEmpty() ? Optional.empty() : keys(keys.enumType(), names);
                }
                case Recordings.Slot.Fresh fresh -> argument = one(grammar.freshSpelling(fresh.type()));
            }
            if (argument.isEmpty()) return Optional.empty();
            arguments.addAll(argument.get());
        }
        return Optional.of(Statement.call(writer.method(), arguments));
    }

    private static Optional<List<JavaValue>> one(Optional<JavaValue> value) {
        return value.map(List::of);
    }

    /** A plugin's value at the spot: its {@code @Managed} constant when the bot has one, else written out. */
    private Optional<JavaValue> recorded(RecordedValue<?> answer, RecordedValue.Spot spot) {
        if (spot == null) return Optional.empty();
        Optional<?> value;
        try {
            value = answer.at(services, spot);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
        if (value == null || value.isEmpty()) return Optional.empty();
        return constants.spell(value.get()).or(() -> grammar.spellAny(value.get()));
    }

    private Optional<JavaValue> number(Class<?> type, Object value) {
        if (!(value instanceof Number n)) return Optional.empty();
        Object coerced = type == long.class ? (Object) n.longValue()
                : type == double.class ? (Object) n.doubleValue() : (Object) n.intValue();
        return grammar.spellAny(coerced);
    }

    private Optional<JavaValue> built(Recordings.Slot.Parts parts, List<Object> values) {
        List<Object> coerced = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (!(values.get(i) instanceof Number n)) return Optional.empty();
            Class<?> type = parts.components().get(i);
            coerced.add(type == long.class ? (Object) n.longValue()
                    : type == double.class ? (Object) n.doubleValue() : (Object) n.intValue());
        }
        Object value;
        try {
            value = parts.type().build(coerced);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
        return value == null ? Optional.empty() : grammar.spellAny(value);
    }

    /** One argument per key: a varargs tail, {@code Key.CTRL, Key.C}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Optional<List<JavaValue>> keys(Class<?> enumType, List<Object> names) {
        List<JavaValue> written = new ArrayList<>();
        for (Object name : names) {
            if (!(name instanceof String key)) return Optional.empty();
            Object constant;
            try {
                constant = Enum.valueOf((Class) enumType, key);
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            Optional<JavaValue> spelled = grammar.spellAny(constant);
            if (spelled.isEmpty()) return Optional.empty();
            written.add(spelled.get());
        }
        return Optional.of(List.copyOf(written));
    }
}
