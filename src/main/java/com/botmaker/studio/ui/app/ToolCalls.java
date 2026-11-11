package com.botmaker.studio.ui.app;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.event.CoreEvent.ToolCallEnded;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Part;
import com.botmaker.core.event.CoreEvent.ToolCallProgress;
import com.botmaker.core.event.CoreEvent.ToolCallStarted;
import com.botmaker.core.event.CoreEvent.ToolCallStep;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * The MCP calls the core told this window ({@code Request.WatchCalls}), each folded from its events: started, its device
 * steps, its last progress, its end. Newest last, the last {@link #KEPT} only, as the core keeps them. One list, which
 * the Calls pane filters ({@link #view}); the tool checklist is counted from it ({@link #tallies}). Not thread-safe: the
 * window folds on the FX thread.
 */
final class ToolCalls {

    /** As many as the core keeps (`ToolCallLog`). */
    static final int KEPT = 500;
    /** A call's steps kept, as the core keeps them (`ToolCallLog.STEPS_KEPT`). */
    static final int STEPS_KEPT = 200;
    /** How far back {@link Sort#LAST_5_MIN} counts. */
    static final long RECENT_MILLIS = 5 * 60_000;
    /** The plugin a core tool names: none. */
    static final String CORE = "";
    /** The core's own device tool (DeviceTools), whose screenshot is a frame a tap is drawn on. */
    static final String DEVICE_TOOL = "device";

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

    /** How the tool checklist is ordered (spec: the most used first by default). */
    enum Sort {
        MOST_CALLED("most", "Most called"),
        LAST_5_MIN("recent", "Last 5 min"),
        BY_PLUGIN("plugin", "By plugin"),
        A_Z("name", "A–Z");

        private final String id;
        private final String displayName;

        Sort(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        String id() {
            return id;
        }

        String displayName() {
            return displayName;
        }

        /** The sort {@code id} names; {@link #MOST_CALLED}, the default, for any other. */
        static Sort fromId(String id) {
            for (Sort sort : values()) if (sort.id.equals(id)) return sort;
            return MOST_CALLED;
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

        String tool() {
            return started.tool();
        }

        String plugin() {
            return started.plugin();
        }

        Status status() {
            if (ended == null) return Status.RUNNING;
            return switch (ended.outcome()) {
                case OK -> Status.OK;
                case REFUSED -> Status.REFUSED;
                case ERROR -> Status.ERROR;
            };
        }

        /** The instances its steps and progress touched, in order. */
        Set<String> devices() {
            Set<String> out = new LinkedHashSet<>();
            for (ToolCallStep step : steps) if (!step.device().isEmpty()) out.add(step.device());
            if (progress != null && !progress.device().isEmpty()) out.add(progress.device());
            return out;
        }

        /** Its reply's first image, if it ended with one. */
        Optional<Part> image() {
            if (ended == null) return Optional.empty();
            return ended.reply().stream().filter(part -> part.kind() == Part.Kind.IMAGE).findFirst();
        }

        /** Its reply's texts, one after the other. */
        String replyText() {
            if (ended == null) return "";
            StringBuilder text = new StringBuilder();
            for (Part part : ended.reply()) if (part.kind() == Part.Kind.TEXT) text.append(part.value()).append('\n');
            return text.toString();
        }

        private Call with(ToolCallStep step) {
            if (steps.size() >= STEPS_KEPT) return new Call(started, steps, moreSteps + 1, progress, ended);
            List<ToolCallStep> more = new ArrayList<>(steps);
            more.add(step);
            return new Call(started, more, moreSteps, progress, ended);
        }
    }

    /** A tool's line in the checklist: its plugin, how often it was called (all, and lately), whether it runs now. */
    record Tally(String tool, String plugin, int count, int recent, boolean running) {}

    private final ObservableList<Call> list = FXCollections.observableArrayList();
    private final ObservableList<Call> view = FXCollections.unmodifiableObservableList(list);
    /** Each kept call's place: its number in arrival order, so its index is that less the calls dropped. */
    private final Map<Long, Long> order = new HashMap<>();
    private long arrived;
    private long dropped;

    /** Folds {@code event} in; the call it changed, or null for an event about no call this list has. */
    Call told(CoreEvent event) {
        return switch (event) {
            case ToolCallStarted started -> add(new Call(started, List.of(), 0, null, null));
            case ToolCallStep step -> at(step.call(), call -> call.with(step));
            case ToolCallProgress progress -> at(progress.call(),
                    call -> new Call(call.started(), call.steps(), call.moreSteps(), progress, call.ended()));
            case ToolCallEnded ended -> at(ended.call(),
                    call -> new Call(call.started(), call.steps(), call.moreSteps(), null, ended));
            default -> null;
        };
    }

    private Call add(Call call) {
        if (order.containsKey(call.id())) return at(call.id(), was -> call);
        order.put(call.id(), arrived++);
        list.add(call);
        while (list.size() > KEPT) {
            order.remove(list.getFirst().id());
            list.removeFirst();
            dropped++;
        }
        return call;
    }

    /** The call kept as {@code id}, if it still is. */
    Optional<Call> find(long id) {
        Long number = order.get(id);
        return number == null ? Optional.empty() : Optional.of(list.get((int) (number - dropped)));
    }

    private Call at(long id, UnaryOperator<Call> change) {
        Long number = order.get(id);
        if (number == null) return null;
        int index = (int) (number - dropped);
        Call changed = change.apply(list.get(index));
        list.set(index, changed);
        return changed;
    }

    /** Every call kept, newest last, as the Calls pane shows them; read-only. */
    ObservableList<Call> view() {
        return view;
    }

    /** Every call kept, newest last. */
    List<Call> all() {
        return List.copyOf(list);
    }

    /** Forgets every call: the core restarted, and its history with it. */
    void clear() {
        list.clear();
        order.clear();
        arrived = 0;
        dropped = 0;
    }

    /** Each tool called, ordered by {@code sort}; {@code now} is what {@link Sort#LAST_5_MIN} counts back from. */
    List<Tally> tallies(Sort sort, long now) {
        Map<String, Tally> byTool = new LinkedHashMap<>();
        for (Call call : list) {
            Tally was = byTool.getOrDefault(call.tool(), new Tally(call.tool(), call.plugin(), 0, 0, false));
            boolean recent = call.started().atMillis() >= now - RECENT_MILLIS;
            byTool.put(call.tool(), new Tally(call.tool(), call.plugin(), was.count() + 1,
                    was.recent() + (recent ? 1 : 0), was.running() || call.status() == Status.RUNNING));
        }
        Comparator<Tally> byName = Comparator.comparing(Tally::tool);
        Comparator<Tally> order = switch (sort) {
            case MOST_CALLED -> Comparator.comparingInt(Tally::count).reversed().thenComparing(byName);
            case LAST_5_MIN -> Comparator.comparingInt(Tally::recent).reversed()
                    .thenComparing(Comparator.comparingInt(Tally::count).reversed()).thenComparing(byName);
            // The core's first, then each plugin by name; within one, the most called first.
            case BY_PLUGIN -> Comparator.comparing((Tally t) -> !t.plugin().equals(CORE))
                    .thenComparing(Tally::plugin)
                    .thenComparing(Comparator.comparingInt(Tally::count).reversed()).thenComparing(byName);
            case A_Z -> byName;
        };
        return byTool.values().stream().sorted(order).toList();
    }

    /** The plugins whose tools were called, the core ({@link #CORE}) first if any of its were. */
    List<String> plugins() {
        Set<String> named = new TreeSet<>(Comparator.comparing((String p) -> !p.equals(CORE)).thenComparing(p -> p));
        for (Call call : list) named.add(call.plugin());
        return List.copyOf(named);
    }

    /** The instances any call touched, by name. */
    List<String> devices() {
        Set<String> named = new TreeSet<>();
        for (Call call : list) named.addAll(call.devices());
        return List.copyOf(named);
    }

    /**
     * The frame a tap of {@code call} shows on: its own reply's image, else the first later call's on an instance it
     * touched (a screenshot taken after it). Empty while none has come.
     */
    Optional<Framed> frameAfter(Call call) {
        Optional<Part> own = call.image();
        if (own.isPresent()) return Optional.of(new Framed(call, own.get()));
        Set<String> touched = call.devices();
        Long number = order.get(call.id());
        if (number == null || touched.isEmpty()) return Optional.empty();
        for (int i = (int) (number - dropped) + 1; i < list.size(); i++) {
            Call later = list.get(i);
            // Only the core's own device screenshot: it is the whole screen at its size, the frame a point is in. A
            // plugin's may be scaled or a part, and a point drawn on it would land elsewhere.
            if (!later.tool().equals(DEVICE_TOOL) || !later.plugin().equals(CORE)) continue;
            Optional<Part> image = later.image();
            if (image.isPresent() && later.devices().stream().anyMatch(touched::contains)) {
                return Optional.of(new Framed(later, image.get()));
            }
        }
        return Optional.empty();
    }

    /** An image, and the call whose reply it is. */
    record Framed(Call call, Part image) {}
}
