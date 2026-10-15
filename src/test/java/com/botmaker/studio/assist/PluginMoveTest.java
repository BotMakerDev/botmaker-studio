package com.botmaker.studio.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PluginMoveTest {

    private final PluginMove asked = PluginMove.asked("SDK", "1.4.4", "1.4.5-SNAPSHOT");

    @Test
    void aMoveIsBusyUntilItEnds() {
        assertTrue(asked.busy());
        assertTrue(asked.moving().busy());
        assertFalse(asked.moving().moved("Moved.").busy());
        assertFalse(asked.declined("no").busy());
        assertFalse(asked.moving().failed("boom").busy());
    }

    @Test
    void eachStageSaysWhereTheMoveIs() {
        assertEquals("Moving SDK from 1.4.4 to 1.4.5-SNAPSHOT: the user is asked on screen.", asked.line());
        assertEquals("Moving SDK from 1.4.4 to 1.4.5-SNAPSHOT: the user agreed, and the bot is being repaired.",
                asked.moving().line());
        assertEquals("Last move: Moved SDK 1.4.4 → 1.4.5-SNAPSHOT.",
                asked.moving().moved("Moved SDK 1.4.4 → 1.4.5-SNAPSHOT. ").line());
        assertEquals("Not moved SDK from 1.4.4 to 1.4.5-SNAPSHOT: the user did not agree.",
                asked.declined("the user did not agree.").line());
        assertEquals("Moving SDK from 1.4.4 to 1.4.5-SNAPSHOT failed: Studio did not move SDK in 600s. It may have "
                        + "moved part way; the version saved before it is in versions list, for versions revert.",
                asked.moving().failed("Studio did not move SDK in 600s.").line());
        assertEquals("Not moved SDK from 1.4.4 to 1.4.5-SNAPSHOT:", asked.declined(null).line().strip(),
                "a reason that is null says nothing, not null");
    }

    @Test
    void theConfirmedCallSaysWhereToLookNext() {
        assertTrue(asked.started().startsWith("The user is being asked on screen to move SDK from 1.4.4 to "
                + "1.4.5-SNAPSHOT."));
        assertTrue(asked.started().contains("plugins list"));
    }
}
