package com.botmaker.studio.ui.app.reports;

import com.botmaker.cli.reports.Reports;
import com.botmaker.runlog.api.Counts;
import com.botmaker.runlog.api.Level;
import com.botmaker.runlog.api.Occurrence;
import com.botmaker.runlog.api.Report;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The Reports tab's charts as data, free of JavaFX (run reports spec §5): a report grouped by one of the dimensions
 * it names, counted through {@link Counts} as the CLI and Claude's tools count.
 */
final class ReportCharts {

    /** The series of the values past the most met, over time. */
    static final String OTHER = "Other";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private ReportCharts() {
    }

    /** One bar's part: {@code count} of a series at {@code category}. */
    record Point(String category, int count) {
    }

    /**
     * One stacked series.
     *
     * @param name   what the legend says
     * @param points its parts, in {@code categories} order, zeros left out
     * @param rest   whether it sums the values past the most met ({@value #OTHER}), not one value: a runner's value
     *               may be named "Other" too
     */
    record Series(String name, List<Point> points, boolean rest) {
        Series {
            points = List.copyOf(points);
        }

        Series(String name, List<Point> points) {
            this(name, points, false);
        }
    }

    /**
     * A chart: its categories in order and its series.
     *
     * @param categories what each bar is, in the order drawn
     * @param series     what each bar is made of
     */
    record Chart(List<String> categories, List<Series> series) {
        Chart {
            categories = List.copyOf(categories);
            series = List.copyOf(series);
        }
    }

    /**
     * One problem of a value: a fingerprint, how often it was met, and the issues it was reported in.
     *
     * @param fingerprint its identity ({@code class:line|method|level})
     * @param level       its level
     * @param count       how often
     * @param issues      its issues, oldest first; none for run logs
     */
    record Row(String fingerprint, Level level, int count, List<Reports.Issue> issues) {
        Row {
            issues = List.copyOf(issues);
        }
    }

    /** The {@code most} most met values of {@code dimension}, most first, each a bar stacked by level. */
    static Chart totals(Report report, String dimension, int most) {
        List<Counts.Count> counts = Counts.by(report, dimension);
        List<Counts.Count> kept = counts.subList(0, Math.min(most, counts.size()));
        List<Series> series = new ArrayList<>();
        for (Level level : Level.values()) {
            List<Point> points = kept.stream()
                    .filter(c -> c.byLevel().getOrDefault(level, 0) > 0)
                    .map(c -> new Point(c.value(), c.byLevel().get(level))).toList();
            if (!points.isEmpty()) series.add(new Series(level.displayName(), points));
        }
        return new Chart(kept.stream().map(Counts.Count::value).toList(), series);
    }

    /**
     * Each day (UTC) a bar, oldest first, stacked by the {@code most} most met values of {@code dimension}
     * overall; the rest summed as {@value #OTHER}.
     */
    static Chart byDay(Report report, String dimension, int most) {
        Set<String> top = Counts.by(report, dimension).stream().limit(most).map(Counts.Count::value)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<Point>> per = new LinkedHashMap<>();
        top.forEach(v -> per.put(v, new ArrayList<>()));
        List<Point> rest = new ArrayList<>();
        List<String> days = new ArrayList<>();
        for (Counts.Bucket bucket : Counts.overTime(report, dimension, ChronoUnit.DAYS)) {
            String day = DAY.format(bucket.start());
            days.add(day);
            int other = 0;
            for (Counts.Count c : bucket.counts()) {
                if (top.contains(c.value())) per.get(c.value()).add(new Point(day, c.total()));
                else other += c.total();
            }
            if (other > 0) rest.add(new Point(day, other));
        }
        List<Series> series = new ArrayList<>();
        per.forEach((name, points) -> {
            if (!points.isEmpty()) series.add(new Series(name, points));
        });
        if (!rest.isEmpty()) series.add(new Series(OTHER, rest, true));
        return new Chart(days, series);
    }

    /** The problems whose {@code dimension} is {@code value}, most met first, with their issues. */
    static List<Row> rows(Reports reports, String dimension, String value) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Level> levels = new LinkedHashMap<>();
        for (Occurrence o : reports.report().occurrences()) {
            if (!o.value(dimension).equals(value)) continue;
            counts.merge(o.fingerprint(), o.count(), Integer::sum);
            levels.putIfAbsent(o.fingerprint(), o.level());
        }
        return counts.entrySet().stream()
                .map(e -> new Row(e.getKey(), levels.get(e.getKey()), e.getValue(),
                        reports.issues().getOrDefault(e.getKey(), List.of())))
                .sorted(Comparator.comparingInt(Row::count).reversed().thenComparing(Row::fingerprint))
                .toList();
    }

    /**
     * The bot class and line a fingerprint names ({@code com.x.Bot:42|…}); empty when it names no line. A
     * fingerprint is runner-written: what this gives is only looked up as a file of the project.
     */
    static Optional<Map.Entry<String, Integer>> botLine(String fingerprint) {
        int bar = fingerprint.indexOf('|');
        String at = bar < 0 ? fingerprint : fingerprint.substring(0, bar);
        int colon = at.lastIndexOf(':');
        if (colon <= 0) return Optional.empty();
        String className = at.substring(0, colon);
        String line = at.substring(colon + 1);
        // A Java class name and a positive line only: a forged "/elsewhere/File:1" names no file of the bot.
        if (!JAVA_NAME.matcher(className).matches() || !line.matches("[1-9]\\d{0,8}")) return Optional.empty();
        return Optional.of(Map.entry(className, Integer.parseInt(line)));
    }

    private static final Pattern JAVA_NAME =
            Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*");
}
