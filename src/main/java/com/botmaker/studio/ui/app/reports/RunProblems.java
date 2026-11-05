package com.botmaker.studio.ui.app.reports;

import com.botmaker.core.runtime.RunFolders;
import com.botmaker.runlog.Problem;
import com.botmaker.runlog.Problems;
import com.botmaker.runlog.ReportTarget;
import com.botmaker.runlog.RunLog;
import com.botmaker.runlog.RunLogs;
import com.botmaker.runlog.Scrubber;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.reports.ExportLogs;
import com.botmaker.shared.reports.IssueApi;
import com.botmaker.shared.reports.IssueSender;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A project's runs as reports (run reports spec §3), free of JavaFX: each run's problems as the issue texts they
 * would be sent as, scrubbed; sending them as the signed-in Studio account; and what was sent, so a run's problems
 * are not offered twice. The GitHub token is read here, in code, and goes nowhere but GitHub.
 */
public final class RunProblems {

    /** The file of a run's folder that lists the fingerprints sent from it, one a line. */
    public static final String SENT = "reports-sent.txt";

    private RunProblems() {
    }

    /**
     * One problem of a run, ready to show and send.
     *
     * @param problem the problem
     * @param report  its texts, scrubbed
     * @param sent    whether it was sent from this run already
     */
    public record Draft(Problem problem, IssueSender.Report report, boolean sent) {
    }

    /** The project's runs that kept a log, newest first. */
    public static List<Path> runs(Path project) {
        return RunFolders.runs(project);
    }

    /** The project's newest run, when one kept a log. */
    public static Optional<Path> latest(Path project) {
        return RunFolders.latest(project);
    }

    /** {@code run}'s problems as reports; none when its log does not read. */
    public static List<Draft> drafts(Path run) {
        if (!(RunLogs.read(run.resolve(RunLog.FILE)) instanceof RunLogs.Readable read)) return List.of();
        Set<String> sent = sent(run);
        Scrubber scrubber = scrubber();
        return Problems.of(read.lines()).stream()
                .map(p -> new Draft(p, IssueSender.Report.of(p, read.facts(), scrubber), sent.contains(p.fingerprint())))
                .toList();
    }

    /** Whether Studio is signed in to GitHub, so it can send. */
    public static boolean signedIn() {
        return new GitHubAuth().isAuthenticated();
    }

    /**
     * Sends {@code reports} of run {@code run} to {@code target}, but those sent from it already; what failed is
     * kept for the next run. One send at a time, so two dialogs over one run never both send a problem.
     */
    public static synchronized List<IssueSender.Sent> send(Path run, ReportTarget target,
                                                           List<IssueSender.Report> reports) {
        Set<String> already = sent(run);
        List<IssueSender.Report> left = reports.stream().filter(r -> !already.contains(r.fingerprint())).toList();
        if (left.isEmpty()) return List.of();
        List<IssueSender.Sent> sent = IssueSender.send(left, api(target), run);
        remember(run, sent);
        return sent;
    }

    /** Sends what every run of {@code project} kept from a send that failed. */
    public static List<IssueSender.Sent> resend(Path project, ReportTarget target) {
        List<IssueSender.Sent> all = new ArrayList<>();
        IssueApi api = null;
        for (Path run : runs(project)) {
            if (IssueSender.pending(run).isEmpty()) continue;
            if (api == null) api = api(target);
            List<IssueSender.Sent> sent = IssueSender.resend(run, api);
            remember(run, sent);
            all.addAll(sent);
        }
        return all;
    }

    /** Writes {@code run}'s text files, scrubbed, to the zip {@code out}. */
    public static List<String> export(Path run, Path out) throws IOException {
        return ExportLogs.zip(run, out, scrubber());
    }

    /** One line saying what became of {@code sent}. */
    public static String summary(List<IssueSender.Sent> sent) {
        long created = sent.stream().filter(s -> s.outcome() == IssueSender.Outcome.CREATED).count();
        long commented = sent.stream().filter(s -> s.outcome() == IssueSender.Outcome.COMMENTED).count();
        Optional<IssueSender.Sent> queued = sent.stream()
                .filter(s -> s.outcome() == IssueSender.Outcome.QUEUED).findFirst();
        StringBuilder out = new StringBuilder("Reports: ").append(created).append(" new issue")
                .append(created == 1 ? "" : "s").append(", ").append(commented).append(" comment")
                .append(commented == 1 ? "" : "s").append('.');
        queued.ifPresent(q -> out.append(' ').append(sent.stream()
                        .filter(s -> s.outcome() == IssueSender.Outcome.QUEUED).count())
                .append(" kept for the next run: ").append(q.error()).append('.'));
        return out.toString();
    }

    /**
     * Home and user name scrubbed. Window titles are not: Studio does not know which window is the game's, and a
     * title is written into a log only as the game's (spec §3 gap, run reports phase 7).
     */
    private static Scrubber scrubber() {
        return Scrubber.forThisMachine(List.of());
    }

    private static IssueApi api(ReportTarget target) {
        return IssueApi.github(new GitHubClient(), target, new GitHubAuth().token());
    }

    private static Set<String> sent(Path run) {
        try {
            Path file = run.resolve(SENT);
            return Files.isRegularFile(file) ? Set.copyOf(Files.readAllLines(file, StandardCharsets.UTF_8)) : Set.of();
        } catch (IOException e) {
            return Set.of();
        }
    }

    private static void remember(Path run, List<IssueSender.Sent> sent) {
        StringBuilder lines = new StringBuilder();
        sent.stream().filter(s -> s.outcome() != IssueSender.Outcome.QUEUED)
                .forEach(s -> lines.append(s.report().fingerprint()).append('\n'));
        if (lines.isEmpty()) return;
        try {
            Files.writeString(run.resolve(SENT), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Only the "sent" mark is lost: the dialog offers that problem again, ticked.
            System.err.println("[BotMaker] could not mark reports sent: " + e.getMessage());
        }
    }
}
