package com.botmaker.studio.plugin.record;

import com.botmaker.shared.capture.ScreenCapture;
import com.botmaker.shared.input.InputEvent;
import com.botmaker.shared.input.InputListener;
import com.botmaker.shared.input.InputListenerFactory;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * The input a recording sees: the passive global listener (X11 XRecord, so Linux only), the events it
 * buffers, and — for a button press over the window — the window's pixels at that moment.
 *
 * <p>Two things are dropped as they arrive rather than at the end. A pointer event inside {@code exclusion},
 * which is the HUD itself: it sits on the same screen, and is draggable, so filtering against its final
 * position would keep clicks made before a drag. And nothing is buffered while paused.
 *
 * <p>The frame is grabbed on the listener's thread, before the click has had any effect on screen, because
 * what a click was <em>on</em> is only visible before it lands.
 */
public final class InputCapture {

    private final Supplier<Rectangle> window;
    private final Supplier<Rectangle> exclusion;
    private final boolean grabFrames;

    private final List<Gestures.Captured> buffer = Collections.synchronizedList(new ArrayList<>());
    private InputListener listener;
    private volatile boolean recording;

    /**
     * @param window     the recorded window's bounds on screen, read on every press
     * @param exclusion  the HUD's bounds on screen, polled from the listener's thread; may answer null
     * @param grabFrames whether a press over the window grabs its pixels — only worth it when a plugin can read
     *                   a value off them
     */
    public InputCapture(Supplier<Rectangle> window, Supplier<Rectangle> exclusion, boolean grabFrames) {
        this.window = window;
        this.exclusion = exclusion;
        this.grabFrames = grabFrames;
    }

    /** Whether input can be recorded on this platform (Linux/X11 only). */
    public static boolean isSupported() {
        return InputListenerFactory.isSupported();
    }

    /** Starts listening. Armed before the listener starts, so the first press is never lost. */
    public void start() {
        if (recording) return;
        buffer.clear();
        recording = true;
        try {
            listener = InputListenerFactory.create();
            listener.start(this::onEvent);
        } catch (RuntimeException | Error e) {
            recording = false;
            listener = null;
            throw e;
        }
    }

    public boolean isRecording() {
        return recording;
    }

    /** Stops listening and answers what was recorded. The listener is closed before the copy is taken. */
    public List<Gestures.Captured> stop() {
        recording = false;
        if (listener != null) {
            try {
                listener.close();
            } catch (Exception ignored) {
                // idempotent close; nothing to recover
            }
            listener = null;
        }
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }

    /** Called on the native listener thread. */
    private void onEvent(InputEvent e) {
        if (!recording) return;
        Rectangle ex = exclusion == null ? null : exclusion.get();
        if (ex != null && insideExclusion(e, ex)) return;
        BufferedImage frame = null;
        if (grabFrames && e instanceof InputEvent.ButtonPress press) {
            Rectangle bounds = window.get();
            if (bounds != null && bounds.contains(press.x(), press.y())) frame = grab(bounds);
        }
        buffer.add(new Gestures.Captured(e, frame));
    }

    /** The window's pixels, cropped out of a desktop grab; {@code null} when the grab fails. */
    private static BufferedImage grab(Rectangle bounds) {
        try {
            BufferedImage desktop = ScreenCapture.captureDesktop();
            Rectangle origin = ScreenCapture.getVirtualScreenBounds();
            Rectangle crop = new Rectangle(bounds.x - origin.x, bounds.y - origin.y, bounds.width, bounds.height)
                    .intersection(new Rectangle(0, 0, desktop.getWidth(), desktop.getHeight()));
            if (crop.isEmpty()) return null;
            return desktop.getSubimage(crop.x, crop.y, crop.width, crop.height);
        } catch (RuntimeException | Error e) {
            return null;
        }
    }

    private static boolean insideExclusion(InputEvent e, Rectangle ex) {
        return switch (e) {
            case InputEvent.ButtonPress b -> ex.contains(b.x(), b.y());
            case InputEvent.ButtonRelease b -> ex.contains(b.x(), b.y());
            case InputEvent.Motion m -> ex.contains(m.x(), m.y());
            case InputEvent.KeyPress ignored -> false;
            case InputEvent.KeyRelease ignored -> false;
        };
    }
}
