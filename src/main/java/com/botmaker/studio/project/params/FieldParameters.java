package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.params.Param;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.SourceNode;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.Usages;
import com.botmaker.java.edit.helpers.AstRewriteHelper;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.java.grammar.BotAnnotation;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
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
 * Studio's upgrade of a bot's own {@code @Param} fields, the form before groups (2026-11-04, plan
 * {@code 2026-11-04-private-values.md} phase 6c): each becomes one method of {@code params/bot/<Class>.java} and its
 * starting copy, at the field's current value, and every read of it calls the method. A field that is not
 * {@code Param.PUBLIC} only the author sets, so it is no parameter: it stays where it is, a plain constant.
 *
 * <p>A field the bot's code assigns stays a field and is said back: a method cannot take the assignment, and what a
 * run sets is written by the plugin that offers it, which Studio does not name. So does one whose value needs its
 * holder ({@code MAX * 2}), since a value in another package reads none of that.
 *
 * <p><b>Pure, over the bot's one parse</b>: it answers a plan compiled as the whole bot; nothing is written here.
 */
public final class FieldParameters {

    private FieldParameters() {}

    /**
     * What the upgrade would do.
     *
     * @param rewrites the bot's sources changed or created, by absolute path
     * @param deleted  the holders nothing is left in
     * @param starting the group files of {@code starting-params/}, outside the sources
     * @param repaired what it moved, one sentence each
     * @param left     what it kept as written, one sentence each
     */
    public record Plan(Map<Path, String> rewrites, Set<Path> deleted, Map<Path, String> starting,
                       List<String> repaired, List<String> left) {

        public Plan {
            rewrites = Map.copyOf(rewrites);
            deleted = Set.copyOf(deleted);
            starting = Map.copyOf(starting);
            repaired = List.copyOf(repaired);
            left = List.copyOf(left);
        }

        private static Plan leaving(List<String> left) {
            return new Plan(Map.of(), Set.of(), Map.of(), List.of(), left);
        }

        public boolean isEmpty() {
            return rewrites.isEmpty() && deleted.isEmpty();
        }
    }

    /** One field the upgrade looks at, with every use of it. */
    private record Field(JavaParameter parameter, int start, List<Usages.Usage> uses, Path file) {}

    /** The upgrade of {@code index}'s own {@code @Param} fields; {@code index} is the bot on the classpath it moves to. */
    public static Plan plan(ProjectConfig config, BotIndex index, ValueGrammar grammar) {
        List<Field> fields = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (JavaParameter parameter : JavaParameters.over(index, grammar)) {
            if (parameter.group() || parameter.declaredByPlugin()) continue;
            Path file = parameter.file().toAbsolutePath().normalize();
            int start = index.read(units -> Usages.fieldStart(units.get(file), parameter.className(), parameter.name()));
            Optional<List<Usages.Usage>> uses = start < 0 ? Optional.empty() : Refactor.uses(index, file, start);
            if (uses.isEmpty()) {
                left.add(stays(parameter, "it could not be resolved, so its reads cannot be found"));
            } else {
                fields.add(new Field(parameter, start, uses.get(), file));
            }
        }
        if (fields.isEmpty()) return Plan.leaving(left);

        Plan plan = index.read(units -> over(config, index, grammar, units, fields, left));
        if (plan.isEmpty()) return plan;
        Map<Path, String> checked = new LinkedHashMap<>(plan.rewrites());
        plan.deleted().forEach(gone -> checked.put(gone, ""));
        Refactor.Outcome outcome = Refactor.checked(index,
                new Refactor.Planned("Moving the @Param fields", checked, Map.of()),
                "Moving the @Param fields to params/");
        if (outcome instanceof Refactor.Refused refused) {
            List<String> all = new ArrayList<>(left);
            all.add(refused.reason());
            return Plan.leaving(all);
        }
        return plan;
    }

