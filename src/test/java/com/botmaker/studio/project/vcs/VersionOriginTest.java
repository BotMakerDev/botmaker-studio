package com.botmaker.studio.project.vcs;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionOriginTest {

    @Test
    void everyOriginRoundTripsThroughItsTrailer() {
        for (VersionOrigin origin : VersionOrigin.values()) {
            assertEquals(origin, VersionOrigin.of(origin.stamp("Some label")), origin.name());
        }
    }

    @Test
    void idsAreDistinctAndFromIdIsTotal() {
        assertEquals(VersionOrigin.values().length,
                Arrays.stream(VersionOrigin.values()).map(VersionOrigin::id).distinct().count());
        assertEquals(VersionOrigin.UNKNOWN, VersionOrigin.fromId("from-a-newer-studio"));
        assertEquals(VersionOrigin.UNKNOWN, VersionOrigin.fromId(null));
    }

    @Test
    void aCommitWithoutTheTrailerIsUnknown() {
        assertEquals(VersionOrigin.UNKNOWN, VersionOrigin.of("Fix the loop"));
        assertEquals(VersionOrigin.UNKNOWN, VersionOrigin.of("Fix the loop\n\nIt ran twice."));
        assertEquals(VersionOrigin.UNKNOWN, VersionOrigin.of(null));
    }

    @Test
    void onlyTheLastParagraphIsATrailerBlock() {
        String body = "Explain BotMaker-Origin: save in prose\n\nBotMaker-Origin: save is mentioned here too\n\n"
                + "Co-Authored-By: someone";
        assertEquals(VersionOrigin.UNKNOWN, VersionOrigin.of(body));
        assertEquals(VersionOrigin.AI,
                VersionOrigin.of("AI: Claude Code\n\nCo-Authored-By: x\nBotMaker-Origin: ai\n"));
    }

    @Test
    void onlyTheUnchosenVersionsFold() {
        assertTrue(VersionOrigin.AUTO.automatic());
        assertTrue(VersionOrigin.SAFETY.automatic());
        assertFalse(VersionOrigin.SAVE.automatic());
        assertFalse(VersionOrigin.AI.automatic());
    }
}
