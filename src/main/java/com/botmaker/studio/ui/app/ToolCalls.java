package com.botmaker.studio.ui.app;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.event.CoreEvent.ToolCallEnded;
import com.botmaker.core.event.CoreEvent.ToolCallProgress;
import com.botmaker.core.event.CoreEvent.ToolCallStarted;
import com.botmaker.core.event.CoreEvent.ToolCallStep;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The MCP calls the core told this window ({@code Request.WatchCalls}), each folded from its events: started, its device
 * steps, its last progress, its end. Newest last, the last {@link #KEPT} only, as the core keeps them. Not thread-safe:
 * the window folds on the FX thread.
 */
final class ToolCalls {

    /** As many as the core keeps (`ToolCallLog`). */
    static final int KEPT = 500;
    /** A call's steps kept, as the core keeps them (`ToolCallLog.STEPS_KEPT`). */
    static final int STEPS_KEPT = 200;

    /** Where a call is. */
    enum Status {
        RUNNING("◌", "running"),
        OK("✓", "OK"),
        REFUSED("⊘", "refused"),
        ERROR("✗", "error");

        private final String mark;
        private final String displayName;

        Status(String mark, String displayName) {
            this.mark = mark;
            this.displayName = displayName;
        }

        String mark() {
            return mark;
        }

        String displayName() {
            return displayName;
        }
    }

    /**
     * One call as told so far; {@code ended} null while it runs, {@code progress} null until a step reports one. Its
     * first {@link #STEPS_KEPT} steps are kept, {@code moreSteps} counts the rest.
     */
    record Call(ToolCallStarted started, List<ToolCallStep> steps, long moreSteps, ToolCallProgress progress,
                ToolCallEnded ended) {
        Call {
            steps = List.copyOf(steps);
        }

        long id() {
            return started.call();
        }

        Status status() {
            if (ended == null) return Status.RUNNING;
            return switch (ended.outcome()) {
                case OK -> Status.OK;
                case REFUSED -> Status.REFUSED;
                case ERROR -> Status.ERROR;
            };
        }

        private Call with(ToolCallStep step) {
            if (steps.size() >= STEPS_KEPT) return new Call(started, steps, moreSteps + 1, progress, ended);
            List<ToolCallStep> more = new ArrayList<>(steps);
            more.add(step);
            return new Call(started, more, moreSteps, progress, ended);
        }
    }

    private final Map<Long, Call> calls = new LinkedHashMap<>();

    /** Folds {@code event} in; the call it changed, or null for an event about no call this list has. */
    Call told(CoreEvent event) {
        Call changed = switch (event) {
            case ToolCallStarted started -> new Call(started, List.of(), 0, null, null);
            case ToolCallStep step -> at(step.call(), call -> call.with(step));
            case ToolCallProgress progress -> at(progress.call(),
                    call -> new Call(call.started(), call.steps(), call.moreSteps(), progress, call.ended()));
            case ToolCallEnded ended -> at(ended.call(),
                    call -> new Call(call.started(), call.steps(), call.moreSteps(), null, ended));
            default -> null;
        };
        if (changed == null) return null;
        calls.put(changed.id(), changed);
        for (Iterator<Long> oldest = calls.keySet().iterator(); calls.size() > KEPT; ) {
            oldest.next();
            oldest.remove();
        }
        return changed;
    }

    private Call at(long id, UnaryOperator<Call> change) {
        Call call = calls.get(id);
        return call == null ? null : change.apply(call);
    }

    /** Every call kept, newest last. */
    List<Call> all() {
        return List.copyOf(calls.values());
    }

    /** Forgets every call: the core restarted, and its history with it. */
    void clear() {
        calls.clear();
    }
}
