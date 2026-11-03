package com.botmaker.studio.ui.app.reports;

import com.botmaker.runlog.ReportTarget;
import com.botmaker.shared.reports.IssueSender;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.services.ProjectSettingsService;
import javafx.application.Platform;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * What a run's end does with its problems (run reports spec §3), by the bot's {@link ReportConsent}: first what
 * earlier runs kept from a failed send goes; then {@link ReportConsent#NEVER} does nothing, {@link
 * ReportConsent#ALWAYS} sends, and {@link ReportConsent#ASK} shows {@link ReportsDialog}. A bot with no GitHub
 * repository ({@code upstream}, else {@code origin}) reports nothing; its Trace tab still exports its logs.
 */
public final class RunEndReports implements AutoCloseable {

    /** What a run's end does. */
    enum Action {
        NOTHING, SEND, ASK, SIGN_IN
    }

    private final EventBus eventBus;
    private final Path project;
    private final ProjectSettingsService settings;
    private final EventBus.Subscription subscription;

    private RunEndReports(EventBus eventBus, Path project, ProjectSettingsService settings) {
        this.eventBus = eventBus;
        this.project = project;
        this.settings = settings;
        // Off the run's thread: resending and sending wait on GitHub.
        this.subscription = eventBus.subscribe(CoreApplicationEvents.BotRunEndedEvent.class,
                e -> Thread.ofVirtual().name("run-end-reports").start(() -> {
                    try {
                        ended(e.run());
                    } catch (RuntimeException ex) {
                        status("This run's problems were not reported: " + ex.getMessage());
                    }
                }), false);
    }

    /** Reports the problems of each run of {@code project} at its end. */
    public static RunEndReports install(EventBus eventBus, Path project, ProjectSettingsService settings) {
        return new RunEndReports(eventBus, project, settings);
    }

    @Override
    public void close() {
        subscription.close();
    }

    /** What a run's end does: {@code unsent} problems of the run, under {@code consent}. */
    static Action decide(ReportConsent consent, boolean hasTarget, boolean signedIn, int unsent) {
        if (consent == ReportConsent.NEVER || !hasTarget || unsent == 0) return Action.NOTHING;
        if (consent == ReportConsent.ASK) return Action.ASK;
        return signedIn ? Action.SEND : Action.SIGN_IN;
    }

    private void ended(Path run) {
        ReportConsent consent = ReportConsent.of(settings.current());
        ReportTarget target = ReportTarget.of(project).orElse(null);
        boolean signedIn = RunProblems.signedIn();
        if (consent != ReportConsent.NEVER && target != null && signedIn) {
            List<IssueSender.Sent> resent = RunProblems.resend(project, target);
            if (!resent.isEmpty()) status(RunProblems.summary(resent));
        }
        List<RunProblems.Draft> drafts = run == null ? List.of() : RunProblems.drafts(run);
        List<RunProblems.Draft> unsent = drafts.stream().filter(d -> !d.sent()).toList();
        switch (decide(consent, target != null, signedIn, unsent.size())) {
            case NOTHING -> { }
            case SEND -> status(RunProblems.summary(RunProblems.send(run, target,
                    unsent.stream().map(RunProblems.Draft::report).toList())));
            case SIGN_IN -> status("This run's problems were not reported: sign in to GitHub to send them, or "
                    + "Submit logs… on the Trace tab.");
            case ASK -> Platform.runLater(() -> ask(run, target, drafts, consent, signedIn));
        }
    }

    private void ask(Path run, ReportTarget target, List<RunProblems.Draft> drafts, ReportConsent consent,
                     boolean signedIn) {
        // No owner: the window focused at a run's end is often the run overlay, closing as the run ends, and
        // a dialog it owned would close with it.
        open(null, project, run, target, drafts, consent, signedIn, settings, this::status);
    }

    /**
     * Shows {@link ReportsDialog} for run {@code run} of {@code project}: the consent picked is saved, and what is
     * sent goes off the FX thread.
     */
    public static void open(Window owner, Path project, Path run, ReportTarget target, List<RunProblems.Draft> drafts,
                            ReportConsent consent, boolean signedIn, ProjectSettingsService settings,
                            Consumer<String> status) {
        ReportsDialog.show(owner, run, target, drafts, consent, signedIn,
                picked -> {
                    if (picked != consent && settings != null && settings.current() != null) {
                        settings.update(settings.current().withRunProperty(ReportConsent.PROPERTY, picked.id()));
                    }
                },
                reports -> Thread.ofVirtual().name("report-send").start(() ->
                        status.accept(RunProblems.summary(RunProblems.send(run, target, reports)))),
                status);
    }

    private void status(String message) {
        Runnable publish = () -> eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(message));
        if (Platform.isFxApplicationThread()) publish.run();
        else Platform.runLater(publish);
    }
}
