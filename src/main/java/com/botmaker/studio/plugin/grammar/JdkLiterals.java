package com.botmaker.studio.plugin.grammar;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TextBlock;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The one reader and writer for the literals Java itself defines: {@code String}, {@code char}, the eight
 * primitives and their boxes.
 *
 * <p><b>What this replaced</b> is three numeric-literal strippers and a string unescaper spread across the
 * toolkit and plugin-basics' {@code JdkText} (deleted 2026-09-22), none of which agreed with the others about
 * a suffix, an underscore or an escape. Every plugin parsed the text it was handed; now none does, and this
 * is the whole of the host's answer.
 *
 * <p>Reading uses a real parser — {@link JavaExpressions} — so {@code 0x1F}, {@code 1_000}, {@code 3L},
 * {@code '\n'} and {@code "a\"b"} mean what javac says they mean. Writing is by hand and canonical: one
 * spelling per value, so a value read and written back without an edit is the same text it was.
 */
public final class JdkLiterals {

    private JdkLiterals() {}

    /** Every name a literal type is written as, to its canonical name. */
    private static final Map<String, String> CANONICAL = Map.ofEntries(
            Map.entry("boolean", "boolean"), Map.entry("byte", "byte"), Map.entry("char", "char"),
            Map.entry("short", "short"), Map.entry("int", "int"), Map.entry("long", "long"),
            Map.entry("float", "float"), Map.entry("double", "double"),
            Map.entry("Boolean", "java.lang.Boolean"), Map.entry("java.lang.Boolean", "java.lang.Boolean"),
            Map.entry("Byte", "java.lang.Byte"), Map.entry("java.lang.Byte", "java.lang.Byte"),
            Map.entry("Character", "java.lang.Character"), Map.entry("java.lang.Character", "java.lang.Character"),
            Map.entry("Short", "java.lang.Short"), Map.entry("java.lang.Short", "java.lang.Short"),
            Map.entry("Integer", "java.lang.Integer"), Map.entry("java.lang.Integer", "java.lang.Integer"),
            Map.entry("Long", "java.lang.Long"), Map.entry("java.lang.Long", "java.lang.Long"),
            Map.entry("Float", "java.lang.Float"), Map.entry("java.lang.Float", "java.lang.Float"),
            Map.entry("Double", "java.lang.Double"), Map.entry("java.lang.Double", "java.lang.Double"),
            Map.entry("String", "java.lang.String"), Map.entry("java.lang.String", "java.lang.String"));

    /** The canonical name of a literal type written {@code name}, or {@code null} for anything else. */
    public static String canonical(String name) {
        return name == null ? null : CANONICAL.get(name.strip());
    }

    /** Whether {@code name} is a type this class reads and writes. */
    public static boolean handles(String name) {
        return canonical(name) != null;
    }

    /** Whether {@code type} is a type this class reads and writes. */
    public static boolean handles(Class<?> type) {
        return type != null && handles(JavaNames.canonical(type));
    }

    // ---- reading ---------------------------------------------------------------------------------------

    /**
     * The value {@code source} writes, as {@code typeName} — empty when it is not a literal of that type.
     *
     * <p>A wider literal for a narrower type is refused, never truncated: {@code 3000000000L} is not an
     * {@code int}, and reading it as one would put a different number in the user's file on the next write.
     * An {@code int} literal <em>is</em> accepted for a {@code long}, a {@code float} and a {@code double},
     * because javac accepts it there and people write it that way.
     */
    public static Optional<Object> read(String typeName, String source) {
        String type = canonical(typeName);
        if (type == null) return Optional.empty();
        Optional<Object> any = readAny(source);
        if (any.isEmpty()) return Optional.empty();
        Object value = any.get();
        return switch (type) {
            case "java.lang.String" -> value instanceof String ? any : Optional.empty();
            case "char", "java.lang.Character" -> value instanceof Character ? any : Optional.empty();
            case "boolean", "java.lang.Boolean" -> value instanceof Boolean ? any : Optional.empty();
            case "int", "java.lang.Integer" -> value instanceof Integer ? any : Optional.empty();
            case "short", "java.lang.Short" -> value instanceof Integer i && i == i.shortValue()
                    ? Optional.of(i.shortValue()) : Optional.empty();
            case "byte", "java.lang.Byte" -> value instanceof Integer i && i == i.byteValue()
                    ? Optional.of(i.byteValue()) : Optional.empty();
            case "long", "java.lang.Long" -> value instanceof Long || value instanceof Integer
                    ? Optional.of(((Number) value).longValue()) : Optional.empty();
            case "float", "java.lang.Float" -> value instanceof Float || value instanceof Integer
                    ? Optional.of(((Number) value).floatValue()) : Optional.empty();
            case "double", "java.lang.Double" -> value instanceof Number n
                    ? Optional.of(n.doubleValue()) : Optional.empty();
            default -> Optional.empty();
        };
    }

