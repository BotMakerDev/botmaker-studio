package com.botmaker.studio.services.overlay;

import com.botmaker.plugin.api.overlay.OverlayPart;
import com.botmaker.plugin.api.overlay.Probe;
import com.botmaker.plugin.api.overlay.ProbeResult;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import com.botmaker.studio.plugin.PluginHost;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probe engine: the focused row at its pace, one probe at a time and dropping rather than queueing when a
 * probe is slow; a refresh on one frame, dropped by a newer one; a probe's arguments read by the grammar, and
 * a probe that throws answering UNKNOWN.
 */
class ProbeEngineTest {

    private static final BufferedImage FRAME = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);

    /** A screen that counts its frames. */
    private static final class CountingScreen implements ProbeEngine.Screen {
        final AtomicInteger frames = new AtomicInteger();

        @Override
        public Optional<BufferedImage> frame() {
            frames.incrementAndGet();
            return Optional.of(FRAME);
        }

        @Override
        public Optional<Area> area() {
            return Optional.of(new Area(10, 20, 4, 4));
        }
    }

    private static ProbeCalls.Job job(Object key, Probe probe, ProbeCalls.Argument... arguments) throws Exception {
        return new ProbeCalls.Job(key, new ProbeCalls.Declared("test", new OverlayPart.ProbedCall(
                Integer.class.getMethod("parseInt", String.class), probe)), List.of(arguments));
    }

    private static ProbeEngine engine(ProbeEngine.Screen screen, List<ProbeEngine.Answer> answers, long periodMillis) {
        return new ProbeEngine(PluginHost.grammar(), null, screen, answers::add, Duration.ofMillis(periodMillis));
    }

    @Test
    void theFocusedRowIsProbedAtItsPaceUntilFocusLeaves() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<ProbeEngine.Answer> answers = new CopyOnWriteArrayList<>();
        try (ProbeEngine probes = engine(new CountingScreen(), answers, 20)) {
            probes.focus(job("row", context -> {
                calls.incrementAndGet();
                return ProbeResult.missing("no");
            }));
            Thread.sleep(400);
            int seen = calls.get();
            assertTrue(seen >= 5 && seen <= 25, "about once per 20 ms over 400 ms, was " + seen);

            probes.focus(null);
            Thread.sleep(60);
            int after = calls.get();
            Thread.sleep(200);
            assertEquals(after, calls.get(), "no focused row, no probing");
        }
        assertTrue(answers.stream().allMatch(a -> "row".equals(a.job().key())));
    }

    @Test
    void aSlowProbeStretchesThePaceInsteadOfStackingUp() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        try (ProbeEngine probes = engine(new CountingScreen(), new CopyOnWriteArrayList<>(), 5)) {
            probes.focus(job("row", context -> {
                mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
                calls.incrementAndGet();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
                running.decrementAndGet();
                return ProbeResult.missing("slow");
            }));
            Thread.sleep(550);
        }
        assertEquals(1, mostAtOnce.get(), "one probe at a time");
        assertTrue(calls.get() <= 7, "a 100 ms probe at a 5 ms pace runs about every 105 ms, ran " + calls.get());
    }

    @Test
    void aRefreshProbesEveryRowOnOneFrame() throws Exception {
        CountingScreen screen = new CountingScreen();
        List<ProbeEngine.Answer> answers = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        Probe found = context -> ProbeResult.found("at " + context.watchedArea().orElseThrow().x(), null);
        // Counted as each answer is delivered, not as each probe runs, which is before its delivery.
        try (ProbeEngine probes = new ProbeEngine(PluginHost.grammar(), null, screen, answer -> {
            answers.add(answer);
            done.countDown();
        }, Duration.ofMillis(10_000))) {
            probes.refresh(List.of(job(1, found), job(2, found), job(3, found)));
            assertTrue(done.await(5, TimeUnit.SECONDS));
        }
        assertEquals(List.of(1, 2, 3), answers.stream().map(a -> a.job().key()).toList());
        assertEquals(1, screen.frames.get(), "one frame for the whole refresh");
        assertEquals("at 10", answers.getFirst().result().text(), "the probe is told where the frame sits");
    }

    @Test
    void aNewerRefreshDropsWhatIsLeftOfAnOlderOne() throws Exception {
        List<ProbeEngine.Answer> answers = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch newer = new CountDownLatch(1);
        Probe blocking = context -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return ProbeResult.missing("old");
        };
        try (ProbeEngine probes = new ProbeEngine(PluginHost.grammar(), null, new CountingScreen(), answer -> {
            answers.add(answer);
            if ("new".equals(answer.job().key())) newer.countDown();
        }, Duration.ofMillis(10_000))) {
            probes.refresh(List.of(job("old-1", blocking), job("old-2", blocking), job("old-3", blocking)));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            probes.refresh(List.of(job("new", context -> ProbeResult.found("new", null))));
            release.countDown();
            assertTrue(newer.await(5, TimeUnit.SECONDS));
        }
        assertEquals(List.of("old-1", "new"), answers.stream().map(a -> a.job().key()).toList(),
                "the probe already running answers; the rest of its batch is dropped");
    }

    @Test
    void anArgumentIsReadByTheGrammarAConstantByItsInitializer() throws Exception {
        Probe reads = context -> ProbeResult.found(context.argument(0, String.class).orElse("<none>"), null);
        ProbeResult literal = ProbeEngine.evaluate(job("a", reads, new ProbeCalls.Argument("\"42\"", null)),
                PluginHost.grammar(), null, Optional.of(FRAME), Optional.empty());
        assertEquals("42", literal.text());

        ProbeResult constant = ProbeEngine.evaluate(job("b", reads, new ProbeCalls.Argument("Pictures.ONE", "\"1\"")),
                PluginHost.grammar(), null, Optional.of(FRAME), Optional.empty());
        assertEquals("1", constant.text(), "a constant is read as what it holds");

        ProbeResult local = ProbeEngine.evaluate(job("c", reads, new ProbeCalls.Argument("name", null)),
                PluginHost.grammar(), null, Optional.of(FRAME), Optional.empty());
        assertEquals("<none>", local.text(), "a local is not a value the overlay can read");

        ProbeResult outOfRange = ProbeEngine.evaluate(job("d", context ->
                        ProbeResult.found(String.valueOf(context.argument(3, String.class).isPresent()), null)),
                PluginHost.grammar(), null, Optional.empty(), Optional.empty());
        assertEquals("false", outOfRange.text());
    }

    @Test
    void aProbeThatThrowsAnswersUnknownWithItsMessage() throws Exception {
        ProbeResult result = ProbeEngine.evaluate(job("x", context -> {
            throw new IllegalStateException("no picture loaded");
        }), PluginHost.grammar(), null, Optional.empty(), Optional.empty());
        assertEquals(ProbeResult.State.UNKNOWN, result.state());
        assertEquals("no picture loaded", result.text());
    }
}
