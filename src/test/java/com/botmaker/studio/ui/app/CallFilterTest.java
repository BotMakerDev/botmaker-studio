package com.botmaker.studio.ui.app;

import com.botmaker.core.event.CoreEvent.ToolCallEnded;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Part;
import com.botmaker.core.event.CoreEvent.ToolCallStarted;
import com.botmaker.core.event.CoreEvent.ToolCallStep;
import com.botmaker.studio.ui.app.ToolCalls.Call;
import com.botmaker.studio.ui.app.ToolCalls.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Calls pane's filters over a fixed list of calls. */
class CallFilterTest {

    private static final List<String> TOOLS = List.of("read_tree", "botmaker_sdk_click", "device");

    private final ToolCalls calls = fixed();

    private static ToolCalls fixed() {
        ToolCalls calls = new ToolCalls();
        calls.told(new ToolCallStarted(1, "read_tree", "", "{\"method\":\"farm\"}", 0));
        calls.told(new ToolCallEnded(1, ToolCallEnded.Outcome.OK, List.of(new Part(Part.Kind.TEXT, "body b0")), 3, 0));
        calls.told(new ToolCallStarted(2, "botmaker_sdk_click", "BotMaker SDK", "{\"element\":\"PLAY\"}", 1));
        calls.told(new ToolCallStep(2, "127.0.0.1:16384", "input tap 450 1520", true, "", 1, 38));
        calls.told(new ToolCallEnded(2, ToolCallEnded.Outcome.REFUSED,
                List.of(new Part(Part.Kind.TEXT, "REFUSED: no Gold Mine on screen")), 40, 0));
        calls.told(new ToolCallStarted(3, "device", "", "{\"action\":\"install\"}", 2));
        calls.told(new ToolCallStep(3, "127.0.0.1:5555", "install x.apk", true, "", 2, 900));
        return calls;
    }

    private List<Long> shown(CallFilter filter) {
        return calls.all().stream().filter(filter).map(Call::id).toList();
    }

    @Test
    void everyCallShowsUnfiltered() {
        assertEquals(List.of(1L, 2L, 3L), shown(CallFilter.ALL));
    }

    @Test
    void theChecklistHidesTheToolsUntickedAndANewToolShows() {
        CallFilter filter = CallFilter.ALL.toggled("device");
        assertEquals(List.of(1L, 2L), shown(filter));
        assertFalse(filter.ticks("device"));
        assertEquals(List.of(1L, 2L, 3L), shown(filter.toggled("device")));

        CallFilter only = CallFilter.ALL.only("botmaker_sdk_click", TOOLS);
        assertEquals(List.of(2L), shown(only));
        assertTrue(only.ticks("list_files"), "a tool first called after Only shows");
        assertEquals(List.of(), shown(only.noneTicked(TOOLS)));
        assertEquals(List.of(1L, 2L, 3L), shown(only.noneTicked(TOOLS).allTicked()));
        assertEquals(List.of(2L), shown(CallFilter.ALL.ticking(List.of("read_tree", "device"), false)),
                "a plugin's box unticks all its tools");
    }

    @Test
    void pluginDeviceStatusAndSearchEachNarrowIt() {
        assertEquals(List.of(1L, 3L), shown(CallFilter.ALL.withPlugin(Optional.of(ToolCalls.CORE))));
        assertEquals(List.of(2L), shown(CallFilter.ALL.withPlugin(Optional.of("BotMaker SDK"))));
        assertEquals(List.of(3L), shown(CallFilter.ALL.withDevice(Optional.of("127.0.0.1:5555"))));
        assertEquals(List.of(3L), shown(CallFilter.ALL.withStatus(Optional.of(Status.RUNNING))));
        assertEquals(List.of(2L), shown(CallFilter.ALL.withStatus(Optional.of(Status.REFUSED))));
        assertEquals(List.of(2L), shown(CallFilter.ALL.withSearch("gold mine")), "the reply's text, any case");
        assertEquals(List.of(1L), shown(CallFilter.ALL.withSearch("farm")), "the arguments");
        assertEquals(List.of(3L), shown(CallFilter.ALL.withSearch(" DEV ")), "the tool");
        assertEquals(List.of(), shown(CallFilter.ALL.withPlugin(Optional.of(ToolCalls.CORE))
                .withStatus(Optional.of(Status.REFUSED))), "all of them at once");
        assertEquals(Set.of(), CallFilter.ALL.withSearch("x").unticked());
    }
}
