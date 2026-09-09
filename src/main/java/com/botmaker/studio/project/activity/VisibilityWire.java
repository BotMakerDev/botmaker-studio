package com.botmaker.studio.project.activity;

import com.botmaker.plugin.api.value.Visibility;
import com.fasterxml.jackson.databind.util.StdConverter;

/**
 * How a {@link Visibility} is written into {@code activities.json}, and read back out of it.
 *
 * <p>The same half of the same split as {@link ChoiceWire}, for the same reason: the vocabulary is the plugin
 * contract's, <b>the contract carries no Jackson annotations</b> — its one dependency is
 * {@code javafx-controls} at {@code provided}, and a JSON library there would be imposed on every plugin — so
 * the contract declares the wire form ({@link Visibility#id()} out, {@link Visibility#fromId(String)} back)
 * and whoever owns the file supplies the parser.
 *
 * <p>Studio's own {@code ParamVisibility} carried {@code @JsonValue} and {@code @JsonCreator} and was deleted
 * on 2026-09-10 as the last of its rival vocabulary. <b>The stored text is unchanged</b> — {@code "public"}
 * and {@code "editor"} — which is the whole requirement: an id is what a project file holds, and every
 * project ever written has to keep its meaning. Serialising the enum itself would have written
 * {@code "PUBLIC"} and {@code "EDITOR_ONLY"}, which is why this class exists rather than nothing.
 *
 * <p>The wording a picker shows did <em>not</em> come along. "Anyone running the bot" is a sentence about
 * the Runner window, which is the host's, so it stays where it is read — see {@code ParametersDialog}.
 * Compare {@code ValueShape.label()}, which is the contract's precisely because a stored shape has to read
 * the same in every host.
 */
public final class VisibilityWire {

    private VisibilityWire() {}

    /** Serialises a {@link Visibility} record component; see {@link ActivityVariable#visibility()}. */
    public static final class ToWire extends StdConverter<Visibility, String> {
        @Override
        public String convert(Visibility value) {
            return value == null ? null : value.id();
        }
    }
}
