// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.ui.app.runs;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.run.claims.LiveRunsView;
import com.botmaker.core.run.claims.RunRegistry.Hold;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The table of a project's Runs window (run-claims phase 14, the user's pick P-A). */
class RunsRowsTest {

    private static final String ACCOUNT = "com.refbot.params.bot.Farm#account";
    private static final String INSTANCE = "com.botmaker.sdk.Sdk#instance";
    private static final String SDK = "com.github.BotMakerDev:botmaker-sdk";
    private static final long NOW = 10_000_000L;

    @Test
    void liveThenReadyThenNotDealtThenOtherBotsHoldingThePluginsElements() {
        LiveRunsView.Live live = new LiveRunsView.Live("r1", NOW - 42 * 60_000, List.of(
                Hold.plugin(SDK, INSTANCE, "Instance.of(\"LDPlayer-1\")"), Hold.bot("farm", ACCOUNT, "GameAccount.MAIN")),
                List.of(), "Daily quests done");
        LiveRunsView.Group mine = new LiveRunsView.Group("farm", "farm", LiveRunsView.Kind.MY_BOT,
                Optional.of(Path.of("farm")), List.of(live));
        CoreEvent.RunsStarted count = new CoreEvent.RunsStarted("t1", "1 of 3 can run: too few instances", List.of(
                new CoreEvent.RunsStarted.Started(1, "", Map.of(INSTANCE, List.of("Instance.of(\"LDPlayer-2\")"),
                        ACCOUNT, List.of("GameAccount.ALT")))), List.of(), List.of("Add instances…"), true);
        LiveRunsView.Group arena = new LiveRunsView.Group("arena", "Arena bot", LiveRunsView.Kind.MY_BOT,
                Optional.of(Path.of("arena")), List.of(new LiveRunsView.Live("a1", NOW, List.of(
                Hold.plugin(SDK, INSTANCE, "Instance.of(\"LDPlayer-3\")"), Hold.bot("arena", ACCOUNT, "X.Y")),
                List.of(), "")));

        RunsRows rows = RunsRows.of(Optional.of(mine), Optional.of(count), 3, List.of(arena), NOW);

        assertEquals(List.of(INSTANCE, ACCOUNT), rows.columns());
        assertEquals(List.of(RunsRows.Kind.LIVE, RunsRows.Kind.READY, RunsRows.Kind.MISSING, RunsRows.Kind.MISSING,
                RunsRows.Kind.OTHER), rows.rows().stream().map(RunsRows.Row::kind).toList());
        assertEquals("Running · 42 min", rows.rows().getFirst().status());
        assertEquals(0, rows.rows().get(1).index(), "a pin names the count's row from 0");
        assertEquals(1, rows.ready());
        RunsRows.Row other = rows.rows().getLast();
        assertEquals("Held by Arena bot", other.status());
        assertEquals(Map.of(INSTANCE, List.of("Instance.of(\"LDPlayer-3\")")), other.picks(),
                "only a plugin's element is another bot's to hold; its own account is not this bot's");
    }

    @Test
    void beforeACountOnlyTheLiveRowsShow() {
        RunsRows rows = RunsRows.of(Optional.empty(), Optional.empty(), 2, List.of(), NOW);
        assertEquals(List.of(), rows.rows());
        assertEquals(List.of(), rows.columns());
    }

    @Test
    void headingsElementsAndTimesAreShortWords() {
        assertEquals("account()", RunsRows.heading(ACCOUNT));
        assertEquals(Optional.of("Farm"), RunsRows.owner(ACCOUNT));
        assertEquals("LDPlayer-1", RunsRows.shown("Instance.of(\"LDPlayer-1\")"));
        assertEquals("GameAccount.MAIN", RunsRows.shown("GameAccount.MAIN"));
        assertEquals("a \"b\"", RunsRows.shown("\"a \\\"b\\\"\""));
        assertEquals("under a minute", RunsRows.since(NOW - 5_000, NOW));
        assertEquals("1 h 05", RunsRows.since(NOW - 65 * 60_000, NOW));
    }
}
