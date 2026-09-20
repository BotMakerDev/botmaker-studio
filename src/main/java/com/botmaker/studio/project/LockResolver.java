package com.botmaker.studio.project;

import com.botmaker.plugin.api.ManagedValue;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.managed.JavaManagedSource;
import com.botmaker.studio.project.params.JavaParameterSource;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.nio.file.Path;
import java.util.List;

/**
 * The one answer to "may the user change <em>this</em> node, and how?"
 *
 * <p><b>Why this exists.</b> Two verdicts — the file's and the enclosing method's — used to be consulted
 * separately and contradicted each other, and whichever caller asked last won. Both asked here instead, so
 * they could not drift apart. The method-level half is gone (see {@link FileRole} on why nothing is generated
 * any more), and what is left is still the one place to ask: if you find yourself calling {@code FileRole.of}
 * outside this class to decide whether an edit is allowed, that is the bug this class was written to end.
 *
 * <p><b>The rule</b>, now that a project's Java is the user's:
 * <pre>
 * denied  &lt;- the project is open for reading  (an installed bot, see {@link ProjectMode})
 * denied  &lt;- the file is bundled library source, or a plugin's generated model ({@link FileRole})
 * denied  &lt;- SIGNATURE edits to {@code public static void main(String[])}
 * denied  &lt;- anything in the file the Parameters window owns ({@link Managed})
 * denied  &lt;- a {@code @Param} field, wherever it is
 * denied  &lt;- a {@code @Managed} method's body, and all of a {@code @Managed} class ({@link ManagedValue})
 * allowed &lt;- otherwise, the BODY of {@code main} included
 * </pre>
 *
 * <p><b>The fourth rule (2026-09-19) is keyed on a path, and that is the difference from {@code main}'s.</b>
 * {@code Parameters.java} is what <i>Project ▸ Parameters</i> reads and writes, and that window keeps what
 * the canvas does not — the annotation's members — so an edit made on the canvas is the one that leaves a
 * parameter half-changed. The blocks are still drawn, with their pickers as previews: the file is shown and
 * refused, never hidden. The window writes through {@code BotSources}, not through here, so the rule stops
 * the canvas and nothing else. <b>Only the host's own file is named</b>: a file a plugin's vocabulary shapes
 * (a class of {@code ImageTemplate} constants) is that plugin's to speak for, not this class's.
 *
 * <p><b>The last two are matched on an annotation the author wrote.</b> A {@code @Param} field is the
 * Parameters window's wherever its author put it, so being in another file does not make it editable. A
 * plugin says which ids it keeps in step through its own window ({@code StudioPlugin.managedValues}, read
 * from {@code PluginHost}), and this class matches a {@code @Managed("id")} method or type against them
 * without knowing what any of them are.
 *
 * <p>The third is the only rule left that is about a <em>member</em>, and it is the narrowest thing that
 * works: <b>{@code main}'s signature is fixed and its body is the user's.</b> The signature is not a
 * BotMaker convention — it is the one the JVM will look for — so renaming it, giving it a return type or
 * changing its parameter is the single edit whose consequence the user cannot read off the screen: the
 * project stops launching, or stops compiling, with nothing pointing at what changed. Its <em>body</em> is
 * the opposite: it is where the bot is actually put together, one static call at a time, and it is meant to
 * be edited. Nothing is "installed" there and no plugin is registered — {@code PopupGuard.install},
 * {@code Bot.start} and {@code FlowGraph.run} are ordinary static API methods a user calls or does not.</p>
 *
 * <p>Pure and cheap: no I/O, no state, constructed per call. A null {@code config} or {@code file} means "we
 * don't know what project this is" — used by tests and by editor paths with no project open — and permits
 * everything. A null <em>node</em>, by contrast, is a caller that forgot to say what it was editing, and is
 * always denied.
 */
public record LockResolver(ProjectConfig config, Path file, boolean readerMode) {

    /** The message shown when an edit is refused solely because the project is open for reading. */
    public static final String READER_MODE_REASON =
            "This bot is open for reading. Switch to Editor mode to change it.";

    /** Back-compat overload for callers with no project mode in hand: never reader. */
    public LockResolver(ProjectConfig config, Path file) {
        this(config, file, false);
    }

