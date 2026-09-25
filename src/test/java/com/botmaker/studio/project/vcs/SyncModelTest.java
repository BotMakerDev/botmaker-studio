package com.botmaker.studio.project.vcs;

import com.botmaker.studio.project.vcs.SyncModel.Action;
import com.botmaker.studio.project.vcs.SyncModel.Facts;
import com.botmaker.studio.project.vcs.SyncModel.Ownership;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ownership from the remotes and the login, and what the strip offers for each ({@code 39} §2). */
class SyncModelTest {

    private static final String ALICE_BOT = "https://github.com/alice/miner.git";
    private static final String BOB_FORK = "https://github.com/bob/miner.git";
    private static final String BOB_OWN = "https://github.com/bob/farmer.git";

    private static SyncModel model(String login, String mine, String original, int unsaved, int notIn,
                                   String installed, String available) {
        return SyncModel.of(new Facts(login, mine, original, unsaved, notIn, installed, available, false));
    }

    @Test
    void signedOutIsLocalOnlyWhateverTheRemotes() {
        SyncModel m = model(null, BOB_FORK, ALICE_BOT, 0, 0, "v1.0.0", null);
        assertEquals(Ownership.LOCAL_ONLY, m.ownership());
        assertEquals(Action.SAVE_VERSION, m.main());
        assertEquals(List.of(Action.SIGN_IN), m.also());
        assertEquals("Latest (v1.0.0)", m.original(), "the original still shows: it needs no account");
    }

    @Test
    void anOwnBotNotOnGitHubPublishes() {
        SyncModel m = model("bob", null, null, 2, -1, null, null);
        assertEquals(Ownership.OWN_NEW, m.ownership());
        assertEquals(Action.PUBLISH, m.main());
        assertEquals(List.of(Action.SAVE_TO_MY_COPY), m.also());
        assertEquals("2 unsaved changes", m.thisComputer());
        assertEquals("Not saved to your copy yet", m.myCopy());
        assertNull(m.original());
    }

    @Test
    void anOwnBotOnGitHubSavesToItsCopy() {
        SyncModel m = model("bob", BOB_OWN, null, 0, 3, null, null);
        assertEquals(Ownership.OWN_PUBLISHED, m.ownership());
        assertEquals(Action.SAVE_TO_MY_COPY, m.main());
        assertEquals(List.of(Action.PUBLISH), m.also());
        assertEquals("Saved", m.thisComputer());
        assertEquals("3 versions not in your copy", m.myCopy());
        assertEquals("main", m.remoteBranch("main"));
    }

    @Test
    void someoneElsesBotSavesToAForkBesideTheAuthorsLine() {
        SyncModel m = model("bob", BOB_FORK, ALICE_BOT, 0, 0, "v1.0.0", "v1.2.0");
        assertEquals(Ownership.OTHERS, m.ownership());
        assertEquals(Action.SAVE_TO_MY_COPY, m.main());
        assertEquals("Up to date", m.myCopy());
        assertEquals("v1.2.0 available", m.original());
        assertEquals("studio/main", m.remoteBranch("main"));
        assertEquals("bob/miner", m.mineSlug().orElseThrow().toString());
    }

    @Test
    void theAuthorInstallingTheirOwnBotOwnsIt() {
        SyncModel m = model("Alice", null, ALICE_BOT, 0, -1, "v1.0.0", null);
        assertEquals(Ownership.OWN_PUBLISHED, m.ownership(), "logins compare without case");
        assertEquals("alice/miner", m.mineSlug().orElseThrow().toString());
    }

    @Test
    void aNewerReleaseIsOfferedByNameEvenSignedOut() {
        SyncModel signedOut = model(null, null, ALICE_BOT, 0, -1, "v1.0.0", "v1.2.0");
        assertEquals(List.of(Action.GET_UPDATE, Action.SIGN_IN), signedOut.also());
        assertEquals("Get v1.2.0", signedOut.label(Action.GET_UPDATE));
        SyncModel others = model("bob", BOB_FORK, ALICE_BOT, 0, 0, "v1.0.0", "v1.2.0");
        assertEquals(List.of(Action.GET_UPDATE, Action.SUGGEST), others.also());
        assertEquals(List.of(Action.SUGGEST), model("bob", BOB_FORK, ALICE_BOT, 0, 0, "v1.2.0", null).also());
    }

    @Test
    void aHalfDoneUpdateComesFirst() {
        SyncModel m = SyncModel.of(new Facts("bob", BOB_FORK, ALICE_BOT, 1, 0, "v1.0.0", "v1.2.0", true));
        assertEquals(Action.FINISH_UPDATE, m.main());
        assertTrue(m.also().isEmpty());
        assertEquals("Updating — files to decide", m.thisComputer());
    }

    @Test
    void slugReadsGitHubUrlsOnly() {
        assertEquals("alice/miner", SyncModel.slug("https://github.com/alice/miner.git").orElseThrow().toString());
        assertEquals("alice/miner", SyncModel.slug("https://github.com/alice/miner").orElseThrow().toString());
        assertTrue(SyncModel.slug("file:///tmp/repo").isEmpty());
        assertTrue(SyncModel.slug(null).isEmpty());
    }

    @Test
    void newestIsTheHighestReleaseAfterTheInstalledOne() {
        assertEquals("v1.10.0", SyncModel.newest(List.of("v1.2.0", "v1.10.0", "nightly", "v1.9.9"), "v1.2.0"));
        assertNull(SyncModel.newest(List.of("v1.2.0", "v1.0.0"), "v1.2.0"));
        assertEquals("1.0.0", SyncModel.newest(List.of("1.0.0"), null));
    }
}