    private static Plan over(ProjectConfig config, BotIndex index, ValueGrammar grammar,
                             Map<Path, CompilationUnit> units, List<Field> fields, List<String> left) {
        List<Field> constants = new ArrayList<>();
        List<Field> candidates = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Field field : fields) {
            VariableDeclarationFragment fragment = fragmentOf(units, field);
            if (fragment == null) {
                left.add(stays(field.parameter(), "it is not a field the upgrade can read"));
            } else if (field.file().startsWith(config.mainPackageDir().resolve("plugins").toAbsolutePath().normalize())) {
                left.add(stays(field.parameter(), "it is in a plugin's file"));
            } else if (field.parameter().row().visibility() == Visibility.EDITOR_ONLY) {
                // @Param's own default: an author who never named a visibility wrote an author-only value.
                constants.add(field);
            } else {
                candidates.add(field);
                keys.add(Usages.keyOf(fragment.resolveBinding()));
            }
        }

        Map<Path, String> groups = new LinkedHashMap<>();
        Map<Path, String> starting = new LinkedHashMap<>();
        List<Field> moving = new ArrayList<>();
        List<String> repaired = new ArrayList<>();
        for (Field field : candidates) {
            VariableDeclarationFragment fragment = fragmentOf(units, field);
            JavaParameter parameter = field.parameter();
            Optional<String> why = whyItStays(units, field, fragment, keys);
            if (why.isEmpty() && !addToGroup(config, index, grammar, parameter, fragment, groups, starting)) {
                why = Optional.of("params/bot/" + parameter.className() + ".java cannot take it: it has a parameter of"
                        + " that name, or its type cannot be written");
            }
            if (why.isPresent()) {
                left.add(stays(parameter, why.get()));
            } else {
                moving.add(field);
                repaired.add(parameter.qualified() + " is a parameter of params/bot/" + parameter.className()
                        + ".java now, its value kept.");
            }
        }
        Set<ASTNode> removed = new HashSet<>();
        Set<Path> deleted = holdersEmptied(units, moving, removed);

        Map<Path, ASTRewrite> rewriters = new LinkedHashMap<>();
        Map<Path, Set<String>> groupImports = new LinkedHashMap<>();
        for (Field field : moving) {
            rewriteReads(config, units, field, deleted, rewriters, groupImports);
            VariableDeclarationFragment fragment = fragmentOf(units, field);
            FieldDeclaration declaration = (FieldDeclaration) fragment.getParent();
            ASTRewrite rewriter = rewriterOf(units, field.file(), rewriters);
            rewriter.remove(removed.contains(declaration) ? declaration : fragment, null);
        }
        Set<FieldDeclaration> marked = new HashSet<>();
        for (Field field : constants) {
            VariableDeclarationFragment fragment = fragmentOf(units, field);
            FieldDeclaration declaration = (FieldDeclaration) fragment.getParent();
            if (!marked.add(declaration)) continue;
            ASTRewrite rewriter = rewriterOf(units, field.file(), rewriters);
            rewriter.remove(BotAnnotation.PARAM.on(declaration), null);
            if (canBeFinal(units, field, declaration, fragment)) {
                rewriter.getListRewrite(declaration, FieldDeclaration.MODIFIERS2_PROPERTY)
                        .insertLast(declaration.getAST().newModifier(Modifier.ModifierKeyword.FINAL_KEYWORD), null);
            }
            repaired.add(field.parameter().qualified() + " only its author sets, so it is a plain constant now.");
        }
        removeHolderImports(units, moving, deleted, rewriters);
        groupImports.forEach((file, imports) -> JavaParameterEdits.addImports(units.get(file),
                rewriterOf(units, file, rewriters), List.copyOf(imports)));

