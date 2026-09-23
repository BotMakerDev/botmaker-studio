package com.botmaker.studio.plugin.record;

import com.botmaker.plugin.api.record.Gesture;
import com.botmaker.plugin.api.record.RecordedValue;
import com.botmaker.shared.input.InputEvent;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recognises {@link Gesture}s in a recorded input stream. Pure: no native code, no JavaFX, no plugin.
 *
 * <p>Coordinates become relative to the window the recording was made over, and a pointer event outside it
 * is dropped. Keys are named with the host's neutral names — the names a plugin's key enum is looked up by.
 */
public final class Gestures {

    /** Idle time between two gestures that is recorded as a {@link Gesture#PAUSE}. */
    static final long PAUSE_MS = 400;
    /** Pauses are rounded to this many milliseconds. */
    private static final long PAUSE_ROUND_MS = 100;
    /** Pointer travel with the left button held beyond which a click is a drag. */
    private static final int DRAG_PX = 5;
    /** Two clicks this close in time and space are one double click. */
    private static final long DOUBLE_CLICK_MS = 400;

    private Gestures() {}

    /** One recorded input event, with the window's pixels grabbed at the moment of a button press. */
    public record Captured(InputEvent event, BufferedImage frame) {}

    /**
     * One recognised gesture.
     *
     * @param values the gesture's values, in {@link Gesture}'s documented order
     * @param spot   where it happened, for a pointer gesture; {@code null} otherwise
     * @param time   when it began, in the listener's milliseconds
     */
    public record Recognized(Gesture gesture, List<Object> values, RecordedValue.Spot spot, long time) {

        public Recognized {
            values = List.copyOf(values);
        }
    }

    /** The gestures in {@code events}, recorded over {@code window}, with pauses between them. */
    public static List<Recognized> recognize(List<Captured> events, Rectangle window) {
        return withPauses(new Reader(window).read(events));
    }

    private static List<Recognized> withPauses(List<Recognized> gestures) {
        List<Recognized> out = new ArrayList<>();
        Recognized previous = null;
        for (Recognized gesture : gestures) {
            if (previous != null) {
                long gap = gesture.time() - previous.time();
                if (gap >= PAUSE_MS) {
                    long rounded = Math.max(PAUSE_ROUND_MS, Math.round((double) gap / PAUSE_ROUND_MS) * PAUSE_ROUND_MS);
                    out.add(new Recognized(Gesture.PAUSE, List.of(rounded), null, previous.time()));
                }
            }
            out.add(gesture);
            previous = gesture;
        }
        return out;
    }

    /** One pass over the events; the state a gesture spans lives here rather than in arrays of one. */
    private static final class Reader {

        private final Rectangle window;
        private final List<Recognized> out = new ArrayList<>();
        private final Set<String> modifiers = new LinkedHashSet<>();

        private final StringBuilder typing = new StringBuilder();
        private long typingStart;

        private int scroll;
        private long scrollStart;

        private InputEvent.ButtonPress leftDown;
        private BufferedImage leftFrame;
        private boolean dragged;

        Reader(Rectangle window) {
            this.window = window;
        }

        List<Recognized> read(List<Captured> events) {
            for (Captured captured : events) {
                switch (captured.event()) {
                    case InputEvent.KeyPress k -> keyPressed(k);
                    case InputEvent.KeyRelease k -> {
                        String modifier = MODIFIERS.get(k.keysym());
                        if (modifier != null) modifiers.remove(modifier);
                    }
                    case InputEvent.ButtonPress b -> buttonPressed(b, captured.frame());
                    case InputEvent.Motion m -> {
                        if (leftDown != null && far(leftDown.x(), leftDown.y(), m.x(), m.y())) dragged = true;
                    }
                    case InputEvent.ButtonRelease b -> buttonReleased(b);
                }
            }
            flushTyping();
            flushScroll();
            return out;
        }

        private void keyPressed(InputEvent.KeyPress k) {
            long sym = k.keysym();
            String modifier = MODIFIERS.get(sym);
            if (modifier != null) {
                modifiers.add(modifier);
                return;
            }
            if (sym == LOCKS[0] || sym == LOCKS[1] || sym == LOCKS[2]) return;
            boolean chord = modifiers.stream().anyMatch(m -> !m.equals("SHIFT"));
            if (chord) {
                String key = keyName(sym);
                if (key == null) return;
                flushTyping();
                flushScroll();
                List<Object> keys = new ArrayList<>(modifiers);
                keys.add(key);
                add(Gesture.COMBO, keys, null, k.timestampMs());
                return;
            }
            if (sym == BACKSPACE && !typing.isEmpty()) {
                typing.deleteCharAt(typing.length() - 1);
                return;
            }
            if (printable(sym)) {
                flushScroll();
                if (typing.isEmpty()) typingStart = k.timestampMs();
                typing.append((char) sym);
                return;
            }
            String named = NAMED.get(sym);
            if (named == null) return;
            flushTyping();
            flushScroll();
            add(Gesture.KEY, List.of(named), null, k.timestampMs());
        }

