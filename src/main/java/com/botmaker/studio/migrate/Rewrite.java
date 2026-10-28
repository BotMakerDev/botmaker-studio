package com.botmaker.studio.migrate;

import com.botmaker.plugin.api.meta.Migrate;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.Uses;
import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.parser.guard.CompileGuard;
import com.botmaker.studio.parser.guard.CompileGuard.Problem;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.refactor.ReviewMarks;
import com.botmaker.studio.plugin.grammar.JavaExpressions;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every use of an element a ready rule names, rewritten through the rule's body, as one plan over the whole bot
 * (2026-10-16). Pure: two parses in, a {@link Refactor.Outcome} out, nothing written.
 *
 * <p><b>Each use in place.</b> A call, a {@code new}, a field read and a type name take the rule's body with the
 * site's own parts put in ({@link Template}); a use inside another's parts is rewritten inside it, so a chain
 * {@code a.marker(x).marker(y)} comes out whole. A rule that returns nothing replaces the statement its call is.
 *
 * <p><b>Checked, then left.</b> The plan is parsed as the whole bot against the new jars; a use whose rewrite
 * adds an error is left as written, and so is one no rule can stand at (a method reference, an assignment).
 * Each one left is listed and its function marked {@code @Refactor}. An error no use accounts for refuses the
 * whole plan with javac's sentence.
 */
public final class Rewrite {

    private Rewrite() {}

    /** A use left as written, and why. */
    public record Left(Sites.Site site, String why) {}

    /**
     * What applying the ready rules does.
     *
     * @param outcome the files rewritten, or why nothing is
     * @param applied each use rewritten
     * @param left    each use left as written
     * @param changes each outermost use rewritten, as it reads before and after: what a preview shows
     */
    public record Result(Refactor.Outcome outcome, List<Sites.Site> applied, List<Left> left, List<Change> changes) {

        public Result {
            applied = List.copyOf(applied);
            left = List.copyOf(left);
            changes = List.copyOf(changes);
        }

        Result(Refactor.Outcome outcome, List<Sites.Site> applied, List<Left> left) {
            this(outcome, applied, left, List.of());
        }
    }

    /**
     * One use as a rewrite changes it: its text before, and after — {@code ""} for a call its rule deletes. A use
     * inside it that a rule rewrites too is rewritten in both.
     */
    public record Change(Sites.Site site, String before, String after) {}

    /**
     * One use, planned: the range of the original text it replaces and its new text, or why it has none.
     *
     * @param methodStart the start of the function it is in, or -1
     */
    private record Candidate(Sites.Site site, Rule rule, int start, int end, String text, String why,
                             int methodStart) {

        boolean ok() {
            return text != null;
        }

        boolean contains(Candidate other) {
            return this != other && start <= other.start && other.end <= end;
        }
    }

    /** One file's plan, as plain data: its uses, the imports that name an element, and which elements no rule has. */
    private record FilePlan(Path file, String source, List<Candidate> candidates, List<Sites.Site> imports,
                            Map<Sites.Site, int[]> importRanges, boolean holdsRules, Set<String> unruled) {}

    /** One file as one round writes it. */
    private record Written(String text, Map<Candidate, int[]> ranges) {}

    /**
     * @param seededToo whether a seeded rule is applied too, as {@link Rule#applicable} says
     * @param unruled   broken elements no rule stands for, and why: each use is left, listed and marked, and an
     *                  import of one nothing else uses any more goes — the jar that declared it is gone
     */
    static Result plan(BotIndex now, BotIndex old, Path probe, List<Rule> rules, boolean markable,
                       boolean seededToo, Map<OldElement, String> unruled) {
        Map<String, Rule> byKey = new LinkedHashMap<>();
        Map<String, OldElement> elements = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (!rule.applicable(seededToo)) continue;
            byKey.put(rule.element().key(), rule);
            elements.put(rule.element().key(), rule.element());
        }
        Map<String, String> why = new LinkedHashMap<>();
        unruled.forEach((element, reason) -> {
            if (byKey.containsKey(element.key())) return;
            elements.putIfAbsent(element.key(), element);
            why.put(element.key(), reason);
        });
        if (byKey.isEmpty() && why.isEmpty()) {
            return new Result(new Refactor.Refused("No rule is ready: write one for an element the bot uses."),
                    List.of(), List.of());
        }
        Set<Path> ruleFiles = new LinkedHashSet<>();
        for (Rule rule : rules) ruleFiles.add(rule.file());
        List<FilePlan> files = old.read(units -> {
            List<FilePlan> out = new ArrayList<>();
            units.forEach((file, unit) -> {
                if (Rules.added(file, probe)) return;
                List<Uses.Found> found = Uses.in(unit, elements.keySet(), true);
                if (!found.isEmpty()) {
                    out.add(planned(file, old.sources().get(file), unit, found, byKey, why, elements,
                            ruleFiles.contains(file)));
                }
            });
            return out;
        });

