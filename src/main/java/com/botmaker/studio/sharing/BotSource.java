package com.botmaker.studio.sharing;

import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.Remote;
import com.botmaker.studio.project.vcs.SyncModel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where a project started from someone's bot came from: the repository and the release it is at.
 *
 * <p>Since 2026-10-23 that is read off git alone: the remote {@link Remote#ORIGINAL} names the repository, and
 * the nearest tag {@code HEAD} contains is the release ({@link ProjectVcs#releaseTag}). Nothing is written for it.
 * A project made before then may still carry {@code botmaker-source.json}, Studio's own record of the same thing,
 * which is read when there is no {@code original} — a bot installed from a zip, or one its author published
 * from this computer. Absent for a project of the user's own.
 *
 * @param owner GitHub repo owner
 * @param repo  GitHub repo name
 * @param tag   the release the project is at, blank when no tag is reachable
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BotSource(String owner, String repo, String tag) {

    /** The file older projects carry; never written since 2026-10-23. */
    public static final String FILE_NAME = "botmaker-source.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public BotSource {
        tag = tag == null ? "" : tag;
    }

    public String slug() {
        return owner + "/" + repo;
    }

    /** Where {@code projectDir} came from: its {@code original} remote, else the older file; empty for neither. */
    public static Optional<BotSource> read(Path projectDir) {
        ProjectVcs vcs = new ProjectVcs(projectDir);
        Optional<SyncModel.Slug> original = SyncModel.slug(vcs.remoteUrl(Remote.ORIGINAL));
        if (original.isPresent()) {
            return Optional.of(new BotSource(original.get().owner(), original.get().repo(), vcs.releaseTag()));
        }
        return readFile(projectDir);
    }

    /** The file an older project carries, if present and valid. */
    static Optional<BotSource> readFile(Path projectDir) {
        Path file = projectDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), BotSource.class));
        } catch (Exception e) {
            System.err.println("Failed to read " + FILE_NAME + " in " + projectDir + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}
