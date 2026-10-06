package com.botmaker.studio.assist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@link AssistTurn}'s tools as a client reads them: one method each, answering text. {@link McpTools} names
 * and describes them; this holds the turn in progress, and each call gets a fresh turn ({@link #begin}).
 *
 * <p>Everything a tool answers is either JSON of a view, or {@code OK: …} / {@code REFUSED: …}. A refusal
 * carries the reasons verbatim, because a compile error with its line is exactly what a model needs to try
 * something else.
 */
public final class AssistTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AssistTurn turn;
    private final List<String> log = new ArrayList<>();

    /** Starts a turn: every tool call from now until the next {@code begin} acts on {@code next}. */
    synchronized void begin(AssistTurn next) {
        turn = Objects.requireNonNull(next);
        log.clear();
    }

    /** One line per tool call of the current turn. */
    synchronized List<String> log() {
        return List.copyOf(log);
    }

    /** The palette entries whose label or category contains {@code filter}; all of them for an empty one. */
    public synchronized String listPalette(String filter) {
        String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        List<PaletteEntry> entries = current().palette().stream()
                .filter(e -> needle.isEmpty() || e.label().toLowerCase(Locale.ROOT).contains(needle)
                        || e.category().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        record("listPalette(" + needle + ") → " + entries.size() + " entries");
        return json(entries);
    }

    public synchronized String readTree() {
        return readTree("");
    }

    /**
     * The tree of the method named {@code method}, every overload of it; the whole file's for a blank name.
     *
     * @throws IllegalArgumentException when the file has no method of that name
     */
    public synchronized String readTree(String method) {
        String asked = method == null ? "" : method.strip().replaceFirst("\\(\\)$", "");
        // list_methods names a method Owner.name: either spelling is the method.
        int dot = asked.lastIndexOf('.');
        String owner = dot < 0 ? "" : asked.substring(0, dot);
        String wanted = asked.substring(dot + 1);
        List<BlockView.Method> methods = current().tree().stream()
                .filter(m -> wanted.isEmpty() || m.name().equals(wanted) && (owner.isEmpty() || m.owner().equals(owner)))
                .toList();
        if (methods.isEmpty() && !wanted.isEmpty()) {
            throw new IllegalArgumentException("This file has no method " + asked + " with a body. list_methods "
                    + "names them.");
        }
        record("readTree(" + asked + ")");
        return json(methods);
    }

    /** The file's methods with a body, each as {@code Owner.name} and the id of its body. */
    public synchronized String listMethods() {
        List<String> methods = current().tree().stream()
                .map(m -> (m.owner().isEmpty() ? "" : m.owner() + ".") + m.name() + "  body " + m.body().id())
                .toList();
        record("listMethods() → " + methods.size());
        return methods.isEmpty() ? "No method with a body." : String.join("\n", methods);
    }

    public synchronized String insertBlock(String bodyId, int index, String paletteId) {
        return outcome("insertBlock(" + paletteId + " at " + index + ")", current().insert(bodyId, index, paletteId));
    }

    public synchronized String setSlot(String slotId, String value) {
        return outcome("setSlot(" + value + ")", current().setSlot(slotId, value));
    }

    public synchronized String deleteBlock(String blockId) {
        return outcome("deleteBlock()", current().delete(blockId));
    }

    public synchronized String listErrors() {
        List<String> errors = current().errors();
        record("listErrors() → " + errors.size());
        return errors.isEmpty() ? "No errors." : String.join("\n", errors);
    }

    private AssistTurn current() {
        if (turn == null) throw new IllegalStateException("no turn in progress");
        return turn;
    }

    private String outcome(String call, Outcome outcome) {
        return switch (outcome) {
            case Outcome.Accepted a -> {
                record(call + " ✓ " + a.summary());
                yield "OK: " + a.summary();
            }
            case Outcome.Refused r -> {
                record(call + " ✗ " + (r.reasons().isEmpty() ? "" : r.reasons().getFirst()));
                yield "REFUSED: " + String.join("\n", r.reasons());
            }
        };
    }

    private void record(String line) {
        log.add(line);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "REFUSED: could not describe that (" + e.getOriginalMessage() + ")";
        }
    }
}
