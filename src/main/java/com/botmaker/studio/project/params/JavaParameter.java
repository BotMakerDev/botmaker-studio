package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.ParameterRow;

import java.nio.file.Path;

/**
 * One {@code @Param} field, as the Parameters window needs it: where it is written, and what it says.
 *
 * <p><b>The row is the same {@link ParameterRow} a plugin hands over</b>, so every consumer of the
 * parameter surface — the window, the Runner, the variable picker, the expression menu — reads a Java
 * parameter and a plugin's row through one shape. What is extra here is the <em>position</em>: the file and
 * the class an edit has to go back to, which a plugin's row does not need because its owner writes its own
 * file.
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
}
