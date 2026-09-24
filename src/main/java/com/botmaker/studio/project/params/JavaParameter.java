package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.studio.plugin.grammar.ValueTypes;

import java.lang.reflect.Type;

import java.nio.file.Path;

/**
 * One {@code @Param} field, as the Parameters window needs it: where it is written, what it says, and the
 * type it holds as a tree.
 *
 * <p><b>The row is the contract's {@link ParameterRow}</b>, so every consumer — the window, the Runner, the
 * variable picker, the expression menu — reads one parameter through one shape. What is extra here is the
 * <em>position</em> — the file and the class an edit has to go back to, and whether the initialiser there
 * can be rewritten at all — and the <em>form</em>, which the row carried itself until the grammar became
 * the host's on 2026-09-22 and the row kept only the type's written name.
 *
 * @param file        the source file the field is declared in
 * @param className   the class it is declared in — what a bot spells in front of the name
 * @param form        the field's type as the host's grammar reads it
 * @param initializer the field's initialiser exactly as written, which is what a cell shows when the value
 *                    could not be read back ({@code editable} false)
 * @param editable    whether the value cell may rewrite this initialiser. False for a field that is not
 *                    {@code public static}, one whose type nothing declares, one that is {@code final}, and
 *                    one whose initialiser the grammar does not read
 * @param note        why it is not editable, for the cell's tooltip — blank when it is
 */
public record JavaParameter(Path file, String className, ParameterRow row, Type form, String initializer,
                            boolean editable, String note) {

    public JavaParameter {
        form = form == null ? ValueTypes.NONE : form;
        initializer = initializer == null ? "" : initializer;
        note = note == null ? "" : note;
    }

    /** The field's name, which is the row's name — {@code Parameters.maxAttempts}'s {@code maxAttempts}. */
    public String name() {
        return row.name();
    }

    /** What a bot writes to read it: {@code Parameters.maxAttempts}. */
    public String qualified() {
        return className + "." + row.name();
    }

    /**
     * True when this is the field called {@code name} in {@code otherClass}.
     *
     * <p>The pair, never the name alone: two classes may each declare a {@code timeout}, which javac allows
     * and already keeps apart.
     */
    public boolean is(String otherClass, String name) {
        return className.equals(otherClass) && row.name().equals(name);
    }

    /**
     * The same field carrying {@code newRow} — what a window holds after an edit it has already written.
     *
     * <p>The position does not change when a value does, so re-scanning the whole project to learn the file
     * a field is still in would be a walk that can only confirm what is here.
     */
    public JavaParameter withRow(ParameterRow newRow) {
        return new JavaParameter(file, className, newRow, form, initializer, editable, note);
    }

    /** The same field, retyped to {@code newForm}. */
    public JavaParameter withForm(Type newForm) {
        return new JavaParameter(file, className, row, newForm, initializer, editable, note);
    }
}
