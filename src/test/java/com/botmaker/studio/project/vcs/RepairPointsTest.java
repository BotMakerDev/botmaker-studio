package com.botmaker.studio.project.vcs;

import com.botmaker.cli.repair.Compile;
import com.botmaker.cli.repair.Damage;
import com.botmaker.studio.sharing.BotPublisher;
import com.botmaker.studio.sharing.PublishPlan;
import com.botmaker.studio.sharing.PublishRequest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairPointsTest {

    private static byte[] readAt(Path dir, String commit, String path) throws IOException {
        try (Repository repo = new ProjectVcs(dir).open().getRepository(); RevWalk walk = new RevWalk(repo)) {
            RevCommit c = walk.parseCommit(repo.resolve(commit));
            try (TreeWalk tree = TreeWalk.forPath(repo, path, c.getTree())) {
                return tree == null ? null : repo.open(tree.getObjectId(0)).getBytes();
            }
        }
    }

    private static String messageOf(Path dir, String commit) throws IOException {
        try (Repository repo = new ProjectVcs(dir).open().getRepository(); RevWalk walk = new RevWalk(repo)) {
            return walk.parseCommit(repo.resolve(commit)).getFullMessage();
        }
    }

    private static String indexAndHead(Path dir) throws IOException {
        try (Git git = new ProjectVcs(dir).open()) {
            Repository repo = git.getRepository();
            return repo.getFullBranch() + " " + repo.resolve(Constants.HEAD).name() + " "
                    + git.status().call().getUntracked() + git.status().call().getModified();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static Path project(Path dir) throws IOException {
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        new ProjectVcs(dir).init();
        return dir;
    }

    @Test
    void aCompileMovesTheRecordOutsideTheBranch(@TempDir Path dir) throws IOException {
        project(dir);
        Files.writeString(dir.resolve("Bot.java"), "class Bot { int kept; }");
        Files.writeString(dir.resolve("New.java"), "class New {}");
        String before = indexAndHead(dir);
        RepairPoints points = new RepairPoints(dir);

        assertTrue(points.recordCompiled());

        assertEquals(before, indexAndHead(dir), "neither the branch nor the index moves");
        String record = points.lastCompiling().orElseThrow();
        assertEquals("class New {}", new String(readAt(dir, record, "New.java")));
        assertEquals("class Bot { int kept; }", new String(readAt(dir, record, "Bot.java")));
        assertEquals(1, new ProjectVcs(dir).history().size(), "no line in the timeline");
        assertFalse(points.recordCompiled(), "nothing changed, so it stays");
        assertEquals(record, points.lastCompiling().orElseThrow());
    }

    @Test
    void theRecordLeavesOutWhatGitIgnoresAndWhatIsGone(@TempDir Path dir) throws IOException {
        project(dir);
        Files.writeString(dir.resolve("Other.java"), "class Other {}");
        new ProjectVcs(dir).checkpoint(VersionOrigin.SAVE, "two files");
        Files.delete(dir.resolve("Other.java"));
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("target/Bot.class"), "built");
        RepairPoints points = new RepairPoints(dir);

        points.recordCompiled();

        String record = points.lastCompiling().orElseThrow();
        assertNull(readAt(dir, record, "Other.java"));
        assertNull(readAt(dir, record, "target/Bot.class"));
    }

    @Test
    void aHeldRecordStaysUntilReleased(@TempDir Path dir) throws IOException {
        project(dir);
        RepairPoints points = new RepairPoints(dir);
        points.recordCompiled();
        String record = points.lastCompiling().orElseThrow();
        points.hold();
        Files.writeString(dir.resolve("Bot.java"), "class Bot { /* half gone */ }");

        assertFalse(points.recordCompiled());
        assertEquals(record, points.lastCompiling().orElseThrow());
        assertTrue(new RepairPoints(dir).held(), "the hold outlives this Studio");

        points.release();
        assertTrue(points.recordCompiled());
    }

    @Test
    void noRepositoryGetsNoRecordAndOneIsNotMade(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Bot.java"), "class Bot {}");
        assertFalse(new RepairPoints(dir).recordCompiled());
        assertFalse(new ProjectVcs(dir).isRepo());
    }

    @Test
    void aSharedRepositoryGetsNoRecordAndNoSaves(@TempDir Path dir, @TempDir Path elsewhere) throws IOException {
        project(elsewhere);
        Files.writeString(dir.resolve(".git"), "gitdir: " + elsewhere.resolve(".git") + "\n");
        Files.writeString(dir.resolve("Bot.java"), "class Bot { int mine; }");
        RepairPoints points = new RepairPoints(dir);

        assertFalse(points.recordCompiled());
        assertEquals(Optional.empty(), points.save("Before a repair"));
        assertEquals(Optional.empty(), points.lastCompiling());
    }

    @Test
    void aSaveHoldsEveryByteAndMovesNoBranch(@TempDir Path dir) throws IOException {
        project(dir);
        byte[] picture = new byte[70_000];
        for (int i = 0; i < picture.length; i++) picture[i] = (byte) i;
        Files.createDirectories(dir.resolve("src/main/resources/images"));
        Files.write(dir.resolve("src/main/resources/images/play.png"), picture);
        byte[] broken = "class Bot { // half of it\r\n\u0000".getBytes();
        Files.write(dir.resolve("Bot.java"), broken);
        String before = indexAndHead(dir);

        String branch = new RepairPoints(dir).save("Before a repair").orElseThrow();

        assertTrue(branch.matches("botmaker/saved/\\d{4}-\\d{2}-\\d{2}-\\d{4}"), branch);
        assertEquals(before, indexAndHead(dir));
        assertArrayEquals(picture, readAt(dir, branch, "src/main/resources/images/play.png"), "untracked");
        assertArrayEquals(broken, readAt(dir, branch, "Bot.java"), "modified");
        assertArrayEquals(broken, Files.readAllBytes(dir.resolve("Bot.java")), "the disk is left alone");
    }

    @Test
    void aSaveNamesTheLastCompilingCommitWhichThenMovesOn(@TempDir Path dir) throws IOException {
        project(dir);
        RepairPoints points = new RepairPoints(dir);
        Files.writeString(dir.resolve("Bot.java"), "class Bot { int a; }");
        points.recordCompiled();
        String then = points.lastCompiling().orElseThrow();

        String branch = points.save("Before a repair").orElseThrow();
        Files.writeString(dir.resolve("Bot.java"), "class Bot { int b; }");
        points.recordCompiled();

        String message = messageOf(dir, branch);
        assertEquals(VersionOrigin.SAVED_STATE, VersionOrigin.of(message));
        assertEquals(Optional.of(then), RepairPoints.lastCompilingOf(message));
        assertFalse(then.equals(points.lastCompiling().orElseThrow()));
    }

    /** The trailer names a commit the next compile orphans, so the save itself keeps it. */
    @Test
    void aSaveKeepsItsLastCompilingCommitAlive(@TempDir Path dir) throws IOException {
        project(dir);
        RepairPoints points = new RepairPoints(dir);
        Files.writeString(dir.resolve("Bot.java"), "class Bot { int a; }");
        points.recordCompiled();
        String then = points.lastCompiling().orElseThrow();

        String branch = points.save("Before a repair").orElseThrow();

        try (Repository repo = new ProjectVcs(dir).open().getRepository(); RevWalk walk = new RevWalk(repo)) {
            assertTrue(walk.isMergedInto(walk.parseCommit(repo.resolve(then)), walk.parseCommit(repo.resolve(branch))));
        }
    }

    @Test
    void aSaveHoldsLineEndingsGitWouldNormalize(@TempDir Path dir) throws IOException {
        project(dir);
        Files.writeString(dir.resolve(".gitattributes"), "* text=auto\n");
        String crlf = "class Bot {\r\n  int a;\r\n}\r\n";
        Files.writeString(dir.resolve("Bot.java"), crlf);

        String branch = new RepairPoints(dir).save("Before a repair").orElseThrow();

        assertEquals(crlf, new String(readAt(dir, branch, "Bot.java")));
    }

    @Test
    void aVersionsTimeIsWhenItWasWritten(@TempDir Path dir) throws Exception {
        project(dir);
        ProjectVcs vcs = new ProjectVcs(dir);
        Thread.sleep(1100);
        Files.writeString(dir.resolve("Bot.java"), "class Bot { int a; }");
        Instant before = Instant.now().minusMillis(999);
        vcs.checkpoint(VersionOrigin.SAVE, "later");
        assertFalse(vcs.history().getFirst().when().isBefore(before));
    }

    @Test
    void theFallbackTriesTheSameSourcesOnce(@TempDir Path dir) throws IOException {
        project(dir);
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("a.png"), "one");
        vcs.checkpoint(VersionOrigin.SAVE, "a picture");
        Files.writeString(dir.resolve("b.png"), "two");
        vcs.checkpoint(VersionOrigin.SAVE, "another");
        List<Path> tried = new ArrayList<>();

        new RepairPoints(dir).newestCompiling(folder -> {
            tried.add(folder);
            return false;
        });

        assertEquals(1, tried.size());
    }

    @Test
    void aSaveWithNoRecordNamesNone(@TempDir Path dir) throws IOException {
        project(dir);
        String branch = new RepairPoints(dir).save("Before a repair").orElseThrow();
        String message = messageOf(dir, branch);
        assertEquals(VersionOrigin.SAVED_STATE, VersionOrigin.of(message));
        assertEquals(Optional.empty(), RepairPoints.lastCompilingOf(message));
    }

    @Test
    void twoSavesInOneMinuteAreTwoBranches(@TempDir Path dir) throws IOException {
        project(dir);
        RepairPoints points = new RepairPoints(dir);
        String first = points.save("One").orElseThrow();
        String second = points.save("Two").orElseThrow();
        assertFalse(first.equals(second));
    }

    @Test
    void aPushCarriesNeitherTheRecordNorASave(@TempDir Path dir, @TempDir Path remote) throws Exception {
        project(dir);
        try (Git bare = Git.init().setBare(true).setDirectory(remote.toFile()).call()) {
            ProjectVcs vcs = new ProjectVcs(dir);
            RepairPoints points = new RepairPoints(dir);
            Files.writeString(dir.resolve("Bot.java"), "class Bot { int a; }");
            points.recordCompiled();
            points.save("Before a repair");
            try (Git git = vcs.open()) {
                git.remoteAdd().setName("elsewhere").setUri(new URIish(remote.toUri().toString())).call();
            }

            vcs.push("elsewhere", vcs.branch(), null, false);

            List<String> refs = bare.getRepository().getRefDatabase().getRefs().stream().map(r -> r.getName())
                    .filter(name -> !name.equals(Constants.HEAD)).toList();
            assertEquals(List.of("refs/heads/" + vcs.branch()), refs);
        }
    }

    @Test
    void savesAreNotOfferedAsBranchesUnlessOnOne(@TempDir Path dir) throws IOException {
        project(dir);
        ProjectVcs vcs = new ProjectVcs(dir);
        String main = vcs.branch();
        String branch = new RepairPoints(dir).save("Before a repair").orElseThrow();

        assertEquals(List.of(main), vcs.branches());
        vcs.switchTo(branch);
        assertEquals(List.of(branch, main), vcs.branches());
    }

    @Test
    void theFallbackIsTheNewestCommitThatCompiles(@TempDir Path dir) throws IOException {
        project(dir);
        ProjectVcs vcs = new ProjectVcs(dir);
        Files.writeString(dir.resolve("Bot.java"), "class Bot { int good; }");
        vcs.checkpoint(VersionOrigin.SAVE, "good");
        String good = vcs.history().getFirst().sha();
        Files.writeString(dir.resolve("Bot.java"), "class Bot {");
        vcs.checkpoint(VersionOrigin.SAVE, "bad");
        Files.createDirectories(dir.resolve("images"));
        Files.writeString(dir.resolve("images/play.png"), "picture");
        Files.writeString(dir.resolve("Bot.java"), "class Bot { int");
        vcs.checkpoint(VersionOrigin.SAVE, "worse");
        List<String> tried = new ArrayList<>();

        Optional<String> found = new RepairPoints(dir).newestCompiling(folder -> {
            try {
                String source = Files.readString(folder.resolve("Bot.java"));
                tried.add(source);
                assertFalse(Files.exists(folder.resolve("images")), "only what a compile reads");
                return source.endsWith("}") && source.contains("good");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });

        assertEquals(Optional.of(good), found);
        assertEquals(List.of("class Bot { int", "class Bot {", "class Bot { int good; }"), tried);
    }

    @Test
    void theFallbackCanFindNothing(@TempDir Path dir) throws IOException {
        project(dir);
        assertEquals(Optional.empty(), new RepairPoints(dir).newestCompiling(folder -> false));
    }

    @Test
    void aPublishFromASaveIsRefused(@TempDir Path dir) throws IOException {
        project(dir);
        ProjectVcs vcs = new ProjectVcs(dir);
        vcs.switchTo(new RepairPoints(dir).save("Before a repair").orElseThrow());
        PublishRequest request = new PublishRequest(dir, "Bot", "bot", "", "1.0.1", List.of(), false);

        PublishPlan plan = new BotPublisher(null, null).start(request, vcs, "me").resume(p -> { });

        assertTrue(plan.failure().orElseThrow().detail().contains("saved state"));
    }

    @Test
    void theDamageIsToldAgainstTheRecord(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("Bot.java"), "class Bot { void a() { } void b() { } }");
        Files.write(dir.resolve("play.png"), new byte[]{1, 2});
        new ProjectVcs(dir).init();
        RepairPoints points = new RepairPoints(dir);
        points.recordCompiled();
        assertTrue(points.damage(Compile.clean()).clean());

        Files.writeString(dir.resolve("Bot.java"), "class Bot { void a() { } }");
        Files.delete(dir.resolve("play.png"));
        Files.delete(dir.resolve("pom.xml"));

        List<Damage.Row> rows = points.damage(Compile.clean()).rows();
        assertEquals(List.of(Damage.Kind.NO_POM, Damage.Kind.FILES_GONE, Damage.Kind.MEMBERS_GONE),
                rows.stream().map(Damage.Row::kind).toList());
        assertEquals(Damage.Repair.PUT_BACK_FILES, rows.getFirst().repair());
        assertEquals(List.of("play.png"), rows.get(1).files());
        assertEquals(List.of("Bot#b()"), rows.get(2).members());
    }

    @Test
    void withNoRepositoryTheDamageSaysSo(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        assertEquals(List.of(Damage.Kind.NO_GIT), new RepairPoints(dir).damage(Compile.notRun()).rows().stream()
                .map(Damage.Row::kind).toList());
    }

    @Test
    void withNothingThatCompilesTheDamageSaysSo(@TempDir Path dir) throws IOException {
        project(dir); // No pom in any commit, so Maven builds none of them.
        assertEquals(List.of(Damage.Kind.NO_POM, Damage.Kind.NOTHING_TO_COMPARE),
                new RepairPoints(dir).damage(Compile.notRun()).rows().stream().map(Damage.Row::kind).toList());
    }

    @Test
    void aSavedBranchIsKnownByItsName() {
        assertTrue(RepairPoints.isSavedBranch("botmaker/saved/2026-10-30-1200"));
        assertFalse(RepairPoints.isSavedBranch("main"));
        assertFalse(RepairPoints.isSavedBranch(null));
    }
}
