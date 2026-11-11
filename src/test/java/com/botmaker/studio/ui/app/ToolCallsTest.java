package com.botmaker.studio.ui.app;

import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.event.CoreEvent.ToolCallEnded;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Part;
import com.botmaker.core.event.CoreEvent.ToolCallProgress;
import com.botmaker.core.event.CoreEvent.ToolCallStarted;
import com.botmaker.core.event.CoreEvent.ToolCallStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        String detail = AssistantWindow.detail(call);
        assertTrue(detail.contains("REFUSED: no") && detail.contains("install x.apk") && detail.contains("elsewhere: 2"),
                detail);
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
        assertTrue(AssistantWindow.detail(call).contains("… and 4 more steps"));
    }

    @Test
    void theOldestGoPastTheCap() {
        ToolCalls calls = new ToolCalls();
        for (int i = 0; i < ToolCalls.KEPT + 3; i++) calls.told(new ToolCallStarted(i, "t", "", "{}", i));
        assertEquals(ToolCalls.KEPT, calls.all().size());
        assertEquals(3, calls.all().getFirst().id());
    }

    @Test
    void aRowSummarisesItsArgumentsOnOneLine() {
        assertEquals("", AssistantWindow.summary("{}"));
        assertEquals("{\"a\": 1, \"b\": 2}", AssistantWindow.summary("{\"a\": 1,\n  \"b\": 2}"));
        assertEquals(90, AssistantWindow.summary("{\"x\":\"" + "y".repeat(200) + "\"}").length());
    }
}