        Map<Path, String> rewrites = new LinkedHashMap<>(groups);
        rewriters.forEach((file, rewriter) -> {
            if (!deleted.contains(file)) {
                rewrites.put(file, withoutUnusedParamImport(
                        AstRewriteHelper.applyRewrite(rewriter, index.sources().get(file))));
            }
        });
        return new Plan(rewrites, deleted, starting, repaired, left);
    }

    private static String stays(JavaParameter parameter, String why) {
        return parameter.qualified() + " stays a field: " + why + ".";
    }

    private static VariableDeclarationFragment fragmentOf(Map<Path, CompilationUnit> units, Field field) {
        CompilationUnit unit = units.get(field.file());
        return unit != null && NodeFinder.perform(unit, field.start(), 0) instanceof SimpleName name
                && name.getParent() instanceof VariableDeclarationFragment fragment
                && fragment.getParent() instanceof FieldDeclaration ? fragment : null;
    }

    private static Optional<String> whyItStays(Map<Path, CompilationUnit> units, Field field,
                                               VariableDeclarationFragment fragment, Set<String> keys) {
        FieldDeclaration declaration = (FieldDeclaration) fragment.getParent();
        int modifiers = declaration.getModifiers();
        if (!(declaration.getParent() instanceof AbstractTypeDeclaration holder) || !holder.isPackageMemberTypeDeclaration()) {
            return Optional.of("it is in a nested class");
        }
        if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)
                || fragment.getInitializer() == null) {
            return Optional.of("it is not public static with a value");
        }
        for (Usages.Usage use : field.uses()) {
            if (!(NodeFinder.perform(units.get(use.file().toAbsolutePath().normalize()), use.start(), 0) instanceof SimpleName used)
                    || inside(used, ImportDeclaration.class) || inside(used, Javadoc.class)) {
                continue;
            }
            ASTNode whole = JavaParameters.wholeUse(used);
            if (JavaParameters.writes(whole)
                    || whole.getParent() instanceof ArrayAccess access && access.getArray() == whole
                    && JavaParameters.writes(access)) {
                return Optional.of("the bot's code assigns it");
            }
            if (whole.getParent() instanceof QualifiedName outer && outer.getQualifier() == whole) {
                return Optional.of("the bot reads a field of its value directly");
            }
        }
        return importsOf(fragment.getInitializer(), holder.getName().getIdentifier(), keys).isEmpty()
                ? Optional.of("its value needs another field or its own class")
                : Optional.empty();
    }

    /**
     * What {@code value} needs imported in another package; empty when it reads what cannot follow it: a name with no
     * qualifier, its own class, another field that is moving, or something unresolved.
     */
    private static Optional<List<String>> importsOf(Expression value, String holder, Set<String> keys) {
        Set<String> imports = new LinkedHashSet<>();
        boolean[] stuck = {false};
        value.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                ASTNode parent = name.getParent();
                boolean qualified = parent instanceof QualifiedName outer && outer.getName() == name
                        || parent instanceof FieldAccess access && access.getName() == name
                        || parent instanceof MethodInvocation call && call.getName() == name && call.getExpression() != null;
                IBinding binding = name.resolveBinding();
                switch (binding) {
                    case ITypeBinding type -> {
                        if (type.getName().equals(holder)) stuck[0] = true;
                        if (!qualified && !type.isTypeVariable() && !type.isPrimitive()
                                && !"java.lang".equals(type.getPackage().getName())) {
                            imports.add(type.getErasure().getQualifiedName());
                        }
                    }
                    case IVariableBinding variable when variable.isField() -> {
                        if (!qualified || keys.contains(Usages.keyOf(variable))) stuck[0] = true;
                    }
                    case IMethodBinding method -> {
                        if (!qualified && !method.isConstructor()) stuck[0] = true;
                    }
                    case null -> stuck[0] = true;
                    default -> { }
                }
                return false;
            }
        });
        return stuck[0] ? Optional.empty() : Optional.of(List.copyOf(imports));
    }

    /** The field as a method of its group, in {@code groups} and {@code starting}; false when one will not take it. */
    private static boolean addToGroup(ProjectConfig config, BotIndex index, ValueGrammar grammar,
                                      JavaParameter parameter, VariableDeclarationFragment fragment,
                                      Map<Path, String> groups, Map<Path, String> starting) {
        Expression initializer = fragment.getInitializer();
        // `int[] slots = {1, 2}` is no expression a method can return.
        if (initializer instanceof ArrayInitializer) return false;
        SourceNode written = new SourceNode(initializer, index.sources().get(parameter.file().toAbsolutePath().normalize()));
        // Only its imports are wanted here: whyItStays has already refused a value that cannot follow it.
        List<String> imports = importsOf(initializer, "", Set.of()).orElse(List.of());
        JavaValue value = new JavaValue(initializer, imports, written.source());
        String className = parameter.className();
        Path file = JavaParameters.ownGroupFile(config, className).toAbsolutePath().normalize();
        Optional<Path> startingFile = JavaParameters.startingFile(config, file);
        if (startingFile.isEmpty()) return false;
        String source = groups.containsKey(file) ? groups.get(file)
                : index.sources().containsKey(file) ? index.sources().get(file)
                : read(file).orElseGet(() -> JavaParameters.emptyGroup(config, className));
        String copy = starting.containsKey(startingFile.get()) ? starting.get(startingFile.get())
                // Never seeded from params/: that file is private, its values this computer's; this one is committed.
                : read(startingFile.get()).orElseGet(() -> JavaParameters.emptyGroup(config, className));
        String withMethod = withMethod(source, grammar, parameter, value);
        String copyWithMethod = withMethod(copy, grammar, parameter, value);
        if (withMethod.equals(source) || copyWithMethod.equals(copy)) return false;
        groups.put(file, withMethod);
        starting.put(startingFile.get(), copyWithMethod);
        return true;
    }

    private static String withMethod(String source, ValueGrammar grammar, JavaParameter parameter, JavaValue value) {
        return JavaParameterEdits.addMethod(source, grammar, parameter.className(), parameter.name(), parameter.form(),
                value, parameter.row().category(), parameter.row().description(), Param.Changes.OUTSIDE_RUN);
    }

    private static Optional<String> read(Path file) {
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file)) : Optional.empty();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * The holders every member of which moves, the only type of their file; {@code removed} gets each declaration
     * all of whose fragments move.
     */
    private static Set<Path> holdersEmptied(Map<Path, CompilationUnit> units, List<Field> moving, Set<ASTNode> removed) {
        Map<FieldDeclaration, Integer> gone = new LinkedHashMap<>();
        for (Field field : moving) {
            gone.merge((FieldDeclaration) fragmentOf(units, field).getParent(), 1, Integer::sum);
        }
        gone.forEach((declaration, count) -> {
            if (count == declaration.fragments().size()) removed.add(declaration);
        });
        Set<Path> deleted = new HashSet<>();
        for (Field field : moving) {
            CompilationUnit unit = units.get(field.file());
            if (unit.types().size() != 1) continue;
            AbstractTypeDeclaration holder = (AbstractTypeDeclaration) unit.types().getFirst();
            // Only a class: an enum's constants and a record's components are not body declarations.
            if (holder instanceof TypeDeclaration && removed.containsAll(holder.bodyDeclarations())) {
                deleted.add(field.file());
            }
        }
        return deleted;
    }

    private static ASTRewrite rewriterOf(Map<Path, CompilationUnit> units, Path file, Map<Path, ASTRewrite> rewriters) {
        return rewriters.computeIfAbsent(file, at -> ASTRewrite.create(units.get(at).getAST()));
    }

    /**
     * Each read of {@code field} a call of its method; a holder that stays keeps its simple name, so the group is
     * written in full beside it, the one real name clash.
     */
    private static void rewriteReads(ProjectConfig config, Map<Path, CompilationUnit> units, Field field,
                                     Set<Path> deleted, Map<Path, ASTRewrite> rewriters,
                                     Map<Path, Set<String>> groupImports) {
        String className = field.parameter().className();
        String group = config.parametersPackage() + "." + JavaParameterSource.BOT + "." + className;
        boolean holderStays = !deleted.contains(field.file());
        for (Usages.Usage use : field.uses()) {
            Path file = use.file().toAbsolutePath().normalize();
            if (!(NodeFinder.perform(units.get(file), use.start(), 0) instanceof SimpleName used)) continue;
            if (inside(used, Javadoc.class)) continue;
            ASTRewrite rewriter = rewriterOf(units, file, rewriters);
            ImportDeclaration imported = enclosing(used, ImportDeclaration.class);
            if (imported != null) {
                rewriter.remove(imported, null);
                continue;
            }
            AST ast = used.getAST();
            ClassInstanceCreation made = ast.newClassInstanceCreation();
            made.setType(ast.newSimpleType(ast.newName(holderStays ? group : className)));
            MethodInvocation call = ast.newMethodInvocation();
            call.setExpression(made);
            call.setName(ast.newSimpleName(field.parameter().name()));
            rewriter.replace(JavaParameters.wholeUse(used), call, null);
            if (!holderStays) groupImports.computeIfAbsent(file, at -> new LinkedHashSet<>()).add(group);
        }
    }

    private static boolean canBeFinal(Map<Path, CompilationUnit> units, Field field, FieldDeclaration declaration,
                                      VariableDeclarationFragment fragment) {
        int modifiers = declaration.getModifiers();
        if (declaration.fragments().size() != 1 || fragment.getInitializer() == null
                || !Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
            return false;
        }
        for (Usages.Usage use : field.uses()) {
            if (NodeFinder.perform(units.get(use.file().toAbsolutePath().normalize()), use.start(), 0) instanceof SimpleName used
                    && JavaParameters.writes(JavaParameters.wholeUse(used))) {
                return false;
            }
        }
        return true;
    }

    /** The imports of a deleted holder, in every file: what would no longer resolve, or clash with its group. */
    private static void removeHolderImports(Map<Path, CompilationUnit> units, List<Field> moving, Set<Path> deleted,
                                            Map<Path, ASTRewrite> rewriters) {
        Set<String> holders = new HashSet<>();
        for (Field field : moving) {
            if (!deleted.contains(field.file())) continue;
            AbstractTypeDeclaration holder = (AbstractTypeDeclaration) units.get(field.file()).types().getFirst();
            holders.add(holder.resolveBinding() == null ? holder.getName().getIdentifier()
                    : holder.resolveBinding().getQualifiedName());
        }
        units.forEach((file, unit) -> {
            for (Object each : unit.imports()) {
                ImportDeclaration imported = (ImportDeclaration) each;
                if (holders.contains(imported.getName().getFullyQualifiedName())) {
                    rewriterOf(units, file, rewriters).remove(imported, null);
                }
            }
        });
    }

    /** {@code source} without its import of {@code @Param} once nothing in it names the annotation. */
    private static String withoutUnusedParamImport(String source) {
        CompilationUnit unit = BotParser.syntax(source);
        String simple = Param.class.getSimpleName();
        boolean[] named = {false};
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(ImportDeclaration declaration) {
                return false;
            }

            @Override
            public boolean visit(SimpleName name) {
                if (name.getIdentifier().equals(simple)) named[0] = true;
                return false;
            }
        });
        if (named[0]) return source;
        ASTRewrite rewriter = ASTRewrite.create(unit.getAST());
        for (Object each : unit.imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            if (imported.getName().getFullyQualifiedName().equals(Param.class.getCanonicalName())) {
                rewriter.remove(imported, null);
            }
        }
        return AstRewriteHelper.applyRewrite(rewriter, source);
    }

    private static boolean inside(ASTNode node, Class<? extends ASTNode> kind) {
        return enclosing(node, kind) != null;
    }

    private static <T extends ASTNode> T enclosing(ASTNode node, Class<T> kind) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (kind.isInstance(at)) return kind.cast(at);
        }
        return null;
    }
}
