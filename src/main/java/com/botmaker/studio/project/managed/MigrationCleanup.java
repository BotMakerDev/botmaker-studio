package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.Usages;
import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.parser.guard.CompileGuard;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.refactor.ReviewMarker;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.project.source.BotAnnotation;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What follows a migration (2026-10-12, {@code docs/refactor/25-compatibility.md} §12): the bot's uses of the old
 * values switched to their copies, the originals nothing uses any more removed, and the {@code @Migrate} methods
 * nothing needs any more removed with them — {@value MigrationSteps#CLASS} too once it holds no other.
 *
 * <p><b>Switching never breaks the bot.</b> Each use of {@code OldShots.ORE} becomes {@code Shots.ORE}; a use that
 * would not compile so — held in a variable of the old type, passed where only the old type fits — is left as it
 * is and its function marked {@code @Refactor}, so the Review tab lists it like any upgrade leftover. The author
 * changes what holds it, and switches again.
 *
 * <p><b>Removing is the author's own step</b>, each one confirmed by the window that asks: only an original whose
 * copy exists and that nothing uses goes, and only a method whose old type has no value left.
 */
public final class MigrationCleanup {

    private MigrationCleanup() {}

    /** A switch's change or refusal, the uses it switched, and each one it left with why. */
    public record Switched(Refactor.Outcome outcome, List<String> switched, List<String> left) {}

    /** A removal's change or refusal, and the files it deletes whole. */
    public record Removal(Refactor.Outcome outcome, Set<Path> deleted) {}

    // --- switching uses ------------------------------------------------------------------------------------

    /**
     * Every use of {@code from}'s values named in {@code names} — all of them when it is empty — pointed at the copy
     * of the same name in {@code to}'s open set. A use that would add a compile error is left, its function marked
     * with {@code note} when one is given ({@link ReviewMarker#available}); refused when nothing could be switched.
     */
    public static Switched switchUses(BotIndex index, ValueGrammar grammar, List<ManagedValue<?>> declared,
                                      Class<?> from, Class<?> to, Set<String> names, String note) {
        Optional<ManagedValue<?>> into = single(declared, to);
        List<ManagedSets.Holder> holders = into.map(set -> ManagedSets.holders(index, set.id())).orElse(List.of());
        if (holders.size() != 1) {
            return refused("No single class holds the " + to.getSimpleName() + " values to switch to.");
        }
        ManagedSets.Holder holder = holders.getFirst();
        Set<String> named = new HashSet<>();
        ManagedSets.members(index, into.get().id(), grammar).forEach(member -> named.add(member.name()));
        Set<String> copies = migrated(index, holder);
        String qualified = qualifiedName(index, holder);
        if (qualified == null) return refused(holder.className() + " could not be read.");

        List<String> left = new ArrayList<>();
        Map<Path, List<Use>> byFile = new LinkedHashMap<>();
        for (ManagedSets.Member member : originals(index, grammar, declared, from)) {
            if (!names.isEmpty() && !names.contains(member.name())) continue;
            if (!copies.contains(member.name())) {
                // Only a copy the migration wrote is this value's: one of the same name the author made, or edited
                // since, may hold anything, and a use typed loosely enough would compile pointed at it.
                left.add(member.qualified() + ": " + (named.contains(member.name())
                        ? holder.className() + "." + member.name() + " is not marked @Migrated, so it may not be its"
                                + " copy; switch its uses by hand"
                        : holder.className() + " has no " + member.name() + "; migrate it first"));
                continue;
            }
            for (Usages.Usage use : ManagedSets.uses(index, member)) {
                // A static import of the old constant still compiles, and goes when the original is removed.
                if (isImport(use)) continue;
                byFile.computeIfAbsent(use.file(), file -> new ArrayList<>()).add(new Use(member, use));
            }
        }
        if (byFile.isEmpty()) {
            return new Switched(new Refactor.Refused(left.isEmpty() ? "Nothing uses the " + from.getSimpleName()
                    + " values." : String.join("; ", left) + "."), List.of(), left);
        }

        // Switch everything, then keep back each use on a line the switch broke; twice, as keeping one back can
        // change what its neighbours resolve to. A file still breaking after that is left whole.
        Map<Path, List<Use>> kept = new LinkedHashMap<>();
        Map<Path, String> rewrites = Map.of();
        for (int pass = 0; pass < 3; pass++) {
            rewrites = switched(index, byFile, holder.className(), qualified);
            Map<Path, Set<Integer>> broken = brokenLines(index, index.with(rewrites), rewrites);
            if (broken.isEmpty()) break;
            for (Map.Entry<Path, Set<Integer>> file : broken.entrySet()) {
                List<Use> uses = byFile.getOrDefault(file.getKey(), List.of());
                List<Use> back = pass == 2 ? List.copyOf(uses)
                        : uses.stream().filter(use -> file.getValue().contains(use.use().line())).toList();
                if (back.isEmpty()) back = List.copyOf(uses);
                kept.computeIfAbsent(file.getKey(), f -> new ArrayList<>()).addAll(back);
                List<Use> rest = new ArrayList<>(uses);
                rest.removeAll(back);
                if (rest.isEmpty()) byFile.remove(file.getKey());
                else byFile.put(file.getKey(), rest);
            }
            if (pass == 2) rewrites = switched(index, byFile, holder.className(), qualified);
        }

        List<String> switchedUses = new ArrayList<>();
        byFile.forEach((file, uses) -> uses.forEach(use -> switchedUses.add(file.getFileName() + ":" + use.use().line()
                + " " + use.member().qualified())));
        kept.forEach((file, uses) -> uses.forEach(use -> left.add(file.getFileName() + ":" + use.use().line() + " "
                + use.member().qualified() + ": " + holder.className() + "." + use.member().name()
                + " does not fit there")));

        Map<Path, String> marked = marked(index, rewrites, kept, note);
        String what = "Switched " + switchedUses.size() + (switchedUses.size() == 1 ? " use" : " uses") + " of "
                + from.getSimpleName() + " to " + to.getSimpleName();
        if (switchedUses.isEmpty() && marked.isEmpty()) {
            return new Switched(new Refactor.Refused("No use of " + from.getSimpleName() + " could be switched: "
                    + String.join("; ", left) + "."), List.of(), left);
        }
        Refactor.Outcome outcome = Refactor.checked(index, new Refactor.Planned(what, marked), what);
        // A mark the bot cannot compile (@Refactor missing from its classpath) is dropped, never the switch.
        if (outcome instanceof Refactor.Refused && !marked.equals(rewrites) && !rewrites.isEmpty()) {
            outcome = Refactor.checked(index, new Refactor.Planned(what, rewrites), what);
        }
        return new Switched(outcome, List.copyOf(switchedUses), List.copyOf(left));
    }

    /** One use of one original. */
    private record Use(ManagedSets.Member member, Usages.Usage use) {}

    /** Each file with its uses written as {@code Holder.NAME}, the holder imported where it is not already. */
    private static Map<Path, String> switched(BotIndex index, Map<Path, List<Use>> byFile, String className,
                                              String qualified) {
        Map<Path, String> out = new LinkedHashMap<>();
        index.read(units -> {
            byFile.forEach((file, uses) -> {
                String source = index.sources().get(file);
                CompilationUnit unit = units.get(file);
                if (source == null || unit == null) return;
                AST ast = unit.getAST();
                ASTRewrite rewrite = ASTRewrite.create(ast);
                boolean any = false;
                for (Use use : uses) {
                    ASTNode at = NodeFinder.perform(unit, use.use().start(), use.member().name().length());
                    if (!(at instanceof SimpleName name)) continue;
                    ASTNode whole = name;
                    if (name.getParent() instanceof QualifiedName q && q.getName() == name) whole = q;
                    if (name.getParent() instanceof FieldAccess access && access.getName() == name) whole = access;
                    if (whole.getParent() instanceof ImportDeclaration) continue;
                    rewrite.replace(whole, ast.newQualifiedName(ast.newSimpleName(className),
                            ast.newSimpleName(use.member().name())), null);
                    any = true;
                }
                if (!any) return;
                ImportManager.addImport(unit, rewrite, qualified);
                String rewritten = AstRewriteHelper.applyRewrite(rewrite, source);
                if (rewritten != null && !rewritten.equals(source)) out.put(file, rewritten);
            });
            return null;
        });
        return out;
    }

    /**
     * The lines, as {@code before} numbers them, where {@code after} has an error {@code before} did not. The
     * import a switch adds shifts what follows it by the lines it adds, which are taken off again.
     */
    private static Map<Path, Set<Integer>> brokenLines(BotIndex before, BotIndex after, Map<Path, String> rewrites) {
        Map<Path, Set<Integer>> out = new LinkedHashMap<>();
        before.introduced(after).forEach((file, introduced) -> {
            String text = after.sources().getOrDefault(file, "");
            String original = before.sources().getOrDefault(file, text);
            int shift = rewrites.containsKey(file) ? lines(text) - lines(original) : 0;
            Set<Integer> broken = new LinkedHashSet<>();
            for (CompileGuard.Problem problem : introduced) broken.add(lineOf(text, problem.offset()) - shift);
            out.put(file, broken);
        });
        return out;
    }

    /** {@code rewrites}, with each kept use's function marked {@code note}; unchanged with no note. */
    private static Map<Path, String> marked(BotIndex index, Map<Path, String> rewrites, Map<Path, List<Use>> kept,
                                            String note) {
        if (note == null || note.isBlank() || kept.isEmpty()) return rewrites;
        Map<Path, String> out = new LinkedHashMap<>(rewrites);
        kept.forEach((file, uses) -> {
            String original = index.sources().get(file);
            String text = out.getOrDefault(file, original);
            if (text == null) return;
            int shift = lines(text) - lines(original);
            Set<Integer> at = new LinkedHashSet<>();
            uses.forEach(use -> at.add(use.use().line() + shift));
            String result = ReviewMarker.markLines(text, at, note);
            if (!result.equals(original)) out.put(file, result);
        });
        return out;
    }

    // --- removing originals --------------------------------------------------------------------------------

    /** {@code from}'s values with a copy of their name in {@code to}'s open set and nothing using them. */
    public static List<ManagedSets.Member> removable(BotIndex index, ValueGrammar grammar,
                                                     List<ManagedValue<?>> declared, Class<?> from, Class<?> to) {
        Optional<ManagedValue<?>> into = single(declared, to);
        List<ManagedSets.Holder> holders = into.map(set -> ManagedSets.holders(index, set.id())).orElse(List.of());
        if (holders.size() != 1) return List.of();
        Set<String> copies = migrated(index, holders.getFirst());
        return originals(index, grammar, declared, from).stream()
                .filter(member -> copies.contains(member.name())
                        && ManagedSets.uses(index, member).stream().allMatch(MigrationCleanup::isImport))
                .toList();
    }

    /**
     * Every one of {@code members} deleted, file by file, with the static imports of them — the uses a switch
     * leaves — as one change that compiles.
     */
    public static Refactor.Outcome removeOriginals(BotIndex index, List<ManagedSets.Member> members) {
        if (members.isEmpty()) return new Refactor.Refused("No original is left that nothing uses.");
        Map<Path, String> rewrites = new LinkedHashMap<>();
        for (ManagedSets.Member member : members) {
            String source = rewrites.getOrDefault(member.file(), index.sources().get(member.file()));
            String removed = member.group() == null ? ManagedSets.removed(source, member.className(), member.name())
                    : ManagedSets.removedFromGroup(source, member.className(), member.group(), member.name());
            if (removed.equals(source)) return new Refactor.Refused(member.qualified() + " could not be removed.");
            rewrites.put(member.file(), removed);
            for (Usages.Usage use : ManagedSets.uses(index, member)) {
                if (!isImport(use)) continue;
                String text = rewrites.getOrDefault(use.file(), index.sources().get(use.file()));
                rewrites.put(use.file(), withoutStaticImport(text, member));
            }
        }
        String what = "Removed " + members.size() + (members.size() == 1 ? " original" : " originals");
        return Refactor.checked(index, new Refactor.Planned(what, rewrites), what);
    }

    /**
     * What the assistant's {@code migration remove_migrated} removes (2026-10-13): the originals of {@code from} copied into
     * {@code to} that nothing uses ({@link #removable}, none when {@code to} is null), then every {@code @Migrate}
     * method nothing needs once they are gone ({@link #unneeded}, {@link #removeMethods}) — one change, worked out
     * over the bot as the first leaves it.
     */
    public record Cleared(Refactor.Outcome outcome, Set<Path> deleted, List<String> originals, List<String> methods) {}

    public static Cleared clear(BotIndex index, ValueGrammar grammar, List<ManagedValue<?>> declared, Class<?> from,
                                Class<?> to) {
        List<ManagedSets.Member> removable = to == null ? List.of() : removable(index, grammar, declared, from, to);
        List<String> originals = removable.stream().map(ManagedSets.Member::qualified).toList();
        Map<Path, String> rewrites = new LinkedHashMap<>();
        BotIndex after = index;
        if (!removable.isEmpty()) {
            Refactor.Outcome outcome = removeOriginals(index, removable);
            if (!(outcome instanceof Refactor.Planned removed)) {
                return new Cleared(outcome, Set.of(), originals, List.of());
            }
            rewrites.putAll(removed.rewrites());
            Map<Path, String> sources = new LinkedHashMap<>(index.sources());
            sources.putAll(removed.rewrites());
            after = index.replacing(sources);
        }
        List<ManagedMigrations.Method> unneeded = unneeded(after, grammar);
        List<String> methods = unneeded.stream().map(ManagedMigrations.Method::qualified).toList();
        Set<Path> deleted = Set.of();
        if (!unneeded.isEmpty()) {
            Removal removal = removeMethods(after, unneeded);
            if (!(removal.outcome() instanceof Refactor.Planned plan)) {
                return new Cleared(removal.outcome(), Set.of(), originals, methods);
            }
            rewrites.putAll(plan.rewrites());
            deleted = removal.deleted();
            rewrites.keySet().removeAll(deleted);
        }
        if (rewrites.isEmpty() && deleted.isEmpty()) {
            return new Cleared(new Refactor.Refused("Nothing to remove: no original of " + from.getSimpleName()
                    + " has its copy" + (to == null ? "" : " in " + to.getSimpleName()) + " with nothing using it, "
                    + "and no @Migrate method is unneeded."), Set.of(), List.of(), List.of());
        }
        List<String> said = new ArrayList<>();
        if (!originals.isEmpty()) {
            said.add(originals.size() + (originals.size() == 1 ? " original" : " originals") + " of "
                    + from.getSimpleName());
        }
        if (!methods.isEmpty()) {
            said.add(methods.size() + (methods.size() == 1 ? " @Migrate method" : " @Migrate methods"));
        }
        return new Cleared(new Refactor.Planned("Removed " + String.join(" and ", said), rewrites), deleted,
                originals, methods);
    }

    /** The one type {@code from}'s copies are in, or empty when none has any or several do. */
    public static Optional<Class<?>> copiedInto(BotIndex index, ValueGrammar grammar, List<ManagedValue<?>> declared,
                                                Class<?> from) {
        Set<Class<?>> into = new LinkedHashSet<>(copies(index, grammar, declared, from).values());
        return into.size() == 1 ? Optional.of(into.iterator().next()) : Optional.empty();
    }

    // --- removing methods nothing needs --------------------------------------------------------------------

    /**
     * The bot's {@code @Migrate} methods whose old type nothing in the bot names any more — outside the migration
     * methods, and an import aside — and that no method still needed migrates a part through. Any value of the
     * type, in an open set, a grouped one, a parameter or a field, is written with its type's name, so a value left
     * anywhere keeps its method. A method whose old type cannot be read (its plugin not loaded), or whose name the
     * bot writes where it cannot be resolved, is kept: unneeded is offered only when known.
     */
    public static List<ManagedMigrations.Method> unneeded(BotIndex index, ValueGrammar grammar) {
        List<ManagedMigrations.Method> methods = ManagedMigrations.methods(index, grammar);
        Set<String> named = typesNamed(index);
        Set<ManagedMigrations.Method> needed = new LinkedHashSet<>();
        List<ManagedMigrations.Method> queue = new ArrayList<>();
        for (ManagedMigrations.Method method : methods) {
            if (!(method.source() instanceof Class<?> from) || named.contains(from.getCanonicalName())
                    || named.contains(UNRESOLVED + from.getSimpleName())) {
                queue.add(method);
            }
        }
        while (!queue.isEmpty()) {
            ManagedMigrations.Method method = queue.removeFirst();
            if (!needed.add(method)) continue;
            for (Map.Entry<Class<?>, Class<?>> pair : throughParts(grammar, method)) {
                methods.stream().filter(other -> other.maps(pair.getKey(), pair.getValue())).forEach(queue::add);
            }
        }
        return methods.stream().filter(method -> !needed.contains(method)).toList();
    }

    /** The pairs {@code method} migrates a part through: a parameter declared as another type than its part. */
    private static List<Map.Entry<Class<?>, Class<?>>> throughParts(ValueGrammar grammar,
                                                                    ManagedMigrations.Method method) {
        if (!(method.source() instanceof Class<?> from) || !(method.target() instanceof Class<?> to)
                || !(MigrationSteps.shape(grammar, from, to) instanceof MigrationSteps.Shape.Parts parts)
                || parts.from().size() != method.params().size()) {
            return List.of();
        }
        List<Map.Entry<Class<?>, Class<?>>> out = new ArrayList<>();
        for (int i = 0; i < parts.from().size(); i++) {
            Class<?> part = parts.from().get(i).type();
            if (method.params().get(i).form() instanceof Class<?> form && !ManagedMigrations.named(form, part)) {
                out.add(Map.entry(part, form));
            }
        }
        return out;
    }

    /**
     * {@code methods} deleted from their classes, and a class left with no other method and no field deleted with
     * its file: what the bot keeps of a migration it is done with.
     */
    public static Removal removeMethods(BotIndex index, List<ManagedMigrations.Method> methods) {
        if (methods.isEmpty()) return new Removal(new Refactor.Refused("No migration method is unneeded."), Set.of());
        Map<Path, List<ManagedMigrations.Method>> byFile = new LinkedHashMap<>();
        methods.forEach(method -> byFile.computeIfAbsent(method.file(), f -> new ArrayList<>()).add(method));
        Map<Path, String> rewrites = new LinkedHashMap<>();
        Set<Path> deleted = new LinkedHashSet<>();
        for (Map.Entry<Path, List<ManagedMigrations.Method>> file : byFile.entrySet()) {
            String source = index.sources().get(file.getKey());
            CompilationUnit unit = source == null ? null : BotParser.syntax(source);
            if (unit == null) continue;
            ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
            Set<MethodDeclaration> removed = new HashSet<>();
            TypeDeclaration only = null;
            for (ManagedMigrations.Method method : file.getValue()) {
                if (!(ManagedSets.topLevel(unit, method.className()) instanceof TypeDeclaration holder)) continue;
                MethodDeclaration declaration = MigrationSteps.declared(holder, method);
                if (declaration == null) continue;
                rewrite.remove(declaration, null);
                removed.add(declaration);
                only = holder;
            }
            // Emptied: every member left is a constructor — a field, a nested type of any kind, an initialiser or
            // another method is somebody's, and keeps the file.
            TypeDeclaration holder = only;
            boolean emptied = holder != null && unit.types().size() == 1 && holder.bodyDeclarations().stream()
                    .allMatch(member -> removed.contains(member)
                            || member instanceof MethodDeclaration m && m.isConstructor());
            if (emptied) {
                deleted.add(file.getKey());
                continue;
            }
            String rewritten = AstRewriteHelper.applyRewrite(rewrite, source);
            if (rewritten != null && !rewritten.equals(source)) rewrites.put(file.getKey(), rewritten);
        }
        Map<Path, String> after = new LinkedHashMap<>(index.sources());
        after.putAll(rewrites);
        deleted.forEach(after::remove);
        String what = "Removed " + methods.size() + (methods.size() == 1 ? " migration method" : " migration methods")
                + (deleted.isEmpty() ? "" : " and " + String.join(", ", deleted.stream()
                        .map(path -> path.getFileName().toString()).toList()));
        Optional<String> broken = index.firstNewError(index.replacing(after));
        if (broken.isPresent()) {
            return new Removal(new Refactor.Refused(what + " would stop the bot compiling (" + broken.get()
                    + "), so nothing was changed."), Set.of());
        }
        return new Removal(new Refactor.Planned(what, rewrites), deleted);
    }

    // --- shared --------------------------------------------------------------------------------------------

    /** Every constant of an open set of {@code type}, in declaration order. */
    static List<ManagedSets.Member> originals(BotIndex index, ValueGrammar grammar, List<ManagedValue<?>> declared,
                                              Class<?> type) {
        List<ManagedSets.Member> out = new ArrayList<>();
        for (ManagedValue<?> set : declared) {
            if (set != null && set.isOpenSet() && !set.isEnum() && !set.isGrouped()
                    && ManagedMigrations.named(set.type(), type)) {
                out.addAll(ManagedSets.members(index, set.id(), grammar));
            }
        }
        return out;
    }

    /**
     * The type whose open set holds the copy of {@code from}'s constant {@code name} — of that name, marked
     * {@code @Migrated} — in declaration order. Empty when no other set holds one.
     */
    public static Optional<Class<?>> copyTarget(BotIndex index, ValueGrammar grammar, List<ManagedValue<?>> declared,
                                                Class<?> from, String name) {
        return Optional.ofNullable(copies(index, grammar, declared, from).get(name));
    }

    /** {@link #copyTarget} for every constant of {@code from} at once: each name with a copy, and the copy's type. */
    public static Map<String, Class<?>> copies(BotIndex index, ValueGrammar grammar, List<ManagedValue<?>> declared,
                                               Class<?> from) {
        List<Class<?>> types = new ArrayList<>();
        for (ManagedValue<?> set : declared) {
            if (set != null && set.type() != null && !types.contains(set.type())) types.add(set.type());
        }
        Map<String, Class<?>> out = new HashMap<>();
        for (Class<?> type : types) {
            if (ManagedMigrations.named(type, from)) continue;
            single(declared, type).map(set -> ManagedSets.holders(index, set.id()))
                    .filter(holders -> holders.size() == 1)
                    .ifPresent(holders -> migrated(index, holders.getFirst()).forEach(name -> out.putIfAbsent(name,
                            type)));
        }
        return out;
    }

    private static Optional<ManagedValue<?>> single(List<ManagedValue<?>> declared, Class<?> type) {
        List<ManagedValue<?>> sets = declared.stream().filter(set -> set != null && set.isOpenSet() && !set.isEnum()
                && !set.isGrouped() && ManagedMigrations.named(set.type(), type)).toList();
        return sets.size() == 1 ? Optional.of(sets.getFirst()) : Optional.empty();
    }

    /** {@code holder}'s class as an import names it, or {@code null} when its file is not in the index. */
    private static String qualifiedName(BotIndex index, ManagedSets.Holder holder) {
        return index.read(units -> {
            CompilationUnit unit = units.get(holder.file());
            if (unit == null) return null;
            return unit.getPackage() == null ? holder.className()
                    : unit.getPackage().getName().getFullyQualifiedName() + "." + holder.className();
        });
    }

    /** The constants of {@code holder} the migration wrote and nobody has edited since: marked {@code @Migrated}. */
    private static Set<String> migrated(BotIndex index, ManagedSets.Holder holder) {
        return index.read(units -> {
            Set<String> out = new HashSet<>();
            CompilationUnit unit = units.get(holder.file());
            if (unit == null || !(ManagedSets.topLevel(unit, holder.className()) instanceof TypeDeclaration type)) {
                return out;
            }
            for (FieldDeclaration field : type.getFields()) {
                if (BotAnnotation.MIGRATED.on(field) == null) continue;
                for (Object each : field.fragments()) {
                    out.add(((VariableDeclarationFragment) each).getName().getIdentifier());
                }
            }
            return out;
        });
    }

    /** Whether {@code use} is the name in an import: what a switch leaves, and a removal takes with it. */
    private static boolean isImport(Usages.Usage use) {
        return use.text().startsWith("import ");
    }

    /** {@code source} without {@code import static …Holder.NAME;} for {@code member}. */
    private static String withoutStaticImport(String source, ManagedSets.Member member) {
        CompilationUnit unit = BotParser.syntax(source);
        if (unit == null) return source;
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        boolean any = false;
        for (Object each : unit.imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            if (imported.isStatic() && !imported.isOnDemand() && imported.getName() instanceof QualifiedName name
                    && name.getName().getIdentifier().equals(member.name())
                    && name.getQualifier().getFullyQualifiedName().endsWith(member.className())) {
                rewrite.remove(imported, null);
                any = true;
            }
        }
        String rewritten = any ? AstRewriteHelper.applyRewrite(rewrite, source) : null;
        return rewritten == null ? source : rewritten;
    }

    /** What {@link #typesNamed} puts before the simple name of a type it could not resolve. */
    private static final String UNRESOLVED = "?";

    /**
     * Every type the bot names in a declaration, a creation or a literal, by canonical name, outside its
     * {@code @Migrate} methods; one that does not resolve by {@link #UNRESOLVED} and its simple name.
     */
    private static Set<String> typesNamed(BotIndex index) {
        return index.read(units -> {
            Set<String> out = new HashSet<>();
            units.forEach((file, unit) -> unit.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration method) {
                    return BotAnnotation.MIGRATE.on(method) == null;
                }

                @Override
                public boolean visit(SimpleType type) {
                    add(type.resolveBinding(), type.getName());
                    return true;
                }

                @Override
                public boolean visit(QualifiedType type) {
                    add(type.resolveBinding(), type.getName());
                    return true;
                }

                private void add(ITypeBinding binding, Name name) {
                    if (binding != null && !binding.isRecovered()) {
                        out.add(binding.getErasure().getQualifiedName());
                    } else {
                        String written = name.getFullyQualifiedName();
                        out.add(UNRESOLVED + written.substring(written.lastIndexOf('.') + 1));
                    }
                }
            }));
            return out;
        });
    }

    private static Switched refused(String reason) {
        return new Switched(new Refactor.Refused(reason), List.of(), List.of());
    }

    private static int lines(String text) {
        return text == null ? 0 : (int) text.chars().filter(c -> c == '\n').count();
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, text.length()); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
}
