// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.ui.app.params;

import com.botmaker.core.project.params.PoolEdits;
import com.botmaker.java.grammar.ValueTypes;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a parameter's card says about several runs (2026-11-16, spec {@code 2026-11-15-run-claims-design.md} §7, the
 * user's pick 6A): the pool that fills it, in plain words, or that every run shares it. Pure, so its words are tested
 * without a screen; {@link ParametersDialog} draws them.
 */
public final class PoolCards {

    /** A card's strip when the bot deals some parameter from a pool and not this one. */
    public static final String SHARED = "Shared by every run";

    private PoolCards() {}

    /** {@code ⛁ Pool · 4 listed · one a run}, and so on, for the bot's own pool. */
    public static String strip(PoolEdits.Shape pool) {
        List<String> parts = new ArrayList<>();
        parts.add("⛁ Pool");
        parts.add(pool.all() ? "every " + ValueTypes.sourceName(pool.element())
                : pool.elements().isEmpty() ? "none listed yet" : pool.elements().size() + " listed");
        parts.add(pool.take() > 0 ? pool.take() + " a run" : "one a run");
        if (pool.each() > 1) parts.add("up to " + pool.each() + " runs each");
        if (pool.rotates()) parts.add("rotating");
        return String.join(" · ", parts);
    }

    /** The strip of a parameter a plugin's pool fills: read-only, its candidates the plugin's. */
    public static String pluginStrip(String plugin) {
        return "⛁ Pool · " + plugin + "'s · each run is dealt one from what the plugin finds";
    }

    /** What the window shows of a pool that does not read, so it cannot change it. */
    public static String unread(String problem) {
        return "⛁ Pool, not read: " + problem + ". Open in code to fix it.";
    }

    /** Whether {@code form} is a list, which a pool fills by taking several elements a run. */
    public static boolean isList(Type form) {
        return form instanceof ParameterizedType list && list.getRawType() == List.class;
    }

    /** What a list parameter's elements are; empty for one that is not a list. */
    public static Optional<Type> listElement(Type form) {
        if (!(form instanceof ParameterizedType list) || list.getRawType() != List.class) return Optional.empty();
        return Optional.of(list.getActualTypeArguments()[0]);
    }

    /** Whether "All, new ones included" may be offered: the element is an enum, whose constants are all there is. */
    public static boolean offersAll(PoolEdits.Shape pool) {
        return pool.all() || pool.element() instanceof Class<?> type && type.isEnum();
    }
}
