package com.botmaker.studio.assist;

import java.util.List;

/**
 * One thing an assistant may insert: a statement block from {@code palette/BlockCatalog}, or a static call a
 * loaded plugin catalogues. The {@link #id} is what {@link AssistTurn#insert} takes back, and it is the only
 * way in: there is no tool that accepts Java.
 *
 * @param params the parameter types of a call, by simple name; empty for a statement block
 * @param returns the call's return type by simple name, or {@code ""} for a statement block
 */
public record PaletteEntry(String id, Kind kind, String label, String category, List<String> params,
                           String returns) {

    public PaletteEntry {
        params = params == null ? List.of() : List.copyOf(params);
        returns = returns == null ? "" : returns;
    }

    /** Which half of the palette an entry is from. The prefix of its {@link #id}. */
    public enum Kind {
        STATEMENT("block"),
        CALL("call");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        /** {@code block:IF}, {@code call:Mouse.click(Point)}. */
        String idOf(String rest) {
            return prefix + ":" + rest;
        }

        /** What follows this kind's prefix in {@code id}, or {@code null} when {@code id} is not of this kind. */
        String restOf(String id) {
            String head = prefix + ":";
            return id != null && id.startsWith(head) ? id.substring(head.length()) : null;
        }
    }
}
