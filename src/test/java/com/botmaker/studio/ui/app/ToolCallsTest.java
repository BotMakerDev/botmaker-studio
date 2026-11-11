package com.botmaker.studio.ui.app;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.event.CoreEvent.ToolCallEnded;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Part;
import com.botmaker.core.event.CoreEvent.ToolCallProgress;
import com.botmaker.core.event.CoreEvent.ToolCallStarted;
import com.botmaker.core.event.CoreEvent.ToolCallStep;
import com.botmaker.studio.ui.app.ToolCalls.Sort;
import com.botmaker.studio.ui.app.ToolCalls.Tally;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallsTest {

    @Test
    void aCallIsFoldedFromItsEvents() {
        ToolCalls calls = new ToolCalls();
        calls.told(new ToolCallStarted(7, "device", "", "{\"action\":\"install\"}", 1000));
        assertEquals(ToolCalls.Status.RUNNING, calls.all().getFirst().status());
        calls.told(new ToolCallStep(7, "127.0.0.1:16384", "install x.apk", true, "Success", 1001, 40));
        calls.told(new ToolCallProgress(7, "127.0.0.1:16384", "push x.apk", 5, 10));
        ToolCalls.Call call = calls.all().getFirst();
        assertEquals(1, call.steps().size());
        assertEquals(5, call.progress().done());
        calls.told(new ToolCallEnded(7, ToolCallEnded.Outcome.REFUSED,
                List.of(new Part(Part.Kind.TEXT, "REFUSED: no")), 50, 2));
        call = calls.all().getFirst();
        assertEquals(ToolCalls.Status.REFUSED, call.status());
        assertNull(call.progress(), "an ended call has no progress left");
        assertEquals(1, calls.view().size(), "one list: the view is the calls, changed in place");
        assertSame(call, calls.view().getFirst());
    }

    @Test
    void anEventOfNoKnownCallChangesNothing() {
        ToolCalls calls = new ToolCalls();
        assertNull(calls.told(new ToolCallEnded(3, ToolCallEnded.Outcome.OK, List.of(), 1, 0)));
        assertNull(calls.told(new CoreEvent.Status("x")));
        assertTrue(calls.all().isEmpty());
    }

    @Test
    void aCallKeepsItsFirstStepsAndCountsTheRest() {
        ToolCalls calls = new ToolCalls();
        calls.told(new ToolCallStarted(1, "device", "", "{}", 0));
        for (int i = 0; i < ToolCalls.STEPS_KEPT + 4; i++) {
            calls.told(new ToolCallStep(1, "d", "tap " + i, true, "", i, 1));
        }
        ToolCalls.Call call = calls.all().getFirst();
        assertEquals(ToolCalls.STEPS_KEPT, call.steps().size());
        assertEquals(4, call.moreSteps());
    }

    @Test
    void theOldestGoPastTheCapAndALaterOneIsStillFound() {
        ToolCalls calls = new ToolCalls();
        for (int i = 0; i < ToolCalls.KEPT + 3; i++) calls.told(new ToolCallStarted(i, "t", "", "{}", i));
        assertEquals(ToolCalls.KEPT, calls.all().size());
        assertEquals(3, calls.all().getFirst().id());
        assertNull(calls.told(new ToolCallEnded(1, ToolCallEnded.Outcome.OK, List.of(), 1, 0)), "dropped");
        calls.told(new ToolCallEnded(400, ToolCallEnded.Outcome.OK, List.of(), 1, 0));
        assertEquals(ToolCalls.Status.OK, calls.view().get(397).status(), "found at its place after the drops");
    }

    @Test
    void theChecklistCountsEachToolAndSortsAsAsked() {
        ToolCalls calls = new ToolCalls();
        long now = 10 * 60_000;
        int id = 0;
        for (int i = 0; i < 3; i++) calls.told(new ToolCallStarted(id++, "read_tree", "", "{}", 0));
        for (int i = 0; i < 2; i++) calls.told(new ToolCallStarted(id++, "botmaker_sdk_click", "BotMaker SDK", "{}", now));
        calls.told(new ToolCallStarted(id++, "device", "", "{}", now));
        for (int i = 0; i < id - 1; i++) calls.told(new ToolCallEnded(i, ToolCallEnded.Outcome.OK, List.of(), 1, 0));

        assertEquals(List.of(new Tally("read_tree", "", 3, 0, false),
                new Tally("botmaker_sdk_click", "BotMaker SDK", 2, 2, false),
                new Tally("device", "", 1, 1, true)), calls.tallies(Sort.MOST_CALLED, now));
        assertEquals(List.of("botmaker_sdk_click", "device", "read_tree"),
                calls.tallies(Sort.LAST_5_MIN, now).stream().map(Tally::tool).toList());
        assertEquals(List.of("read_tree", "device", "botmaker_sdk_click"),
                calls.tallies(Sort.BY_PLUGIN, now).stream().map(Tally::tool).toList(), "the core's first");
        assertEquals(List.of("botmaker_sdk_click", "device", "read_tree"),
                calls.tallies(Sort.A_Z, now).stream().map(Tally::tool).toList());
        assertEquals(List.of("", "BotMaker SDK"), calls.plugins());
        assertEquals(Sort.MOST_CALLED, Sort.fromId("gone"));
    }

    @Test
    void aTapsFrameIsItsOwnImageElseTheNextOnTheSameDevice() {
        ToolCalls calls = new ToolCalls();
        calls.told(new ToolCallStarted(1, "device", "", "{}", 0));
        calls.told(new ToolCallStep(1, "emu-a", "input tap 1 2", true, "", 0, 5));
        calls.told(new ToolCallEnded(1, ToolCallEnded.Outcome.OK, List.of(new Part(Part.Kind.TEXT, "Tapped.")), 5, 0));
        ToolCalls.Call tap = calls.all().getFirst();
        assertTrue(calls.frameAfter(tap).isEmpty());

        shot(calls, 2, "emu-b", "B");
        shot(calls, 3, "emu-a", "A");
        assertEquals(3, calls.frameAfter(tap).orElseThrow().call().id(), "another device's shot is no frame of it");
        assertEquals("A", calls.frameAfter(tap).orElseThrow().image().value());
        assertEquals(2, calls.frameAfter(calls.all().get(1)).orElseThrow().call().id(), "a call's own image first");
    }

    @Test
    void aRowSummarisesItsArgumentsOnOneLine() {
        assertEquals("", AssistantWindow.summary("{}"));
        assertEquals("{\"a\": 1, \"b\": 2}", AssistantWindow.summary("{\"a\": 1,\n  \"b\": 2}"));
        assertEquals(90, AssistantWindow.summary("{\"x\":\"" + "y".repeat(200) + "\"}").length());
    }

    static void shot(ToolCalls calls, long id, String device, String jpeg) {
        calls.told(new ToolCallStarted(id, "device", "", "{\"action\":\"screenshot\"}", id));
        calls.told(new ToolCallStep(id, device, "screencap -p", true, "", id, 30));
        calls.told(new ToolCallEnded(id, ToolCallEnded.Outcome.OK, List.of(new Part(Part.Kind.IMAGE, jpeg, 1080, 1920)),
                30, 0));
    }
}
