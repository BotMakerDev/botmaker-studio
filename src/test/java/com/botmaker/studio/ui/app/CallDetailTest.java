package com.botmaker.studio.ui.app;

import com.botmaker.core.event.CoreEvent.ToolCallEnded;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Changed;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Extras;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Part;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Point;
import com.botmaker.core.event.CoreEvent.ToolCallProgress;
import com.botmaker.core.event.CoreEvent.ToolCallStarted;
import com.botmaker.core.event.CoreEvent.ToolCallStep;
import com.botmaker.studio.ui.app.CallDetail.Block;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each detail renderer's choice, over captured calls: what is drawn, without a screen. */
class CallDetailTest {

    private static List<Block> blocks(ToolCalls calls, long id) {
        return CallDetail.blocks(calls.all().stream().filter(c -> c.id() == id).findFirst().orElseThrow(), calls);
    }

    @Test
    void aTextReplyIsDrawnAsWrittenAfterItsArguments() {
        ToolCalls calls = new ToolCalls();
        calls.told(new ToolCallStarted(1, "list_methods", "", "{\"file\":\"Main\"}", 0));
        calls.told(new ToolCallEnded(1, ToolCallEnded.Outcome.OK,
                List.of(new Part(Part.Kind.TEXT, "Main.run()\n  body b0   \n")), 4, 0));
        assertEquals(List.of(new Block.Section("Arguments"), new Block.Code("{\n  \"file\" : \"Main\"\n}"),
                new Block.Section("Reply"), new Block.Code("Main.run()\n  body b0")), blocks(calls, 1));
        assertTrue(CallDetail.heading(calls.all().getFirst()).startsWith("list_methods · OK · "));
    }

    @Test
    void aRunningCallShowsItsProgressAndItsSteps() {
        ToolCalls calls = new ToolCalls();
        calls.told(new ToolCallStarted(2, "device", "", "{}", 0));
        ToolCallStep step = new ToolCallStep(2, "emu", "push x.apk", true, "", 0, 0);
        calls.told(step);
        calls.told(new ToolCallProgress(2, "emu", "push x.apk", 512, 2048));
        assertEquals(List.of(new Block.Section("Arguments"), new Block.Note("None."),
                new Block.Progress("emu", "push x.apk", 512, 2048), new Block.Section("Reply"),
                new Block.Note("Running…"), new Block.Section("Device steps"),
                new Block.Steps(List.of(step), 0, 0)), blocks(calls, 2));
    }

    @Test
    void anImageIsDrawnWithItsOwnTap() {
        ToolCalls calls = new ToolCalls();
        Part frame = new Part(Part.Kind.IMAGE, "JPEG", 1080, 1920);
        calls.told(new ToolCallStarted(3, "botmaker_sdk_click", "BotMaker SDK", "{}", 0));
        calls.told(new ToolCallEnded(3, ToolCallEnded.Outcome.OK, List.of(frame, frame), 4, 0,
                new Extras(List.of(new Point(450, 1520)), List.of())));
        List<Block> blocks = blocks(calls, 3);
        assertEquals(new Block.Picture(frame, List.of(new Point(450, 1520)), ""), blocks.get(3));
        assertEquals(new Block.Picture(frame, List.of(), ""), blocks.get(4), "the point on the first image only");
    }

    @Test
    void aTapWithNoImageIsDrawnOnTheFrameAfterIt() {
        ToolCalls calls = new ToolCalls();
        calls.told(new ToolCallStarted(4, "device", "", "{\"action\":\"swipe\"}", 0));
        calls.told(new ToolCallStep(4, "emu", "input swipe 1 2 30 40 300", true, "", 0, 300));
        List<Point> path = List.of(new Point(1, 2), new Point(30, 40));
        calls.told(new ToolCallEnded(4, ToolCallEnded.Outcome.OK, List.of(new Part(Part.Kind.TEXT, "Swiped.")), 300, 0,
                new Extras(path, List.of())));
        assertEquals(new Block.Note("Touched at 1,2 → 30,40; no frame after it yet."), blocks(calls, 4).get(4));

        ToolCallsTest.shot(calls, 5, "emu", "AFTER");
        Block drawn = blocks(calls, 4).get(4);
        Block.Picture picture = assertInstanceOf(Block.Picture.class, drawn);
        assertEquals("AFTER", picture.image().value());
        assertEquals(path, picture.touched());
        assertTrue(picture.caption().contains("on the frame after it: device at"), picture.caption());
    }

    @Test
    void anEditsChangedMethodsAreLinks() {
        ToolCalls calls = new ToolCalls();
        Changed run = new Changed("/bots/farm/src/main/java/com/farm/Farm.java", "Farm", "run");
        calls.told(new ToolCallStarted(6, "edit_code", "", "{}", 0));
        calls.told(new ToolCallEnded(6, ToolCallEnded.Outcome.OK, List.of(new Part(Part.Kind.TEXT, "Done.")), 8, 0,
                new Extras(List.of(), List.of(run))));
        List<Block> blocks = blocks(calls, 6);
        assertEquals(List.of(new Block.Section("Changed methods"), new Block.Methods(List.of(run))),
                blocks.subList(blocks.size() - 2, blocks.size()));
    }

    @Test
    void stepsBeyondTheKeptAndElsewhereAreCounted() {
        ToolCalls calls = new ToolCalls();
        calls.told(new ToolCallStarted(7, "device", "", "{}", 0));
        for (int i = 0; i < ToolCalls.STEPS_KEPT + 2; i++) calls.told(new ToolCallStep(7, "d", "tap", true, "", i, 1));
        calls.told(new ToolCallEnded(7, ToolCallEnded.Outcome.OK, List.of(), 9, 3));
        Block.Steps steps = assertInstanceOf(Block.Steps.class, blocks(calls, 7).getLast());
        assertEquals(ToolCalls.STEPS_KEPT, steps.steps().size());
        assertEquals(2, steps.more());
        assertEquals(3, steps.elsewhere());
        assertEquals("not json", CallDetail.pretty("not json"));
    }
}