        Map<Candidate, String> failed = new LinkedHashMap<>();
        Map<Path, Written> written = new LinkedHashMap<>();
        String unexplained = null;
        for (int round = 0; ; round++) {
            written.clear();
            for (FilePlan file : files) {
                Written one = written(file, failed);
                if (!one.text().equals(file.source())) written.put(file.file(), one);
            }
            if (written.isEmpty()) break;
            Map<Path, String> rewrites = new LinkedHashMap<>();
            written.forEach((file, one) -> rewrites.put(file, one.text()));
            BotIndex after = now.with(rewrites);
            Map<Path, List<Problem>> introduced = now.introduced(after);
            if (introduced.isEmpty()) break;
            // Each round fails at least one use, and a use nested n deep is reached in n rounds: a bound, not a guess.
            if (round > files.stream().mapToInt(f -> f.candidates().size()).sum()) {
                Map.Entry<Path, List<Problem>> first = introduced.entrySet().iterator().next();
                unexplained = first.getKey().getFileName() + ", " + first.getValue().getFirst().message();
                break;
            }
            unexplained = blame(introduced, written, files, failed);
            if (unexplained != null) break;
        }

        List<Sites.Site> applied = new ArrayList<>();
        List<Left> left = new ArrayList<>();
        for (FilePlan file : files) {
            for (Candidate candidate : file.candidates()) {
                // A use inside one whose rewrite drops it reaches the file neither rewritten nor left.
                if (consumed(candidate, file, failed)) continue;
                if (candidate.ok() && !failed.containsKey(candidate)) applied.add(candidate.site());
                else {
                    left.add(new Left(candidate.site(), candidate.ok() ? failed.get(candidate) : candidate.why()));
                }
            }
        }
        if (unexplained != null) {
            return new Result(new Refactor.Refused("Applying the rules would stop the bot compiling (" + unexplained
                    + "), so nothing was changed."), applied, left);
        }

