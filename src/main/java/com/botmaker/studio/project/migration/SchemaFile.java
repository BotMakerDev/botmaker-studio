package com.botmaker.studio.project.migration;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.StudioProjectSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

/**
 * A project data file that carries a schema version, and the two operations a version needs: reading the one
 * on disk, and stamping the current one.
 *
 * <p><b>One file, since 2026-09-27.</b> {@code PROPERTIES} — {@code botmaker-project.properties}, the runtime
 * contract the SDK parsed inside the bot — went when nothing read it any more: a bot's tuning is its
 * {@code @Managed("settings")} value in its own Java, and what it launches is this machine's run property. An
 * old project keeps its file; nothing reads, writes or migrates it. {@code ACTIVITIES} went on 2026-09-11,
 * because {@code activities.json} was the SDK plugin's format and a host ledger over it would have been a
 * second writer. <b>Only a file this editor writes is listed here</b>, and {@link #SETTINGS} is the one left.
 *
 * <p><b>Absent means 0.</b> Every project that predates the marker has none, so a missing version is not an
 * error and not "unknown" — it is the oldest shape, and the migration steps from 0 are exactly the ones
 * written to bring that shape forward.
 *
 * <p><b>The current version is not written down twice.</b> {@link #current()} is the number of migration steps
 * {@link SchemaMigrations} holds for this file — step <i>i</i> takes version <i>i</i> to <i>i+1</i>, so "how
 * new is this shape" and "how many steps reach it" are the same number by construction.
 */
public enum SchemaFile {

    /** {@code .botmaker/settings.json} — per-project editor state (favourites, overlay position, layout). */
    SETTINGS(StudioProjectSettings.FILE_NAME, "editor settings");

    /** The JSON member holding the version. Written first, so it is the first line a human reads. */
    public static final String JSON_FIELD = "schemaVersion";

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final String fileName;
    private final String description;

    SchemaFile(String fileName, String description) {
        this.fileName = fileName;
        this.description = description;
    }

    /** The file's name inside {@link #dirOf}. */
    public String fileName() {
        return fileName;
    }

    /** The directory this file lives in: the project's {@code .botmaker} (since 2026-09-26). */
    public Path dirOf(ProjectConfig config) {
        return config.studioRoot();
    }

    /** A short human phrase for a refusal message. */
    public String description() {
        return description;
    }

    /** The version this Studio writes: one per migration step it knows. See the class note. */
    public int current() {
        return SchemaMigrations.stepsFor(this).size();
    }

    /** This file inside {@code dir} — the directory {@link #dirOf} names for it. */
    public Path in(Path dir) {
        return dir.resolve(fileName);
    }

    /**
     * The version recorded in the file, or empty when the file is not there.
     *
     * <p>Present-but-unstamped reads as {@code 0} — that is the "absent means 0" rule and it is deliberately
     * <em>not</em> the same as "no file". A file that does not exist has no shape to migrate, so the caller
     * skips it rather than stamping one it never wrote. An unparseable file also reads as empty: a migration
     * pass is not the place to discover a hand-edit is broken.
     */
    public OptionalInt versionIn(Path dir) {
        Path file = in(dir);
        if (!Files.exists(file)) return OptionalInt.empty();
        try {
            var node = MAPPER.readTree(file.toFile());
            var value = node == null ? null : node.get(JSON_FIELD);
            return OptionalInt.of(value != null && value.isInt() ? value.asInt() : 0);
        } catch (Exception e) {
            return OptionalInt.empty();
        }
    }

    /**
     * Records {@link #current()} in the file, leaving everything else in it alone. Does nothing when the file
     * is absent — see {@link #versionIn}; the next ordinary write stamps it, because every writer of the file
     * goes through {@link #stamped}.
     */
    public void stampIfPresent(Path dir) throws IOException {
        Path file = in(dir);
        if (!Files.exists(file)) return;
        var read = MAPPER.readTree(file.toFile());
        ObjectNode body = read instanceof ObjectNode o ? o : MAPPER.createObjectNode();
        body.remove(JSON_FIELD);
        MAPPER.writeValue(file.toFile(), stamped(body));
    }

    /**
     * {@code body} with this file's current version as its <em>first</em> member — what a JSON writer emits
     * instead of the bare object.
     *
     * <p>Every write stamps, not just a migration's. A record serializes to exactly its components, so a save
     * that did not put the number back would quietly return the file to 0 and the next open would re-run
     * every step against an already-migrated file.
     */
    public ObjectNode stamped(ObjectNode body) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put(JSON_FIELD, current());
        out.setAll(body);
        return out;
    }
}
