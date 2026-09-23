package com.botmaker.studio.plugin.record;

import com.botmaker.plugin.api.record.Gesture;
import com.botmaker.shared.input.InputEvent;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a recorded input stream is recognised as, over a window at (100, 50). */
class GesturesTest {

    private static final Rectangle WINDOW = new Rectangle(100, 50, 800, 600);

    private final List<Gestures.Captured> events = new ArrayList<>();

    private void key(long keysym, long at) {
        events.add(new Gestures.Captured(new InputEvent.KeyPress(0, keysym, at), null));
        events.add(new Gestures.Captured(new InputEvent.KeyRelease(0, keysym, at + 5), null));
    }

    private void press(int button, int x, int y, long at) {
        events.add(new Gestures.Captured(new InputEvent.ButtonPress(button, x, y, at), null));
    }

    private void release(int button, int x, int y, long at) {
        events.add(new Gestures.Captured(new InputEvent.ButtonRelease(button, x, y, at), null));
    }

    private void click(int x, int y, long at) {
        press(1, x, y, at);
        release(1, x, y, at + 20);
    }

    private List<Gestures.Recognized> recognized() {
        return Gestures.recognize(events, WINDOW);
    }

    private static List<Gesture> gestures(List<Gestures.Recognized> recognized) {
        return recognized.stream().map(Gestures.Recognized::gesture).toList();
    }

    @Test
    void a_click_is_made_relative_to_the_window() {
        click(150, 80, 0);

        Gestures.Recognized click = recognized().getFirst();
        assertEquals(Gesture.CLICK, click.gesture());
        assertEquals(List.of(50, 30), click.values());
        assertEquals(50, click.spot().x());
    }

    @Test
    void a_click_outside_the_window_is_dropped() {
        click(10, 10, 0);

        assertEquals(List.of(), recognized());
    }

    @Test
    void two_quick_clicks_at_one_spot_are_a_double_click() {
        click(150, 80, 0);
        click(151, 81, 150);

        assertEquals(List.of(Gesture.DOUBLE_CLICK), gestures(recognized()));
    }

    @Test
    void the_button_held_while_the_pointer_travels_is_a_drag() {
        press(1, 150, 80, 0);
        events.add(new Gestures.Captured(new InputEvent.Motion(200, 80, 100), null));
        release(1, 250, 80, 300);

        Gestures.Recognized drag = recognized().getFirst();
        assertEquals(Gesture.DRAG, drag.gesture());
        assertEquals(List.of(50, 30, 150, 30, 300L), drag.values());
    }

    @Test
    void printable_keys_are_one_typed_burst_and_backspace_edits_it() {
        key('h', 0);
        key('x', 10);
        key(0xFF08L, 20);
        key('i', 30);

        Gestures.Recognized typed = recognized().getFirst();
        assertEquals(Gesture.TYPE, typed.gesture());
        assertEquals(List.of("hi"), typed.values());
    }

    @Test
    void a_modifier_held_over_a_key_is_a_combo_in_neutral_names() {
        events.add(new Gestures.Captured(new InputEvent.KeyPress(0, 0xFFE3L, 0), null));
        key('s', 10);
        events.add(new Gestures.Captured(new InputEvent.KeyRelease(0, 0xFFE3L, 30), null));

        Gestures.Recognized combo = recognized().getFirst();
        assertEquals(Gesture.COMBO, combo.gesture());
        assertEquals(List.of("CTRL", "S"), combo.values());
    }

    @Test
    void a_named_key_and_the_wheel_are_their_own_gestures() {
        key(0xFF0DL, 0);
        press(4, 150, 80, 10);
        press(4, 150, 80, 20);
        press(5, 150, 80, 30);

        List<Gestures.Recognized> all = recognized();
        assertEquals(List.of(Gesture.KEY, Gesture.SCROLL_UP, Gesture.SCROLL_DOWN), gestures(all));
        assertEquals(List.of("ENTER"), all.get(0).values());
        assertEquals(List.of(2), all.get(1).values());
    }

    @Test
    void a_gap_between_gestures_is_a_rounded_pause() {
        click(150, 80, 0);
        key(0xFF0DL, 1260);

        List<Gestures.Recognized> all = recognized();
        assertEquals(List.of(Gesture.CLICK, Gesture.PAUSE, Gesture.KEY), gestures(all));
        assertEquals(List.of(1300L), all.get(1).values());
    }
}
