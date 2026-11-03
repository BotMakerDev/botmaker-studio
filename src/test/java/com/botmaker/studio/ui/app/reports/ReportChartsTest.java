package com.botmaker.studio.ui.app.reports;

import com.botmaker.cli.reports.Reports;
import com.botmaker.runlog.api.Dimension;
import com.botmaker.runlog.api.Level;
import com.botmaker.runlog.api.Occurrence;
import com.botmaker.runlog.api.Report;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Reports tab's grouping into chart series, headless. */
class ReportChartsTest {

    static final Instant DAY1 = Instant.parse("2026-10-28T09:00:00Z");
    static final String CLAIM = "com.firestone.Daily:42|com.botmaker.sdk.api.vision.Vision.find|warn";
    static final String CLAIM_ERR = "com.firestone.Daily:42|com.botmaker.sdk.api.input.Mouse.click|error";
    static final String SHOP = "com.firestone.Shop:118|com.botmaker.sdk.api.text.Ocr.read|warn";
    static final String MAP = "com.firestone.Map:77|com.botmaker.sdk.api.flow.Flow.go|error";

    static Occurrence at(int day, String fingerprint, int count, String flow) {
        String[] parts = fingerprint.split("\\|");
        Level level = Level.fromId(parts[2]);
        return new Occurrence(DAY1.plus(Duration.ofDays(day)), level, fingerprint, count,
                Map.of("botLine", parts[0], "method", parts[1], "level", level.id(), "details.Flow", flow));
    }

    /** A week of a bot's problems: Daily:42 most, Shop next, Map the fewest. */
    static Reports week() {
        List<Occurrence> all = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            all.add(at(day, CLAIM, 3 + day, "Daily rewards"));
            if (day % 2 == 0) all.add(at(day, CLAIM_ERR, 1, "Daily rewards"));
            if (day >= 2) all.add(at(day, SHOP, 2, "Shop"));
            if (day == 5) all.add(at(day, MAP, 4, "Map"));
        }
        Report report = new Report(List.of(new Dimension("botLine", "Bot line"),
                new Dimension("method", "Called method"), new Dimension("level", "Level"),
                new Dimension("details.Flow", "Flow")), all);
        return new Reports("LiQiyeDev/firestone-bot", report,
                Map.of(CLAIM, List.of(new Reports.Issue(12, true, "Vision.find missed at Daily:42")),
                        MAP, List.of(new Reports.Issue(3, false, "Flow.go failed at Map:77"),
                                new Reports.Issue(19, true, "Flow.go failed at Map:77"))),
                List.of());
    }

    @Test
    void totalsAreABarPerValueMostFirstStackedByLevel() {
        ReportCharts.Chart chart = ReportCharts.totals(week().report(), "botLine", 15);

        assertEquals(List.of("com.firestone.Daily:42", "com.firestone.Shop:118", "com.firestone.Map:77"),
                chart.categories());
        assertEquals(List.of("Warning", "Error"), chart.series().stream().map(ReportCharts.Series::name).toList());
        assertEquals(List.of(new ReportCharts.Point("com.firestone.Daily:42", 42),
                        new ReportCharts.Point("com.firestone.Shop:118", 10)),
                chart.series().getFirst().points());
        assertEquals(List.of(new ReportCharts.Point("com.firestone.Daily:42", 4),
                        new ReportCharts.Point("com.firestone.Map:77", 4)),
                chart.series().get(1).points());
    }

    @Test
    void totalsKeepOnlyTheMostMetValues() {
        assertEquals(List.of("com.firestone.Daily:42"),
                ReportCharts.totals(week().report(), "botLine", 1).categories());
    }

    @Test
    void byDayIsABarPerDayStackedByTheMostMetValuesAndTheRestAsOther() {
        ReportCharts.Chart chart = ReportCharts.byDay(week().report(), "details.Flow", 1);

        assertEquals(7, chart.categories().size());
        assertEquals("2026-10-28", chart.categories().getFirst());
        assertEquals(List.of("Daily rewards", ReportCharts.OTHER),
                chart.series().stream().map(ReportCharts.Series::name).toList());
        assertEquals(new ReportCharts.Point("2026-10-28", 4), chart.series().getFirst().points().getFirst());
        assertEquals(new ReportCharts.Point("2026-11-02", 6), chart.series().get(1).points().get(3));
    }

    @Test
    void aValuesRowsAreItsFingerprintsMostFirstWithTheirIssues() {
        List<ReportCharts.Row> rows = ReportCharts.rows(week(), "botLine", "com.firestone.Daily:42");

        assertEquals(List.of(CLAIM, CLAIM_ERR), rows.stream().map(ReportCharts.Row::fingerprint).toList());
        assertEquals(42, rows.getFirst().count());
        assertEquals(List.of(12), rows.getFirst().issues().stream().map(Reports.Issue::number).toList());
        assertEquals(List.of(), rows.get(1).issues());
    }

    @Test
    void aFingerprintNamesItsBotLineOrNothing() {
        assertEquals(Optional.of(Map.entry("com.firestone.Daily", 42)), ReportCharts.botLine(CLAIM));
        assertEquals(Optional.empty(), ReportCharts.botLine("unknown|x.y|warn"));
        assertEquals(Optional.empty(), ReportCharts.botLine("com.x.Bot:abc|x.y|warn"));
        assertEquals(Optional.empty(), ReportCharts.botLine("/home/u/other/Secret:1|x.y|warn"));
        assertEquals(Optional.empty(), ReportCharts.botLine("com.x.Bot:-5|x.y|warn"));
    }

    @Test
    void aValueNamedOtherStaysItsOwnSeriesBesideTheRest() {
        List<Occurrence> all = new ArrayList<>();
        all.add(new Occurrence(DAY1, Level.WARN, "a", 5, Map.of("f", ReportCharts.OTHER)));
        all.add(new Occurrence(DAY1, Level.WARN, "b", 1, Map.of("f", "Shop")));
        ReportCharts.Chart chart = ReportCharts.byDay(new Report(List.of(new Dimension("f", "F")), all), "f", 1);

        assertEquals(List.of(new ReportCharts.Series(ReportCharts.OTHER, List.of(new ReportCharts.Point("2026-10-28", 5))),
                new ReportCharts.Series(ReportCharts.OTHER, List.of(new ReportCharts.Point("2026-10-28", 1)), true)),
                chart.series());
    }

    @Test
    void aRowReadsAsLevelLineAndMethodShort() {
        ReportCharts.Row row = ReportCharts.rows(week(), "botLine", "com.firestone.Map:77").getFirst();

        assertEquals("Error · Map:77 · Flow.go ×4 #3 #19", ReportsPanel.rowText(row));
    }
}
