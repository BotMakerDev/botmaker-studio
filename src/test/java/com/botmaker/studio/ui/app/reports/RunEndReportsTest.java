package com.botmaker.studio.ui.app.reports;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunEndReportsTest {

    @Test
    void neverSendsNorAsks() {
        assertEquals(RunEndReports.Action.NOTHING, RunEndReports.decide(ReportConsent.NEVER, true, true, 3));
    }

    @Test
    void askAsksEvenSignedOut() {
        assertEquals(RunEndReports.Action.ASK, RunEndReports.decide(ReportConsent.ASK, true, false, 1));
    }

    @Test
    void alwaysSendsWhenSignedIn() {
        assertEquals(RunEndReports.Action.SEND, RunEndReports.decide(ReportConsent.ALWAYS, true, true, 1));
        assertEquals(RunEndReports.Action.SIGN_IN, RunEndReports.decide(ReportConsent.ALWAYS, true, false, 1));
    }

    @Test
    void noRepositoryOrNoProblemDoesNothing() {
        assertEquals(RunEndReports.Action.NOTHING, RunEndReports.decide(ReportConsent.ASK, false, true, 2));
        assertEquals(RunEndReports.Action.NOTHING, RunEndReports.decide(ReportConsent.ALWAYS, true, true, 0));
    }

    @Test
    void anUnknownConsentAsks() {
        assertEquals(ReportConsent.ASK, ReportConsent.fromId("sometimes"));
        assertEquals(ReportConsent.ASK, ReportConsent.fromId(null));
        assertEquals(ReportConsent.NEVER, ReportConsent.fromId(" never "));
    }
}
