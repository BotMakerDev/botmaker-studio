package com.botmaker.studio.ui.app.reports;

import com.botmaker.cli.reports.Reports;
import com.botmaker.runlog.api.Counts;
import com.botmaker.runlog.api.Level;
import com.botmaker.runlog.api.Occurrence;
import com.botmaker.runlog.api.Report;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
        Map<String, Counts.Bucket> buckets = buckets(report, dimension);
        List<String> days = everyDay(buckets);
        for (String day : days) {
            Counts.Bucket bucket = buckets.get(day);
            if (bucket == null) continue;
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

    /** {@link #byDay} as lines: every series has a point on every day, zero where it was not met. */
    static Chart lines(Report report, String dimension, int most) {
        Chart bars = byDay(report, dimension, most);
        List<Series> filled = bars.series().stream().map(s -> {
            Map<String, Integer> at = s.points().stream()
                    .collect(Collectors.toMap(Point::category, Point::count, Integer::sum));
            return new Series(s.name(), bars.categories().stream()
                    .map(day -> new Point(day, at.getOrDefault(day, 0))).toList(), s.rest());
        }).toList();
        return new Chart(bars.categories(), filled);
    }

    /**
     * A grid of counts: a row per value, a column per day.
     *
     * @param values the {@code most} most met values, most first
     * @param days   each day (UTC) met, oldest first
     * @param counts {@code counts.get(value).get(day)}, zero where not met
     */
    record Heat(List<String> values, List<String> days, List<List<Integer>> counts) {
        Heat {
            values = List.copyOf(values);
            days = List.copyOf(days);
            counts = counts.stream().map(List::copyOf).toList();
        }

        /** The largest cell, which is the darkest. */
        int max() {
            return counts.stream().flatMap(List::stream).mapToInt(Integer::intValue).max().orElse(0);
        }
    }

    /**
     * The {@code most} most met values of {@code dimension}, each counted per day (UTC) over the last
     * {@code lastDays} days that end on the latest met: a cell is a node, so a year of history is not drawn whole.
     */
    static Heat heatmap(Report report, String dimension, int most, int lastDays) {
        List<String> top = Counts.by(report, dimension).stream().limit(most).map(Counts.Count::value).toList();
        Map<String, Counts.Bucket> buckets = buckets(report, dimension);
        List<Counts.Bucket> met = List.copyOf(buckets.values());
        List<String> days = met.isEmpty() ? List.of()
                : lastDays(met.getFirst().start(), met.getLast().start(), Math.max(1, lastDays));
        List<List<Integer>> counts = top.stream().map(value -> days.stream()
                .map(day -> buckets.containsKey(day) ? buckets.get(day).counts().stream()
                        .filter(c -> c.value().equals(value)).mapToInt(Counts.Count::total).sum() : 0)
                .toList()).toList();
        return new Heat(top, days, counts);
    }

    /** Each day (UTC) met, keyed by its {@code yyyy-MM-dd}. */
    private static Map<String, Counts.Bucket> buckets(Report report, String dimension) {
        Map<String, Counts.Bucket> byDay = new LinkedHashMap<>();
        Counts.overTime(report, dimension, ChronoUnit.DAYS).forEach(b -> byDay.put(DAY.format(b.start()), b));
        return byDay;
    }

    /**
     * Every day from the first met to the last, the quiet ones too, so a gap reads as zero, not as nothing. Past
     * {@value #FILLED_DAYS} days, only the days met: a time is a runner's, and a forged year 1 or 9999 must not
     * make millions of days.
     */
    private static List<String> everyDay(Map<String, Counts.Bucket> buckets) {
        if (buckets.isEmpty()) return List.of();
        List<Counts.Bucket> met = List.copyOf(buckets.values());
        if (ChronoUnit.DAYS.between(met.getFirst().start(), met.getLast().start()) >= FILLED_DAYS) {
            return List.copyOf(buckets.keySet());
        }
        return lastDays(met.getFirst().start(), met.getLast().start(), FILLED_DAYS);
    }

    /** The days from {@code first} to {@code last}, at most the latest {@code most}. */
    private static List<String> lastDays(Instant first, Instant last, int most) {
        Instant from = last.minus(most - 1L, ChronoUnit.DAYS);
        List<String> days = new ArrayList<>();
        for (Instant day = first.isAfter(from) ? first : from; !day.isAfter(last); day = day.plus(1, ChronoUnit.DAYS)) {
            days.add(DAY.format(day));
        }
        return days;
    }

    /** Days a chart over time fills with quiet ones at most. */
    static final int FILLED_DAYS = 366;

    /**
     * One slice of the donut.
     *
     * @param rest whether it sums the values past the most met ({@value #OTHER})
     */
    record Slice(String value, int count, boolean rest) {
    }

    /** The {@code most} most met values of {@code dimension} as slices, most first, the rest as one more. */
    static List<Slice> share(Report report, String dimension, int most) {
        List<Counts.Count> counts = Counts.by(report, dimension);
        List<Slice> slices = new ArrayList<>(counts.stream().limit(most)
                .map(c -> new Slice(c.value(), c.total(), false)).toList());
        int rest = counts.stream().skip(most).mapToInt(Counts.Count::total).sum();
        if (rest > 0) slices.add(new Slice(OTHER, rest, true));
        return slices;
    }

    /**
     * One value's line of the table.
     *
     * @param firstSeen its earliest occurrence
     * @param lastSeen  its latest occurrence
     * @param issues    how many distinct issues its problems were reported in
     */
    record TableRow(String value, int warnings, int errors, int total, Instant firstSeen, Instant lastSeen,
                    int issues) {
    }

    /** Every value of {@code dimension}, most met first. */
    static List<TableRow> table(Reports reports, String dimension) {
        Map<String, Instant> first = new HashMap<>();
        Map<String, Instant> last = new HashMap<>();
        Map<String, Set<Integer>> issues = new HashMap<>();
        for (Occurrence o : reports.report().occurrences()) {
            String value = o.value(dimension);
            first.merge(value, o.time(), (a, b) -> a.isBefore(b) ? a : b);
            last.merge(value, o.time(), (a, b) -> a.isAfter(b) ? a : b);
            Set<Integer> numbers = issues.computeIfAbsent(value, v -> new HashSet<>());
            reports.issues().getOrDefault(o.fingerprint(), List.of()).forEach(i -> numbers.add(i.number()));
        }
        return Counts.by(reports.report(), dimension).stream()
                .map(c -> new TableRow(c.value(), c.byLevel().getOrDefault(Level.WARN, 0),
                        c.byLevel().getOrDefault(Level.ERROR, 0), c.total(), first.get(c.value()),
                        last.get(c.value()), issues.getOrDefault(c.value(), Set.of()).size()))
                .toList();
    }

    /** {@code yyyy-MM-dd}, UTC, as the days are drawn. */
    static String day(Instant at) {
        return at == null ? "" : DAY.format(at);
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