    /**
     * The value {@code source} writes, typed by its own spelling — a {@code String}, {@code Character},
     * {@code Boolean}, {@code Integer}, {@code Long}, {@code Float} or {@code Double} — or empty when it is
     * not a literal at all.
     *
     * <p>What reads the element of a list whose element type nothing states: the SDK's flow keeps an
     * activity's outcomes as a {@code List<String>}, and its declaration can only say {@code List.class}.
     */
    public static Optional<Object> readAny(String source) {
        Expression expression = JavaExpressions.parse(source);
        if (expression == null || (expression.getFlags() & (ASTNode.MALFORMED | ASTNode.RECOVERED)) != 0) {
            return Optional.empty();
        }
        if (expression.getLength() != source.strip().length()) return Optional.empty();
        return literal(expression, false);
    }

    private static Optional<Object> literal(Expression expression, boolean negated) {
        return switch (expression) {
            case StringLiteral string when !negated -> Optional.of(string.getLiteralValue());
            case TextBlock block when !negated -> Optional.of(block.getLiteralValue());
            case CharacterLiteral character when !negated -> Optional.of(character.charValue());
            case BooleanLiteral bool when !negated -> Optional.of(bool.booleanValue());
            case NumberLiteral number -> number(number.getToken(), negated);
            case ParenthesizedExpression inner -> literal(inner.getExpression(), negated);
            case PrefixExpression prefix when prefix.getOperator() == PrefixExpression.Operator.MINUS && !negated ->
                    literal(prefix.getOperand(), true);
            case PrefixExpression prefix when prefix.getOperator() == PrefixExpression.Operator.PLUS && !negated ->
                    literal(prefix.getOperand(), false);
            default -> Optional.empty();
        };
    }

