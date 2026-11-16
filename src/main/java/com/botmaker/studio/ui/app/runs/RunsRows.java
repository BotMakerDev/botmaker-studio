package com.botmaker.studio.ui.app.runs;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.run.claims.LiveRunsView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The table of a project's Runs window (plan phase 14, the user's pick P-A): this bot's live runs, then the rows a
 * count dealt for the runs asked for, then a row per run that could not be dealt, then the other bots' runs holding a
 * plugin's element this bot's rows need, greyed. One column per pooled parameter. What a row picks is the core's;
 * this only lays it out, and keeps the pins the user clicked.
 */
public final class RunsRows {

    /** What a row of the table is. */
    public enum Kind { LIVE, READY, MISSING, OTHER }

    /**
     * One row.
     *
     * @param index for a ready row, its row in the count from 0, which a pin names; -1 otherwise
     * @param picks by column key, the elements as the grammar writes them
     */
    public record Row(Kind kind, int index, Map<String, List<String>> picks, String status) {
        public Row {
            picks = Map.copyOf(picks);
        }
    }

    private final List<String> columns;
    private final List<Row> rows;

    private RunsRows(List<String> columns, List<Row> rows) {
        this.columns = List.copyOf(columns);
        this.rows = List.copyOf(rows);
    }

    /**
     * The table of {@code mine} (empty when none of this bot's runs is live), {@code counted} (empty before a count)
     * for {@code asked} more runs, and {@code others}' runs.
     */
    public static RunsRows of(Optional<LiveRunsView.Group> mine, Optional<CoreEvent.RunsStarted> counted, int asked,
                              List<LiveRunsView.Group> others, long nowMillis) {
        Set<String> columns = new LinkedHashSet<>();
        List<Row> rows = new ArrayList<>();
        for (LiveRunsView.Live run : mine.map(LiveRunsView.Group::runs).orElse(List.of())) {
            columns.addAll(run.picks().keySet());
            rows.add(new Row(Kind.LIVE, -1, run.picks(), "Running · " + since(run.startedMillis(), nowMillis)));
        }
        if (counted.isPresent()) {
            List<CoreEvent.RunsStarted.Started> ready = counted.get().runs();
            for (CoreEvent.RunsStarted.Started row : ready) {
                columns.addAll(row.picks().keySet());
                rows.add(new Row(Kind.READY, row.row() - 1, row.picks(), "Ready"));
            }
            for (int missing = ready.size(); missing < asked; missing++) {
                rows.add(new Row(Kind.MISSING, -1, Map.of(), "Not dealt"));
            }
        }
        for (LiveRunsView.Group other : others) {
            for (LiveRunsView.Live run : other.runs()) {
                Map<String, List<String>> held = new LinkedHashMap<>();
                for (String column : columns) {
                    if (run.holdsPluginElement(column)) held.put(column, run.picks().get(column));
                }
                if (!held.isEmpty()) rows.add(new Row(Kind.OTHER, -1, held, "Held by " + other.name()));
            }
        }
        return new RunsRows(List.copyOf(columns), rows);
    }

    /** The pooled parameters, as the picks name them, in the order they first come. */
    public List<String> columns() {
        return columns;
    }

    public List<Row> rows() {
        return rows;
    }

    /** How many rows the count dealt. */
    public int ready() {
        return (int) rows.stream().filter(row -> row.kind() == Kind.READY).count();
    }

    /** What a column is headed: {@code account()} for {@code com.acme.params.bot.Farm#account}. */
    public static String heading(String column) {
        int hash = column.lastIndexOf('#');
        if (hash >= 0) return column.substring(hash + 1) + "()";
        int dot = column.lastIndexOf('.');
        return dot >= 0 ? column.substring(dot + 1) : column;
    }

    /** {@code Farm} for {@code com.acme.params.bot.Farm#account}; empty for a column that names no class. */
    public static Optional<String> owner(String column) {
        int hash = column.lastIndexOf('#');
        if (hash < 0) return Optional.empty();
        String owner = column.substring(0, hash);
        return Optional.of(owner.substring(owner.lastIndexOf('.') + 1));
    }

    private static final Pattern ONE_STRING = Pattern.compile("^[^\"]*\"((?:[^\"\\\\]|\\\\.)*)\"[^\"]*$");

    /** An element as the table shows it: the one string it names, {@code LDPlayer-1} for {@code Instance.of("LDPlayer-1")}. */
    public static String shown(String element) {
        Matcher one = ONE_STRING.matcher(element);
        return one.matches() ? one.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : element;
    }

    /** {@code 42 min}, {@code 1 h 05}: how long a run has run. */
    public static String since(long startedMillis, long nowMillis) {
        if (startedMillis <= 0) return "—";
        long minutes = Math.max(0, (nowMillis - startedMillis) / 60_000);
        if (minutes < 1) return "under a minute";
        if (minutes < 60) return minutes + " min";
        return minutes / 60 + " h " + String.format("%02d", minutes % 60);
    }
}
