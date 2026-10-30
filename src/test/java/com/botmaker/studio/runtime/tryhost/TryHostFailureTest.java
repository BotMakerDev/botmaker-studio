package com.botmaker.studio.runtime.tryhost;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TryHostFailureTest {

    private static final StackTraceElement SDK =
            new StackTraceElement("app", null, null, "com.botmaker.sdk.api.screens.Navigate", "to", "Navigate.java", 72);
    private static final StackTraceElement BOT =
            new StackTraceElement("try", null, null, "com.hadesii.VisitMenus", "body", "VisitMenus.java", 11);
    private static final StackTraceElement TRY =
            new StackTraceElement("try", null, null, "com.hadesii.BotMakerTry", "main", "BotMakerTry.java", 10);

    @Test
    void aBotFailureIsOneLineNamingTheBotsOwnLine() {
        IllegalStateException thrown = new IllegalStateException("No way from Options\nto Save slots");
        thrown.setStackTrace(new StackTraceElement[] {SDK, BOT, TRY});
        assertEquals("Failed: IllegalStateException: No way from Options to Save slots (VisitMenus.java:11)",
                TryHost.failure(thrown));
    }

    @Test
    void eachCauseFollowsAndAFailureOutsideTheBotNamesTheDeepestCausesLine() {
        IOException cause = new IOException("disk full");
        cause.setStackTrace(new StackTraceElement[] {SDK});
        ExceptionInInitializerError thrown = new ExceptionInInitializerError(cause);
        thrown.setStackTrace(new StackTraceElement[] {TRY});
        assertEquals("Failed: ExceptionInInitializerError ← IOException: disk full (Navigate.java:72)",
                TryHost.failure(thrown));
    }
}
