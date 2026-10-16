package com.botmaker.studio.migrate;

import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.project.source.BotAnnotation;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The bot between two APIs: as it compiles now, and as it compiled with the jars it used to have — what every
 * question of the author's migration is asked of (2026-10-16, {@code docs/refactor/25-compatibility.md} §12).
 *
 * <p><b>One engine.</b> The bot's {@code @Migrate} rules ({@link Rules}) name an old method, constructor, field or
 * type by their own signature; {@link #broken()} lists what the new jars break and what each rule covers;
 * {@link #plan} rewrites every use a ready rule names. The author decides what replaces what: with no rule,
 * nothing is rewritten. {@link #finished} is the last resort, an upgrade or a removal the author finishes anyway:
 * a default rule seeded for each element nothing covers ({@link Seed}), and every rule applied.
 *
 * <p>Pure: two parses in, answers out. The old classpath is the pinned version's jars on an upgrade, or the jars
 * {@link ApiCache} kept from the last load on a reload of the same {@code -SNAPSHOT}.
 */
public final class Migration {

    /** The class the bot's rules are written into when it has none: {@code Migrations}, beside the main class. */
    public static final String CLASS = "Migrations";

    private final BotIndex now;
    private final BotIndex old;
    private final List<String> oldClasspath;
    private final Path probe;
    private final List<Rule> rules;

    private Migration(BotIndex now, BotIndex old, List<String> oldClasspath, Path probe, List<Rule> rules) {
        this.now = now;
        this.old = old;
        this.oldClasspath = oldClasspath;
        this.probe = probe;
        this.rules = rules;
    }

    /** {@code now}, and its sources parsed against {@code oldClasspath}, with the bot's rules resolved. */
    public static Migration of(BotIndex now, List<String> oldClasspath) {
        List<Rule> declared = Rules.declared(now);
        Path root = now.parser().sourceRoot() == null ? null : now.parser().sourceRoot().toAbsolutePath().normalize();
        Path probe = Rules.probePath(root);
        BotIndex old = now.against(oldClasspath, Rules.probe(declared, root));
        return new Migration(now, old, List.copyOf(oldClasspath), probe, Rules.resolved(declared, old, probe));
    }

    /**
     * {@code now} against what {@code project} loaded before its last change ({@link ApiCache#previous}): the
     * migration a person asks for, after an upgrade, a removal or a dev build's reload.
     */
    public static Migration sinceLastChange(BotIndex now, Path project) {
        return of(now, ApiCache.shared().previous(project, now.classpath()));
    }

    /** Whether the old side is the bot's own classpath: nothing changed since the last load, so nothing broke. */
    public boolean unchanged() {
        return oldClasspath.equals(now.classpath());
    }

    /** Every {@code @Migrate} rule in the bot, each resolved or saying why not. */
    public List<Rule> rules() {
        return rules;
    }

    /** Every old element the bot still uses that the new jars break or a rule names. */
    public List<Broken> broken() {
        return Broken.in(now, old, probe, rules);
    }

    /** Every use of {@code element} in the bot. */
    public List<Sites.Site> sites(OldElement element) {
        Map<String, OldElement> one = new LinkedHashMap<>();
        one.put(element.key(), element);
        return Sites.in(old, one, probe);
    }

    /**
     * Every use a ready rule names, rewritten, checked to compile; a use left as written is listed and, when
     * {@code markable}, its function marked {@code @Refactor}.
     */
    public Rewrite.Result plan(boolean markable) {
        return Rewrite.plan(now, old, probe, rules, markable, false, Map.of());
    }

    /**
     * What finishing anyway does.
     *
     * @param result   the rules applied, seeded ones too; its outcome also writes the seeds' file
     * @param seeded   each element a default rule was seeded for
     * @param unseeded each broken element no rule covers and none could be seeded for, and why
     */
    public record Finished(Rewrite.Result result, List<OldElement> seeded, Map<OldElement, String> unseeded) {

        public Finished {
            seeded = List.copyOf(seeded);
            unseeded = Map.copyOf(unseeded);
        }
    }

    /**
     * The last resort: a default rule seeded into {@code file} — the bot's {@value #CLASS}, created in
     * {@code mainPackage} when missing — for each broken element no rule names, then every resolved rule applied,
     * a seeded one too. The seeds stay in the bot marked {@code @Refactor}, each for its author to write over.
     */
    public Finished finished(Path file, String mainPackage, boolean markable) {
        List<Broken> broken = broken();
        List<OldElement> open = broken.stream().filter(b -> b.state() == Broken.State.NONE)
                .map(Broken::element).toList();
        Seed.Seeded seeds = Seed.of(now, open, file, mainPackage);
        Migration over = this;
        if (seeds.source() != null) {
            Map<Path, String> sources = new LinkedHashMap<>(now.sources());
            sources.put(file, seeds.source());
            over = of(now.replacing(sources), oldClasspath);
        }
        // What no rule stands for is left, listed and marked: the elements none could be seeded for, and those
        // whose rule cannot be applied.
        Map<OldElement, String> unruled = new LinkedHashMap<>(seeds.unseeded());
        for (Broken each : broken) {
            if (each.state() == Broken.State.REFUSED) {
                unruled.put(each.element(), "its rule " + each.rule().qualified() + " " + each.rule().problem());
            }
        }
        // A seed that does not resolve, or is refused, stands for nothing: its uses are left and said, not dropped.
        Set<String> standing = new HashSet<>();
        for (Rule rule : over.rules) if (rule.applicable(true)) standing.add(rule.element().key());
        for (OldElement element : seeds.seeded()) {
            if (!standing.contains(element.key())) {
                unruled.putIfAbsent(element, "the default rule seeded for it could not be applied");
            }
        }
        Rewrite.Result result = Rewrite.plan(over.now, over.old, over.probe, over.rules, markable, true, unruled);
        if (seeds.source() != null && result.outcome() instanceof Refactor.Planned planned) {
            Map<Path, String> rewrites = new LinkedHashMap<>();
            rewrites.put(file, seeds.source());
            rewrites.putAll(planned.rewrites());
            result = new Rewrite.Result(new Refactor.Planned(planned.summary() + ", " + seeds.seeded().size()
                    + (seeds.seeded().size() == 1 ? " default rule" : " default rules") + " written into "
                    + file.getFileName(), rewrites), result.applied(), result.left(), result.changes());
        }
        return new Finished(result, seeds.seeded(), seeds.unseeded());
    }

    /**
     * Every rule whose old element the bot no longer uses, by {@code broken} — {@link #broken()}, which lists each
     * element still used: what Migrate's <i>Remove finished rules</i> removes.
     */
    public List<Rule> done(List<Broken> broken) {
        Set<String> used = new HashSet<>();
        for (Broken each : broken) used.add(each.element().key());
        return rules.stream().filter(rule -> rule.element() != null && !used.contains(rule.element().key())).toList();
    }

    /** What removing rules does: the files rewritten, or why nothing is, and the files it leaves empty. */
    public record Removal(Refactor.Outcome outcome, Set<Path> deleted) {

        public Removal {
            deleted = Set.copyOf(deleted);
        }
    }

    /**
     * {@code done} removed from the bot, each by its declaration, and a file deleted whose one class it leaves
     * holding nothing but constructors; refused when the bot would no longer compile.
     */
    public Removal remove(List<Rule> done) {
        if (done.isEmpty()) return new Removal(new Refactor.Refused("No rule is finished."), Set.of());
        Map<Path, Set<Integer>> byFile = new LinkedHashMap<>();
        for (Rule rule : done) byFile.computeIfAbsent(rule.file(), f -> new HashSet<>()).add(rule.start());
        Map<Path, String> rewrites = new LinkedHashMap<>();
        Set<Path> deleted = new LinkedHashSet<>();
        byFile.forEach((file, starts) -> {
            String source = now.sources().get(file);
            CompilationUnit unit = source == null ? null : BotParser.syntax(source);
            if (unit == null) return;
            ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
            List<MethodDeclaration> removed = new ArrayList<>();
            unit.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration method) {
                    if (starts.contains(method.getStartPosition())) {
                        rewrite.remove(method, null);
                        removed.add(method);
                    }
                    return false;
                }
            });
            if (removed.isEmpty()) return;
            boolean emptied = unit.types().size() == 1 && unit.types().getFirst() instanceof TypeDeclaration only
                    && ((List<?>) only.bodyDeclarations()).stream().allMatch(member -> removed.contains(member)
                            || member instanceof MethodDeclaration m && m.isConstructor());
            if (emptied) {
                deleted.add(file);
                return;
            }
            String rewritten = AstRewriteHelper.applyRewrite(rewrite, source);
            if (rewritten != null && !rewritten.equals(source)) rewrites.put(file, rewritten);
        });
        if (rewrites.isEmpty() && deleted.isEmpty()) {
            return new Removal(new Refactor.Refused("No finished rule was found where it was read: refresh and"
                    + " try again."), Set.of());
        }
        Map<Path, String> after = new LinkedHashMap<>(now.sources());
        after.putAll(rewrites);
        deleted.forEach(after::remove);
        String what = "Removed " + done.size() + (done.size() == 1 ? " finished rule" : " finished rules")
                + (deleted.isEmpty() ? "" : " and " + String.join(", ", deleted.stream()
                        .map(path -> path.getFileName().toString()).toList()));
        Optional<String> broken = now.firstNewError(now.replacing(after));
        if (broken.isPresent()) {
            return new Removal(new Refactor.Refused(what + " would stop the bot compiling (" + broken.get()
                    + "), so nothing was changed."), Set.of());
        }
        return new Removal(new Refactor.Planned(what, rewrites), deleted);
    }

    /**
     * {@code element}'s rule, seeded into {@code file} as {@link #finished} seeds one — the source of {@code file}
     * with it, or a refusal saying why none can be.
     */
    public Refactor.Outcome seed(OldElement element, Path file, String mainPackage) {
        Seed.Seeded seeds = Seed.of(now, List.of(element), file, mainPackage);
        if (seeds.source() == null) {
            return new Refactor.Refused("No rule can be seeded for " + element.display() + ": "
                    + seeds.unseeded().getOrDefault(element, "it could not be written") + ".");
        }
        return new Refactor.Planned("Seeded a rule for " + element.display() + " in " + file.getFileName(),
                Map.of(file, seeds.source()));
    }

    /**
     * {@code rule} with {@code body} as what each use becomes — the one value it returns, or its one statement for a
     * {@code void} rule — and its seed's {@code @Refactor} mark taken off, {@code imports} (qualified names) added to
     * its file: the source of its file, checked to compile, or a refusal saying why not. What an assistant writes a
     * seeded rule with, which edit_code's values cannot say (2026-10-16).
     */
    public Refactor.Outcome write(Rule rule, String body, List<String> imports) {
        String text = body == null ? "" : body.strip();
        while (text.endsWith(";")) text = text.substring(0, text.length() - 1).strip();
        if (text.isEmpty()) return new Refactor.Refused("Give the rule's body: what each use becomes.");
        String source = now.sources().get(rule.file());
        if (source == null) return new Refactor.Refused(rule.qualified() + " is in no file of the bot.");
        CompilationUnit unit = BotParser.syntax(source);
        MethodDeclaration[] found = {null};
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (method.getStartPosition() == rule.start()) found[0] = method;
                return found[0] == null;
            }
        });
        MethodDeclaration method = found[0];
        if (method == null || method.getBody() == null) {
            return new Refactor.Refused(rule.qualified() + " has changed since it was read. Try again.");
        }
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        String statement = rule.statement() ? text + ";" : "return " + text + ";";
        // The rewrite indents each line after the first to the method's own level.
        rewrite.replace(method.getBody(), rewrite.createStringPlaceholder("{\n    " + statement + "\n}",
                method.getBody().getNodeType()), null);
        for (Object each : method.modifiers()) {
            if (each instanceof Annotation mark
                    && BotAnnotation.REFACTOR.on(List.of(mark)) != null) {
                rewrite.remove(mark, null);
            }
        }
        String written = AstRewriteHelper.applyRewrite(rewrite, source);
        if (imports != null && !imports.isEmpty()) {
            written = Rewrite.imported(written, new LinkedHashSet<>(imports), Set.of());
        }
        return Refactor.checked(now, new Refactor.Planned("Wrote " + rule.qualified() + ": each use becomes "
                + text, Map.of(rule.file(), written)), "That body");
    }
}
