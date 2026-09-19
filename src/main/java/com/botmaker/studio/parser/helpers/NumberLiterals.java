package com.botmaker.studio.parser.helpers;

import java.util.Optional;

/**
 * A Java number literal's value, read by the language's grammar rather than by two suffixes.
 *
 * <p><b>The reader this replaced sent everything that was not {@code f} or {@code .}/{@code d} to
 * {@code Integer.parseInt}</b>, so {@code 60000L} threw — and nothing between that throw and
 * {@code BlockConverter.convert}'s outer catch stopped it, so one {@code Duration.ofMillis(60000L)} anywhere in
 * a file drew that file as an empty canvas. {@code long} suffixes, hex / binary / octal prefixes and {@code _}
 * separators are all legal Java and all reached the same branch.
 *
 * <p>The boxed type is the literal's own type — {@link Long} for an {@code L} literal, {@link Integer}
 * otherwise, {@link Float}/{@link Double} for a floating one — because {@code LiteralBlock} decides its input
 * filter and the suffix it writes back from that type.
 */
public final class NumberLiterals {

    private NumberLiterals() {}

    /** The literal's value, or empty for a token that is not a valid Java number literal. */
    public static Optional<Number> value(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String t = token.replace("_", "");
        String lower = t.toLowerCase();
        boolean hex = lower.startsWith("0x");
        try {
            if (isFloating(lower, hex)) {
                if (lower.endsWith("f")) return Optional.of(Float.parseFloat(t));
                return Optional.of(Double.parseDouble(t));
            }
            boolean isLong = lower.endsWith("l");
            String digits = isLong ? t.substring(0, t.length() - 1) : t;
            int radix = 10;
            if (hex) {
                radix = 16;
                digits = digits.substring(2);
            } else if (lower.startsWith("0b")) {
                radix = 2;
                digits = digits.substring(2);
            } else if (digits.length() > 1 && digits.startsWith("0")) {
                radix = 8;
                digits = digits.substring(1);
            }
            // A non-decimal literal spells the bit pattern, so 0xFFFFFFFF is a legal int (-1): unsigned parse.
            if (isLong) {
                return Optional.of(radix == 10 ? Long.parseLong(digits) : Long.parseUnsignedLong(digits, radix));
            }
            return Optional.of(radix == 10 ? Integer.parseInt(digits) : Integer.parseUnsignedInt(digits, radix));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether the literal is spelled the way a number field writes one back — digits, one optional point,
     * one optional type suffix. {@code 0xFF}, {@code 1_000} and {@code 1e3} are not: a field would show their
     * value in decimal and, on the first edit, replace the author's spelling with its own.
     */
    public static boolean isPlainDecimal(String token) {
        return token != null && token.matches("(0|[1-9][0-9]*)(\\.[0-9]*)?[lLfFdD]?|\\.[0-9]+[fFdD]?");
    }

    private static boolean isFloating(String lower, boolean hex) {
        if (hex) return lower.contains("p");                 // 0x1.8p1 — a hex float needs its exponent
        return lower.contains(".") || lower.contains("e") || lower.endsWith("f") || lower.endsWith("d");
    }
}
