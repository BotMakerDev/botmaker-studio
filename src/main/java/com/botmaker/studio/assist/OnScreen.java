package com.botmaker.studio.assist;

import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * An assistant's call that asks the user on screen (2026-10-28): the call answers once the question is put, and what
 * follows runs on a thread of its own, since the user's answer outlasts an MCP client's call. The pattern
 * {@link PluginMove} set, for every tool that asks. At most one runs; while it is asked or runs, the tools that write
 * the bot wait ({@link #busy}); how it ended is said once, after the next answer of any tool ({@link #news}).
 *
 * <p>Process-wide, as the questions are: the project's endpoint and the home's ask over the same windows.
 */
public final class OnScreen {

    /** How long a question stays up unanswered before it is closed and taken as a no. */
    static final long ASK_MINUTES = 5;

    private enum Stage {
        /** The question is on screen. */
        ASKED,
        /** The user agreed; the work runs. */
        DOING
    }

    /** The call under way: what it does, the question on screen now. */
    private record Job(String doing, String question, Stage stage) {

        String line() {
            return switch (stage) {
                case ASKED -> "The user is asked on screen \"" + question + "\" (" + doing + ").";
                case DOING -> doing + ": the user agreed, and it runs.";
            };
        }
    }

    private static final AtomicReference<Job> NOW = new AtomicReference<>();
    private static final ConcurrentLinkedQueue<String> NEWS = new ConcurrentLinkedQueue<>();

    private OnScreen() {
    }

    /**
     * Runs {@code work} on its own thread and answers at once; {@code work} asks with {@link #agrees}, its first
     * question {@code question}, and its sentence, or its refusal, is the {@link #news} of how it ended.
     *
     * @param doing what the call does, as a sentence's subject: {@code "Adding Firestone"}
     * @throws IllegalArgumentException while another one is under way
     */
    public static String start(String doing, String question, Supplier<String> work) {
        Job job = new Job(doing, question, Stage.ASKED);
        Job before = NOW.get();
        if (before != null || !NOW.compareAndSet(null, job)) {
            throw new IllegalArgumentException((before == null ? "Another question is on screen." : before.line())
                    + " Wait for it: the next answer says how it ended.");
        }
        try {
            Thread.ofVirtual().name("on-screen").start(() -> run(job, work));
        } catch (RuntimeException | Error e) {
            NOW.set(null);
            throw e;
        }
        return "Asked the user on screen \"" + question + "\". " + doing + " goes on once they agree"
                + (UiTools.enabled() ? ": ui press OK or Cancel in that dialog, or the user answers" : "")
                + ". The answer of a tool called after it ends says how it ended.";
    }

    private static void run(Job job, Supplier<String> work) {
        String ended = job.doing() + " stopped unexpectedly.";
        try {
            ended = work.get();
        } catch (RuntimeException e) {
            ended = job.doing() + " is not done: " + (e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage());
        } finally {
            // Said before it is let go: a call that finds nothing busy finds how it ended.
            NEWS.add("The question on screen ended. " + ended);
            NOW.set(null);
        }
    }

    /** What is asked or runs now, as a refusal says it; empty when nothing is. */
    public static String busy() {
        Job now = NOW.get();
        return now == null ? "" : now.line();
    }

    /** Whether a question is on screen and not yet answered: {@code ui} may press its buttons then. */
    public static boolean asking() {
        Job now = NOW.get();
        return now != null && now.stage() == Stage.ASKED;
    }

    /** How the calls that asked ended since the last call, oldest first; each is said once. */
    public static List<String> news() {
        List<String> out = new ArrayList<>();
        for (String line; (line = NEWS.poll()) != null; ) out.add(line);
        return out;
    }

    /** Drops what {@link #news} would say: for a test. */
    static void forget() {
        NEWS.clear();
    }

    /**
     * Asks the user {@code question} on screen over {@code owner} and waits for the answer, off the FX thread; a
     * question left unanswered for {@link #ASK_MINUTES} is closed and refused, so no dialog lingers that would act
     * after the call ended. Inside {@link #start}'s work, the question is the one {@link #busy} names.
     */
    public static boolean agrees(Window owner, String header, String question) {
        NOW.updateAndGet(job -> job == null ? null : new Job(job.doing(), header, Stage.ASKED));
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        AtomicReference<Alert> shown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Alert ask = ThemedWindows.alert(Alert.AlertType.CONFIRMATION, question, ButtonType.OK, ButtonType.CANCEL);
                ask.initOwner(owner);
                ask.setHeaderText(header);
                // The question pops up while the user may be typing to the assistant: an Enter meant for the
                // terminal must not answer yes. Only a click on OK, or Space on it once focused, does.
                ((Button) ask.getDialogPane().lookupButton(ButtonType.OK)).setDefaultButton(false);
                ((Button) ask.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
                shown.set(ask);
                answer.complete(ask.showAndWait().filter(b -> b == ButtonType.OK).isPresent());
            } catch (RuntimeException e) {
                answer.completeExceptionally(e);
            }
        });
        try {
            boolean yes = answer.get(ASK_MINUTES, TimeUnit.MINUTES);
            if (yes) NOW.updateAndGet(job -> job == null ? null : new Job(job.doing(), job.question(), Stage.DOING));
            return yes;
        } catch (TimeoutException e) {
            Platform.runLater(() -> {
                if (shown.get() != null) shown.get().close();
            });
            throw new IllegalArgumentException("The user did not answer in " + ASK_MINUTES + " minutes.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the user", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause().getMessage(), e.getCause());
        }
    }
}