    /**
     * Which half of a member an edit touches. Both are now judged the same way — the distinction survives
     * because callers say which they mean, and because a future rule may again treat them differently.
     */
    public enum EditKind {
        /** Statements inside a method body. */
        BODY,
        /** A method's name/params/return type/existence, a field, an import, the class itself. */
        SIGNATURE
    }

    /** Whether an edit is allowed, and — when it isn't — what to tell the user. */
    public record Verdict(boolean allowed, String reason) {
        private static final Verdict OK = new Verdict(true, null);

        static Verdict ok() {
            return OK;
        }

        static Verdict no(String reason) {
            return new Verdict(false, reason);
        }
    }

    /** A file whose contents another window owns, with where to go instead and the status-line badge. */
    public enum Managed {
        PARAMETERS("Parameters.java holds the bot's parameters. Change them in Project ▸ Parameters…",
                "Parameters - Read Only");

        private final String reason;
        private final String badge;

        Managed(String reason, String badge) {
            this.reason = reason;
            this.badge = badge;
        }

        public String reason() {
            return reason;
        }

        public String badge() {
            return badge;
        }
    }

    /** Which window owns this file, or {@code null} for a file the canvas may edit. */
    public Managed managed() {
        if (config == null || file == null) return null;
        Path normalized = file.toAbsolutePath().normalize();
        return normalized.equals(config.parametersSourceFile().toAbsolutePath().normalize())
                ? Managed.PARAMETERS : null;
    }

    /** The status-line suffix for this file, or {@code null} for an ordinary one. */
    public String badge() {
        Managed managed = managed();
        return managed != null ? managed.badge() : role().badge();
    }

    /** The resolver for whatever file is being edited right now, or a permissive one if there is no project. */
    public static LockResolver forActiveFile(ProjectConfig config, ProjectState state) {
        if (config == null || state == null) return new LockResolver(null, null, false);
        ProjectFile active = state.getActiveFile();
        return new LockResolver(config, active == null ? null : active.getPath(), state.isReaderMode());
    }

    /** This file's role. {@link FileRole#EDITABLE} when we don't know the project. */
    public FileRole role() {
        return config == null ? FileRole.EDITABLE : FileRole.of(file);
    }

    /** True when {@code node}'s member may be renamed/retyped/deleted, or its class-level structure changed. */
    public boolean signatureEditable(ASTNode node) {
        return editable() && !isEntryPointMain(node) && managedReason(node, PluginHost.managedValues()) == null;
    }

    /** True when statements inside {@code node}'s method may be changed — {@code main}'s included. */
    public boolean bodyEditable(ASTNode node) {
        return editable() && managedReason(node, PluginHost.managedValues()) == null;
    }

    /** Why the Parameters window, not the canvas, changes a {@code @Param} field. */
    public static final String PARAM_REASON =
            "This is a parameter. Change it in Project ▸ Parameters…, which keeps its annotation in step.";

