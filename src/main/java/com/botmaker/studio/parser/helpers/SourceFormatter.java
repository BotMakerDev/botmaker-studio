package com.botmaker.studio.parser.helpers;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;

import java.util.Map;

/**
 * Lays out a whole Java source file — the step Studio never had.
 *
 * <p><b>Why.</b> Every rewrite in the write path is an {@code ASTRewrite} applied to the previous text, and
 * {@code ASTRewrite} only formats what it inserts, relative to what it finds. Nothing ever re-laid-out the
 * file, so a generated bot degraded edit by edit: one user's activity had its whole lambda, {@code switch},
 * both guarded labels and the first {@code ->{}} packed onto a single line. That is unreadable on its own
 * terms, and it also makes every positional bug report unreproducible, because the formatting is the input.
 *
 * <p><b>Never destructive.</b> Source that doesn't parse is returned untouched. JDT will happily format it
 * anyway — it recovers a tree and lays that out — and the write path does legitimately produce broken source
 * mid-edit, so a best-effort reflow of a recovered tree is a real way to lose text that was going to be
 * refused (and logged, and dumped) intact. Everything else is caught and falls back to the input: a lost
 * layout is a cosmetic problem, a lost edit is not.
 *
 * <p><b>Code only, never comments.</b> {@code F_INCLUDE_COMMENTS} is deliberately not set. Re-wrapping a
 * user's prose to fit a column is an opinion about their writing, not about their code, and it is the one part
 * of a file where reflowing changes what a reader sees rather than only where they see it.
 *
 * <p>Settings follow the repo's {@code .editorconfig} (4 spaces, 120 columns, LF) so a file Studio writes and
 * a file a human edits in the IDE agree, and compliance comes from {@link SourceParser#latestLevelOptions()}
 * rather than a second copy — the formatter parses too, and one left at JDT's 1.3 default mangles the very
 * {@code switch} rules this exists to lay out.
 */
public final class SourceFormatter {

    private SourceFormatter() {}

    /** Matches {@code .editorconfig}: {@code indent_size = 4}, {@code indent_style = space}. */
    private static final int INDENT = 4;

    /** Matches {@code .editorconfig}: {@code max_line_length = 120}. */
    private static final int LINE_WIDTH = 120;

    /** Matches {@code .editorconfig}: {@code end_of_line = lf} — and keeps a diff free of line-ending churn. */
    private static final String LINE_SEPARATOR = "\n";

    private static final CodeFormatter FORMATTER = ToolFactory.createCodeFormatter(options());

    /**
     * The same layout for one expression or statement shown on its own — a cell's label, a value's
     * {@code source()} — which is never wrapped: it has no column to fit and no indentation to continue at.
     */
    private static final CodeFormatter SNIPPETS = ToolFactory.createCodeFormatter(snippetOptions());

    /**
     * {@code source} laid out, or {@code source} unchanged when the formatter declines it. Never null for
     * non-null input, and never throws: this sits on the edit path, where failing closed means losing a user's
     * change to a cosmetic pass.
     */
    public static String format(String source) {
        if (source == null || source.isBlank()) return source;
        if (SourceParser.hasSyntaxErrors(SourceParser.parse(source))) return source;
        try {
            TextEdit edit = FORMATTER.format(
                    CodeFormatter.K_COMPILATION_UNIT, source, 0, source.length(), 0, LINE_SEPARATOR);
            if (edit == null) return source;
            IDocument document = new Document(source);
            edit.apply(document);
            return document.get();
        } catch (Exception e) {
            return source;
        }
    }

