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
        record("readTree()");
        return json(current().tree());
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
