package com.botmaker.studio.assist;

import com.botmaker.cli.reports.GitHubGet;
import com.botmaker.cli.reports.IssueReports;
import com.botmaker.cli.reports.Reports;
import com.botmaker.cli.reports.ReportsText;
import com.botmaker.cli.reports.RunReports;
import com.botmaker.runlog.ReportTarget;
import com.botmaker.runlog.RunLogs;
import com.botmaker.runlog.api.Level;
import com.botmaker.runlog.api.LogLine;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Past runs as an assistant reads them: one run's {@code run.jsonl} ({@code read_trace} with {@code run}) through
 * {@link RunLogs}, and the bot's reports ({@code reports}) through the CLI's {@code com.botmaker.cli.reports}, from
 * its GitHub issues or its run logs.
 */
final class PastRuns {

    /** Where a project's runs are kept, one folder a run; the runlog's writer, named in full past assist's RunLog. */
    static final Path RUNS = com.botmaker.runlog.RunLog.FOLDER;
    private static final Pattern RUN_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private static final int READ_LIMIT = 400;

    private PastRuns() {
    }

    /**
     * The last {@code count} lines of run {@code run} at {@code min} or above, oldest first; an
     * {@link IllegalArgumentException} naming the runs there are when it is not one of them.
     */
    static String trace(Path project, String run, Level min, int count) {
        Path log = runFolder(project, run).resolve(RunReports.LOG);
        return switch (RunLogs.read(log)) {
            case RunLogs.Unreadable why -> throw new IllegalArgumentException(why.reason());
            case RunLogs.Readable read -> {
                List<LogLine> kept = read.lines().stream()
                        .filter(l -> min == null || l.level() == Level.UNKNOWN || l.level().ordinal() >= min.ordinal())
                        .toList();
                int wanted = Math.max(1, Math.min(count, READ_LIMIT));
                List<LogLine> last = kept.subList(Math.max(0, kept.size() - wanted), kept.size());
                String head = "Run " + run + ", " + read.facts().tag() + ": " + last.size() + " of " + kept.size()
                        + " lines" + (min == null ? "" : " at " + min.id() + " or above") + ".\n";
                yield head + last.stream().map(PastRuns::line).collect(Collectors.joining("\n"));
            }
        };
    }

    /**
     * The bot's reports, counted as {@code botmaker reports} counts them: from run {@code run}'s log ({@code all}:
     * every run kept), else from repository {@code repo}'s issues, else from the project's own remote's.
     */
    static String reports(Path project, String repo, String run, String groupBy, String level, String since) {
        Level only = null;
        if (level != null && !level.isBlank()) {
            only = Level.fromId(level);
            if (!only.problem()) throw new IllegalArgumentException("level takes warn or error, not " + level + ".");
        }
        Instant from = null;
        if (since != null && !since.isBlank()) {
            try {
                from = LocalDate.parse(since.strip()).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("since takes a day as yyyy-mm-dd, not " + since + ".");
            }
        }
        Reports reports;
        if (run != null && !run.isBlank()) {
            reports = RunReports.read("all".equals(run.strip()) ? project.resolve(RUNS) : runFolder(project, run));
        } else {
            ReportTarget target = (repo != null && !repo.isBlank() ? ReportTarget.parse(repo) : ReportTarget.of(project))
                    .orElseThrow(() -> new IllegalArgumentException(repo != null && !repo.isBlank()
                            ? repo + " is not owner/repo."
                            : "The project has no GitHub remote (upstream or origin): give repo, or run."));
            reports = new IssueReports(signedIn(), IssueReports.defaultCache()).read(target);
        }
        return ReportsText.text(reports.filtered(only, from), groupBy);
    }

    private static Path runFolder(Path project, String run) {
        Path runs = project.resolve(RUNS);
        if (run == null || !RUN_NAME.matcher(run.strip()).matches() || run.strip().matches("\\.+")
                || !Files.isRegularFile(runs.resolve(run.strip()).resolve(RunReports.LOG))) {
            throw new IllegalArgumentException("No run " + run + ". The runs kept: " + runs(runs) + ".");
        }
        return runs.resolve(run.strip());
    }

    private static String runs(Path runs) {
        try (Stream<Path> list = Files.list(runs)) {
            String names = list.filter(p -> Files.isRegularFile(p.resolve(RunReports.LOG)))
                    .map(p -> p.getFileName().toString()).sorted().collect(Collectors.joining(", "));
            return names.isEmpty() ? "none" : names;
        } catch (IOException e) {
            return "none";
        }
    }

    private static String line(LogLine l) {
        StringBuilder out = new StringBuilder(TIME.format(Instant.ofEpochMilli(l.atMillis()))).append(' ')
                .append(l.level().id().toUpperCase()).append(" [").append(l.source()).append("] ").append(l.text());
        if (l.count() > 1) out.append(" (×").append(l.count()).append(')');
        if (!l.className().isEmpty()) {
            out.append("  (").append(l.className()).append(l.line() >= 0 ? ":" + l.line() : "").append(')');
        }
        return out.toString();
    }

    /** GitHub as the signed-in Studio account reads it, anonymously when nobody is signed in. */
    private static GitHubGet signedIn() {
        GitHubClient client = new GitHubClient();
        String token = new GitHubAuth().token();
        return path -> {
            try {
                return client.getOrFail(GitHubConfig.API_BASE + "/" + path, token).get(30, TimeUnit.SECONDS)
                        .toString();
            } catch (ExecutionException e) {
                throw new IOException(e.getCause() == null ? e.getMessage() : e.getCause().getMessage(), e);
            } catch (TimeoutException e) {
                throw new IOException("GitHub did not answer in 30 s", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        };
    }
}
