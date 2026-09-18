package com.botmaker.studio.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.botmaker.studio.config.Constants.ARCHIVE_ROOT;
import static com.botmaker.studio.config.Constants.PROJECTS_ROOT;

/**
 * Manages project discovery and listing.
 *
 * <p>The list is what sits directly under the projects root. A project elsewhere — opened with <em>Open
 * project…</em> and remembered by its path — is found one directory at a time ({@link #projectAt}), never by
 * widening the scan: the folders a user keeps their own repositories in hold every other Maven project they
 * have too, and none of those is a bot.
 */
public class ProjectManager {

    /** What makes a directory a project, said the one way the refusals below also say it. */
    public static final String PROJECT_SHAPE = "a pom.xml and a src/main/java folder";

    private final Path root;
    private final Path archiveRoot;

    public ProjectManager() {
        this(PROJECTS_ROOT, ARCHIVE_ROOT);
    }

    /** A manager over another root — for tests, which must not touch the user's own projects. */
    ProjectManager(Path root, Path archiveRoot) {
        this.root = root.toAbsolutePath().normalize();
        this.archiveRoot = archiveRoot.toAbsolutePath().normalize();
    }

    /**
     * Lists all available projects
     */
    public List<ProjectInfo> listProjects() {
        return listProjectsUnder(root);
    }

    /** Lists archived (soft-deleted) projects, in the same shape as {@link #listProjects()}. */
    public List<ProjectInfo> listArchivedProjects() {
        return listProjectsUnder(archiveRoot);
    }

    /** The project in {@code dir}, or empty when {@code dir} is not one. The directory need not be under the root. */
    public Optional<ProjectInfo> projectAt(Path dir) {
        Path projectPath = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(projectPath) || !isValidProject(projectPath)) return Optional.empty();
        try {
            FileTime lastModified = Files.getLastModifiedTime(projectPath);
            LocalDateTime modifiedDate = LocalDateTime.ofInstant(lastModified.toInstant(), ZoneId.systemDefault());
            return Optional.of(new ProjectInfo(projectPath.getFileName().toString(), projectPath, modifiedDate));
        } catch (IOException e) {
            System.err.println("Error reading project: " + projectPath);
            return Optional.empty();
        }
    }

    /**
     * True when {@code dir} sits directly under the projects root — the projects Studio lists, archives and
     * restores. Anything else is a folder the user keeps somewhere of their own.
     */
    public boolean isUnderRoot(Path dir) {
        return root.equals(dir.toAbsolutePath().normalize().getParent());
    }

    private List<ProjectInfo> listProjectsUnder(Path root) {
        List<ProjectInfo> projects = new ArrayList<>();

        if (!Files.exists(root)) {
            return projects;
        }

        try (Stream<Path> paths = Files.list(root)) {
            paths.filter(Files::isDirectory)
                    .map(this::projectAt)
                    .flatMap(Optional::stream)
                    .forEach(projects::add);
        } catch (IOException e) {
            System.err.println("Error listing projects: " + e.getMessage());
        }

        return projects;
    }

    /**
     * Checks if a directory is a valid project
     * (has src/main/java structure and pom.xml)
     */
    public static boolean isValidProject(Path projectPath) {
        Path srcPath = projectPath.resolve("src/main/java");
        Path pom = projectPath.resolve("pom.xml");
        return Files.exists(srcPath) && Files.exists(pom);
    }

    /** Soft-deletes a project by moving it into the archive directory. */
    public void archiveProject(String name) throws IOException {
        archiveProject(root.resolve(name));
    }

    /**
     * Soft-deletes the project in {@code dir} by moving it into the archive directory — only when it is one of
     * the root's. A project elsewhere is refused: it is a folder in the user's own tree, maybe a repository,
     * and moving it into {@code ~/BotMakerProjects/.archive} would take it out from under whatever else keeps
     * it there. Forgetting it is the recents list's job.
     */
    public void archiveProject(Path dir) throws IOException {
        Path source = dir.toAbsolutePath().normalize();
        if (!isUnderRoot(source)) {
            throw new IOException("“" + source.getFileName() + "” is in " + source.getParent()
                    + ", not in " + root + ". Studio archives only the projects it keeps; remove this one "
                    + "from the recent list instead, and its folder stays where it is.");
        }
        if (!Files.exists(source)) {
            throw new IOException("Project '" + source.getFileName() + "' does not exist.");
        }
        Files.createDirectories(archiveRoot);
        Files.move(source, archiveRoot.resolve(source.getFileName()));
    }

    /** Restores an archived project back into the live projects directory. */
    public void restoreProject(String name) throws IOException {
        Path source = archiveRoot.resolve(name);
        if (!Files.exists(source)) {
            throw new IOException("Archived project '" + name + "' does not exist.");
        }
        Path dest = root.resolve(name);
        if (Files.exists(dest)) {
            throw new IOException("A project named '" + name + "' already exists.");
        }
        Files.move(source, dest);
    }

    /**
     * Permanently deletes an archived project (recursively removes its directory under
     * {@link com.botmaker.studio.config.Constants#ARCHIVE_ROOT ARCHIVE_ROOT}). Only archived projects can be
     * hard-deleted; live projects must be archived first, keeping the destructive action one step removed.
     */
    public void deleteProject(String name) throws IOException {
        Path dir = archiveRoot.resolve(name);
        if (!Files.exists(dir)) {
            throw new IOException("Archived project '" + name + "' does not exist.");
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Gets the entry-point source file path for a project.
     *
     * <p>Delegates to {@link ProjectConfig} rather than rebuilding the path: this used to derive the package
     * and file name itself, which meant the rule lived in two places and only one of them learned that a
     * project's class name is derived from its name rather than equal to it.
     */
    public Path getSourceFilePath(String projectName) {
        return ProjectConfig.forProject(projectName, root).mainSourceFile();
    }
}