    /**
     * {@code source} with only the lines that differ from {@code previous} laid out — what an edit is allowed
     * to reformat.
     *
     * <p><b>Formatting the whole file on every edit broke a promise the template makes in writing</b>: <i>"your
     * formatting and comments survive"</i>. Adding one statement to {@code define()} rewrote
     * {@code private Collect() {}} three methods away into two lines, and a one-block edit became a diff the
     * author had to read. The cumulative damage {@link #format} was written for is damage <i>to the text an
     * edit wrote</i>, so that text, and nothing else, is what gets laid out.
     *
     * <p>The region is the span between the common prefix and the common suffix, widened to whole lines. JDT
     * formats a region against the whole tree, so an inserted line still lands at the indentation of the
     * block it sits in.
     */
    public static String formatChanged(String previous, String source) {
        if (source == null || source.isBlank()) return source;
        if (previous == null) return format(source);
        if (SourceParser.hasSyntaxErrors(SourceParser.parse(source))) return source;
        int prefix = 0;
        int limit = Math.min(previous.length(), source.length());
        while (prefix < limit && previous.charAt(prefix) == source.charAt(prefix)) prefix++;
        if (prefix == source.length() && prefix == previous.length()) return source;
        int suffix = 0;
        while (suffix < limit - prefix
                && previous.charAt(previous.length() - 1 - suffix) == source.charAt(source.length() - 1 - suffix)) {
            suffix++;
        }
        int start = source.lastIndexOf('\n', Math.max(0, prefix - 1)) + 1;
        int endOfChange = source.length() - suffix;
        int end = source.indexOf('\n', Math.max(start, endOfChange - 1));
        end = end < 0 ? source.length() : end;
        if (end <= start) return source;                     // nothing left on the changed line to lay out
        try {
            TextEdit edit = FORMATTER.format(
                    CodeFormatter.K_COMPILATION_UNIT, source, start, end - start, 0, LINE_SEPARATOR);
            if (edit == null) return source;
            IDocument document = new Document(source);
            edit.apply(document);
            return document.get();
        } catch (Exception e) {
            return source;
        }
    }

    /**
     * One expression laid out as a file's would be — {@code new Point(10, 20)} for the {@code new Point(10,20)}
     * JDT prints a node it built as. For showing a value the host wrote; the file itself gets the node, which
     * {@code ASTRewrite} lays out on its own. The input unchanged when the formatter declines it.
     */
    public static String expression(String source) {
        return snippet(CodeFormatter.K_EXPRESSION, source);
    }

    /** {@link #expression} for a statement, {@code Mouse.click(new Point(1, 2));}. */
    public static String statement(String source) {
        return snippet(CodeFormatter.K_STATEMENTS, source);
    }

    private static String snippet(int kind, String source) {
        if (source == null || source.isBlank()) return source;
        try {
            TextEdit edit = SNIPPETS.format(kind, source, 0, source.length(), 0, LINE_SEPARATOR);
            if (edit == null) return source;
            IDocument document = new Document(source);
            edit.apply(document);
            return document.get().strip();
        } catch (Exception e) {
            return source;
        }
    }

    /**
     * JDT's defaults with this repository's indentation, for {@link AstRewriteHelper}: {@code ASTRewrite}
     * indents what it inserts by these, and handed {@code null} it indents with a tab — into a file that is four
     * spaces everywhere else. Only the indentation is overridden, so nothing else about what a rewrite emits
     * changes.
     */
    static Map<String, String> rewriteOptions() {
        Map<String, String> options = JavaCore.getOptions();
        options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.SPACE);
        options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE, String.valueOf(INDENT));
        options.put(DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE, String.valueOf(INDENT));
        return options;
    }

    private static Map<String, String> snippetOptions() {
        Map<String, String> options = options();
        options.put(DefaultCodeFormatterConstants.FORMATTER_LINE_SPLIT, String.valueOf(Integer.MAX_VALUE / 2));
        return options;
    }

    private static Map<String, String> options() {
        Map<String, String> options = SourceParser.latestLevelOptions();
        options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.SPACE);
        options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE, String.valueOf(INDENT));
        options.put(DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE, String.valueOf(INDENT));
        options.put(DefaultCodeFormatterConstants.FORMATTER_LINE_SPLIT, String.valueOf(LINE_WIDTH));
        // The one setting that is not taste: joining lines would undo a user's own paragraphing on every save.
        options.put(DefaultCodeFormatterConstants.FORMATTER_JOIN_WRAPPED_LINES, DefaultCodeFormatterConstants.FALSE);
        options.put(DefaultCodeFormatterConstants.FORMATTER_NUMBER_OF_EMPTY_LINES_TO_PRESERVE, "1");
        return options;
    }
}
