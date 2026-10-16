package com.botmaker.studio.parser.refactor;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.SignatureType;
import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.MethodReferences.CallSite;
import com.botmaker.studio.parser.refactor.SignatureMigration.ArgumentEdit;
import com.botmaker.studio.parser.refactor.SignatureMigration.CallChange;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The writing half of a signature change: it takes the {@linkplain SignatureMigration.Plan plan} the user has
 * just approved and turns it into edits.
 *
 * <p>Two audiences, because a project's calls are not all in the file being edited. The declaration's own file
 * goes through {@link com.botmaker.studio.parser.CodeEditor}'s guarded write like every other edit —
 * {@link #applyIn} records the calls that live there into <em>that same</em> rewrite, so the declaration and
 * its local calls change together or not at all. Every other file is rewritten here, in
 * {@link #rewriteOthers}, and — this is the point — <b>rewritten before anything is published</b>: the new
 * source for all of them is computed and parsed first, and a single file that comes out broken abandons the
 * whole change with nothing written anywhere.
 *
 * <p>Only then does {@link #commit} touch the disk. A migration that half-lands is the failure this whole
 * feature exists to prevent, so the ordering is deliberate rather than incidental.
 */
public final class CallMigrator {

    private CallMigrator() {}

    /** A file the migration changes, and what it should say afterwards. */
    public record Rewritten(ProjectFile file, String newSource) {}

    /**
     * Records into {@code ctx}'s rewrite every call the plan changes that lives in {@code ctx.cu()} — false
     * when one of them cannot be expressed there, which is the caller's cue to abandon the edit.
     *
     * <p>A plan built from a signature dialog can never fail this: its changes are renames and argument
     * shuffles at sites it just found. Only an SDK migration produces a change that can be refused.
     */
    public static boolean applyIn(EditContext ctx, SignatureMigration.Plan plan) {
        return applyIn(ctx, plan, false);
    }

    /**
     * The same, recording a {@linkplain ReviewMarks review mark} on the function around every call the change
     * leaves needing a look — see {@link #reviewEntries} — when {@code marking}, which is
     * {@link ReviewMarker#available} for a caller in a project.
     */
    public static boolean applyIn(EditContext ctx, SignatureMigration.Plan plan, boolean marking) {
        if (plan == null) return true;
        boolean applied = true;
        Map<MethodDeclaration, Set<String>> marks = new LinkedHashMap<>();
        for (CallChange change : plan.calls()) {
            if (change.site().unit() != ctx.cu()) continue;
            applied &= apply(ctx, change);
            if (marking) note(marks, change);
        }
        marks.forEach((method, entries) -> ReviewMarks.mark(ctx, method, List.copyOf(entries)));
        return applied;
    }

    /**
     * The new source of every file the plan touches other than {@code active} — or {@code null} when one of
     * them cannot be rewritten into source that parses, which refuses the migration entirely.
     *
     * <p>Files whose text the change leaves alone (a call standing as its own statement, when only the return
     * type moved) are simply absent from the result rather than rewritten to themselves.
     */
    public static List<Rewritten> rewriteOthers(SignatureMigration.Plan plan, CompilationUnit active,
                                                ProjectAnalyzer analyzer, ProjectState state) {
        return rewriteOthers(plan, active, analyzer, state, null, false);
    }

    /**
     * The same, marking for review in each of those files as {@link #applyIn} does in the active one. The
     * marks go into that file's own rewrite, so a file that comes out broken takes its marks down with it —
     * and a file Studio generates is rewritten like any other but never marked, since the next regeneration
     * would erase the mark ({@link ReviewMarker#marksSurvive}).
     */
    public static List<Rewritten> rewriteOthers(SignatureMigration.Plan plan, CompilationUnit active,
                                                ProjectAnalyzer analyzer, ProjectState state,
                                                ProjectConfig config, boolean marks) {
        Map<CompilationUnit, List<CallChange>> byUnit = new LinkedHashMap<>();
        for (CallChange change : plan.calls()) {
            if (change.site().unit() == active) continue;
            byUnit.computeIfAbsent(change.site().unit(), unit -> new ArrayList<>()).add(change);
        }

        List<Rewritten> rewritten = new ArrayList<>();
        for (Map.Entry<CompilationUnit, List<CallChange>> entry : byUnit.entrySet()) {
            ProjectFile file = entry.getValue().getFirst().site().file();
            EditContext ctx = EditContext.of(entry.getKey(), analyzer, state);
            // A change this cannot express — a constant moved to another class with no type written at the
            // call site to retarget — refuses the migration whole, exactly as an unparseable result does.
            boolean marking = marks && ReviewMarker.marksSurvive(config, state, file.getPath());
            Map<MethodDeclaration, Set<String>> noted = new LinkedHashMap<>();
            for (CallChange change : entry.getValue()) {
                if (!apply(ctx, change)) return null;
                if (marking) note(noted, change);
            }
            noted.forEach((method, entries) -> ReviewMarks.mark(ctx, method, List.copyOf(entries)));

            String source = ctx.applyTo(file.getContent());
            if (source == null) return null;
            if (source.equals(file.getContent())) continue;
            // The one check that stands between a bad rewrite and a file the user cannot open: applyRewrite
            // returns the original text when an edit won't apply, and a rewrite that *does* apply can still
            // produce nonsense. Either way this file is not written, and neither is any other.
            if (SourceParser.hasSyntaxErrors(SourceParser.parse(source))) return null;
            rewritten.add(new Rewritten(file, source));
        }
        return List.copyOf(rewritten);
    }

    /**
     * Writes the rewritten files to disk and updates the editor's copy of each.
     *
     * <p>The cached AST is dropped rather than re-parsed: {@code ProjectAnalyzer} re-parses a file whose AST is
     * missing and skips one that has any, so leaving yesterday's tree in place would keep every suggestion in
     * Studio reading the pre-migration signature.
     */
    public static void commit(List<Rewritten> files) throws IOException {
        for (Rewritten each : files) {
            Files.writeString(each.file().getPath(), each.newSource());
            each.file().setContent(each.newSource());
            each.file().setAst(null);
        }
    }

    /** A default value for {@code type}, importing whatever naming it needs in {@code ctx}'s file. */
    public static Expression defaultFor(EditContext ctx, SignatureType type) {
        if (type instanceof SignatureType.Typed typed) {
            return com.botmaker.studio.parser.handlers.MethodHandler.defaultValueFor(ctx, typed.type());
        }
        // Only List needs importing: every non-primitive BotType offers is written fully qualified since
        // 2026-09-01, when the fourteen SDK class literals left and the rest became plugin-seeded.
        type.described().ifPresent(choice -> {
            if (choice.isList()) ctx.addImport("java.util.List");
        });
        ResolvedType resolved = resolvedOf(type);
        Expression value = InitializerFactory.createDefaultInitializer(ctx.ast(), resolved, ctx.cu(), ctx.state());
        return value == null ? ctx.ast().newNullLiteral() : value;
    }

    /**
     * A default for {@code typeName} that compiles whatever the type turns out to be, spelled as source spells
     * it: {@code false}, {@code 0}, {@code ""} or {@code null}, and nothing that needs an import — what a
     * seeded {@code @Migrate} rule returns ({@code migrate.Seed}).
     *
     * <p>Deliberately blunter than {@link #defaultFor}, which asks the palette and may answer {@code new Point()}:
     * right for a signature edit, wrong where the type may be the very one a new jar dropped. {@code null} for
     * every reference type, including {@code List} and the boxed primitives.
     */
    public static String literalDefaultText(String typeName) {
        String type = typeName == null ? "" : typeName.trim();
        return switch (type) {
            case "boolean" -> "false";
            case "byte", "short", "int", "long", "float", "double", "char" -> "0";
            case "String", "java.lang.String" -> "\"\"";
            default -> "null";
        };
    }

    /** A {@code List<Point>} is a list; anything else is what the signature calls it. */
    private static ResolvedType resolvedOf(SignatureType type) {
        if (type instanceof SignatureType.Typed typed) {
            return com.botmaker.studio.plugin.ValueWire.resolvedType(typed.type());
        }
        return type.described().filter(BotType.Choice::isList)
                .map(choice -> ResolvedType.named("java.util.List"))
                .orElseGet(() -> ResolvedType.named(type.sourceName()));
    }

    // --- one call ------------------------------------------------------------------------------------------

    /** Records one change into {@code ctx}'s rewrite — false when it cannot be expressed at that site. */
    private static boolean apply(EditContext ctx, CallChange change) {
        return switch (change) {
            case CallChange.ValueReplaced replaced -> {
                ctx.rewriter().replace(replaced.site().node(), defaultFor(ctx, replaced.expected()), null);
                yield true;
            }
            case CallChange.Rewrite rewrite -> applyRewrite(ctx, rewrite);
        };
    }

    // --- what the user still has to look at ----------------------------------------------------------------

    /** Files {@code change}'s entries, if any, under the function they belong to. */
    private static void note(Map<MethodDeclaration, Set<String>> marks, CallChange change) {
        List<String> entries = reviewEntries(change);
        if (entries.isEmpty()) return;
        MethodDeclaration method = ReviewMarks.enclosingMethod(change.site().node());
        if (method == null) return;   // a call in a field initialiser: nowhere to hang a @Target(METHOD) mark
        marks.computeIfAbsent(method, m -> new LinkedHashSet<>()).addAll(entries);
    }

    /**
     * What one call change costs the user, in sentences — empty when the change is <em>complete</em> and there
     * is nothing to look at.
     *
     * <p>The distinction is the whole value of the review list, and it is the same one the SDK upgrade draws.
     * A rename, a reorder, a dropped literal: the call afterwards does exactly what it did before, and burying
     * the sites whose meaning changed under the ones that did not is how a list stops being read. What is
     * recorded is only ever a place where <b>a value the user wrote was replaced by a placeholder, or work the
     * call used to do stopped happening</b>.
     */
    private static List<String> reviewEntries(CallChange change) {
        return switch (change) {
            case CallChange.ValueReplaced replaced -> List.of(
                    "this used the result of \"" + calledName(replaced.site())
                            + "\", which no longer fits here — it now reads "
                            + replaced.expected().defaultText() + ".");
            case CallChange.Rewrite rewrite -> rewriteEntries(rewrite);
        };
    }

    private static List<String> rewriteEntries(CallChange.Rewrite rewrite) {
        List<String> entries = new ArrayList<>();
        String called = calledName(rewrite.site());
        for (ArgumentEdit edit : rewrite.arguments()) {
            if (edit instanceof ArgumentEdit.Fresh fresh) {
                entries.add("\"" + called + "\" gained an input here, filled in with "
                        + fresh.type().defaultText() + " — check that is the value you want.");
            }
        }
        entries.addAll(droppedWork(rewrite, called));
        return entries;
    }

    /**
     * Arguments the new call no longer has a place for <em>that did something</em>.
     *
     * <p>Dropping {@code clickAt(p, 3)}'s {@code 3} is a change the user asked for and read in the preview;
     * dropping {@code clickAt(p, countTargets())} silently stops {@code countTargets} from running, which is
     * not visible anywhere afterwards. Only the second is worth a row.
     */
    private static List<String> droppedWork(CallChange.Rewrite rewrite, String called) {
        List<?> current = rewrite.site().arguments();
        Set<Integer> kept = new LinkedHashSet<>();
        for (ArgumentEdit edit : rewrite.arguments()) {
            if (edit instanceof ArgumentEdit.Keep keep) kept.add(keep.from());
        }
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            if (kept.contains(i) || !(current.get(i) instanceof Expression argument)) continue;
            if (!doesSomething(argument)) continue;
            entries.add("\"" + called + "\" lost the input \"" + argument
                    + "\" here, so what it did no longer runs.");
        }
        return entries;
    }

    /** True when throwing this expression away throws work away — it calls or constructs something. */
    private static boolean doesSomething(Expression argument) {
        boolean[] found = {false};
        argument.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                found[0] = true;
                return false;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                found[0] = true;
                return false;
            }
        });
        return found[0];
    }

    /** What the call is called, for a sentence — the class's name for a {@code new Thing(…)}. */
    private static String calledName(CallSite site) {
        SimpleName name = site.nameNode();
        return name != null ? name.getIdentifier() : site.className();
    }

    private static boolean applyRewrite(EditContext ctx, CallChange.Rewrite rewrite) {
        CallSite site = rewrite.site();
        SimpleName name = site.nameNode();
        // Null for a `new GoHome(…)`: its name is the class's, and renaming that is a different edit.
        if (name != null && !name.getIdentifier().equals(rewrite.newName())) {
            // A bare name reached through a static import is only half-renamed by touching the use: the
            // import still names the old member, so the file stops compiling. `case UP ->` is the one bare
            // name with no import behind it, and rightly gets none invented for it.
            if (site.node() instanceof SimpleName && !isCaseLabel(site.node())
                    && !renameStaticImport(ctx, name.getIdentifier(), rewrite.newName())) {
                return false;
            }
            // A bare call is the same story when the method is statically imported, which a bound finder
            // now reports rather than refusing as unsure. Inside the declaring class, or inherited, there is
            // no import and nothing to rename but the call.
            if (site.node() instanceof MethodInvocation call && call.getExpression() == null) {
                renameStaticImport(ctx, name.getIdentifier(), rewrite.newName());
            }
            ctx.rewriter().set(name, SimpleName.IDENTIFIER_PROPERTY, rewrite.newName(), null);
        }
        // A reference names the method and passes nothing: its name is all a rename has to follow, and a
        // change of shape was refused before a plan was made (SignatureEdits).
        if (site.isReference() || unchanged(site, rewrite.arguments())) return true;

        ListRewrite arguments = ctx.rewriter().getListRewrite(site.node(), site.argumentsProperty());
        List<?> current = site.arguments();
        for (int i = current.size() - 1; i >= 0; i--) arguments.remove((ASTNode) current.get(i), null);
        for (ArgumentEdit edit : rewrite.arguments()) {
            Expression argument = nodeFor(ctx, current, edit);
            if (argument == null) return false;
            arguments.insertLast(argument, null);
        }
        return true;
    }

    private static boolean isCaseLabel(ASTNode node) {
        return node.getLocationInParent() == SwitchCase.EXPRESSIONS2_PROPERTY;
    }

    /** Renames the member in the static import that brought {@code member} in — false when there is none. */
    private static boolean renameStaticImport(EditContext ctx, String member, String newName) {
        ImportDeclaration imp = staticImportOf(ctx.cu(), member);
        if (imp == null || !(imp.getName() instanceof QualifiedName qualified)) return false;
        ctx.rewriter().set(qualified.getName(), SimpleName.IDENTIFIER_PROPERTY, newName, null);
        return true;
    }

    /**
     * The {@code import static …} that brings {@code member} into the file by name, or null.
     *
     * <p>An on-demand {@code import static …Key.*} is not one: it says nothing about which names were meant,
     * so there is nothing to rewrite in it and nothing that could be rewritten safely.
     */
    private static ImportDeclaration staticImportOf(CompilationUnit cu, String member) {
        for (Object each : cu.imports()) {
            ImportDeclaration imp = (ImportDeclaration) each;
            if (!imp.isStatic() || imp.isOnDemand()) continue;
            if (imp.getName() instanceof QualifiedName qualified
                    && qualified.getName().getIdentifier().equals(member)) {
                return imp;
            }
        }
        return null;
    }

    /** True when the wanted arguments are the ones already there, in the order they are already in. */
    private static boolean unchanged(CallSite site, List<ArgumentEdit> wanted) {
        if (wanted.size() != site.argumentCount()) return false;
        for (int i = 0; i < wanted.size(); i++) {
            if (!(wanted.get(i) instanceof ArgumentEdit.Keep keep) || keep.from() != i) return false;
        }
        return true;
    }

    /**
     * The node for one argument of the new call: a copy of what the user wrote, or a default value.
     *
     * <p>A copy, not the original node — the list it came from is being removed by the same rewrite, and a node
     * cannot be both removed and re-inserted.
     */
    private static Expression nodeFor(EditContext ctx, List<?> current, ArgumentEdit edit) {
        return switch (edit) {
            case ArgumentEdit.Keep keep ->
                    (Expression) ASTNode.copySubtree(ctx.ast(), (ASTNode) current.get(keep.from()));
            case ArgumentEdit.Fresh fresh -> defaultFor(ctx, fresh.type());
        };
    }
}
