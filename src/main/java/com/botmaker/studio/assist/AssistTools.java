package com.botmaker.studio.assist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** The palette's index for an empty {@code filter}, else the entries it matches, one line each ({@link #listed}). */
    public synchronized String listPalette(String filter) {
        String needle = filter == null ? "" : filter.strip();
        record("listPalette(" + needle + ")");
        return listed(current().palette(), needle);
    }

    /**
     * What list_palette says. No filter: the index, one line per class of calls or category of statements with
     * how many entries it has and each one's bare name, so every name is seen and none is hidden by a word that
     * missed it; a name with overloads says how many ({@code untilFind ×2}). A filter: each entry it matches as
     * {@code id → returns}, the id being what insert_block takes. The filter's words are OR'd and each looks in an
     * entry's id (without its {@code call:} or {@code block:}), label, category, parameter and return types.
     */
    static String listed(List<PaletteEntry> palette, String filter) {
        List<String> words = filter == null ? List.of() : Arrays.stream(filter.toLowerCase(Locale.ROOT)
                .split("[\\s,]+")).filter(w -> !w.isEmpty()).toList();
        if (words.isEmpty()) {
            Map<Group, Map<String, Integer>> groups = new LinkedHashMap<>();
            for (PaletteEntry e : palette) {
                groups.computeIfAbsent(group(e), g -> new LinkedHashMap<>()).merge(bareName(e), 1, Integer::sum);
            }
            StringBuilder said = new StringBuilder();
            groups.forEach((group, names) -> {
                int count = names.values().stream().mapToInt(Integer::intValue).sum();
                said.append(group.name()).append(" (").append(count)
                        .append(group.kind() == PaletteEntry.Kind.CALL ? " call" : " statement")
                        .append(count == 1 ? "): " : "s): ")
                        .append(String.join(", ", names.entrySet().stream()
                                .map(n -> n.getValue() == 1 ? n.getKey() : n.getKey() + " ×" + n.getValue()).toList()))
                        .append('\n');
            });
            return said.append("Filter by a class or a word for each entry's id, parameters and return type.")
                    .toString();
        }
        List<String> lines = palette.stream()
                .filter(e -> words.stream().anyMatch(w -> haystack(e).contains(w)))
                .map(e -> e.kind() == PaletteEntry.Kind.CALL ? e.id() + " → " + e.returns()
                        : e.id() + " — " + e.label() + " (" + e.category() + ")")
                .toList();
        return lines.isEmpty() ? "Nothing matches " + filter.strip() + ". An empty filter gives every name."
                : String.join("\n", lines);
    }

    /** A line of the index: a call's class or a statement's category, the word that filters it. */
    private record Group(String name, PaletteEntry.Kind kind) {
    }

    private static Group group(PaletteEntry e) {
        return new Group(e.kind() == PaletteEntry.Kind.CALL ? owner(e) : e.category(), e.kind());
    }

    /** A call's method name without its class; a statement's id without its prefix ({@code IF}). */
    private static String bareName(PaletteEntry e) {
        if (e.kind() == PaletteEntry.Kind.STATEMENT) return PaletteEntry.Kind.STATEMENT.restOf(e.id());
        return e.label().substring(e.label().lastIndexOf('.') + 1);
    }

    private static String owner(PaletteEntry e) {
        int dot = e.label().lastIndexOf('.');
        return dot < 0 ? e.category() : e.label().substring(0, dot);
    }

    /** Without the id's prefix: {@code call} would match every call. */
    private static String haystack(PaletteEntry e) {
        return String.join(" ", e.kind().restOf(e.id()), e.label(), e.category(), String.join(" ", e.params()),
                e.returns()).toLowerCase(Locale.ROOT);
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

    public synchronized String addMethod(String className, String name, String returns, List<?> params) {
        return outcome("addMethod(" + name + ")", current().addMethod(className, name, returns, params));
    }

    public synchronized String editVariable(String blockId, String name, String type) {
        return outcome("editVariable(" + blockId + ")", current().editVariable(blockId, name, type));
    }

    public synchronized String addArgument(String callId) {
        return outcome("addArgument(" + callId + ")", current().addArgument(callId));
    }

    public synchronized String moveBlock(String blockId, String bodyId, int index) {
        return outcome("moveBlock(" + blockId + " to " + index + ")", current().moveBlock(blockId, bodyId, index));
    }

    public synchronized String editSignature(String method, String name, String returns, List<?> params) {
        return outcome("editSignature(" + method + ")", current().editSignature(method, name, returns, params));
    }

    public synchronized String deleteBlock(String blockId) {
        return outcome("deleteBlock()", current().delete(blockId));
    }

    public synchronized String listErrors() {
        List<String> errors = current().errors();
        record("listErrors() → " + errors.size());
        return errors.isEmpty() ? "No errors." : String.join("\n", errors);
    }

    /**
     * The methods whose statements were added, removed or moved since {@code before}, as {@code read_tree} gives
     * them — so an edit that shifts ids answers with the ids it left, and the assistant need not read the tree
     * again. Empty when no statement moved: a {@code set_slot} changes values, never ids.
     *
     * <p>An outline, not the tree: each body and statement id with the statement's first words, no slots — the
     * whole tree of a long method ran to thousands of characters per edit (2026-10-14); read_tree has the slots.
     */
    synchronized String reshaped(List<BlockView.Method> before) {
        Map<String, List<String>> was = new HashMap<>();
        keyed(before).forEach((key, m) -> was.put(key, ids(m.body(), new ArrayList<>())));
        StringBuilder out = new StringBuilder();
        keyed(current().tree()).forEach((key, m) -> {
            if (ids(m.body(), new ArrayList<>()).equals(was.get(key))) return;
            out.append('\n').append(m.owner()).append('.').append(m.name()).append("()");
            outline(m.body(), 1, out);
        });
        return out.toString();
    }

    /** Past this, a statement's words in an outline are cut. */
    private static final int OUTLINE_WORDS = 60;

    private static void outline(BlockView.Body body, int depth, StringBuilder out) {
        String indent = "  ".repeat(depth);
        out.append('\n').append(indent).append("body ").append(body.id());
        for (BlockView.Statement s : body.statements()) {
            String words = (s.text() == null ? "" : s.text()).strip().lines().findFirst().orElse("");
            if (words.length() > OUTLINE_WORDS) words = words.substring(0, OUTLINE_WORDS) + "…";
            out.append('\n').append(indent).append("  ").append(s.id()).append("  ").append(words);
            s.bodies().forEach(b -> outline(b, depth + 2, out));
        }
    }

    /** Each method by {@code Owner.name#n}, {@code n} telling overloads apart in file order. */
    private static Map<String, BlockView.Method> keyed(List<BlockView.Method> methods) {
        Map<String, BlockView.Method> out = new LinkedHashMap<>();
        for (BlockView.Method m : methods) {
            String name = m.owner() + "." + m.name();
            int n = 0;
            while (out.containsKey(name + "#" + n)) n++;
            out.put(name + "#" + n, m);
        }
        return out;
    }

    private static List<String> ids(BlockView.Body body, List<String> into) {
        into.add(body.id());
        for (BlockView.Statement s : body.statements()) {
            into.add(s.id());
            s.bodies().forEach(b -> ids(b, into));
        }
        return into;
    }

    /** The turn in progress. */
    synchronized AssistTurn turn() {
        return current();
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