        Map<Path, String> rewrites = new LinkedHashMap<>();
        for (FilePlan file : files) {
            Written one = written.get(file.file());
            String text = one == null ? file.source() : one.text();
            if (markable) text = marked(file, text, one, failed);
            if (!text.equals(file.source())) rewrites.put(file.file(), text);
        }
        if (rewrites.isEmpty()) {
            return new Result(new Refactor.Refused("No use of an element a rule covers could be rewritten."),
                    applied, left);
        }
        String summary = "Migrated " + applied.size() + (applied.size() == 1 ? " use" : " uses")
                + (left.isEmpty() ? "" : ", " + left.size() + " left for review");
        List<Change> changes = new ArrayList<>();
        for (FilePlan file : files) {
            List<Candidate> live = file.candidates().stream()
                    .filter(c -> c.ok() && !failed.containsKey(c) && !consumed(c, file, failed)).toList();
            for (Candidate each : top(live)) {
                changes.add(new Change(each.site(), file.source().substring(each.start(), each.end()), each.text()));
            }
        }
        return new Result(new Refactor.Planned(summary, rewrites), applied, left, changes);
    }

    // --- planning one file, over the old parse ------------------------------------------------------------------

    private static FilePlan planned(Path file, String source, CompilationUnit unit, List<Uses.Found> found,
                                    Map<String, Rule> byKey, Map<String, String> unruled,
                                    Map<String, OldElement> elements, boolean holdsRules) {
        List<Uses.Found> ordered = new ArrayList<>(found);
        ordered.sort(Comparator.comparingInt((Uses.Found f) -> f.node().getLength()));
        List<Candidate> candidates = new ArrayList<>();
        List<Sites.Site> imports = new ArrayList<>();
        Map<Sites.Site, int[]> importRanges = new HashMap<>();
        for (Uses.Found each : ordered) {
            ASTNode node = each.node();
            Sites.Site site = Sites.Site.of(file, unit, elements.get(each.key()), each);
            if (each.shape() == Uses.Shape.IMPORT) {
                imports.add(site);
                importRanges.put(site, lineRange(source, node));
                continue;
            }
            Rule rule = byKey.get(each.key());
            candidates.add(rule == null ? left(site, null, node, unruled.get(each.key()), methodStart(node))
                    : candidate(site, node, rule, source, candidates));
        }
        candidates.sort(Comparator.comparingInt(Candidate::start));
        return new FilePlan(file, source, List.copyOf(candidates), List.copyOf(imports), importRanges, holdsRules,
                unruled.keySet());
    }

    /** The start of the function {@code node} is in, or -1. */
    private static int methodStart(ASTNode node) {
        MethodDeclaration method = ReviewMarks.enclosingMethod(node);
        return method == null ? -1 : method.getStartPosition();
    }

    private static Candidate candidate(Sites.Site site, ASTNode node, Rule rule, String source,
                                       List<Candidate> inner) {
        int methodStart = methodStart(node);
        int start = node.getStartPosition();
        int end = start + node.getLength();
        String name = rule.qualified();
        Map<String, Template.Part> parts = new LinkedHashMap<>();
        List<?> arguments = List.of();
        switch (site.shape()) {
            case TYPE_NAME -> {
                return new Candidate(site, rule, start, end, OldElement.simple(rule.returns()), null, methodStart);
            }
            case REFERENCE -> {
                return left(site, rule, node, "it is a method reference, which has no parts to put into " + name,
                        methodStart);
            }
            case WRITE -> {
                return left(site, rule, node, "it assigns the field, and " + name + " stands only for its value",
                        methodStart);
            }
            case CASE -> {
                Expression body = JavaExpressions.parse(rule.body());
                if (!(body instanceof Name constant)) {
                    return left(site, rule, node, "a case label takes a constant, and " + name
                            + " returns something else", methodStart);
                }
                String label = constant instanceof QualifiedName qualified ? qualified.getName().getIdentifier()
                        : constant.getFullyQualifiedName();
                return new Candidate(site, rule, start, end, label, null, methodStart);
            }
            case READ -> {
                if (node instanceof SuperFieldAccess) {
                    return left(site, rule, node, "it reads super's field, which " + name + " cannot stand for",
                            methodStart);
                }
                if (rule.self()) {
                    ASTNode receiver = switch (node) {
                        case QualifiedName qualified -> qualified.getQualifier();
                        case FieldAccess access -> access.getExpression();
                        default -> null;
                    };
                    parts.put(rule.params().getFirst(), receiver == null ? new Template.Part("this", true)
                            : Template.Part.of(receiver, textOf(receiver, source, inner)));
                }
            }
            case CREATION -> {
                ClassInstanceCreation creation = (ClassInstanceCreation) node;
                if (creation.getAnonymousClassDeclaration() != null) {
                    return left(site, rule, node, "it declares a class of its own, which " + name
                            + " cannot carry", methodStart);
                }
                arguments = creation.arguments();
            }
            case DELEGATION -> {
                return left(site, rule, node, "it is this(…) or super(…), which only a constructor call can be",
                        methodStart);
            }
            case CALL, STATEMENT -> {
                if (!(node instanceof MethodInvocation call)) {
                    return left(site, rule, node, "it calls super's method, which " + name + " cannot stand for",
                            methodStart);
                }
                if (rule.self()) {
                    Expression receiver = call.getExpression();
                    parts.put(rule.params().getFirst(), receiver == null ? new Template.Part("this", true)
                            : Template.Part.of(receiver, textOf(receiver, source, inner)));
                }
                arguments = call.arguments();
                if (rule.statement()) {
                    if (site.shape() != Uses.Shape.STATEMENT) {
                        return left(site, rule, node, name + " returns nothing, and this call's value is used",
                                methodStart);
                    }
                    ASTNode statement = node.getParent();
                    start = statement.getStartPosition();
                    end = start + statement.getLength();
                    if (rule.body().isEmpty()) {
                        // Nothing in its place: the call goes, and a statement that must be one stays an empty block.
                        String text = statement.getParent() instanceof Block ? "" : "{}";
                        return new Candidate(site, rule, start, end, text, null, methodStart);
                    }
                }
            }
            case IMPORT -> throw new IllegalArgumentException("an import is no use a rule rewrites");
        }
        if (rule.kind() == Migrate.Kind.TYPE && site.shape() != Uses.Shape.CREATION) {
            return left(site, rule, node, "it uses the type in a way only its name changes for", methodStart);
        }
        List<String> names = rule.parts();
        if (site.shape() != Uses.Shape.READ && arguments.size() != names.size()) {
            return left(site, rule, node, "it passes " + arguments.size() + " parts, and " + name + " takes "
                    + names.size(), methodStart);
        }
        for (int i = 0; i < arguments.size(); i++) {
            ASTNode argument = (ASTNode) arguments.get(i);
            parts.put(names.get(i), Template.Part.of(argument, textOf(argument, source, inner)));
        }
        Optional<String> filled = Template.filled(rule.body(), rule.statement(), parts);
        if (filled.isEmpty()) {
            return left(site, rule, node, name + "'s body could not be filled in: it declares a name one of its"
                    + " parameters has", methodStart);
        }
        String text = filled.get();
        if (!rule.statement() && !Template.bare(node)) {
            Expression result = JavaExpressions.parse(text);
            if (result == null || !Template.primary(result)) text = "(" + text + ")";
        }
        return new Candidate(site, rule, start, end, text, null, methodStart);
    }

    private static Candidate left(Sites.Site site, Rule rule, ASTNode node, String why, int methodStart) {
        return new Candidate(site, rule, node.getStartPosition(), node.getStartPosition() + node.getLength(), null,
                why, methodStart);
    }

    /**
     * Whether {@code each}, a use not rewritten, went with a use around it that was: the rewrite of that use no
     * longer holds its text — a part a rule drops, a call a rule deletes. It is neither left nor kept.
     */
    private static boolean consumed(Candidate each, FilePlan file, Map<Candidate, String> failed) {
        // A use rewritten is looked for as rewritten, one kept as it is written.
        String original = each.ok() && !failed.containsKey(each) ? each.text()
                : file.source().substring(each.start(), each.end());
        if (original.isEmpty()) return false;
        for (Candidate outer : file.candidates()) {
            if (outer.ok() && !failed.containsKey(outer) && outer.contains(each) && !outer.text().contains(original)) {
                return true;
            }
        }
        return false;
    }

    /** {@code node}'s text with each use inside it already planned put in its place. */
    private static String textOf(ASTNode node, String source, List<Candidate> inner) {
        int from = node.getStartPosition();
        int to = from + node.getLength();
        List<Candidate> inside = new ArrayList<>();
        for (Candidate each : inner) {
            if (each.ok() && each.start() >= from && each.end() <= to) inside.add(each);
        }
        StringBuilder out = new StringBuilder(source.substring(from, to));
        top(inside).stream().sorted(Comparator.comparingInt(Candidate::start).reversed())
                .forEach(each -> out.replace(each.start() - from, each.end() - from, each.text()));
        return out.toString();
    }

    /** The candidates no other one of them contains. */
    private static List<Candidate> top(List<Candidate> candidates) {
        return candidates.stream()
                .filter(each -> candidates.stream().noneMatch(other -> other.contains(each)
                        && !(other.start() == each.start() && other.end() == each.end()
                        && candidates.indexOf(other) > candidates.indexOf(each))))
                .toList();
    }

    /** {@code node}'s range with the line break after it, so a removed import leaves no blank line. */
    private static int[] lineRange(String source, ASTNode node) {
        int start = node.getStartPosition();
        int end = start + node.getLength();
        if (end < source.length() && source.charAt(end) == '\r') end++;
        if (end < source.length() && source.charAt(end) == '\n') end++;
        return new int[] {start, end};
    }

    // --- one round ------------------------------------------------------------------------------------------------

    /** {@code file} with every use not failed rewritten, and the imports that follow. */
    private static Written written(FilePlan file, Map<Candidate, String> failed) {
        List<Candidate> live = file.candidates().stream().filter(c -> c.ok() && !failed.containsKey(c)).toList();
        // The elements a use of is still written in this file: kept as written, and not inside a rewrite that drops it.
        Set<String> kept = new LinkedHashSet<>();
        for (Candidate each : file.candidates()) {
            if ((!each.ok() || failed.containsKey(each)) && !consumed(each, file, failed)) {
                kept.add(each.site().element().key());
            }
        }
        record Edit(int start, int end, String text, Candidate candidate) {}
        List<Edit> edits = new ArrayList<>();
        for (Candidate each : top(live)) edits.add(new Edit(each.start(), each.end(), each.text(), each));
        if (!file.holdsRules()) {
            for (Sites.Site imported : file.imports()) {
                // A static import of a method names every overload of it, and only one is this element's.
                if (imported.element().kind() == Migrate.Kind.METHOD) continue;
                if (kept.contains(imported.element().key())) continue;
                // A rule's element is still declared until every use is rewritten; one no rule stands for is gone.
                boolean ruled = file.candidates().stream().anyMatch(c -> c.rule() != null
                        && c.site().element().key().equals(imported.element().key()));
                if (ruled && live.stream().noneMatch(c -> c.site().element().key().equals(imported.element().key()))) {
                    continue;
                }
                if (!ruled && !file.unruled().contains(imported.element().key())) continue;
                int[] range = file.importRanges().get(imported);
                edits.add(new Edit(range[0], range[1], "", null));
            }
        }
        edits.sort(Comparator.comparingInt(Edit::start));
        StringBuilder text = new StringBuilder();
        Map<Candidate, int[]> ranges = new LinkedHashMap<>();
        int at = 0;
        for (Edit edit : edits) {
            if (edit.start() < at) continue;
            text.append(file.source(), at, edit.start());
            if (edit.candidate() != null) {
                ranges.put(edit.candidate(), new int[] {text.length(), text.length() + edit.text().length()});
            }
            text.append(edit.text());
            at = edit.end();
        }
        text.append(file.source().substring(at));
        String once = text.toString();
        if (live.isEmpty()) return new Written(once, ranges);

        Set<String> imports = new LinkedHashSet<>();
        Set<String> statics = new LinkedHashSet<>();
        for (Candidate each : live) {
            imports.addAll(each.rule().imports());
            statics.addAll(each.rule().staticImports());
            if (each.rule().kind() == Migrate.Kind.TYPE) imports.add(each.rule().returns());
        }
        String imported = imported(once, imports, statics);
        // Imports sit above every use, so each range moves by what they added.
        int shift = imported.length() - once.length();
        ranges.replaceAll((candidate, range) -> new int[] {range[0] + shift, range[1] + shift});
        return new Written(imported, ranges);
    }

    /** {@code source} importing each of {@code imports} and, statically, each of {@code statics}. */
    static String imported(String source, Set<String> imports, Set<String> statics) {
        CompilationUnit unit = BotParser.syntax(source);
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        for (String each : imports) ImportManager.addImport(unit, rewrite, each);
        if (!statics.isEmpty()) {
            Set<String> present = new LinkedHashSet<>();
            for (Object each : unit.imports()) {
                ImportDeclaration declaration = (ImportDeclaration) each;
                if (declaration.isStatic()) present.add(declaration.getName().getFullyQualifiedName());
            }
            AST ast = unit.getAST();
            ListRewrite list = rewrite.getListRewrite(unit, CompilationUnit.IMPORTS_PROPERTY);
            for (String each : statics) {
                if (present.contains(each)) continue;
                ImportDeclaration declaration = ast.newImportDeclaration();
                declaration.setStatic(true);
                declaration.setName(ast.newName(each));
                list.insertLast(declaration, null);
            }
        }
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    // --- the check ------------------------------------------------------------------------------------------------

    /**
     * Fails each use an introduced error lies in — or, failing that, every use in the statement or declaration
     * it lies in — with every use containing it. Answers the first error no use accounts for, or null.
     */
    private static String blame(Map<Path, List<Problem>> introduced, Map<Path, Written> written,
                                List<FilePlan> files, Map<Candidate, String> failed) {
        for (Map.Entry<Path, List<Problem>> entry : introduced.entrySet()) {
            Written one = written.get(entry.getKey());
            FilePlan file = files.stream().filter(f -> f.file().equals(entry.getKey())).findFirst().orElse(null);
            for (Problem problem : entry.getValue()) {
                String said = one == null ? entry.getKey().getFileName() + ", " + problem.message()
                        : entry.getKey().getFileName() + ", "
                        + CompileGuard.describe(List.of(problem), one.text()).getFirst();
                if (one == null || file == null) return said;
                List<Candidate> blamed = new ArrayList<>();
                one.ranges().forEach((candidate, range) -> {
                    if (problem.offset() >= range[0] && problem.offset() < range[1]) blamed.add(candidate);
                });
                if (blamed.isEmpty()) {
                    int[] around = around(one.text(), problem.offset());
                    one.ranges().forEach((candidate, range) -> {
                        if (around != null && range[0] >= around[0] && range[1] <= around[1]) blamed.add(candidate);
                    });
                }
                if (blamed.isEmpty()) return said;
                for (Candidate candidate : blamed) {
                    String why = "its rewrite would not compile (" + said + ")";
                    failed.putIfAbsent(candidate, why);
                    for (Candidate outer : file.candidates()) {
                        if (outer.contains(candidate)) failed.putIfAbsent(outer, why);
                    }
                }
            }
        }
        return null;
    }

    /** The range of the statement, or else the declaration, {@code offset} lies in. */
    private static int[] around(String text, int offset) {
        CompilationUnit unit = BotParser.syntax(text);
        ASTNode node = NodeFinder.perform(unit, offset, 0);
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof Statement || at instanceof BodyDeclaration) {
                return new int[] {at.getStartPosition(), at.getStartPosition() + at.getLength()};
            }
        }
        return null;
    }

    // --- marks ------------------------------------------------------------------------------------------------------

    /**
     * {@code text} with each function holding a use left as written marked {@code @Refactor}, naming the rule and
     * why. A use outside every function (a constant's initialiser) is only listed.
     */
    private static String marked(FilePlan file, String text, Written one, Map<Candidate, String> failed) {
        Map<Integer, List<String>> entries = new LinkedHashMap<>();
        for (Candidate each : file.candidates()) {
            String why = each.ok() ? failed.get(each) : each.why();
            if (why == null || each.methodStart() < 0 || consumed(each, file, failed)) continue;
            entries.computeIfAbsent(each.methodStart(), k -> new ArrayList<>())
                    .add(each.rule() == null
                            ? each.site().element().display() + " is gone and was left as written here: " + why
                            : each.rule().qualified() + " could not rewrite " + each.site().element().display()
                                    + " here: " + why);
        }
        if (entries.isEmpty()) return text;
        CompilationUnit unit = BotParser.syntax(text);
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        EditContext ctx = EditContext.of(unit, rewrite, null, null);
        boolean any = false;
        for (Map.Entry<Integer, List<String>> entry : entries.entrySet()) {
            int at = moved(entry.getKey(), file, one);
            if (at < 0) continue;
            ASTNode node = NodeFinder.perform(unit, at, 0);
            MethodDeclaration method = node instanceof MethodDeclaration declared ? declared
                    : ReviewMarks.enclosingMethod(node);
            if (method == null) continue;
            ReviewMarks.mark(ctx, method, entry.getValue());
            any = true;
        }
        return any ? ctx.applyTo(text) : text;
    }

    /**
     * Where an offset of the original text lies in {@code one}: moved by every change above it — the imports and
     * each rewrite before it — which is the whole change less each rewrite after it; -1 when a rewrite covers it.
     */
    private static int moved(int offset, FilePlan file, Written one) {
        if (one == null) return offset;
        int shift = one.text().length() - file.source().length();
        for (Map.Entry<Candidate, int[]> entry : one.ranges().entrySet()) {
            Candidate each = entry.getKey();
            if (each.start() <= offset && offset < each.end()) return -1;
            if (each.start() > offset) shift -= entry.getValue()[1] - entry.getValue()[0] - (each.end() - each.start());
        }
        return offset + shift;
    }
}
