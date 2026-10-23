package com.botmaker.studio.project.vcs;

import com.botmaker.studio.project.vcs.SyncModel.Action;
import com.botmaker.studio.project.vcs.SyncModel.Facts;
import com.botmaker.studio.project.vcs.SyncModel.Ownership;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ownership from the remotes and the login, and what the strip offers for each ({@code 39} §2). */
class SyncModelTest {

    private static final String ALICE_BOT = "https://github.com/alice/miner.git";
    private static final String BOB_FORK = "https://github.com/bob/miner.git";
    private static final String BOB_OWN = "https://github.com/bob/farmer.git";

    private static SyncModel model(String login, String mine, String original, int unsaved, int notIn,
                                   String installed, String available) {
        return SyncModel.of(new Facts(login, mine, original, unsaved, notIn, installed, available, false, false));
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
    void someoneElsesBotWithNoForkYetSavesToOne() {
        SyncModel m = model("bob", null, ALICE_BOT, 0, -1, "v1.0.0", null);
        assertEquals(Ownership.OTHERS, m.ownership());
        assertEquals(Action.SAVE_TO_MY_COPY, m.main());
        assertEquals(List.of(Action.SUGGEST, Action.STOP_FOLLOWING), m.also(), "no publish before the fork");
        assertEquals("Not saved to your copy yet", m.myCopy());
        assertEquals("studio/main", m.remoteBranch("main"));
    }

    /** A fork published as its own bot, still following its author (2026-10-23). */
    @Test
    void aForkSavesBesideTheAuthorsLineAndPublishesAsItsOwnBot() {
        SyncModel m = model("bob", BOB_FORK, ALICE_BOT, 0, 0, "v1.0.0", "v1.2.0");
        assertEquals(Ownership.FORK, m.ownership());
        assertEquals(Action.SAVE_TO_MY_COPY, m.main());
        assertEquals(List.of(Action.GET_UPDATE, Action.PUBLISH, Action.SUGGEST, Action.STOP_FOLLOWING), m.also());
        assertEquals("Up to date", m.myCopy());
        assertEquals("v1.2.0 available", m.original());
        assertEquals("studio/main", m.remoteBranch("main"));
        assertEquals("bob/miner", m.mineSlug().orElseThrow().toString());
    }

    @Test
    void aCopyOnSomeoneElsesAccountIsNoFork() {
        SyncModel m = model("bob", "https://github.com/carol/miner.git", ALICE_BOT, 0, 0, "v1.0.0", null);
        assertEquals(Ownership.OTHERS, m.ownership());
    }

    /** Stop following removes original: the fork is the user's own published bot, the bot with no remote a new one. */
    @Test
    void aBotThatStoppedFollowingIsTheUsersOwn() {
        SyncModel fork = model("bob", BOB_FORK, null, 0, 0, null, null);
        assertEquals(Ownership.OWN_PUBLISHED, fork.ownership());
        assertEquals(List.of(Action.PUBLISH), fork.also());
        assertNull(fork.original());
        assertEquals("main", fork.remoteBranch("main"), "never pushed beside: its own main");
        SyncModel pushed = SyncModel.of(new Facts("bob", BOB_FORK, null, 0, 0, null, null, false, true));
        assertEquals("studio/main", pushed.remoteBranch("main"), "the line it pushed as a fork stays where it was");
        assertEquals(Ownership.OWN_NEW, model("bob", null, null, 0, -1, null, null).ownership());
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
        SyncModel others = model("bob", null, ALICE_BOT, 0, -1, "v1.0.0", "v1.2.0");
        assertEquals(List.of(Action.GET_UPDATE, Action.SUGGEST, Action.STOP_FOLLOWING), others.also());
        assertEquals(List.of(Action.SUGGEST, Action.STOP_FOLLOWING),
                model("bob", null, ALICE_BOT, 0, -1, "v1.2.0", null).also());
    }

    @Test
    void aHalfDoneUpdateComesFirst() {
        SyncModel m = SyncModel.of(new Facts("bob", BOB_FORK, ALICE_BOT, 1, 0, "v1.0.0", "v1.2.0", true, false));
        assertEquals(Action.FINISH_UPDATE, m.main());
        assertTrue(m.also().isEmpty());
        assertEquals("Updating — not finished", m.thisComputer());
    }

    @Test
    void slugReadsGitHubUrlsOnly() {
        assertEquals("alice/miner", SyncModel.slug("https://github.com/alice/miner.git").orElseThrow().toString());
        assertEquals("alice/miner", SyncModel.slug("https://github.com/alice/miner").orElseThrow().toString());
        assertEquals("BotMakerDev/botmaker-gamebot",
                SyncModel.slug("git@github.com:BotMakerDev/botmaker-gamebot.git").orElseThrow().toString());
        assertEquals("alice/miner", SyncModel.slug("ssh://git@github.com/alice/miner.git").orElseThrow().toString());
        assertTrue(SyncModel.slug("file:///tmp/repo").isEmpty());
        assertTrue(SyncModel.slug(null).isEmpty());
    }

    /**
     * The template's provenance still names the account it was published from before the repository moved to
     * the organization; it is the project's own release, never an original to link (and so never "someone
     * else's bot").
     */
    @Test
    void ownProvenanceIsTheCopysRepositoryUnderItsOwnerOrTheUser() {
        var mine = SyncModel.slug("git@github.com:BotMakerDev/botmaker-gamebot.git");
        assertTrue(SyncModel.ownProvenance(new SyncModel.Slug("LiQiyeDev", "botmaker-gamebot"), mine, "LiQiyeDev"));
        assertTrue(SyncModel.ownProvenance(new SyncModel.Slug("botmakerdev", "botmaker-gamebot"), mine, null));
        assertFalse(SyncModel.ownProvenance(new SyncModel.Slug("alice", "botmaker-gamebot"), mine, "bob"),
                "an author's bot of the same name is theirs");
        assertFalse(SyncModel.ownProvenance(new SyncModel.Slug("LiQiyeDev", "other"), mine, "LiQiyeDev"));
        assertFalse(SyncModel.ownProvenance(new SyncModel.Slug("LiQiyeDev", "botmaker-gamebot"),
                java.util.Optional.empty(), "LiQiyeDev"), "no copy: an installed bot's provenance");
    }

    @Test
    void newestIsTheHighestReleaseAfterTheInstalledOne() {
        assertEquals("v1.10.0", SyncModel.newest(List.of("v1.2.0", "v1.10.0", "nightly", "v1.9.9"), "v1.2.0"));
        assertNull(SyncModel.newest(List.of("v1.2.0", "v1.0.0"), "v1.2.0"));
        assertEquals("1.0.0", SyncModel.newest(List.of("1.0.0"), null));
    }
}
