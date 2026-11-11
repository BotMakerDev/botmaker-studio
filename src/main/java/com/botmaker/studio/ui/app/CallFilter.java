package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.app.ToolCalls.Call;
import com.botmaker.studio.ui.app.ToolCalls.Status;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which calls the Calls pane shows (spec {@code 2026-11-11-assistant-window-design.md}): the tools left unticked in
 * the checklist are hidden (so a tool called for the first time shows), and a plugin, a device, a status and a search
 * each narrow it when set. A value: each change is a new filter.
 *
 * @param unticked the tools the checklist hides, by MCP name
 * @param plugin   the plugin a call's tool must be ({@link ToolCalls#CORE} for the core's), or any
 * @param device   an instance the call touched, or any
 * @param status   where the call is, or any
 * @param search   words found in its tool, arguments or reply text, any case; blank for any
 */
record CallFilter(Set<String> unticked, Optional<String> plugin, Optional<String> device, Optional<Status> status,
                  String search) implements Predicate<Call> {

    /** Every call. */
    static final CallFilter ALL = new CallFilter(Set.of(), Optional.empty(), Optional.empty(), Optional.empty(), "");

    CallFilter {
        unticked = Set.copyOf(unticked);
        search = search == null ? "" : search.strip();
    }

    @Override
    public boolean test(Call call) {
        if (unticked.contains(call.tool())) return false;
        if (plugin.isPresent() && !plugin.get().equals(call.plugin())) return false;
        if (device.isPresent() && !call.devices().contains(device.get())) return false;
        if (status.isPresent() && status.get() != call.status()) return false;
        if (search.isEmpty()) return true;
        String words = search.toLowerCase(Locale.ROOT);
        return call.tool().toLowerCase(Locale.ROOT).contains(words)
                || call.started().arguments().toLowerCase(Locale.ROOT).contains(words)
                || call.replyText().toLowerCase(Locale.ROOT).contains(words);
    }

    boolean ticks(String tool) {
        return !unticked.contains(tool);
    }

    /** {@code tool} ticked if it was not, unticked if it was. */
    CallFilter toggled(String tool) {
        return ticking(Set.of(tool), !ticks(tool));
    }

    /** {@code tools} all ticked, or all unticked: a plugin's box in the checklist. */
    CallFilter ticking(Collection<String> tools, boolean ticked) {
        Set<String> now = new HashSet<>(unticked);
        if (ticked) now.removeAll(tools);
        else now.addAll(tools);
        return new CallFilter(now, plugin, device, status, search);
    }

    /** {@code tool} alone ticked of {@code every} tool: the checklist's <em>Only</em>. */
    CallFilter only(String tool, Collection<String> every) {
        Set<String> now = new HashSet<>(every);
        now.remove(tool);
        return new CallFilter(now, plugin, device, status, search);
    }

    /** Every tool ticked: the checklist's <em>All</em>. */
    CallFilter allTicked() {
        return new CallFilter(Set.of(), plugin, device, status, search);
    }

    /** Each of {@code every} tool unticked: the checklist's <em>None</em>. */
    CallFilter noneTicked(Collection<String> every) {
        return new CallFilter(Set.copyOf(every), plugin, device, status, search);
    }

    CallFilter withPlugin(Optional<String> plugin) {
        return new CallFilter(unticked, plugin, device, status, search);
    }

    CallFilter withDevice(Optional<String> device) {
        return new CallFilter(unticked, plugin, device, status, search);
    }

    CallFilter withStatus(Optional<Status> status) {
        return new CallFilter(unticked, plugin, device, status, search);
    }

    CallFilter withSearch(String search) {
        return new CallFilter(unticked, plugin, device, status, search);
    }
}
