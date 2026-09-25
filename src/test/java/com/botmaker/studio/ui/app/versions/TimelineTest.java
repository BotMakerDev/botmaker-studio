package com.botmaker.studio.ui.app.versions;

import com.botmaker.studio.project.vcs.ProjectVcs.CommitInfo;
import com.botmaker.studio.project.vcs.VersionOrigin;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the Simple timeline folds, and what it never hides. */
class TimelineTest {

    private static int next;

    private static CommitInfo commit(VersionOrigin origin) {
        return commit(origin, null);
    }

    private static CommitInfo commit(VersionOrigin origin, String name) {
        String sha = "%040d".formatted(++next);
        return new CommitInfo(sha, sha.substring(0, 7), origin.displayName(), origin, name, "me",
                Instant.EPOCH, List.of());
    }

    @Test
    void runsOfUnchosenVersionsFoldBetweenMilestones() {
        CommitInfo save = commit(VersionOrigin.SAVE);
        List<CommitInfo> autos = List.of(commit(VersionOrigin.AUTO), commit(VersionOrigin.SAFETY),
                commit(VersionOrigin.AUTO));
        CommitInfo created = commit(VersionOrigin.CREATE);
        List<CommitInfo> history = new ArrayList<>(List.of(save));
        history.addAll(autos);
        history.add(created);

        List<Timeline.Row> rows = Timeline.rows(history, 0, Set.of());

        assertEquals(3, rows.size(), rows.toString());
        assertEquals(save, ((Timeline.Version) rows.get(0)).commit());
        Timeline.Fold fold = assertInstanceOf(Timeline.Fold.class, rows.get(1));
        assertEquals(autos, fold.commits());
        assertEquals("·· 3 automatic versions", fold.label());
        assertEquals(created, ((Timeline.Version) rows.get(2)).commit());
    }

    @Test
    void anOpenedFoldListsItsVersionsQuietly() {
        List<CommitInfo> autos = List.of(commit(VersionOrigin.AUTO), commit(VersionOrigin.AUTO));
        String key = autos.getFirst().sha();

        List<Timeline.Row> rows = Timeline.rows(autos, 0, Set.of(key));

        assertTrue(((Timeline.Fold) rows.get(0)).expanded());
        assertEquals(3, rows.size());
        assertTrue(((Timeline.Version) rows.get(1)).quiet());
    }

    @Test
    void aSingleUnchosenVersionIsNotFolded() {
        CommitInfo auto = commit(VersionOrigin.AUTO);
        List<Timeline.Row> rows = Timeline.rows(List.of(commit(VersionOrigin.SAVE), auto), 0, Set.of());

        Timeline.Version row = assertInstanceOf(Timeline.Version.class, rows.get(1));
        assertEquals(auto, row.commit());
        assertTrue(row.quiet());
    }

    @Test
    void aNamedAutomaticVersionIsAMilestone() {
        CommitInfo named = commit(VersionOrigin.AUTO, "It worked here");
        List<Timeline.Row> rows = Timeline.rows(
                List.of(commit(VersionOrigin.AUTO), named, commit(VersionOrigin.AUTO)), 0, Set.of());

        assertEquals(3, rows.size(), "the name splits the run: nothing left to fold");
        Timeline.Version middle = assertInstanceOf(Timeline.Version.class, rows.get(1));
        assertFalse(middle.quiet());
        assertEquals("It worked here", middle.commit().title());
    }

    @Test
    void anAiSessionAndAnUpdateAreNeverFolded() {
        List<Timeline.Row> rows = Timeline.rows(
                List.of(commit(VersionOrigin.AI), commit(VersionOrigin.UPDATE)), 0, Set.of());
        assertTrue(rows.stream().allMatch(r -> r instanceof Timeline.Version v && !v.quiet()));
    }

    @Test
    void historyWithoutTrailersFoldsAsMore() {
        List<Timeline.Row> rows = Timeline.rows(
                List.of(commit(VersionOrigin.UNKNOWN), commit(VersionOrigin.AUTO)), 0, Set.of());
        assertEquals("·· 2 more versions", ((Timeline.Fold) rows.getFirst()).label());
    }

    @Test
    void devListsEveryVersionUnfolded() {
        List<CommitInfo> history = List.of(commit(VersionOrigin.UNKNOWN), commit(VersionOrigin.AUTO),
                commit(VersionOrigin.AUTO), commit(VersionOrigin.SAVE));
        List<Timeline.Row> rows = Timeline.all(history, 1);

        assertEquals(new Timeline.Unsaved(1), rows.getFirst());
        assertEquals(5, rows.size());
        assertTrue(rows.stream().noneMatch(r -> r instanceof Timeline.Fold));
        assertTrue(((Timeline.Version) rows.get(2)).quiet());
        assertFalse(((Timeline.Version) rows.get(4)).quiet());
        assertEquals(VersionsView.SIMPLE, VersionsView.fromId("nonsense"));
        assertEquals(VersionsView.DEV, VersionsView.fromId("dev"));
    }

    @Test
    void unsavedChangesArePinnedFirstOnlyWhenThereAreSome() {
        List<CommitInfo> history = List.of(commit(VersionOrigin.CREATE));
        assertEquals(new Timeline.Unsaved(2), Timeline.rows(history, 2, Set.of()).getFirst());
        assertInstanceOf(Timeline.Version.class, Timeline.rows(history, 0, Set.of()).getFirst());
    }
}
