package com.botmaker.studio.project.params;

import com.botmaker.shared.tools.UserDirs;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/**
 * The values a bot's runner chose in its Run window, kept apart from the bot (2026-10-25, spec § 2 step 6), so an
 * update that brings the author's defaults back, or a newer tag unpacked beside the old one, gets them again.
 *
 * <p>One file per bot: {@code <data>/runner-values/<owner>/<repo>.json} for a bot got from someone, and
 * {@code <data>/runner-values/local/<folder>.json} for the user's own ({@link #keyOf}). It maps a field,
 * {@code Settings.speed}, to the Java its initialiser held, {@code 3}: the bot's settings stay {@code @Param}
 * fields, and this is only the runner's memory of what they set there.
 *
 * <p>Immutable; {@link #load} and {@link #save} are the edges.
 *
 * @param file   where these values are kept
 * @param values each field's initialiser source, by {@link JavaParameter#qualified()}
 */
public record RunnerValues(Path file, Map<String, String> values) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public RunnerValues {
        Map<String, String> kept = new LinkedHashMap<>();
        values.forEach((field, source) -> {
            if (field != null && source != null) kept.put(field, source);
        });
        values = Map.copyOf(kept);
    }

    /**
     * What {@link #applyTo} found: the values to write, each to its row, and the fields that were dropped — gone
     * from the bot, or holding a value that no longer reads as the field's type.
     */
    public record Applied(Map<JavaParameter, String> values, List<String> dropped) {}

    /**
     * The key of a bot's values: {@code owner/repo} for one got from someone (its {@code original} remote),
     * {@code _local/<folder>} for the user's own — no GitHub account starts with {@code _}, so the two never
     * meet. Each part kept to letters, digits, {@code . _ -}.
     */
    public static String keyOf(Optional<String> owner, Optional<String> repo, Path dir) {
        if (owner.isPresent() && repo.isPresent()) return part(owner.get()) + "/" + part(repo.get());
        Path name = dir.getFileName();
        return "_local/" + part(name == null ? "bot" : name.toString());
    }

    /** The values kept under {@code key} on this computer; none when there are none or they cannot be read. */
    public static RunnerValues load(String key) {
        return at(UserDirs.data().resolve("runner-values").resolve(key + ".json"));
    }

    /** The values kept in {@code file}; none when it is missing or unreadable. */
    public static RunnerValues at(Path file) {
        if (!Files.isRegularFile(file)) return new RunnerValues(file, Map.of());
        try {
            Map<String, String> read = JSON.readValue(file.toFile(), new TypeReference<Map<String, String>>() {});
            return new RunnerValues(file, read == null ? Map.of() : read);
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not read the runner's values in " + file + ": " + e.getMessage());
            return new RunnerValues(file, Map.of());
        }
    }

    /** These values with {@code classField} holding {@code source}. */
    public RunnerValues with(String classField, String source) {
        Map<String, String> next = new LinkedHashMap<>(values);
        next.put(classField, source);
        return new RunnerValues(file, next);
    }

    /** These values without {@code classFields}. */
    public RunnerValues without(Collection<String> classFields) {
        Map<String, String> next = new LinkedHashMap<>(values);
        classFields.forEach(next::remove);
        return new RunnerValues(file, next);
    }

    /**
     * Writes these values, sorted by field, beside the file and then over it, so a crash mid-write leaves the
     * old values. Best effort: a file that cannot be written is said and left.
     */
    public void save() {
        try {
            Files.createDirectories(file.getParent());
            Path next = file.resolveSibling(file.getFileName() + ".next");
            JSON.writerWithDefaultPrettyPrinter().writeValue(next.toFile(), new TreeMap<>(values));
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("Could not keep the runner's values in " + file + ": " + e.getMessage());
        }
    }

    /**
     * What these values do to the bot's {@code rows} as its source holds them now: each kept value whose field is
     * a row and {@code fits} it, where the source holds something else, is to be written; a kept value whose field
     * is not {@code declared} at all, or is a row it does not fit, is dropped. A value the source already holds,
     * and one whose field is declared but not a row now (a conditional one not applying), is neither.
     *
     * @param declared every {@code @Param} the bot declares, by {@link JavaParameter#qualified()}, shown or not
     */
    public Applied applyTo(List<JavaParameter> rows, Set<String> declared, BiPredicate<JavaParameter, String> fits) {
        Map<JavaParameter, String> apply = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(kept -> {
            Optional<JavaParameter> row = rows.stream().filter(r -> r.qualified().equals(kept.getKey())).findFirst();
            if (row.isEmpty()) {
                if (!declared.contains(kept.getKey())) dropped.add(kept.getKey());
            } else if (!fits.test(row.get(), kept.getValue())) {
                dropped.add(kept.getKey());
            } else if (!kept.getValue().equals(row.get().row().value())) {
                apply.put(row.get(), kept.getValue());
            }
        });
        return new Applied(apply, List.copyOf(dropped));
    }

    private static String part(String text) {
        String kept = text.replaceAll("[^A-Za-z0-9._-]", "_");
        return kept.isBlank() || kept.chars().allMatch(c -> c == '.') ? "_" : kept;
    }
}
