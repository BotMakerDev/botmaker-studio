package com.botmaker.studio.project.managed;

import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.Usages;
import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.refactor.ReviewMarker;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.params.JavaParameterSource;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.project.source.ValueTypeResolver;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import javax.lang.model.SourceVersion;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A plugin's open sets — a class carrying {@code @SdkValue(SdkValue.Id.PICTURES)} whose {@code public static
 * final} constants the plugin adds, renames, repoints and removes (2026-09-28), or an enum carrying one whose
 * bare constants it does the same to ({@code ManagedValue.isEnum}, 2026-10-10).
 *
 * <p><b>Pure, over the bot's one parse.</b> A {@link BotIndex} in, a {@link Refactor.Planned plan} or a
 * {@link Refactor.Refused refusal} out, nothing written — the shape {@link Refactor} has, and for the same
 * reason: whoever asked writes the plan through its own path ({@code plugin/HostPluginValues}).
 *
 * <p><b>By binding, never by spelling.</b> This is what replaced {@code Sources}, which searched the bot for
 * the tokens a plugin guessed its constant was spelled with: a rename changed {@code Pictures.ORE} at each use
 * and left the declaration named {@code ORE}, so the bot stopped compiling. A use here is what javac resolves
 * to the constant ({@link Usages}), a rename is {@link Refactor#rename}, and every plan is compiled as the
 * whole bot before it is offered.
 */
public final class ManagedSets {

    private ManagedSets() {}

    /**
     * One constant of an open set.
     *
     * @param file        the file its class is declared in
     * @param className   the class's simple name — {@code Pictures}
     * @param name        the constant's name — {@code ORE}
     * @param start       where its name starts, the handle {@link Refactor} takes
     * @param form        its declared type, as a value's type; null for an enum's constant
     * @param initializer its initialiser, as written; null for an enum's constant, whose name is all it has
     */
    public record Member(Path file, String className, String name, int start, Type form, String initializer) {

        /** What a bot writes to reach it: {@code Pictures.ORE}. */
        public String qualified() {
            return className + "." + name;
        }
    }

    /** The class or enum carrying {@code id}'s mark: where it is and what it is called. */
    private record Holder(Path file, String className) {}

    /** The constants of the open set {@code id}, in the order they are written; empty when no class carries it. */
    public static List<Member> members(BotIndex index, String id, ValueGrammar grammar) {
        if (id == null || id.isBlank()) return List.of();
        return index.read(units -> {
            List<Member> out = new ArrayList<>();
            units.forEach((file, unit) -> {
                if (!out.isEmpty()) return;
                String source = index.sources().get(file);
                for (Object each : unit.types()) {
                    if (!(each instanceof AbstractTypeDeclaration declared) || !carries(declared, id)) continue;
                    String className = declared.getName().getIdentifier();
                    if (declared instanceof EnumDeclaration enumeration) {
                        for (Object c : enumeration.enumConstants()) {
                            SimpleName name = ((EnumConstantDeclaration) c).getName();
                            out.add(new Member(file, className, name.getIdentifier(), name.getStartPosition(),
                                    null, null));
                        }
                        return;
                    }
                    if (!(declared instanceof TypeDeclaration type)) return;
                    for (FieldDeclaration field : type.getFields()) {
                        if (!isConstant(field)) continue;
                        for (Object f : field.fragments()) {
                            VariableDeclarationFragment fragment = (VariableDeclarationFragment) f;
                            if (fragment.getInitializer() == null) continue;
                            out.add(new Member(file, type.getName().getIdentifier(),
                                    fragment.getName().getIdentifier(), fragment.getName().getStartPosition(),
                                    ValueTypeResolver.of(grammar, field.getType(), fragment.getExtraDimensions(),
                                            Set.of()),
                                    JavaParameterSource.text(source, fragment.getInitializer())));
                        }
                    }
                    return;
                }
            });
            return List.copyOf(out);
        });
    }

    /** The constant {@code name} of the set {@code id}, or empty. */
    public static Optional<Member> member(BotIndex index, String id, String name, ValueGrammar grammar) {
        return members(index, id, grammar).stream().filter(m -> m.name().equals(name)).findFirst();
    }

    /** Every use of {@code member} outside its declaration; empty when it cannot be resolved. */
    public static List<Usages.Usage> uses(BotIndex index, Member member) {
        return Refactor.uses(index, member.file(), member.start()).orElse(List.of());
    }

    /**
     * {@code public static final <type> name = <initializer>;} after the class's last field, or first in its
     * body when it has none — refused when {@code name} is not a Java name, is taken, or would not compile. An
     * enum holder gets the bare constant {@code name} after its last one, and {@code type} and
     * {@code initializer} are not read.
     */
    public static Refactor.Outcome add(BotIndex index, String id, String name, Class<?> type,
                                       JavaValue initializer) {
        if (name == null || !SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
            return new Refactor.Refused("“" + name + "” is not a name Java accepts.");
        }
        List<Holder> holders = holders(index, id);
        String mark = ManagedIds.spelled(id, PluginHost.managedValues());
        if (holders.isEmpty()) return new Refactor.Refused("The project has no class marked " + mark + ".");
        if (holders.size() > 1) {
            return new Refactor.Refused(holders.get(0).className() + " and " + holders.get(1).className()
                    + " are both marked " + mark + ", so which one gets " + name + " is not clear. Remove the"
                    + " mark from one of them.");
        }
        Holder holder = holders.getFirst();
        String source = index.sources().get(holder.file());
        String added = added(source, holder.className(), name, type, initializer);
        if (added.equals(source)) {
            return new Refactor.Refused(holder.className() + "." + name + " could not be written.");
        }
        return Refactor.checked(index, new Refactor.Planned("Added " + holder.className() + "." + name,
                Map.of(holder.file(), added)), "Adding " + holder.className() + "." + name);
    }

    /**
     * Every use of {@code from} pointed at {@code to}: the name at each use replaced, so {@code Pictures.ORE}
     * becomes {@code Pictures.GOLD} and a static import of it follows. Each function touched is marked
     * {@code @Refactor(note)} when {@code note} is given — the caller decides whether the bot can compile the
     * mark. Refused when nothing uses it, or when the result would add a compile error.
     */
    public static Refactor.Outcome repoint(BotIndex index, Member from, Member to, String note) {
        String key = index.read(units -> {
            CompilationUnit unit = units.get(from.file());
            return unit == null ? null : Usages.keyOf(Usages.bindingAt(unit, from.start()));
        });
        if (key == null) return new Refactor.Refused(from.qualified() + " could not be resolved, so the places "
                + "that use it cannot be found. Nothing was changed.");
        Map<Path, String> rewrites = new LinkedHashMap<>();
        for (Map.Entry<Path, List<Usages.Usage>> file : byFile(Usages.across(index, key, from.name())).entrySet()) {
            String text = index.sources().get(file.getKey());
            List<Integer> starts = file.getValue().stream().map(Usages.Usage::start).toList();
            String rewritten = Usages.renamed(text, starts, from.name(), to.name());
            if (note != null && !note.isBlank()) {
                Set<Integer> lines = new LinkedHashSet<>();
                file.getValue().forEach(use -> lines.add(use.line()));
                rewritten = ReviewMarker.markLines(rewritten, lines, note);
            }
            if (!rewritten.equals(text)) rewrites.put(file.getKey(), rewritten);
        }
        if (rewrites.isEmpty()) return new Refactor.Refused("Nothing uses " + from.qualified() + ".");
        int files = rewrites.size();
        return Refactor.checked(index, new Refactor.Planned("Pointed " + from.qualified() + "'s uses at "
                + to.qualified() + (files > 1 ? ", in " + files + " files" : ""), rewrites),
                "Pointing " + from.qualified() + "'s uses at " + to.qualified());
    }

    /** The declaration of {@code member} deleted — refused, with the uses, while anything still uses it. */
    public static Refactor.Outcome remove(BotIndex index, Member member) {
        List<Usages.Usage> uses = uses(index, member);
        if (!uses.isEmpty()) {
            List<String> where = uses.stream().map(use -> use.file().getFileName() + ":" + use.line())
                    .distinct().limit(3).toList();
            return new Refactor.Refused(member.qualified() + " is still used " + uses.size()
                    + (uses.size() == 1 ? " time" : " times") + ", in " + String.join(", ", where)
                    + (uses.size() > where.size() ? ", …" : "") + ".", uses, List.of());
        }
        String source = index.sources().get(member.file());
        String removed = removed(source, member.className(), member.name());
        if (removed.equals(source)) return new Refactor.Refused(member.qualified() + " could not be removed.");
        return Refactor.checked(index, new Refactor.Planned("Removed " + member.qualified(),
                Map.of(member.file(), removed)), "Removing " + member.qualified());
    }

    // --- pure edits --------------------------------------------------------------------------------------------

    /** {@code source} with the constant declared in {@code className}; unchanged when the class is not in it. */
    static String added(String source, String className, String name, Class<?> type, JavaValue initializer) {
        CompilationUnit unit = BotParser.syntax(source);
        AST ast = unit.getAST();
        AbstractTypeDeclaration target = topLevel(unit, className);
        if (target instanceof EnumDeclaration enumeration) return addedConstant(source, unit, enumeration, name);
        if (!(target instanceof TypeDeclaration holder) || initializer == null) return source;
        Set<String> imports = new LinkedHashSet<>();
        Optional<org.eclipse.jdt.core.dom.Type> declared = ValueTypes.node(ast, type, imports);
        if (declared.isEmpty()) return source;

        VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
        fragment.setName(ast.newSimpleName(name));
        fragment.setInitializer(initializer.copyInto(ast));
        FieldDeclaration field = ast.newFieldDeclaration(fragment);
        field.setType(declared.get());
        @SuppressWarnings("unchecked")
        List<Object> modifiers = field.modifiers();
        modifiers.addAll(ast.newModifiers(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL));

        ASTRewrite rewrite = ASTRewrite.create(ast);
        ListRewrite body = rewrite.getListRewrite(holder, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        FieldDeclaration[] fields = holder.getFields();
        if (fields.length == 0) body.insertFirst(field, null);
        else body.insertAfter(field, fields[fields.length - 1], null);
        imports.addAll(initializer.imports());
        for (String each : imports) ImportManager.addImport(unit, rewrite, each);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    /** {@code source} with the bare constant {@code name} after the enum's last one. */
    private static String addedConstant(String source, CompilationUnit unit, EnumDeclaration holder, String name) {
        AST ast = unit.getAST();
        EnumConstantDeclaration constant = ast.newEnumConstantDeclaration();
        constant.setName(ast.newSimpleName(name));
        ASTRewrite rewrite = ASTRewrite.create(ast);
        rewrite.getListRewrite(holder, EnumDeclaration.ENUM_CONSTANTS_PROPERTY).insertLast(constant, null);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    /** {@code source} with {@code className.name} deleted — the whole field when it declares nothing else. */
    static String removed(String source, String className, String name) {
        CompilationUnit unit = BotParser.syntax(source);
        AbstractTypeDeclaration declared = topLevel(unit, className);
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        if (declared instanceof EnumDeclaration enumeration) {
            for (Object each : enumeration.enumConstants()) {
                EnumConstantDeclaration constant = (EnumConstantDeclaration) each;
                if (!constant.getName().getIdentifier().equals(name)) continue;
                rewrite.remove(constant, null);
                return AstRewriteHelper.applyRewrite(rewrite, source);
            }
            return source;
        }
        if (!(declared instanceof TypeDeclaration holder)) return source;
        for (FieldDeclaration field : holder.getFields()) {
            for (Object each : field.fragments()) {
                VariableDeclarationFragment fragment = (VariableDeclarationFragment) each;
                if (!fragment.getName().getIdentifier().equals(name)) continue;
                rewrite.remove(field.fragments().size() == 1 ? field : fragment, null);
                return AstRewriteHelper.applyRewrite(rewrite, source);
            }
        }
        return source;
    }

    // --- reading -----------------------------------------------------------------------------------------------

    /** Every class carrying {@code id}: one is the set's holder, two is a project nobody can add to safely. */
    private static List<Holder> holders(BotIndex index, String id) {
        return index.read(units -> {
            List<Holder> found = new ArrayList<>();
            for (Map.Entry<Path, CompilationUnit> entry : units.entrySet()) {
                for (Object each : entry.getValue().types()) {
                    if (each instanceof AbstractTypeDeclaration type && carries(type, id)) {
                        found.add(new Holder(entry.getKey(), type.getName().getIdentifier()));
                    }
                }
            }
            return List.copyOf(found);
        });
    }

    private static boolean carries(AbstractTypeDeclaration type, String id) {
        Annotation annotation = JavaManagedSource.managedAnnotation(type);
        return annotation != null && id.equals(JavaManagedSource.idOf(annotation));
    }

    private static boolean isConstant(FieldDeclaration field) {
        int flags = field.getModifiers();
        return Modifier.isPublic(flags) && Modifier.isStatic(flags) && Modifier.isFinal(flags);
    }

    private static AbstractTypeDeclaration topLevel(CompilationUnit unit, String className) {
        for (Object each : unit.types()) {
            if (each instanceof AbstractTypeDeclaration type && type.getName().getIdentifier().equals(className)) {
                return type;
            }
        }
        return null;
    }

    private static Map<Path, List<Usages.Usage>> byFile(List<Usages.Usage> uses) {
        Map<Path, List<Usages.Usage>> out = new LinkedHashMap<>();
        for (Usages.Usage use : uses) {
            if (!use.declaration()) out.computeIfAbsent(use.file(), f -> new ArrayList<>()).add(use);
        }
        return out;
    }
}
