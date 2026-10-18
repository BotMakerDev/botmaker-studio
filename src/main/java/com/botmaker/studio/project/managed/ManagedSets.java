package com.botmaker.studio.project.managed;

import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.Usages;
import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.helpers.SourceFormatter;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A plugin's open sets — a class carrying {@code @SdkValue(SdkValue.Id.PICTURES)} whose {@code public static
 * final} constants the plugin adds, renames, repoints, removes (2026-09-28) and moves (2026-10-12), or an enum
 * carrying one whose bare constants it does the same to but move ({@code ManagedValue.isEnum}, 2026-10-10).
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
     * @param group       the enum nested in the class that declares it — {@code Collect} — or null for a
     *                    constant of the class itself
     */
    public record Member(Path file, String className, String name, int start, Type form, String initializer,
                         String group) {

        /** A constant of the class itself, or of a top-level enum. */
        public Member(Path file, String className, String name, int start, Type form, String initializer) {
            this(file, className, name, start, form, initializer, null);
        }

        /** What {@code PluginValues} calls it: {@code ORE}, or {@code Collect.WON} in a nested enum. */
        public String memberName() {
            return group == null ? name : group + "." + name;
        }

        /** What a bot writes to reach it: {@code Pictures.ORE}, {@code Activities.Collect.WON}. */
        public String qualified() {
            return className + "." + memberName();
        }
    }

    /**
     * One enum nested in a grouped set's class ({@code ManagedValue.isGrouped}).
     *
     * @param file      the file its class is declared in
     * @param className the class's simple name — {@code Activities}
     * @param name      the enum's simple name — {@code Collect}
     * @param start     where its name starts, the handle {@link Refactor} takes
     * @param members   its constants, in the order they are written
     */
    public record Group(Path file, String className, String name, int start, List<Member> members) {

        /** What a bot writes to reach it: {@code Activities.Collect}. */
        public String qualified() {
            return className + "." + name;
        }
    }

    /** The class or enum carrying {@code id}'s mark: where it is and what it is called. */
    record Holder(Path file, String className) {}

    /** The constants of the open set {@code id}, in the order they are written; empty when no class carries it. */
    public static List<Member> members(BotIndex index, String id, ValueGrammar grammar) {
        if (id == null || id.isBlank()) return List.of();
        // Once per index, set and bind: a block asks it on every render (2026-10-18).
        return index.memo(PluginHost.managedValues(), Arrays.asList(ManagedSets.class, id, grammar), units -> {
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
                    // A set of nested classes: its members are the classes' fields (ManagedClasses).
                    List<ManagedClasses.Nested> classes = ManagedClasses.of(file, source, type, grammar, null);
                    if (!classes.isEmpty()) {
                        classes.forEach(nested -> out.addAll(nested.fields()));
                        return;
                    }
                    // A class of constants may nest a helper enum of its own, which is no member of the set:
                    // only a class holding no constant of its own is a set of nested enums.
                    boolean grouped = Arrays.stream(type.getFields()).noneMatch(ManagedSets::isConstant);
                    if (grouped) for (Group group : nested(file, type)) out.addAll(group.members());
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

    /** The constant {@code name} of the set {@code id} — {@code ORE}, or {@code Collect.WON} — or empty. */
    public static Optional<Member> member(BotIndex index, String id, String name, ValueGrammar grammar) {
        return members(index, id, grammar).stream().filter(m -> m.memberName().equals(name)).findFirst();
    }

    /** The enums nested in the class carrying {@code id}, in the order they are written; empty when none does. */
    public static List<Group> groups(BotIndex index, String id) {
        if (id == null || id.isBlank()) return List.of();
        return index.read(units -> {
            for (Map.Entry<Path, CompilationUnit> entry : units.entrySet()) {
                for (Object each : entry.getValue().types()) {
                    if (each instanceof TypeDeclaration type && carries(type, id)) {
                        return nested(entry.getKey(), type);
                    }
                }
            }
            return List.<Group>of();
        });
    }

    /** The nested enum {@code name} of the set {@code id}, or empty. */
    public static Optional<Group> group(BotIndex index, String id, String name) {
        return groups(index, id).stream().filter(g -> g.name().equals(name)).findFirst();
    }

    /**
     * {@code public enum name implements <element> { constants… }} after the class's last nested enum, or first
     * in its body — refused when a name is not a Java name, the enum is taken, there is no constant, or the bot
     * would not compile.
     */
    public static Refactor.Outcome addGroup(BotIndex index, String id, String name, List<String> constants,
                                            Class<?> element) {
        if (!isJavaName(name)) return new Refactor.Refused("“" + name + "” is not a name Java accepts.");
        if (constants == null || constants.isEmpty()) {
            return new Refactor.Refused(name + " needs a constant: a method returning it has to return one.");
        }
        for (String constant : constants) {
            if (!isJavaName(constant)) return new Refactor.Refused("“" + constant + "” is not a name Java accepts.");
        }
        List<Holder> holders = holders(index, id);
        String mark = ManagedIds.spelled(id, PluginHost.managedValues());
        if (holders.size() != 1) {
            return new Refactor.Refused(holders.isEmpty() ? "The project has no class marked " + mark + "."
                    : "More than one class is marked " + mark + ", so which one gets " + name + " is not clear.");
        }
        Holder holder = holders.getFirst();
        if (group(index, id, name).isPresent()) {
            return new Refactor.Refused(holder.className() + "." + name + " already exists.");
        }
        String source = index.sources().get(holder.file());
        String added = addedGroup(source, holder.className(), name, constants, element);
        if (added.equals(source)) {
            return new Refactor.Refused(holder.className() + "." + name + " could not be written.");
        }
        return Refactor.checked(index, new Refactor.Planned("Added " + holder.className() + "." + name,
                Map.of(holder.file(), added)), "Adding " + holder.className() + "." + name);
    }

    /** The nested enum deleted — refused, with the uses, while anything uses it or one of its constants. */
    public static Refactor.Outcome removeGroup(BotIndex index, Group group) {
        List<Usages.Usage> uses = new ArrayList<>(Refactor.uses(index, group.file(), group.start()).orElse(List.of()));
        for (Member member : group.members()) uses.addAll(uses(index, member));
        uses.removeIf(Usages.Usage::declaration);
        if (!uses.isEmpty()) {
            List<String> where = uses.stream().map(use -> use.file().getFileName() + ":" + use.line())
                    .distinct().limit(3).toList();
            return new Refactor.Refused(group.qualified() + " is still used " + uses.size()
                    + (uses.size() == 1 ? " time" : " times") + ", in " + String.join(", ", where)
                    + (uses.size() > where.size() ? ", …" : "") + ".", uses, List.of());
        }
        String source = index.sources().get(group.file());
        String removed = removedGroup(source, group.className(), group.name());
        if (removed.equals(source)) return new Refactor.Refused(group.qualified() + " could not be removed.");
        return Refactor.checked(index, new Refactor.Planned("Removed " + group.qualified(),
                Map.of(group.file(), removed)), "Removing " + group.qualified());
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
        int dot = name == null ? -1 : name.indexOf('.');
        if (dot > 0) {
            Optional<ManagedClasses.Nested> into = ManagedClasses.named(index, id, name.substring(0, dot),
                    ValueGrammar.empty(), null);
            if (into.isPresent()) return ManagedClasses.addField(index, into.get(), name.substring(dot + 1), type,
                    initializer);
            return addToGroup(index, id, name.substring(0, dot), name.substring(dot + 1));
        }
        if (!isJavaName(name)) {
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

    /** The bare constant {@code name} after the last one of the nested enum {@code group}. */
    private static Refactor.Outcome addToGroup(BotIndex index, String id, String group, String name) {
        if (!isJavaName(name)) return new Refactor.Refused("“" + name + "” is not a name Java accepts.");
        Optional<Group> found = group(index, id, group);
        if (found.isEmpty()) {
            return new Refactor.Refused("The project has no " + group + " in the class marked "
                    + ManagedIds.spelled(id, PluginHost.managedValues()) + ".");
        }
        Group into = found.get();
        if (into.members().stream().anyMatch(m -> m.name().equals(name))) {
            return new Refactor.Refused(into.qualified() + "." + name + " already exists.");
        }
        String source = index.sources().get(into.file());
        CompilationUnit unit = BotParser.syntax(source);
        EnumDeclaration nested = nestedEnum(topLevel(unit, into.className()), group);
        String added = nested == null ? source : addedConstant(source, unit, nested, name);
        if (added.equals(source)) return new Refactor.Refused(into.qualified() + "." + name + " could not be written.");
        return Refactor.checked(index, new Refactor.Planned("Added " + into.qualified() + "." + name,
                Map.of(into.file(), added)), "Adding " + into.qualified() + "." + name);
    }

    /**
     * Every use of {@code from} pointed at {@code to}: the name at each use replaced, so {@code Pictures.ORE}
     * becomes {@code Pictures.GOLD} and a static import of it follows. Each function touched is marked
     * {@code @Refactor(note)} when {@code note} is given — the caller decides whether the bot can compile the
     * mark. Refused when nothing uses it, or when the result would add a compile error.
     */
    public static Refactor.Outcome repoint(BotIndex index, Member from, Member to, String note) {
        if (!java.util.Objects.equals(from.group(), to.group())) {
            // Only the name at each use is replaced, so the enum or class it is qualified with would stay the old one.
            return new Refactor.Refused(from.qualified() + " and " + to.qualified() + " are in different "
                    + (from.initializer() != null ? "classes" : "enums") + ": only the name at each use would change.");
        }
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
        String removed = member.group() == null ? removed(source, member.className(), member.name())
                : member.initializer() != null
                        ? ManagedClasses.removedField(source, member.className(), member.group(), member.name())
                        : removedFromGroup(source, member.className(), member.group(), member.name());
        if (removed.equals(source)) return new Refactor.Refused(member.qualified() + " could not be removed.");
        return Refactor.checked(index, new Refactor.Planned("Removed " + member.qualified(),
                Map.of(member.file(), removed)), "Removing " + member.qualified());
    }

    /**
     * {@code member} declared just above {@code before}, so {@code before}'s initialiser may read it: javac refuses
     * a static field read above its declaration (2026-10-12, a flow running another flow as one card). A plan
     * that rewrites nothing when it is already above. Refused when they are one constant, when {@code member}
     * shares its declaration with another field, or when the bot would not compile after — {@code member}'s own
     * initialiser reading a constant declared between the two. Always refused for an enum's constant: it reads
     * no other, and moving it changes its {@code ordinal()} and {@code values()}.
     *
     * <p>Only the compile is checked: a {@code static {}} block the move jumps over runs in a new order unseen.
     */
    public static Refactor.Outcome moveBefore(BotIndex index, Member member, Member before) {
        if (member.initializer() == null || before.initializer() == null) {
            return new Refactor.Refused(member.qualified() + " is an enum's constant: moving it would change the "
                    + "order the enum lists its constants in, and it reads no other constant.");
        }
        if (member.group() != null || before.group() != null) {
            return new Refactor.Refused(member.qualified() + " is a field of a class of the set: its order is the "
                    + "class's own, and no other class's field can sit beside it.");
        }
        if (member.qualified().equals(before.qualified())) {
            return new Refactor.Refused(member.qualified() + " cannot move above itself.");
        }
        if (!member.file().equals(before.file())) {
            return new Refactor.Refused(member.qualified() + " and " + before.qualified()
                    + " are not declared side by side, so neither can move above the other.");
        }
        String summary = "Moved " + member.qualified() + " above " + before.qualified();
        if (member.start() < before.start()) return new Refactor.Planned(summary, Map.of());
        String source = index.sources().get(member.file());
        String moved = movedBefore(source, member.className(), member.name(), before.name());
        if (moved == null) {
            return new Refactor.Refused(member.qualified() + " could not be moved: it is declared together with "
                    + "another field. Give it a declaration of its own first.");
        }
        if (moved.equals(source)) return new Refactor.Refused(member.qualified() + " could not be moved.");
        return Refactor.checked(index, new Refactor.Planned(summary, Map.of(member.file(), moved)),
                "Moving " + member.qualified() + " above " + before.qualified());
    }

    // --- pure edits --------------------------------------------------------------------------------------------

    /**
     * {@code source} with the constant {@code name} of the class {@code className} moved just above
     * {@code before}, its javadoc and annotations with it; unchanged when either is absent, null when
     * {@code name}'s field declares another one too.
     */
    static String movedBefore(String source, String className, String name, String before) {
        CompilationUnit unit = BotParser.syntax(source);
        if (!(topLevel(unit, className) instanceof TypeDeclaration holder)) return source;
        FieldDeclaration moving = field(holder, name);
        FieldDeclaration anchor = field(holder, before);
        if (moving == null || anchor == null) return source;
        if (moving.fragments().size() > 1) return null;
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        rewrite.getListRewrite(holder, TypeDeclaration.BODY_DECLARATIONS_PROPERTY)
                .insertBefore(rewrite.createMoveTarget(moving), anchor, null);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    /** The field declaring {@code name} in {@code holder}, or null. */
    private static FieldDeclaration field(TypeDeclaration holder, String name) {
        for (FieldDeclaration field : holder.getFields()) {
            for (Object each : field.fragments()) {
                if (((VariableDeclarationFragment) each).getName().getIdentifier().equals(name)) return field;
            }
        }
        return null;
    }

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

    /**
     * {@code source} with {@code public enum name implements <element> { constants… }} in {@code className},
     * after its last nested enum or first in its body; unchanged when the class is not in it or the element type
     * has no node.
     */
    static String addedGroup(String source, String className, String name, List<String> constants, Class<?> element) {
        CompilationUnit unit = BotParser.syntax(source);
        AST ast = unit.getAST();
        if (!(topLevel(unit, className) instanceof TypeDeclaration holder)) return source;
        Set<String> imports = new LinkedHashSet<>();
        EnumDeclaration nested = ast.newEnumDeclaration();
        nested.setName(ast.newSimpleName(name));
        @SuppressWarnings("unchecked")
        List<Object> modifiers = nested.modifiers();
        modifiers.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        if (element != null) {
            Optional<org.eclipse.jdt.core.dom.Type> face = ValueTypes.node(ast, element, imports);
            if (face.isEmpty()) return source;
            @SuppressWarnings("unchecked")
            List<Object> supers = nested.superInterfaceTypes();
            supers.add(face.get());
        }
        @SuppressWarnings("unchecked")
        List<Object> enumConstants = nested.enumConstants();
        for (String constant : constants) {
            EnumConstantDeclaration declared = ast.newEnumConstantDeclaration();
            declared.setName(ast.newSimpleName(constant));
            enumConstants.add(declared);
        }
        ASTRewrite rewrite = ASTRewrite.create(ast);
        ListRewrite body = rewrite.getListRewrite(holder, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        EnumDeclaration last = null;
        for (Object each : holder.bodyDeclarations()) if (each instanceof EnumDeclaration e) last = e;
        if (last == null) body.insertFirst(nested, null);
        else body.insertAfter(nested, last, null);
        for (String each : imports) ImportManager.addImport(unit, rewrite, each);
        // The rewrite writes a new node flat, "implements Outcome{NEXT,WON}": only what it added is laid out.
        return SourceFormatter.formatChanged(source, AstRewriteHelper.applyRewrite(rewrite, source));
    }

    /** {@code source} with the enum {@code name} nested in {@code className} deleted; unchanged when absent. */
    static String removedGroup(String source, String className, String name) {
        CompilationUnit unit = BotParser.syntax(source);
        EnumDeclaration nested = nestedEnum(topLevel(unit, className), name);
        if (nested == null) return source;
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        rewrite.remove(nested, null);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    /** {@code source} with the constant {@code name} of the enum {@code group} nested in {@code className} deleted. */
    static String removedFromGroup(String source, String className, String group, String name) {
        CompilationUnit unit = BotParser.syntax(source);
        EnumDeclaration nested = nestedEnum(topLevel(unit, className), group);
        if (nested == null) return source;
        for (Object each : nested.enumConstants()) {
            EnumConstantDeclaration constant = (EnumConstantDeclaration) each;
            if (!constant.getName().getIdentifier().equals(name)) continue;
            ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
            rewrite.remove(constant, null);
            return AstRewriteHelper.applyRewrite(rewrite, source);
        }
        return source;
    }

    // --- reading -----------------------------------------------------------------------------------------------

    /** The enums nested in {@code type}, each with its constants. */
    private static List<Group> nested(Path file, TypeDeclaration type) {
        String className = type.getName().getIdentifier();
        List<Group> out = new ArrayList<>();
        for (Object body : type.bodyDeclarations()) {
            if (!(body instanceof EnumDeclaration nested)) continue;
            String group = nested.getName().getIdentifier();
            List<Member> members = new ArrayList<>();
            for (Object c : nested.enumConstants()) {
                SimpleName name = ((EnumConstantDeclaration) c).getName();
                members.add(new Member(file, className, name.getIdentifier(), name.getStartPosition(), null, null,
                        group));
            }
            out.add(new Group(file, className, group, nested.getName().getStartPosition(), List.copyOf(members)));
        }
        return List.copyOf(out);
    }

    private static EnumDeclaration nestedEnum(AbstractTypeDeclaration holder, String name) {
        if (!(holder instanceof TypeDeclaration type)) return null;
        for (Object body : type.bodyDeclarations()) {
            if (body instanceof EnumDeclaration nested && nested.getName().getIdentifier().equals(name)) return nested;
        }
        return null;
    }

    private static boolean isJavaName(String name) {
        return name != null && SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name);
    }

    /** Every class carrying {@code id}: one is the set's holder, two is a project nobody can add to safely. */
    static List<Holder> holders(BotIndex index, String id) {
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

    static boolean carries(AbstractTypeDeclaration type, String id) {
        Annotation annotation = JavaManagedSource.managedAnnotation(type);
        return annotation != null && id.equals(JavaManagedSource.idOf(annotation));
    }

    private static boolean isConstant(FieldDeclaration field) {
        int flags = field.getModifiers();
        return Modifier.isPublic(flags) && Modifier.isStatic(flags) && Modifier.isFinal(flags);
    }

    static AbstractTypeDeclaration topLevel(CompilationUnit unit, String className) {
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
