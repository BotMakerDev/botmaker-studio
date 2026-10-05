package com.botmaker.studio.services.overlay;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.overlay.ProbeContext;
import com.botmaker.plugin.api.overlay.ProbeResult;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import com.botmaker.studio.plugin.grammar.ValueGrammar;

import java.awt.image.BufferedImage;
import java.lang.reflect.Executable;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Runs the plugins' probes on the watched screen, off the FX thread, so the overlay editor's rows say what each
 * call would answer now — {@code ✓ found 0.94 at 412,230} — without running the bot.
 *
 * <p><b>Two paces.</b> The row the caret is on ({@link #focus}) is probed about twice a second, on a fresh
 * frame each time. Every other row is probed once when asked ({@link #refresh}: the panel's ⟳, the end of a
 * run), all on one frame. One worker thread does both, a probe at a time.
 *
 * <p><b>It falls behind by dropping, never by queueing.</b> The focused row's next probe is scheduled a period
 * after the last one <em>finished</em>, so a probe slower than the period — a slow capture, a big picture —
 * stretches the pace instead of stacking probes up. A newer {@link #refresh} drops what is left of an older
 * one, and a refresh's answers for a tree since replaced are the caller's to ignore by their key.
 *
 * <p>Answers go to the consumer on the worker thread. No JavaFX here.
 */
public final class ProbeEngine implements AutoCloseable {

    /** How often the focused row is probed. */
    public static final Duration FOCUS_PERIOD = Duration.ofMillis(500);

    /** What is probed on: the watched screen, in the bot's pixels ({@link WatchedScreen}). */
    public interface Screen {
        /** The frame now; empty when it cannot be captured. Blocking. */
        Optional<BufferedImage> frame();

        /** Where the frame sits, in the bot's pixels. */
        Optional<Area> area();
    }

    /** A probe's answer for one job. */
    public record Answer(ProbeCalls.Job job, ProbeResult result) {}

    private final ValueGrammar grammar;
    private final StudioServices services;
    private final Screen screen;
    private final Consumer<Answer> answers;
    private final ScheduledExecutorService worker;
    private final AtomicLong batch = new AtomicLong();
    private volatile ProbeCalls.Job focused;

    public ProbeEngine(ValueGrammar grammar, StudioServices services, Screen screen, Consumer<Answer> answers) {
        this(grammar, services, screen, answers, FOCUS_PERIOD);
    }

    ProbeEngine(ValueGrammar grammar, StudioServices services, Screen screen, Consumer<Answer> answers,
                Duration period) {
        this.grammar = grammar;
        this.services = services;
        this.screen = screen;
        this.answers = answers;
        this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "overlay-probes");
            thread.setDaemon(true);
            return thread;
        });
        long millis = Math.max(1, period.toMillis());
        worker.scheduleWithFixedDelay(this::probeFocused, millis, millis, TimeUnit.MILLISECONDS);
    }

    /** Probes {@code job} at the focused pace from now on, starting at once; null stops. Any thread. */
    public void focus(ProbeCalls.Job job) {
        focused = job;
        if (job != null && !worker.isShutdown()) worker.execute(this::probeFocused);
    }

    /** Probes each of {@code jobs} once, on one frame, dropping what is left of an earlier refresh. Any thread. */
    public void refresh(List<ProbeCalls.Job> jobs) {
        long mine = batch.incrementAndGet();
        List<ProbeCalls.Job> copy = List.copyOf(jobs);
        if (copy.isEmpty() || worker.isShutdown()) return;
        worker.execute(() -> {
            if (batch.get() != mine) return;
            Optional<BufferedImage> frame = screen.frame();
            Optional<Area> area = screen.area();
            for (ProbeCalls.Job job : copy) {
                if (batch.get() != mine) return;
                answers.accept(new Answer(job, evaluate(job, grammar, services, frame, area)));
            }
        });
    }

    private void probeFocused() {
        ProbeCalls.Job job = focused;
        if (job == null) return;
        Optional<BufferedImage> frame = screen.frame();
        // Focus moved while the frame was taken: this answer is for a row the user left.
        if (focused != job) return;
        answers.accept(new Answer(job, evaluate(job, grammar, services, frame, screen.area())));
    }

    /** Stops probing; an answer already being made is still delivered. */
    @Override
    public void close() {
        focused = null;
        batch.incrementAndGet();
        worker.shutdownNow();
    }

    /** What {@code job}'s probe answers on {@code frame}. Never throws: a probe that does answers UNKNOWN. */
    static ProbeResult evaluate(ProbeCalls.Job job, ValueGrammar grammar, StudioServices services,
                                Optional<BufferedImage> frame, Optional<Area> area) {
        try {
            ProbeResult result = job.declared().probed().probe().probe(new Context(job, grammar, services, frame, area));
            return result == null ? ProbeResult.unknown("the probe answered nothing") : result;
        } catch (RuntimeException | LinkageError e) {
            String why = e.getMessage();
            return ProbeResult.unknown(why == null || why.isBlank() ? e.getClass().getSimpleName() : why);
        }
    }

    /** One probe's view of its call and the screen. */
    private record Context(ProbeCalls.Job job, ValueGrammar grammar, StudioServices services,
                           Optional<BufferedImage> frame, Optional<Area> watchedArea) implements ProbeContext {

        @Override
        public Executable call() {
            return job.declared().probed().call();
        }

        /**
         * The argument as {@code type}: the named constant's initializer when it names one, else the argument's
         * own Java, read by the grammar. Empty for what the grammar does not write — a local, a call.
         */
        @Override
        public <T> Optional<T> argument(int index, Class<T> type) {
            if (type == null || index < 0 || index >= job.arguments().size()) return Optional.empty();
            ProbeCalls.Argument argument = job.arguments().get(index);
            String source = argument.constant() != null ? argument.constant() : argument.text();
            try {
                return grammar.valueOf(type, source).or(() -> grammar.valueOfAny(source))
                        .filter(type::isInstance).map(type::cast);
            } catch (RuntimeException | LinkageError unreadable) {
                return Optional.empty();
            }
        }
    }
}
