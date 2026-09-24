package com.botmaker.studio.plugin.grammar;

import org.eclipse.jdt.core.dom.AST;
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

    /**
     * The literal types, by canonical name — {@code int}, {@code java.lang.Integer}, {@code java.lang.String}.
     * Only canonical names: which class a name <em>written</em> in a file means is the resolver's question
     * ({@code project/source/ValueTypeResolver}), answered through a binding or the file's imports.
     */
    private static final Map<String, Class<?>> TYPES = Map.ofEntries(
            entry(boolean.class), entry(byte.class), entry(char.class), entry(short.class), entry(int.class),
            entry(long.class), entry(float.class), entry(double.class),
            entry(Boolean.class), entry(Byte.class), entry(Character.class), entry(Short.class),
            entry(Integer.class), entry(Long.class), entry(Float.class), entry(Double.class), entry(String.class));

    private static Map.Entry<String, Class<?>> entry(Class<?> type) {
        return Map.entry(type.getName(), type);
    }

    /** The literal type whose canonical name is {@code canonical}, or empty. Exact: no simple names. */
    public static Optional<Class<?>> named(String canonical) {
        return canonical == null ? Optional.empty() : Optional.ofNullable(TYPES.get(canonical));
    }

    /** Every literal type's canonical name. */
    public static java.util.Set<String> names() {
        return TYPES.keySet();
    }

    /** Whether {@code type} is a type this class reads and writes. */
    public static boolean handles(Class<?> type) {
        return type != null && TYPES.get(type.getName()) == type;
    }

    /** {@code type} with a primitive boxed, so one comparison covers {@code int} and {@code Integer}. */
    private static Class<?> boxed(Class<?> type) {
        return java.lang.invoke.MethodType.methodType(type).wrap().returnType();
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
    public static Optional<Object> read(Class<?> type, String source) {
        return SourceNode.parse(source).flatMap(parsed -> read(type, parsed.node()));
    }

    /** {@link #read(Class, String)} over an expression already parsed. */
    public static Optional<Object> read(Class<?> type, Expression node) {
        if (!handles(type)) return Optional.empty();
        Optional<Object> any = readAny(node);
        if (any.isEmpty()) return Optional.empty();
        Object value = any.get();
        Class<?> box = boxed(type);
        if (box == String.class) return value instanceof String ? any : Optional.empty();
        if (box == Character.class) return value instanceof Character ? any : Optional.empty();
        if (box == Boolean.class) return value instanceof Boolean ? any : Optional.empty();
        if (box == Integer.class) return value instanceof Integer ? any : Optional.empty();
        if (box == Short.class) {
            return value instanceof Integer i && i == i.shortValue() ? Optional.of(i.shortValue()) : Optional.empty();
        }
        if (box == Byte.class) {
            return value instanceof Integer i && i == i.byteValue() ? Optional.of(i.byteValue()) : Optional.empty();
        }
        if (box == Long.class) {
            return value instanceof Long || value instanceof Integer
                    ? Optional.of(((Number) value).longValue()) : Optional.empty();
        }
        if (box == Float.class) {
            return value instanceof Float || value instanceof Integer
                    ? Optional.of(((Number) value).floatValue()) : Optional.empty();
        }
        return value instanceof Number n ? Optional.of(n.doubleValue()) : Optional.empty();
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
        return SourceNode.parse(source).flatMap(parsed -> readAny(parsed.node()));
    }

    /** {@link #readAny(String)} over an expression already parsed. */
    public static Optional<Object> readAny(Expression node) {
        return node == null ? Optional.empty() : literal(node, false);
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
    public static Optional<String> write(Class<?> type, Object value) {
        if (!handles(type) || value == null) return Optional.empty();
        Class<?> box = boxed(type);
        if (box == String.class) return value instanceof String s ? Optional.of(quote(s)) : Optional.empty();
        if (box == Character.class) return value instanceof Character c ? Optional.of(quote(c)) : Optional.empty();
        if (box == Boolean.class) return value instanceof Boolean b ? Optional.of(b.toString()) : Optional.empty();
        if (box == Integer.class) return whole(value).filter(v -> v == v.intValue()).map(String::valueOf);
        if (box == Short.class) return whole(value).filter(v -> v == v.shortValue()).map(String::valueOf);
        if (box == Byte.class) return whole(value).filter(v -> v == v.byteValue()).map(String::valueOf);
        if (box == Long.class) return whole(value).map(v -> v + "L");
        if (box == Float.class) return decimal(value).map(v -> floatLiteral((float) (double) v));
        return decimal(value).map(JdkLiterals::doubleLiteral);
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

    /** {@link #write(Class, Object)} as a node of {@code ast}. */
    public static Optional<Expression> node(AST ast, Class<?> type, Object value) {
        return write(type, value).map(token -> node(ast, token));
    }

    /** {@link #writeAny(Object)} as a node of {@code ast}. */
    public static Optional<Expression> nodeAny(AST ast, Object value) {
        return writeAny(value).map(token -> node(ast, token));
    }

    /**
     * The node for one literal this class spelled — never for text from anywhere else, so the token's shape
     * is known: quoted, a boolean, or a number with at most a leading minus.
     */
    private static Expression node(AST ast, String token) {
        if (token.startsWith("\"")) {
            StringLiteral literal = ast.newStringLiteral();
            literal.setEscapedValue(token);
            return literal;
        }
        if (token.startsWith("'")) {
            CharacterLiteral literal = ast.newCharacterLiteral();
            literal.setEscapedValue(token);
            return literal;
        }
        if (token.equals("true") || token.equals("false")) return ast.newBooleanLiteral(token.equals("true"));
        if (!token.startsWith("-")) return ast.newNumberLiteral(token);
        PrefixExpression minus = ast.newPrefixExpression();
        minus.setOperator(PrefixExpression.Operator.MINUS);
        minus.setOperand(ast.newNumberLiteral(token.substring(1)));
        return minus;
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