    /**
     * Why {@code node} belongs to another window, or {@code null} when nothing but the file decides.
     *
     * <p>Pure, and public for that reason: the plugin rule is tested with a list rather than a bound plugin.
     * Nothing is resolved — the annotation is matched by simple name, as {@code @Param} is — because the
     * editor routinely draws a file whose siblings do not compile.
     *
     * <p><b>An annotation, since 2026-09-20.</b> This matched a field's <em>declared type</em> until then,
     * which cannot tell two same-typed classes apart, and inferred that a class of nothing but such
     * constants was managed whole. Both were guesses from shape. {@code @Managed("id")} is a statement, and
     * it carries which of a plugin's values a method holds.
     */
    public static String managedReason(ASTNode node, List<ManagedValue> managed) {
        FieldDeclaration field = enclosing(node, FieldDeclaration.class);
        if (field != null && JavaParameterSource.paramAnnotation(field) != null) return PARAM_REASON;
        if (managed.isEmpty()) return null;
        // The method's own annotation first: a @Managed method inside a class that is not annotated is one
        // value of many in an ordinary file, and only its body is the plugin's.
        MethodDeclaration method = enclosing(node, MethodDeclaration.class);
        if (method != null) {
            String reason = pluginReason(method, managed);
            if (reason != null) return reason;
        }
        // A @Managed type is the plugin's whole: adding a member to it, renaming one or deleting its
        // constructor is an edit to a class whose purpose is somebody else's window.
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (!(n instanceof TypeDeclaration type)) continue;
            String reason = pluginReason(type, managed);
            if (reason != null) return reason;
        }
        return null;
    }

    /** The reason of the managed entry this declaration's {@code @Managed} id names, or {@code null}. */
    private static String pluginReason(BodyDeclaration declaration, List<ManagedValue> managed) {
        Annotation annotation = JavaManagedSource.managedAnnotation(declaration);
        if (annotation == null) return null;
        String id = JavaManagedSource.idOf(annotation);
        if (id.isEmpty()) return null;
        for (ManagedValue entry : managed) {
            if (id.equals(entry.id())) return entry.reason();
        }
        return null;
    }

    private static <T extends ASTNode> T enclosing(ASTNode node, Class<T> kind) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (kind.isInstance(n)) return kind.cast(n);
        }
        return null;
    }

    /** True when blocks in this file should default to refusing interaction. */
    public boolean suppressesInteraction() {
        return readerMode || role().suppressesInteraction() || managed() != null;
    }

    private boolean editable() {
        return !readerMode && !role().isReadOnly() && managed() == null;
    }

    /**
     * True when {@code node} is, or sits inside, a {@code public static void main(String[])}.
     *
     * <p>Matched on the <em>shape</em> rather than on the file's path, deliberately: the entry point is the
     * user's file to rename, move or split up, and a rule keyed on {@code config.mainSourceFile()} would stop
     * holding the moment they did. What must not change is the method the JVM calls, wherever they put it.
     */
    public static boolean isEntryPointMain(ASTNode node) {
        MethodDeclaration method = enclosingMethod(node);
        if (method == null || method.getName() == null) return false;
        if (!"main".equals(method.getName().getIdentifier())) return false;
        if (!Modifier.isStatic(method.getModifiers())) return false;
        if (method.parameters().size() != 1) return false;
        Object first = method.parameters().getFirst();
        return first instanceof SingleVariableDeclaration parameter
                && "String[]".equals(parameter.getType().toString());
    }

    /** Why {@code main}'s signature can't change, phrased for the status bar. */
    public static String entryPointReason() {
        return "main(String[] args) is the method Java itself looks for when your bot starts, so its name and "
                + "parameters stay as they are. What it does is entirely yours — edit the code inside it.";
    }

    /**
     * The nearest {@link MethodDeclaration} at or above {@code node}, or null if there is none.
     *
     * <p>JDT keeps comments out of the parent chain — a {@link Comment}'s {@code getParent()} is always null —
     * so one inside {@code main} would be judged by its file instead of its method. Its own parent chain is
     * unavailable, so a comment is judged permissively: a comment is not code, and nothing about the bot's
     * start-up depends on one.
     */
    private static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration method) return method;
        }
        return null;
    }

    /** Whether {@code kind} of edit is permitted at {@code node}. */
    public boolean permits(ASTNode node, EditKind kind) {
        return check(node, kind).allowed();
    }

    /**
     * Whether {@code kind} of edit is permitted at {@code node}, with a reason to show the user when it isn't.
     * A null {@code node} is denied: the escape hatch for "no project" belongs on {@code config}, so a caller
     * that forgot to name its target fails loudly rather than silently editing locked code.
     */
    public Verdict check(ASTNode node, EditKind kind) {
        if (config == null || file == null) return Verdict.ok();
        if (node == null) return Verdict.no("Nothing to edit.");
        // Reading someone else's bot outranks the file's own verdict: nothing here is the user's to change.
        if (readerMode) return Verdict.no(READER_MODE_REASON);
        // Every read-only role answers with its own sentence, so a role added later cannot be locked here
        // and left explaining itself as "library code".
        if (role().isReadOnly()) return Verdict.no(role().reason());
        Managed managed = managed();
        if (managed != null) return Verdict.no(managed.reason());
        String reason = managedReason(node, PluginHost.managedValues());
        if (reason != null) return Verdict.no(reason);
        if (kind == EditKind.SIGNATURE && isEntryPointMain(node)) return Verdict.no(entryPointReason());
        return Verdict.ok();
    }
}
