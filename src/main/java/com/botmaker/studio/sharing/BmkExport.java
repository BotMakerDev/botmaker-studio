package com.botmaker.studio.sharing;

import com.botmaker.cli.bmk.BotFile;
import com.botmaker.cli.bmk.BotFiles;
import com.botmaker.cli.bmk.BotJson;
import com.botmaker.cli.publish.PublishTarget;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.Remote;
import com.botmaker.studio.services.MavenService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <i>Project ▸ Export .bmk…</i> (2026-10-25, spec § 2): the project at its tagged version written as one file
 * ({@link BotFile#write}), for someone who only runs bots. What {@code botmaker bot export} does, with Studio's own
 * git (JGit) and resolver: the sources are the commit's blobs, the jars the pom resolves to.
 */
public final class BmkExport {

    private BmkExport() {}

    /** The jars a project runs with, by coordinate: {@link MavenService#resolvedJars}; a test's list. */
    @FunctionalInterface
    public interface Jars {
        List<BotFile.Jar> of(Path projectDir) throws IOException;
    }

    /**
     * Writes {@code projectDir}'s tagged version into {@code outDir} as {@code <Name>-<version>.bmk} and returns
     * it. Blocking: off the FX thread.
     *
     * @throws BotFile.Refused with the reason for the user: changes not saved as a version, no tag or several, no
     *                         GitHub repository, no {@code main}
     */
    public static Path export(Path projectDir, Path outDir, Jars jars) throws IOException {
        ProjectVcs vcs = new ProjectVcs(projectDir);
        Optional<String> commit = vcs.headCommit();
        if (commit.isEmpty() || !vcs.status().isClean()) {
            throw new BotFile.Refused("Commit first: a .bmk is made from a commit. Save a version (Versions tab), "
                    + "then export.");
        }
        List<String> tags = vcs.tagsAtHead().stream().sorted().toList();
        if (tags.isEmpty()) {
            throw new BotFile.Refused("This version has no tag: a .bmk names the release it is. Publish it first "
                    + "(Project ▸ Publish to Gallery…), which tags it.");
        }
        if (tags.size() > 1) {
            throw new BotFile.Refused("This version has several tags (" + String.join(", ", tags)
                    + "); a .bmk names one.");
        }
        String slug = Optional.ofNullable(vcs.remoteUrl(Remote.MINE))
                .or(() -> Optional.ofNullable(vcs.remoteUrl(Remote.ORIGINAL)))
                .flatMap(PublishTarget::slugOf)
                .orElseThrow(() -> new BotFile.Refused("This bot has no GitHub repository yet: a .bmk names the "
                        + "repository it was made from. Publish it first."));
        Map<String, byte[]> sources;
        try {
            sources = vcs.filesAtHead(BotFile::isSource);
        } catch (IOException e) {
            throw new BotFile.Refused(e.getMessage() + " A .bmk holds files only.");
        }
        BotJson.Head head = BotFiles.head(projectDir.resolve("pom.xml"), sources, tags.getFirst(), commit.get(),
                slug);
        List<BotFile.Jar> resolved = jars.of(projectDir);

        Files.createDirectories(outDir);
        Path target = outDir.resolve(BotFiles.fileName(head.name(), head.tag()));
        Path next = Files.createTempFile(outDir, ".bmk", ".next");
        try {
            try (OutputStream out = Files.newOutputStream(next)) {
                BotFile.write(sources, head, resolved, out);
            }
            Files.move(next, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(next);
        }
        return target;
    }
}