        private void buttonPressed(InputEvent.ButtonPress b, BufferedImage frame) {
            switch (b.button()) {
                case 1 -> {
                    leftDown = b;
                    leftFrame = frame;
                    dragged = false;
                }
                case 2, 3 -> {
                    if (!inside(b.x(), b.y())) return;
                    flushTyping();
                    flushScroll();
                    Gesture gesture = b.button() == 3 ? Gesture.RIGHT_CLICK : Gesture.MIDDLE_CLICK;
                    add(gesture, List.of(relX(b.x()), relY(b.y())), spot(b.x(), b.y(), frame), b.timestampMs());
                }
                case 4, 5 -> {
                    int direction = b.button() == 4 ? 1 : -1;
                    flushTyping();
                    if (scroll != 0 && Integer.signum(scroll) != direction) flushScroll();
                    if (scroll == 0) scrollStart = b.timestampMs();
                    scroll += direction;
                }
                default -> { }
            }
        }

        private void buttonReleased(InputEvent.ButtonRelease b) {
            if (b.button() != 1 || leftDown == null) return;
            InputEvent.ButtonPress down = leftDown;
            leftDown = null;
            if (!inside(down.x(), down.y())) return;
            flushTyping();
            flushScroll();
            if (dragged) {
                add(Gesture.DRAG, List.of(relX(down.x()), relY(down.y()), relX(b.x()), relY(b.y()),
                        Math.max(0L, b.timestampMs() - down.timestampMs())),
                        spot(down.x(), down.y(), leftFrame), down.timestampMs());
                return;
            }
            Recognized last = out.isEmpty() ? null : out.getLast();
            if (last != null && last.gesture() == Gesture.CLICK
                    && down.timestampMs() - last.time() <= DOUBLE_CLICK_MS
                    && !far((int) last.values().get(0), (int) last.values().get(1), relX(down.x()), relY(down.y()))) {
                out.set(out.size() - 1, new Recognized(Gesture.DOUBLE_CLICK, last.values(), last.spot(), last.time()));
                return;
            }
            add(Gesture.CLICK, List.of(relX(down.x()), relY(down.y())), spot(down.x(), down.y(), leftFrame),
                    down.timestampMs());
        }

        private void flushTyping() {
            if (typing.isEmpty()) return;
            add(Gesture.TYPE, List.of(typing.toString()), null, typingStart);
            typing.setLength(0);
        }

        private void flushScroll() {
            if (scroll == 0) return;
            add(scroll > 0 ? Gesture.SCROLL_UP : Gesture.SCROLL_DOWN, List.of(Math.abs(scroll)), null, scrollStart);
            scroll = 0;
        }

        private void add(Gesture gesture, List<Object> values, RecordedValue.Spot spot, long time) {
            out.add(new Recognized(gesture, values, spot, time));
        }

        private RecordedValue.Spot spot(int x, int y, BufferedImage frame) {
            return new RecordedValue.Spot(relX(x), relY(y), frame);
        }

        private int relX(int x) {
            return x - window.x;
        }

        private int relY(int y) {
            return y - window.y;
        }

        private boolean inside(int x, int y) {
            return window.contains(x, y);
        }
    }

    private static boolean far(int x0, int y0, int x1, int y1) {
        return Math.abs(x1 - x0) > DRAG_PX || Math.abs(y1 - y0) > DRAG_PX;
    }

    // ── X keysyms (keysymdef.h) ─────────────────────────────────────────────────────────────────────────

    private static final long BACKSPACE = 0xFF08L;
    /** Caps_Lock, Shift_Lock, Num_Lock: never a gesture. */
    private static final long[] LOCKS = {0xFFE5L, 0xFFE6L, 0xFF7FL};

    /** Latin-1 printable keysyms equal their character. */
    private static boolean printable(long sym) {
        return (sym >= 0x20 && sym <= 0x7E) || (sym >= 0xA0 && sym <= 0xFF);
    }

    private static final Map<Long, String> MODIFIERS = Map.ofEntries(
            Map.entry(0xFFE1L, "SHIFT"), Map.entry(0xFFE2L, "SHIFT"),
            Map.entry(0xFFE3L, "CTRL"), Map.entry(0xFFE4L, "CTRL"),
            Map.entry(0xFFE9L, "ALT"), Map.entry(0xFFEAL, "ALT"), Map.entry(0xFE03L, "ALT"),
            Map.entry(0xFFE7L, "META"), Map.entry(0xFFE8L, "META"),
            Map.entry(0xFFEBL, "META"), Map.entry(0xFFECL, "META"));

    private static final Map<Long, String> NAMED = named();

    private static Map<Long, String> named() {
        Map<Long, String> m = new java.util.HashMap<>();
        m.put(0xFF0DL, "ENTER");
        m.put(0xFF8DL, "ENTER");
        m.put(0xFF1BL, "ESCAPE");
        m.put(0xFF09L, "TAB");
        m.put(BACKSPACE, "BACKSPACE");
        m.put(0xFFFFL, "DELETE");
        m.put(0xFF51L, "LEFT");
        m.put(0xFF52L, "UP");
        m.put(0xFF53L, "RIGHT");
        m.put(0xFF54L, "DOWN");
        for (int i = 0; i < 12; i++) m.put(0xFFBEL + i, "F" + (i + 1));
        for (int i = 0; i < 10; i++) m.put(0xFFB0L + i, "NUM" + i);
        return Map.copyOf(m);
    }

    /** The neutral name of a key pressed inside a chord: a named key, a letter, a digit or the space bar. */
    private static String keyName(long sym) {
        String named = NAMED.get(sym);
        if (named != null) return named;
        if (sym >= 'a' && sym <= 'z') return String.valueOf((char) (sym - 32));
        if (sym >= 'A' && sym <= 'Z') return String.valueOf((char) sym);
        if (sym >= '0' && sym <= '9') return "NUM" + (char) sym;
        if (sym == ' ') return "SPACE";
        return null;
    }
}
