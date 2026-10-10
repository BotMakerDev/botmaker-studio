package com.botmaker.studio.plugin.grammar;

import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.TypeLiteral;

import java.util.Optional;

/**
 * The bot's own names a plugin's value may hold, at any depth of it: a managed constant —
 * {@code Activities.Battle.WON}, {@code Pictures.ORE} — and a class literal of a managed nested enum,
 * {@code Activities.Collect.class}.
 *
 * <p>The grammar knows only what plugins declare; these are declared by the bot, so whoever reads or writes a
 * value in a bot's file hands them in ({@code project/managed/ManagedConstants.Lookup}). Before 2026-10-10 they
 * were consulted only for a whole value, so {@code Flow.edge(Activities.Battle.WON, …)} read as nothing at all.
 */
public interface BotNames {

    /** No bot behind the value: nothing but what plugins declare is read or written. */
    BotNames NONE = new BotNames() {};

    /** The value {@code written} names when it is one of the bot's constants; empty otherwise. */
    default Optional<Object> named(QualifiedName written) {
        return Optional.empty();
    }

    /** The value a class literal of one of the bot's managed enums stands for; empty otherwise. */
    default Optional<Object> type(TypeLiteral written) {
        return Optional.empty();
    }

    /** {@code value} as the bot's constant or class literal that holds it, with its import; empty for none. */
    default Optional<JavaValue> spell(Object value) {
        return Optional.empty();
    }
}
