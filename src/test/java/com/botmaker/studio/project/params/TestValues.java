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
 * not occur. {@link #BODY} is the fourth, added for {@code project/managed}: a type whose literal is a
 * <b>method reference</b>, which is how the SDK will name an activity's body and is a shape no other
 * codec here produces.
 *
 * <p>Public rather than package-private since 2026-09-20, so {@code project/managed}'s tests read the same
 * catalog: what they exercise is the same host reading of the same Java, and two catalogs would be two
 * answers to what a {@code Duration} is written as.
 */
public final class TestValues {

    public static final ValueType TEXT = ValueType.of("TEXT").label("Text")
            .source("String").boxed("String")
            .build();

    public static final ValueType WHOLE_NUMBER = ValueType.of("WHOLE_NUMBER").label("Whole number")
            .source("int").boxed("Integer").primitive()
            .build();

    public static final ValueType DURATION = ValueType.of("DURATION").label("Duration")
            .source("java.time.Duration")
            .build();

    /** A value named by {@code Owner::method}, the shape an activity's body takes. */
    public static final ValueType BODY = ValueType.of("BODY").label("Body")
            .source("com.example.bot.Body")
            .build();

    /** The same shape as a real catalog: one codec per type, each able to read its own literal back. */
    public static final ValueCatalog CATALOG = ValueCatalog.builder()
            .add(BODY, new ValueCodec<String>() {
                @Override
                public String parse(String wire) {
                    return wire == null ? "" : wire.strip();
                }

                @Override
                public String store(String value) {
                    return value;
                }

                /** The value <em>is</em> the reference: {@code Collect::body} names a method, not a string. */
                @Override
                public String literal(String value) {
                    return value;
                }

                @Override
                public Optional<String> valueOfLiteral(String java) {
                    String source = java == null ? "" : java.strip();
                    int arrow = source.indexOf("::");
                    if (arrow <= 0 || arrow + 2 >= source.length()) return Optional.empty();
                    return source.chars().allMatch(c -> Character.isJavaIdentifierPart(c) || c == ':')
                            ? Optional.of(source) : Optional.empty();
                }
            })
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
                public Optional<String> valueOfLiteral(String java) {
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
                public Optional<Integer> valueOfLiteral(String java) {
                    try {
                        return Optional.of(Integer.valueOf(java.strip()));
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
                public Optional<Duration> valueOfLiteral(String java) {
                    String source = java == null ? "" : java.strip();
                    String prefix = "java.time.Duration.ofMillis(";
                    if (!source.startsWith(prefix) || !source.endsWith(")")) return Optional.empty();
                    String inner = source.substring(prefix.length(), source.length() - 1).strip();
                    if (inner.endsWith("L") || inner.endsWith("l")) {
                        inner = inner.substring(0, inner.length() - 1);
                    }
                    try {
                        return Optional.of(Duration.ofMillis(Long.parseLong(inner)));
                    } catch (NumberFormatException notANumber) {
                        return Optional.empty();
                    }
                }
            })
            .build();

    private TestValues() {}
}
