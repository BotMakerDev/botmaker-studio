package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.parameters.ParameterRow;

import java.nio.file.Path;

/**
 * One {@code @Param} field, as the Parameters window needs it: where it is written, and what it says.
 *
 * <p><b>The row is the contract's {@link ParameterRow}</b>, so every consumer — the window, the Runner, the
 * variable picker, the expression menu — reads one parameter through one shape. What is extra here is the
 * <em>position</em>: the file and the class an edit has to go back to, and whether the initialiser there can
 * be rewritten at all.
 *
 * @param file        the source file the field is declared in
 * @param className   the class it is declared in — what a bot spells in front of the name
 * @param initializer the field's initialiser exactly as written, which is what a cell shows when the value
 *                    could not be read back ({@code editable} false)
 * @param editable    whether the value cell may rewrite this initialiser. False for a field that is not
 *                    {@code public static}, one whose type nothing registers, one that is {@code final},
 *                    and one whose initialiser the type's codec does not recognise
 * @param note        why it is not editable, for the cell's tooltip — blank when it is
 */
public record JavaParameter(Path file, String className, ParameterRow row, String initializer,
                            boolean editable, String note) {

    public JavaParameter {
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
        return new JavaParameter(file, className, newRow, initializer, editable, note);
    }
}
