package com.botmaker.studio.assist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@link AssistTurn}'s tools as LangChain4j sees them: one {@code @Tool} method each, answering text a model
 * reads. Holds the turn in progress — the model's tool loop calls back into here, and each user message gets
 * a fresh turn ({@link #begin}).
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

    /** One line per tool call of the current turn, for the pane's transcript. */
    synchronized List<String> log() {
        return List.copyOf(log);
    }

    @Tool("List what can be inserted into the bot: statement blocks and plugin calls. Each entry's id is what "
            + "insertBlock takes. Pass a word to filter by label or category, or an empty string for everything.")
    public synchronized String listPalette(@P("a filter word, or empty for everything") String filter) {
        String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        List<PaletteEntry> entries = current().palette().stream()
                .filter(e -> needle.isEmpty() || e.label().toLowerCase(Locale.ROOT).contains(needle)
                        || e.category().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        record("listPalette(" + needle + ") → " + entries.size() + " entries");
        return json(entries);
    }

    @Tool("Read the open file: its methods, each method's body id, the statements in each body in order (index 0 "
            + "first) and each statement's value slots with the type they expect. Read it again after edits: "
            + "ids of statements below an insertion shift.")
    public synchronized String readTree() {
        record("readTree()");
        return json(current().tree());
    }

    @Tool("Insert a palette entry as a new statement in a body, before the statement now at that index "
            + "(use the body's statement count to append).")
    public synchronized String insertBlock(@P("the body id, from readTree") String bodyId,
                                           @P("where to insert, 0 for first") int index,
                                           @P("the palette id, from listPalette") String paletteId) {
        return outcome("insertBlock(" + paletteId + " at " + index + ")", current().insert(bodyId, index, paletteId));
    }

    @Tool("Write a value into a slot. The value is Java for a value of the slot's type: a literal (3, 2.5, "
            + "true, \"text\"), an enum or static constant of that type, or a factory call the type declares. "
            + "Anything else is refused.")
    public synchronized String setSlot(@P("the slot id, from readTree") String slotId,
                                       @P("the value") String value) {
        return outcome("setSlot(" + value + ")", current().setSlot(slotId, value));
    }

    @Tool("Delete a statement.")
    public synchronized String deleteBlock(@P("the statement id, from readTree") String blockId) {
        return outcome("deleteBlock()", current().delete(blockId));
    }

    @Tool("List the compile errors the file has now. An edit that would add one is refused on its own.")
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
