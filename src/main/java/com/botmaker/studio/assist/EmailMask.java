package com.botmaker.studio.assist;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The emails {@code ui} shows, masked (the user's call, 2026-11-13: an assistant never sees one, even driving the
 * windows that list them). Each email reads as {@code account-N@masked}, the same N for the same email (any case)
 * while Studio runs, so a press or a find can name it; {@link #unmask} gives the email back, as first shown, for a
 * text typed into a window. An email cut by an ellipsis reads as {@code …@masked}.
 */
final class EmailMask {

    /** The domain every placeholder has, never a real one. */
    static final String DOMAIN = "masked";
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    /** An email's start cut by an ellipsis: Studio's own cut, or JavaFX's. */
    private static final Pattern CUT = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]*(?=…|\\.\\.\\.)");
    private static final Pattern PLACEHOLDER = Pattern.compile("account-(\\d{1,6})@" + DOMAIN, Pattern.CASE_INSENSITIVE);

    /** Lower-cased email to its number; each number's email as first shown, the first at index 0. */
    private static final Map<String, Integer> NUMBERS = new HashMap<>();
    private static final List<String> SHOWN = new ArrayList<>();

    private EmailMask() {
    }

    /** {@code text} with every email, whole or cut, masked; null stays null. */
    static String mask(String text) {
        if (text == null || text.indexOf('@') < 0) return text;
        Matcher whole = EMAIL.matcher(text);
        StringBuilder out = new StringBuilder();
        while (whole.find()) whole.appendReplacement(out, Matcher.quoteReplacement(placeholder(whole.group())));
        whole.appendTail(out);
        Matcher cut = CUT.matcher(out.toString());
        StringBuilder done = new StringBuilder();
        while (cut.find()) {
            String found = cut.group();
            String replaced = PLACEHOLDER.matcher(found).matches() ? found : "…@" + DOMAIN;
            cut.appendReplacement(done, Matcher.quoteReplacement(replaced));
        }
        cut.appendTail(done);
        return done.toString();
    }

    /** {@code text} with each placeholder {@link #mask} gave turned back into its email; one never given stays. */
    static String unmask(String text) {
        if (text == null || text.indexOf('@') < 0) return text;
        Matcher placeholder = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (placeholder.find()) {
            String email = email(Integer.parseInt(placeholder.group(1)));
            placeholder.appendReplacement(out, Matcher.quoteReplacement(email == null ? placeholder.group() : email));
        }
        placeholder.appendTail(out);
        return out.toString();
    }

    /** Whether {@code text} holds an email, whole or cut; it numbers none. */
    static boolean holds(String text) {
        if (text == null || text.indexOf('@') < 0) return false;
        if (EMAIL.matcher(text).find()) return true;
        Matcher cut = CUT.matcher(text);
        while (cut.find()) {
            if (!PLACEHOLDER.matcher(cut.group()).matches()) return true;
        }
        return false;
    }

    /** Where to cut masked {@code line} at about {@code at}: after a placeholder the cut would split. */
    static int cutAt(String line, int at) {
        Matcher placeholder = PLACEHOLDER.matcher(line);
        while (placeholder.find()) {
            if (placeholder.start() < at && placeholder.end() > at) return placeholder.end();
        }
        return at;
    }

    private static synchronized String placeholder(String email) {
        int n = NUMBERS.computeIfAbsent(email.toLowerCase(Locale.ROOT), e -> {
            SHOWN.add(email);
            return SHOWN.size();
        });
        return "account-" + n + "@" + DOMAIN;
    }

    private static synchronized String email(int n) {
        return n >= 1 && n <= SHOWN.size() ? SHOWN.get(n - 1) : null;
    }
}
