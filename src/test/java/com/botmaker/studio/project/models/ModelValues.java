package com.botmaker.studio.project.models;

import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueCodec;
import com.botmaker.plugin.api.value.ValueType;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A catalog and a handful of model records for these tests.
 *
 * <p>Studio depends on no plugin, so the leaf here is spelled like plugin-basics' {@code DURATION} rather
 * than taken from it — the same reasoning {@code params/TestValues} states. One catalogued leaf is enough:
 * what is under test is the walk, and a second codec would exercise the codec rather than the walk.
 */
final class ModelValues {

    static final ValueType DURATION = ValueType.of("DURATION").label("Duration")
            .source("java.time.Duration")
            .build();

    static final ValueCatalog CATALOG = ValueCatalog.builder()
            .add(DURATION, new ValueCodec<Duration>() {
                @Override
                public Duration parse(String wire) {
                    try {
                        return Duration.ofMillis(Long.parseLong(wire == null ? "0" : wire.strip()));
                    } catch (NumberFormatException notANumber) {
                        return Duration.ZERO;
                    }
                }

                @Override
                public String store(Duration value) {
                    return Long.toString(value.toMillis());
                }

                @Override
                public String literal(Duration value) {
                    return "java.time.Duration.ofMillis(" + value.toMillis() + "L)";
                }

                @Override
                public Optional<Duration> valueOfLiteral(String javaSource) {
                    String source = javaSource == null ? "" : javaSource.strip();
                    String prefix = "java.time.Duration.ofMillis(";
                    if (!source.startsWith(prefix) || !source.endsWith("L)")) return Optional.empty();
                    String inner = source.substring(prefix.length(), source.length() - 2);
                    try {
                        return Optional.of(Duration.ofMillis(Long.parseLong(inner)));
                    } catch (NumberFormatException notANumber) {
                        return Optional.empty();
                    }
                }
            })
            .build();

    /** Which way a node runs — the enum row of the table. */
    enum Direction { FORWARD, BACK }

    /** A record inside a record, with a leaf, a builtin and a constant. */
    record Node(String activity, Duration pause, Direction direction) {}

    /** The shape the SDK's flow actually has: a list of records and a map keyed by name. */
    record Flow(String name, List<Node> nodes, Map<String, Node> starts, int version) {}

    /** Recursive on purpose: a graph of these is an ordinary model and must not be refused. */
    record Tree(String label, List<Tree> children) {}

    /** Every builtin in one place, so the literal table is exercised as a unit. */
    record Primitives(boolean flag, byte tiny, char letter, short small, int number, long big, float ratio,
                      double precise, String text) {}

    /** Illegal: a component nothing can write. */
    record HasAnInterface(String name, Runnable action) {}

    /** Illegal: a container with no element type written down. */
    @SuppressWarnings("rawtypes")
    record HasARawList(List items) {}

    /** Illegal: a generic record has no one written type per component. */
    record Box<T>(T held) {}

    private ModelValues() {
    }
}
