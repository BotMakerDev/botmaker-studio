package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueCodec;
import com.botmaker.plugin.api.value.ValueType;

import java.time.Duration;
import java.util.Optional;

/**
 * A catalog for these tests — <b>not {@code botmaker-plugin-basics}'</b>, on purpose.
 *
 * <p>Studio depends on no plugin, and a test dependency on one would be the first crack in that: the build
 * would start needing a plugin to compile its own tests, and the next person would reach for a plugin type
 * in main code because it was already on the classpath. What is being tested here is the <em>host's</em>
 * reading of Java, so the types it reads only have to behave like real ones.
 *
 * <p>They are spelled like plugin-basics' three most awkward cases all the same — a structural literal
 * ({@code Duration}), a quoted one ({@code String}) and a bare one ({@code int}) — because those are the
 * three shapes the scanner has to tell apart, and inventing prettier ones would test a situation that does
 * not occur.
 */
final class TestValues {

    static final ValueType TEXT = ValueType.of("TEXT").label("Text")
            .source("String").boxed("String")
            .build();

    static final ValueType WHOLE_NUMBER = ValueType.of("WHOLE_NUMBER").label("Whole number")
            .source("int").boxed("Integer").primitive()
            .build();

    static final ValueType DURATION = ValueType.of("DURATION").label("Duration")
            .source("java.time.Duration")
            .build();

    /** The same shape as a real catalog: one codec per type, each able to read its own literal back. */
    static final ValueCatalog CATALOG = ValueCatalog.builder()
            .add(TEXT, new ValueCodec<String>() {
                @Override
                public String parse(String wire) {
                    return wire == null ? "" : wire;
                }

                @Override
                public String store(String value) {
                    return value;
                }

                @Override
                public String literal(String value) {
                    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                }

                @Override
                public Optional<String> wireOfLiteral(String java) {
                    String source = java == null ? "" : java.strip();
                    if (source.length() < 2 || !source.startsWith("\"") || !source.endsWith("\"")) {
                        return Optional.empty();
                    }
                    return Optional.of(source.substring(1, source.length() - 1)
                            .replace("\\\"", "\"").replace("\\\\", "\\"));
                }
            })
            .add(WHOLE_NUMBER, new ValueCodec<Integer>() {
                @Override
                public Integer parse(String wire) {
                    try {
                        return Integer.parseInt(wire == null ? "" : wire.strip());
                    } catch (NumberFormatException notANumber) {
                        return 0;
                    }
                }

                @Override
                public String store(Integer value) {
                    return Integer.toString(value);
                }

                @Override
                public String literal(Integer value) {
                    return Integer.toString(value);
                }

                @Override
                public Optional<String> wireOfLiteral(String java) {
                    try {
                        return Optional.of(Integer.toString(Integer.parseInt(java.strip())));
                    } catch (NumberFormatException | NullPointerException notANumber) {
                        return Optional.empty();
                    }
                }
            })
            .add(DURATION, new ValueCodec<Duration>() {
                @Override
                public Duration parse(String wire) {
                    try {
                        return Duration.ofMillis(Long.parseLong(wire == null ? "" : wire.strip()));
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
                public Optional<String> wireOfLiteral(String java) {
                    String source = java == null ? "" : java.strip();
                    String prefix = "java.time.Duration.ofMillis(";
                    if (!source.startsWith(prefix) || !source.endsWith(")")) return Optional.empty();
                    String inner = source.substring(prefix.length(), source.length() - 1).strip();
                    if (inner.endsWith("L") || inner.endsWith("l")) {
                        inner = inner.substring(0, inner.length() - 1);
                    }
                    try {
                        return Optional.of(Long.toString(Long.parseLong(inner)));
                    } catch (NumberFormatException notANumber) {
                        return Optional.empty();
                    }
                }
            })
            .build();

    private TestValues() {}
}