    /** One number token, as javac reads it. */
    private static Optional<Object> number(String token, boolean negated) {
        String text = token.replace("_", "");
        String sign = negated ? "-" : "";
        String lower = text.toLowerCase(Locale.ROOT);
        try {
            boolean hex = lower.startsWith("0x");
            boolean binary = lower.startsWith("0b");
            if (!hex && !binary && (lower.contains(".") || lower.contains("e")
                    || lower.endsWith("f") || lower.endsWith("d"))) {
                if (lower.endsWith("f")) return Optional.of(Float.parseFloat(sign + text));
                return Optional.of(Double.parseDouble(sign + text));
            }
            boolean isLong = lower.endsWith("l");
            String digits = isLong ? text.substring(0, text.length() - 1) : text;
            int radix = 10;
            if (hex) {
                digits = digits.substring(2);
                radix = 16;
            } else if (binary) {
                digits = digits.substring(2);
                radix = 2;
            } else if (digits.length() > 1 && digits.startsWith("0")) {
                digits = digits.substring(1);
                radix = 8;
            }
            if (isLong) {
                long parsed = Long.parseUnsignedLong(digits, radix);
                return Optional.of(negated ? -parsed : parsed);
            }
            long value = Long.parseLong(digits, radix);
            // javac takes 0xFFFFFFFF as -1: a non-decimal int literal is its 32 bits.
            if (radix != 10 && value <= 0xFFFF_FFFFL) value = (int) value;
            long result = negated ? -value : value;
            if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) return Optional.empty();
            return Optional.of((int) result);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // ---- writing ---------------------------------------------------------------------------------------

    /**
     * {@code value} written as a literal of {@code typeName}, or empty when it cannot be one.
     *
     * <p>A number is converted to the declared type only when nothing is lost: an {@code Integer} is
     * written into a {@code double} field as {@code 3.0}, a {@code 2.5} into an {@code int} field is refused.
     */
    public static Optional<String> write(String typeName, Object value) {
        String type = canonical(typeName);
        if (type == null || value == null) return Optional.empty();
        return switch (type) {
            case "java.lang.String" -> value instanceof String s ? Optional.of(quote(s)) : Optional.empty();
            case "char", "java.lang.Character" ->
                    value instanceof Character c ? Optional.of(quote(c)) : Optional.empty();
            case "boolean", "java.lang.Boolean" ->
                    value instanceof Boolean b ? Optional.of(b.toString()) : Optional.empty();
            case "int", "java.lang.Integer" -> whole(value).filter(v -> v == v.intValue()).map(String::valueOf);
            case "short", "java.lang.Short" -> whole(value).filter(v -> v == v.shortValue()).map(String::valueOf);
            case "byte", "java.lang.Byte" -> whole(value).filter(v -> v == v.byteValue()).map(String::valueOf);
            case "long", "java.lang.Long" -> whole(value).map(v -> v + "L");
            case "float", "java.lang.Float" -> decimal(value).map(v -> floatLiteral((float) (double) v));
            case "double", "java.lang.Double" -> decimal(value).map(JdkLiterals::doubleLiteral);
            default -> Optional.empty();
        };
    }

    /** {@code value} written as the literal its own class spells, or empty for anything that is not one. */
    public static Optional<String> writeAny(Object value) {
        return switch (value) {
            case String s -> Optional.of(quote(s));
            case Character c -> Optional.of(quote(c));
            case Boolean b -> Optional.of(b.toString());
            case Integer i -> Optional.of(i.toString());
            case Short s -> Optional.of(s.toString());
            case Byte b -> Optional.of(b.toString());
            case Long l -> Optional.of(l + "L");
            case Float f -> Float.isFinite(f) ? Optional.of(floatLiteral(f)) : Optional.empty();
            case Double d -> Double.isFinite(d) ? Optional.of(doubleLiteral(d)) : Optional.empty();
            case null, default -> Optional.empty();
        };
    }

    private static Optional<Long> whole(Object value) {
        return switch (value) {
            case Integer i -> Optional.of(i.longValue());
            case Long l -> Optional.of(l);
            case Short s -> Optional.of(s.longValue());
            case Byte b -> Optional.of(b.longValue());
            case Double d when Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 9.0e15 ->
                    Optional.of(d.longValue());
            case Float f when Float.isFinite(f) && f == Math.rint(f) && Math.abs(f) < 1.6e7f ->
                    Optional.of(f.longValue());
            default -> Optional.empty();
        };
    }

    private static Optional<Double> decimal(Object value) {
        return value instanceof Number n && Double.isFinite(n.doubleValue())
                ? Optional.of(n.doubleValue()) : Optional.empty();
    }

    private static String doubleLiteral(double value) {
        return Double.toString(value);
    }

    private static String floatLiteral(float value) {
        return Float.toString(value) + "f";
    }

    /** {@code text} as a Java string literal, escaped as javac reads it back. */
    public static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) out.append(escape(text.charAt(i), '"'));
        return out.append('"').toString();
    }

    /** {@code c} as a Java character literal. */
    public static String quote(char c) {
        return "'" + escape(c, '\'') + "'";
    }

    private static String escape(char c, char quote) {
        return switch (c) {
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            default -> {
                if (c == quote) yield "\\" + c;
                if (c < 0x20 || c == 0x7F) yield String.format("\\u%04x", (int) c);
                yield String.valueOf(c);
            }
        };
    }
}
