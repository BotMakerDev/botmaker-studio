package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.TypeDelete;
import com.botmaker.studio.nav.TypeRename;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.project.FileRole;
import com.botmaker.studio.project.LockResolver;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectWrites;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.VersionOrigin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The bot's files as files (2026-10-13): a new class, interface, record or enum; a class renamed with its file;
 * a file deleted. The explorer's menu and the MCP tools both come here, so they refuse the same things.
 *
 * <p><b>What is refused.</b> Nothing outside the user's own packages: a library class, and the {@code plugins}
 * folder, whose files a plugin writes and finds by name. A plugin's holder file and Parameters.java are neither
 * renamed nor deleted — the plugin or the Parameters window would write it again under its old name. The entry
 * point is not deleted. A delete is refused
 * while anything still uses what the file declares ({@link TypeDelete}); a rename while the bot would stop
 * compiling ({@link TypeRename}).
 *
 * <p><b>Undo.</b> A rename or a delete changes files the user is not looking at, which the editor's ↶ does not
 * reach, so each takes a safety version first (Versions goes back to it). A new file is a version of its own
 * ({@link ProjectWrites#create}).
 *
 * <p>Planning parses the bot with bindings, so it runs on a worker; applying touches the editor's buffers, so it
 * runs on the FX thread. Each method says which.
 */
public final class ClassFiles {

    /** What a new file declares. */
    public enum Kind {
        CLASS("class", "Class", "public class %s {\n}\n"),
        INTERFACE("interface", "Interface", "public interface %s {\n}\n"),
        RECORD("record", "Record", "public record %s() {\n}\n"),
        ENUM("enum", "Enum", "public enum %s {\n}\n");

        private final String id;
        private final String displayName;
        private final String body;

        Kind(String id, String displayName, String body) {
            this.id = id;
            this.displayName = displayName;
            this.body = body;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** The kind {@code id} names, ignoring case; empty for anything else. */
        public static Optional<Kind> fromId(String id) {
            String wanted = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
            return Arrays.stream(values()).filter(kind -> kind.id.equals(wanted)).findFirst();
        }
    }

    private final ProjectConfig config;
    private final ProjectState state;
    private final StudioContext ctx;

    public ClassFiles(StudioContext ctx) {
        this.ctx = ctx;
        this.config = ctx.config();
        this.state = ctx.state();
    }

    /** The source of a new {@code kind} named {@code name} in {@code packageName}. */
    public static String source(String packageName, Kind kind, String name) {
        return "package " + packageName + ";\n\n" + kind.body.formatted(name);
    }

    /**
     * Why {@code folder} takes no new file of the user's, or empty when it does: it must be the bot's package or
     * one inside it — never {@code java.*}, which the JVM refuses to load — not the bundled library, and not a
     * plugin's folder.
     */
    public Optional<String> folderProblem(Path folder) {
        Path own = config.mainPackageDir().toAbsolutePath().normalize();
        Path at = folder.toAbsolutePath().normalize();
        if (!at.startsWith(own)) {
            return Optional.of("A class goes in the bot's package, " + config.mainPackage() + ", or one inside it.");
        }
        if (FileRole.of(at.resolve("X.java")) != FileRole.EDITABLE) {
            return Optional.of("That is the bundled library's package, which is read only.");
        }
        if (at.startsWith(config.mainPackageDir().resolve("plugins").toAbsolutePath().normalize())) {
            return Optional.of("plugins/ holds the plugins' own files. Put yours in another package.");
        }
        return Optional.empty();
    }

    /** {@code folder}'s package, as a {@code package} line spells it. */
    public String packageOf(Path folder) {
        Path relative = config.sourceRoot().toAbsolutePath().normalize().relativize(folder.toAbsolutePath().normalize());
        return relative.toString().replace('\\', '/').replace('/', '.');
    }

    /**
     * Writes a new {@code kind} named {@code name} in {@code folder} and answers its path. Any thread.
     *
     * @throws IllegalArgumentException with the sentence that says why, when the name or the folder will not do
     *                                  or the file exists
     */
    public Path create(Path folder, Kind kind, String name) {
        String className = name == null ? "" : name.strip().replaceFirst("\\.java$", "");
        FunctionDraft.identifierProblem(className, kind.displayName().toLowerCase(Locale.ROOT)).ifPresent(problem -> {
            throw new IllegalArgumentException(problem);
        });
        folderProblem(folder).ifPresent(problem -> {
            throw new IllegalArgumentException(problem);
        });
        Path file = folder.toAbsolutePath().normalize().resolve(className + ".java");
        if (Files.exists(file)) throw new IllegalArgumentException(className + ".java already exists.");
        String source = source(packageOf(folder), kind, className);
        try {
            if (!ProjectWrites.create(config, file, source, "Create " + className)) {
                throw new IllegalArgumentException("Studio could not write " + className + ".java.");
            }
        } catch (java.io.UncheckedIOException failed) {
            throw new IllegalArgumentException("Studio could not write " + className + ".java: "
                    + failed.getCause().getMessage());
        }
        changed();
        return file;
    }

    /** Why {@code file} is not the user's to rename or delete, or empty when it is. */
    public Optional<String> lockedProblem(Path file) {
        if (FileRole.of(file) != FileRole.EDITABLE) return Optional.of(file.getFileName() + " is read only.");
        // A plugin's holder, and Parameters.java since 2026-10-13 (a user deleted it): each found by its path.
        return managed(file).map(managed -> managed.reason() + " Studio finds it by its name and path.");
    }

    /**
     * Which window keeps {@code file}, or empty for a file of the user's: Parameters.java, or a plugin's holder.
     * The explorer files these apart from the user's own code.
     */
    public Optional<LockResolver.Managed> managed(Path file) {
        return Optional.ofNullable(new LockResolver(config, file).managed());
    }

    /**
     * Plans renaming {@code file}'s class to {@code newName}, and takes a safety version when it can be done.
     * Worker thread; {@code before} taken on the FX thread.
     */
    public Refactor.Outcome planRename(BotIndex index, ProjectState.Snapshot before, Path file, String newName) {
        Optional<String> locked = lockedProblem(file);
        if (locked.isPresent()) return new Refactor.Refused(locked.get());
        String oldName = file.getFileName().toString().replaceFirst("\\.java$", "");
        Path target = file.resolveSibling(newName.strip().replaceFirst("\\.java$", "") + ".java");
        Set<Path> existing = Files.exists(target) ? Set.of(target.toAbsolutePath().normalize()) : Set.of();
        Refactor.Outcome outcome = TypeRename.plan(index, file, newName, existing);
        if (outcome instanceof Refactor.Planned) {
            Checkpoints.take(config.projectPath(), before, VersionOrigin.SAFETY,
                    "Before renaming " + oldName + " to " + newName.strip());
        }
        return outcome;
    }

    /**
     * Writes a rename's plan to the buffers and the disk, moving the file with its class, and answers what it
     * did. FX thread.
     *
     * @throws IOException when a file could not be written; Versions has the project as it was
     */
    public String applyRename(Refactor.Planned plan) throws IOException {
        Path active = state.getActiveFile() == null ? null : state.getActiveFile().getPath();
        try {
            for (Map.Entry<Path, String> rewrite : plan.rewrites().entrySet()) {
                Path path = rewrite.getKey();
                Path to = plan.moves().get(path);
                if (to != null) {
                    Files.writeString(to, rewrite.getValue());
                    Files.delete(path);
                    state.removeFile(path);
                    continue;
                }
                state.getFile(path).ifPresent(buffer -> buffer.setContent(rewrite.getValue()));
                if (Files.isRegularFile(path)) Files.writeString(path, rewrite.getValue());
            }
        } finally {
            changed();
        }
        // The open file is re-drawn from its (possibly rewritten) buffer; a moved one opens under its new name.
        Path movedTo = active == null ? null : plan.moves().get(active.toAbsolutePath().normalize());
        if (movedTo != null) ctx.codeEditorService().switchToFile(movedTo);
        else if (active != null && plan.rewrites().containsKey(active.toAbsolutePath().normalize())) {
            ctx.codeEditorService().switchToFile(active);
        }
        return plan.summary() + ". Versions has a safety copy from before.";
    }

    /**
     * Why {@code file} cannot be deleted, or empty when it can — in which case a safety version is taken. Worker
     * thread; {@code before} taken on the FX thread.
     */
    public Optional<Refactor.Refused> planDelete(BotIndex index, ProjectState.Snapshot before, Path file) {
        Optional<String> locked = lockedProblem(file);
        if (locked.isPresent()) return Optional.of(new Refactor.Refused(locked.get()));
        if (file.toAbsolutePath().normalize().equals(config.entrySourceFile().toAbsolutePath().normalize())) {
            return Optional.of(new Refactor.Refused(file.getFileName() + " holds the bot's main, where it starts."));
        }
        Optional<Refactor.Refused> refused = TypeDelete.refusal(index, file);
        if (refused.isEmpty()) {
            Checkpoints.take(config.projectPath(), before, VersionOrigin.SAFETY, "Before deleting " + file.getFileName());
        }
        return refused;
    }

    /**
     * Deletes {@code file} from the disk and the editor; the open file, when it was this one, becomes the entry
     * point. FX thread, after {@link #planDelete} said it may go.
     */
    public String delete(Path file) throws IOException {
        Path at = file.toAbsolutePath().normalize();
        boolean wasOpen = state.getActiveFile() != null
                && at.equals(state.getActiveFile().getPath().toAbsolutePath().normalize());
        Files.deleteIfExists(at);
        state.removeFile(at);
        changed();
        if (wasOpen) ctx.codeEditorService().switchToFile(config.entrySourceFile());
        return "Deleted " + at.getFileName() + ". Versions has a safety copy from before.";
    }

    private void changed() {
        ctx.eventBus().publish(new CoreApplicationEvents.ProjectFilesChangedEvent());
    }
}
