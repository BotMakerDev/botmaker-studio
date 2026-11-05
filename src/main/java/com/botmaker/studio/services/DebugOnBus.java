package com.botmaker.studio.services;

import com.botmaker.core.events.EventBus;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.EditorState;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.runtime.CodeExecutionService;
import com.botmaker.studio.services.debug.DebugSnapshot;
import com.botmaker.studio.services.debug.DebugView;
import com.botmaker.studio.services.debug.FollowPacer;
import com.botmaker.studio.ui.FxThread;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * A debug session on Studio's canvas and bus (plan phase 7): the menu's debug controls reach {@link DebuggingService},
 * and a stop the debugger names by file and line becomes the block the canvas shows for it, looked up on the FX thread
 * in whatever is drawn at that moment. Blocks resolved once at the start went stale the first time the file was
 * re-drawn, and a highlight on a detached block is a highlight nobody sees.
 */
public final class DebugOnBus implements DebugView {

    /**
     * Follow switches the canvas to another file only once the bot has spent a whole window there, and not
     * more often than this: a bot bouncing between two files must not make the whole canvas flash.
     */
    private static final long FILE_SWITCH_GAP_MS = 1_500;

    private final EditorState state;
    private final EventBus bus;
    // FX-confined: the last file Follow switched to, and when.
    private long lastFileSwitch;
    // FX-confined: the line index of the map last looked up, rebuilt when the canvas is re-drawn.
    private Map<ASTNode, CodeBlock> indexedMap;
    private Map<Integer, CodeBlock> lineIndex = Map.of();

    private DebugOnBus(EditorState state, EventBus bus) {
        this.state = state;
        this.bus = bus;
    }

    /** The project's debugger, its controls taken from {@code bus} and its stops shown on the canvas. */
    public static DebuggingService service(EditorState state, ProjectConfig config, CodeExecutionService execution,
                                           EventBus bus) {
        DebuggingService debugger = new DebuggingService(state, config, execution, FxThread.INSTANCE,
                new DebugOnBus(state, bus), RunOnBus.events(bus), RunOnBus.telemetry(bus));
        bus.subscribe(CoreApplicationEvents.DebugControlRequest.class, e -> {
            switch (e) {
                case CoreApplicationEvents.DebugStartRequestedEvent ignored -> debugger.startDebugging(false);
                case CoreApplicationEvents.FollowStartRequestedEvent ignored -> debugger.startDebugging(true);
                case CoreApplicationEvents.DebugStepOverRequestedEvent ignored -> debugger.stepOver();
                case CoreApplicationEvents.DebugContinueRequestedEvent ignored -> debugger.continueExecution();
                case CoreApplicationEvents.DebugStopRequestedEvent ignored -> debugger.stopDebugging();
            }
        }, false);
        bus.subscribe(CoreApplicationEvents.InputAnsweredEvent.class, e -> debugger.answer(e.id(), e.value()), false);
        return debugger;
    }

    @Override
    public void started() {
        bus.publish(new CoreApplicationEvents.DebugSessionStartedEvent());
        FxThread.INSTANCE.later(() -> bus.publish(new CoreApplicationEvents.OutputClearedEvent()));
    }

    @Override
    public void resumed() {
        bus.publish(new CoreApplicationEvents.DebugSessionResumedEvent());
    }

    @Override
    public void finished() {
        bus.publish(new CoreApplicationEvents.DebugSessionFinishedEvent());
        bus.publish(new CoreApplicationEvents.BlockHighlightEvent(null));
    }

    @Override
    public void paused(FollowPacer.Hit hit, int line, DebugSnapshot snapshot, BooleanSupplier live) {
        bus.publish(new CoreApplicationEvents.DebugSnapshotEvent(snapshot));
        if (hit == null) {
            // A step landed outside the bot's own code (a library frame): say where, highlight nothing.
            bus.publish(new CoreApplicationEvents.DebugSessionPausedEvent(line, null));
            bus.publish(new CoreApplicationEvents.StatusMessageEvent("Paused at line: " + line));
            return;
        }
        onFile(hit.file(), () -> {
            if (!live.getAsBoolean()) return;
            CodeBlock target = blockAt(hit.line());
            bus.publish(new CoreApplicationEvents.DebugSessionPausedEvent(hit.line(), target));
            bus.publish(new CoreApplicationEvents.BlockHighlightEvent(target));
            bus.publish(new CoreApplicationEvents.ExecutionFollowedEvent(target));
            bus.publish(new CoreApplicationEvents.StatusMessageEvent(
                    "Paused at " + hit.file().getFileName() + ", line " + hit.line()));
        });
    }

    /**
     * A frame in the open file highlights its latest block, or the loop enclosing all of them when the bot came round
     * within the window. A frame spent wholly in another file switches to it, at most once per
     * {@link #FILE_SWITCH_GAP_MS}; a frame that crossed files leaves the highlight where it is.
     */
    @Override
    public void followed(FollowPacer.Frame frame, BooleanSupplier live) {
        Optional<Path> single = frame.singleFile();
        Path file = frame.latest().file();
        if (!file.equals(activeFile())) {
            long now = System.currentTimeMillis();
            if (single.isEmpty() || now - lastFileSwitch < FILE_SWITCH_GAP_MS) return;
            lastFileSwitch = now;
        }
        onFile(file, () -> {
            if (!live.getAsBoolean()) return;
            CodeBlock target = null;
            if (frame.looping() && single.isPresent()) {
                List<ASTNode> nodes = frame.hits().stream()
                        .map(h -> blockAt(h.line()))
                        .filter(Objects::nonNull)
                        .map(CodeBlock::getAstNode)
                        .toList();
                target = FollowPacer.enclosingLoop(nodes)
                        .flatMap(state::getBlockForNode)
                        .map(CodeBlock::getHighlightTarget)
                        .orElse(null);
            }
            if (target == null) target = blockAt(frame.latest().line());
            if (target == null) return;
            bus.publish(new CoreApplicationEvents.BlockHighlightEvent(target));
            bus.publish(new CoreApplicationEvents.ExecutionFollowedEvent(target));
        });
    }

    /** Runs {@code then} once {@code file} is the one on screen, asking the editor to switch to it first if needed. */
    private void onFile(Path file, Runnable then) {
        if (file.equals(activeFile())) {
            then.run();
        } else {
            bus.publish(new CoreApplicationEvents.FileOpenRequestedEvent(file));
            // The switch is delivered through runLater; this is queued behind it, so it runs on the new file.
            FxThread.INSTANCE.later(then);
        }
    }

    private Path activeFile() {
        ProjectFile active = state.getActiveFile();
        return active == null ? null : active.getPath();
    }

    /** The block the canvas shows for {@code line} of the open file, preferring a statement to what shares its line. */
    private CodeBlock blockAt(int line) {
        Map<ASTNode, CodeBlock> map = state.getNodeToBlockMap();
        if (map != indexedMap) {
            CompilationUnit cu = state.getCompilationUnit().orElse(null);
            Map<Integer, CodeBlock> index = new HashMap<>();
            for (CodeBlock block : map.values()) {
                if (block == null) continue;
                int at = block.getBreakpointLine(cu);
                if (at > 0 && (!index.containsKey(at) || block instanceof StatementBlock)) index.put(at, block);
            }
            indexedMap = map;
            lineIndex = index;
        }
        CodeBlock block = lineIndex.get(line);
        return block == null ? null : block.getHighlightTarget();
    }
}
