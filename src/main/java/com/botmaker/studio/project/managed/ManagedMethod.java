package com.botmaker.studio.project.managed;

import com.botmaker.studio.plugin.grammar.ValueForm;

import java.nio.file.Path;

/**
 * One {@code @Managed} method, as a plugin's window needs it: where it is written, what it holds, and
 * whether that may be replaced.
 *
 * <p>The twin of {@code JavaParameter}, and the differences are the whole design. A parameter is a
 * <em>field</em> the bot's user changes, carrying a category, a description and bounds the window draws a
 * row from. This is a <em>method</em> the plugin that shipped the file changes, carrying nothing but its id:
 * everything else about how it is presented is the plugin's own window's business, because a flow is not a
 * row.
 *
 * @param file       the source file the method is declared in
 * @param className  the class it is declared in — {@code Sdk}
 * @param methodName the method's own name, which is what an edit is addressed to
 * @param id         the {@code @Managed} id, which is what a plugin asks for
 * @param form       the value type, derived from the declared return type
 * @param expression the expression the body returns, exactly as written, or {@code ""} when the body is not
 *                   a single {@code return}
 * @param editable   whether that expression may be rewritten
 * @param note       why it may not be, for the sentence a window shows — blank when it may
 */
public record ManagedMethod(Path file, String className, String methodName, String id, ValueForm form,
                            String expression, boolean editable, String note) {

    public ManagedMethod {
        expression = expression == null ? "" : expression;
        note = note == null ? "" : note;
    }

    /** What a bot writes to call it: {@code Sdk.flow()}. */
    public String qualified() {
        return className + "." + methodName + "()";
    }
}
