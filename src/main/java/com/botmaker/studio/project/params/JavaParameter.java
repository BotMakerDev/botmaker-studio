package com.botmaker.studio.project.params;

import com.botmaker.plugin.host.grammar.ValueTypes;

import java.lang.reflect.Type;

import java.nio.file.Path;

/**
 * One parameter, as the Parameters window needs it: where it is written, what it says, and the type it holds as a
 * tree.
 *
 * <p><b>The row is the contract's {@link ParameterRow}</b>, so every consumer — the window, the Runner, the
 * variable picker, the expression menu — reads one parameter through one shape. What is extra here is the
 * <em>position</em> — the file and the class an edit has to go back to, and whether the value there can be
 * rewritten at all — and the <em>form</em>, which the row carried itself until the grammar became the host's on
 * 2026-09-22 and the row kept only the type's written name.
 *
 * @param file        the source file the parameter is declared in
 * @param className   the class it is declared in — what a bot spells in front of the name
 * @param form        the parameter's type as the host's grammar reads it
 * @param initializer the value exactly as written, which is what a cell shows when the value could not be read back
 *                    ({@code editable} false)
 * @param editable    whether the value cell may rewrite this value. False for a field that is not
 *                    {@code public static}, one whose type nothing declares, one that is {@code final}, one whose
 *                    value the grammar does not read, and a parameter the bot writes during a run
 * @param note        why it is not editable, for the cell's tooltip — blank when it is
 * @param owner       why the declaration is a plugin's — the sentence its {@code ManagedValue.param} gave, or the
 *                    plugin's folder of {@code params/} — blank for the bot's own parameter (2026-10-11)
 * @param managedId   the id of the plugin's mark on the field, {@code …SdkValue$Id.VM}, which names the
 *                    declaration (and its condition, {@code ManagedParam.appliesIn}); blank for the bot's own
 * @param group       whether this is a method of a {@code @Param} group class in {@code params/} (2026-11-04)
 *                    rather than a field: name, type and value whole, private, its body this computer's value
 */
public record JavaParameter(Path file, String className, ParameterRow row, Type form, String initializer,
                            boolean editable, String note, String owner, String managedId, boolean group) {

    public JavaParameter {
        form = form == null ? ValueTypes.NONE : form;
        initializer = initializer == null ? "" : initializer;
        note = note == null ? "" : note;
        owner = owner == null ? "" : owner;
        managedId = managedId == null ? "" : managedId;
    }

    /** A {@code @Param} field. */
    public JavaParameter(Path file, String className, ParameterRow row, Type form, String initializer,
                         boolean editable, String note, String owner, String managedId) {
        this(file, className, row, form, initializer, editable, note, owner, managedId, false);
    }

    /**
     * Whether a plugin declared this parameter: its name, type and annotation are the plugin's, which the bot's
     * own code reads by name, and only its value is the user's to change.
     */
    public boolean declaredByPlugin() {
        return !owner.isEmpty();
    }

    /** The parameter's name, which is the row's name — {@code Parameters.maxAttempts}'s {@code maxAttempts}. */
    public String name() {
        return row.name();
    }

    /** Its handle, {@code Parameters.maxAttempts}; a group's method {@code RunChoices.tries}, read as {@code tries()}. */
    public String qualified() {
        return className + "." + row.name();
    }

    /**
     * True when this is the parameter called {@code name} in {@code otherClass}.
     *
     * <p>The pair, never the name alone: two classes may each declare a {@code timeout}, which javac allows
     * and already keeps apart.
     */
    public boolean is(String otherClass, String name) {
        return className.equals(otherClass) && row.name().equals(name);
    }

    /**
     * The same parameter carrying {@code newRow} — what a window holds after an edit it has already written.
     *
     * <p>The position does not change when a value does, so re-scanning the whole project to learn the file
     * a field is still in would be a walk that can only confirm what is here.
     */
    public JavaParameter withRow(ParameterRow newRow) {
        return new JavaParameter(file, className, newRow, form, initializer, editable, note, owner, managedId, group);
    }

    /**
     * The same parameter, owned by a plugin for the reason {@code why} through its mark {@code id}, or the bot's own
     * with {@code ""} and {@code ""}.
     */
    public JavaParameter owned(String why, String id) {
        return new JavaParameter(file, className, row, form, initializer, editable, note, why, id, group);
    }

    /** The same parameter, retyped to {@code newForm}. */
    public JavaParameter withForm(Type newForm) {
        return new JavaParameter(file, className, row, newForm, initializer, editable, note, owner, managedId, group);
    }
}
