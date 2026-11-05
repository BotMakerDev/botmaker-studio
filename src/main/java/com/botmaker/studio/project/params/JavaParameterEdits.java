package com.botmaker.studio.project.params;

import com.botmaker.java.parse.JavaLevel;
import com.botmaker.plugin.api.meta.Migrate;
import com.botmaker.plugin.api.params.Param;
import com.botmaker.plugin.api.source.ParameterWrite;
import com.botmaker.java.edit.ImportManager;
import com.botmaker.java.edit.helpers.AstRewriteHelper;
import com.botmaker.java.grammar.SourceFormatter;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ParamValues;
import com.botmaker.java.grammar.ParamsFitter;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.java.grammar.ValueTypes;
import com.botmaker.java.grammar.BotAnnotation;
import com.botmaker.java.parse.BotParser;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The rewrites the Parameters window performs on a bot's own Java — one source in, one source out.
 *
 * <p><b>Pure, like {@link JavaParameterSource}, and for the same reason</b>: what can go wrong in a rewrite
 * is the rewrite, and a test that has to create a project to check where a brace landed tests the project
 * machinery instead. {@link JavaParameters} is the half that knows about files, buffers and history.
 *
 * <p><b>Every method answers the source unchanged when it cannot do what was asked</b> — no field by that
 * name, no class by that name, an initialiser the type cannot spell. The caller compares and reports;
 * nothing here throws, because a Parameters window that takes the project down with it is worse than one
 * that declines a click.
 *
 * <p><b>The field's own formatting is left alone.</b> Edits go through {@link ASTRewrite}, which rewrites
 * the nodes it is given and copies the rest of the file through untouched — so a bot whose author lines up
 * their initialisers keeps them lined up, and a diff after changing one value is one line.
 */
public final class JavaParameterEdits {

    private JavaParameterEdits() {}

    /**
     * Replaces the value of {@code className.fieldName} with {@code initializer}, adding the imports it
     * names — an editor writes {@code Duration.ofSeconds(3)} rather than the qualified name, because the file
     * is one a person reads.
     *
     * <p><b>The initialiser arrives as a tree</b> (2026-09-24): the grammar built it node by node, and it is
     * copied into this file's tree and laid out by {@code ASTRewrite}. Reading it back is
     * {@code ValueGrammar.valueOf}, so the round trip is a fixed point.
     *
     * <p>No initialiser answers the source unchanged: a field with no value is not something this can write,
     * and emptying one is not an edit anybody asked for.
     */
    public static String setValue(String source, String className, String fieldName, JavaValue initializer) {
        if (initializer == null) return source;
        return edit(source, className, fieldName, (unit, ast, rewrite, field, fragment) -> {
            rewrite.set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY, initializer.copyInto(ast), null);
            addImports(unit, rewrite, initializer.imports());
        });
    }

    /**
     * Replaces what the parameter {@code methodName} of the group {@code group} returns with {@code value}
     * (2026-11-04), adding the imports it names. Unchanged when there is no such method or its body is not one
     * {@code return}.
     */
    public static String setReturned(String source, String group, String methodName, JavaValue value) {
        return ParamValues.withReturned(JavaLevel.LATEST, source, group, methodName, value).orElse(source);
    }

    /**
     * {@code source} with the parameter {@code name} added to the group {@code group} (2026-11-04):
     * {@code public <form> name() { return value; }}, under a {@code @Param.Shown} carrying the members the author
     * chose. Unchanged when there is no such class, it already has a method of that name, the grammar cannot write
     * {@code form}, or there is no value.
     */
    @SuppressWarnings("unchecked")
    public static String addMethod(String source, ValueGrammar grammar, String group, String name, Type form,
                                   JavaValue value, String category, String description, Param.Changes changes) {
        if (value == null || !writable(grammar, form) || name == null || name.isBlank()) return source;
        CompilationUnit unit = BotParser.syntax(source);
        AST ast = unit.getAST();
        TypeDeclaration type = typeDeclaration(unit, group);
        // A rename rule may keep the name as a static method (renamedInGroup): that name is free for a parameter.
        if (type == null || parameterOf(type, name) != null) return source;
        Set<String> imports = new LinkedHashSet<>(value.imports());
        org.eclipse.jdt.core.dom.Type returnType = ValueTypes.node(ast, form, imports).orElse(null);
        if (returnType == null) return source;
        MethodDeclaration method = ast.newMethodDeclaration();
        List<MemberValuePair> members = new ArrayList<>();
        if (category != null && !category.isBlank()) members.add(member(ast, "category", category));
        if (description != null && !description.isBlank()) members.add(member(ast, "description", description));
        if (changes == Param.Changes.DURING_RUN) members.add(changesMember(ast, changes));
        if (!members.isEmpty()) {
            NormalAnnotation shown = ast.newNormalAnnotation();
            shown.setTypeName(ast.newName(SHOWN));
            shown.values().addAll(members);
            method.modifiers().add(shown);
            if (!importsSimpleName(unit, Param.class.getSimpleName())) imports.add(Param.class.getCanonicalName());
        }
        method.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        method.setReturnType2(returnType);
        method.setName(ast.newSimpleName(name));
        Block body = ast.newBlock();
        ReturnStatement ret = ast.newReturnStatement();
        ret.setExpression(value.copyInto(ast));
        body.statements().add(ret);
        method.setBody(body);
        ASTRewrite rewrite = ASTRewrite.create(ast);
        rewrite.getListRewrite(type, TypeDeclaration.BODY_DECLARATIONS_PROPERTY).insertLast(method, null);
        String declared = SourceFormatter.formatChanged(JavaLevel.of(rewrite.getAST()), source, AstRewriteHelper.applyRewrite(rewrite, source));
        CompilationUnit reparsed = BotParser.syntax(declared);
        ASTRewrite importing = ASTRewrite.create(reparsed.getAST());
        addImports(reparsed, importing, List.copyOf(imports));
        return AstRewriteHelper.applyRewrite(importing, declared);
    }

    /** A class as a file writes it: {@code Screens.Home}, and the top-level type to import for it. */
    public record ClassName(String imported, String spelled) {}

    /**
     * {@code source} with {@code marks} on the parameter {@code name} of {@code group} and each of {@code groupMarks}
     * on the group, unless the group carries it already (2026-11-04). Each annotation and each class among its values
     * is imported. Empty when there is no such parameter, or a class in {@code classes} cannot be named: a parameter
     * without its marks is not the one asked for.
     */
    public static Optional<String> withMarks(String source, String group, String name, List<ParameterWrite.Mark> marks,
                                             List<ParameterWrite.Mark> groupMarks,
                                             Function<ParameterWrite.ClassOf, Optional<ClassName>> classes) {
        CompilationUnit unit = BotParser.syntax(source);
        AST ast = unit.getAST();
        TypeDeclaration type = typeDeclaration(unit, group);
        MethodDeclaration method = type == null ? null : methodOf(type, name);
        if (method == null) return Optional.empty();
        Set<String> imports = new LinkedHashSet<>();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        ListRewrite onMethod = rewrite.getListRewrite(method, MethodDeclaration.MODIFIERS2_PROPERTY);
        ASTNode firstPlain = firstPlainModifier(method.modifiers());
        for (ParameterWrite.Mark mark : marks) {
            Optional<Annotation> written = annotation(ast, mark, classes, imports);
            if (written.isEmpty()) return Optional.empty();
            if (firstPlain != null) onMethod.insertBefore(written.get(), firstPlain, null);
            else onMethod.insertLast(written.get(), null);
        }
        ListRewrite onGroup = rewrite.getListRewrite(type, TypeDeclaration.MODIFIERS2_PROPERTY);
        ASTNode firstOfGroup = firstPlainModifier(type.modifiers());
        for (ParameterWrite.Mark mark : groupMarks) {
            if (carries(type.modifiers(), spelledAnnotation(mark))) continue;
            Optional<Annotation> written = annotation(ast, mark, classes, imports);
            if (written.isEmpty()) return Optional.empty();
            if (firstOfGroup != null) onGroup.insertBefore(written.get(), firstOfGroup, null);
            else onGroup.insertLast(written.get(), null);
        }
        addImports(unit, rewrite, List.copyOf(imports));
        return Optional.of(AstRewriteHelper.applyRewrite(rewrite, source));
    }

    /** Whether {@code group} in {@code source} has a method {@code name}. */
    static boolean hasMethod(String source, String group, String name) {
        TypeDeclaration type = typeDeclaration(BotParser.syntax(source), group);
        return type != null && methodOf(type, name) != null;
    }

    /** The first modifier that is no annotation: where a new annotation goes, after the ones there. */
    private static ASTNode firstPlainModifier(List<?> modifiers) {
        for (Object modifier : modifiers) {
            if (modifier instanceof Modifier plain) return plain;
        }
        return null;
    }

    private static boolean carries(List<?> modifiers, String spelled) {
        for (Object modifier : modifiers) {
            if (modifier instanceof Annotation there) {
                String name = there.getTypeName().getFullyQualifiedName();
                if (name.equals(spelled) || name.endsWith("." + spelled)) return true;
            }
        }
        return false;
    }

    /** {@code Outer.Inner} for the binary name {@code com.example.Outer$Inner}. */
    private static String spelledAnnotation(ParameterWrite.Mark mark) {
        String binary = mark.annotation();
        return binary.substring(binary.lastIndexOf('.') + 1).replace('$', '.');
    }

    private static Optional<Annotation> annotation(AST ast, ParameterWrite.Mark mark,
                                                   Function<ParameterWrite.ClassOf, Optional<ClassName>> classes,
                                                   Set<String> imports) {
        String binary = mark.annotation();
        int outer = binary.indexOf('$');
        imports.add(outer < 0 ? binary : binary.substring(0, outer));
        Name typeName = ast.newName(spelledAnnotation(mark));
        if (mark.members().isEmpty()) {
            MarkerAnnotation marker = ast.newMarkerAnnotation();
            marker.setTypeName(typeName);
            return Optional.of(marker);
        }
        NormalAnnotation normal = ast.newNormalAnnotation();
        normal.setTypeName(typeName);
        // A map of no order: a file reads the same whichever computer wrote it.
        for (Map.Entry<String, Object> member : new TreeMap<>(mark.members()).entrySet()) {
            Optional<Expression> value = memberValue(ast, member.getValue(), classes, imports);
            if (value.isEmpty()) return Optional.empty();
            MemberValuePair pair = ast.newMemberValuePair();
            pair.setName(ast.newSimpleName(member.getKey()));
            pair.setValue(value.get());
            @SuppressWarnings("unchecked")
            List<MemberValuePair> values = normal.values();
            values.add(pair);
        }
        return Optional.of(normal);
    }

    private static Optional<Expression> memberValue(AST ast, Object value,
                                                    Function<ParameterWrite.ClassOf, Optional<ClassName>> classes,
                                                    Set<String> imports) {
        return switch (value) {
            case String text -> {
                StringLiteral literal = ast.newStringLiteral();
                literal.setLiteralValue(text);
                yield Optional.of(literal);
            }
            case Integer number -> Optional.of(ast.newNumberLiteral(number.toString()));
            case ParameterWrite.ClassOf classOf -> classes.apply(classOf).map(named -> {
                imports.add(named.imported());
                TypeLiteral literal = ast.newTypeLiteral();
                literal.setType(ast.newSimpleType(ast.newName(named.spelled())));
                return literal;
            });
            default -> Optional.empty();
        };
    }

    /**
     * {@code source} with the {@code @Param.Shown} members of the parameter {@code name} of {@code group} set, a blank
     * value removing one (2026-11-04); {@code changes} is spelled {@code during-run} or {@code outside-run}, the
     * default, which removes the member. A method without the annotation gains one; unchanged when there is no such
     * method.
     */
    public static String setShown(String source, String group, String name, Map<String, String> members) {
        if (members == null || members.isEmpty()) return source;
        CompilationUnit unit = BotParser.syntax(source);
        AST ast = unit.getAST();
        TypeDeclaration type = typeDeclaration(unit, group);
        MethodDeclaration method = type == null ? null : methodOf(type, name);
        if (method == null) return source;
        ASTRewrite rewrite = ASTRewrite.create(ast);
        Annotation existing = BotAnnotation.PARAM_SHOWN.on(method);
        NormalAnnotation normal;
        boolean imported = importsSimpleName(unit, Param.class.getSimpleName());
        if (existing instanceof NormalAnnotation present) {
            normal = present;
        } else {
            normal = ast.newNormalAnnotation();
            normal.setTypeName(existing == null ? ast.newName(SHOWN)
                    : (Name) JavaLevel.copy(ast, existing.getTypeName()));
            ListRewrite modifiers = rewrite.getListRewrite(method, MethodDeclaration.MODIFIERS2_PROPERTY);
            if (existing == null) modifiers.insertFirst(normal, null);
            else modifiers.replace(existing, normal, null);
        }
        boolean fresh = !(existing instanceof NormalAnnotation);
        ListRewrite pairs = fresh ? null : rewrite.getListRewrite(normal, NormalAnnotation.VALUES_PROPERTY);
        // A new annotation, or Param.Changes written into one: both spell Param, which must be imported.
        boolean namesParam = existing == null;
        for (Map.Entry<String, String> entry : members.entrySet()) {
            MemberValuePair present = fresh ? null : pairOf(normal, entry.getKey());
            boolean changes = entry.getKey().equals("changes");
            boolean blank = entry.getValue() == null || entry.getValue().isBlank()
                    || changes && Param.Changes.fromId(entry.getValue()) == Param.Changes.OUTSIDE_RUN;
            if (blank) {
                if (present != null) pairs.remove(present, null);
                continue;
            }
            MemberValuePair replacement = changes ? changesMember(ast, Param.Changes.fromId(entry.getValue()))
                    : member(ast, entry.getKey(), entry.getValue());
            namesParam |= changes;
            if (fresh) addPair(normal, replacement);
            else if (present != null) pairs.replace(present, replacement, null);
            else pairs.insertLast(replacement, null);
        }
        if (fresh && normal.values().isEmpty()) return source;
        if (namesParam && !imported) addImports(unit, rewrite, List.of(Param.class.getCanonicalName()));
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    @SuppressWarnings("unchecked")
    private static void addPair(NormalAnnotation annotation, MemberValuePair pair) {
        annotation.values().add(pair);
    }

    /** How {@code @Param.Shown} is written on a method this host adds or marks. */
    private static final String SHOWN = Param.class.getSimpleName() + "." + Param.Shown.class.getSimpleName();

    /** {@code changes = Param.Changes.DURING_RUN}. */
    private static MemberValuePair changesMember(AST ast, Param.Changes changes) {
        MemberValuePair pair = ast.newMemberValuePair();
        pair.setName(ast.newSimpleName("changes"));
        pair.setValue(ast.newName(Param.class.getSimpleName() + "." + Param.Changes.class.getSimpleName() + "."
                + changes.name()));
        return pair;
    }

    /** The method called {@code name} declared directly in {@code type}, or {@code null}. */
    private static MethodDeclaration methodOf(TypeDeclaration type, String name) {
        for (MethodDeclaration method : type.getMethods()) {
            if (method.getName().getIdentifier().equals(name)) return method;
        }
        return null;
    }

    // A rename is not here (2026-09-27): it is project-wide and found by binding, so it is
    // JavaParameters.rename over nav/Usages. The one that stood here matched names by how they were spelled.
    // A group's starting copy is outside the sources, so no binding reaches it: renamedInGroup is its rename.

    /**
     * {@code source} with the parameter {@code from} of the group {@code group} called {@code to}, and the rename rule
     * that keeps its value on every copy of the bot ({@link ParamsFitter#renameTarget}, 2026-11-04): the method renamed
     * when it still has its old name, each rule of the group that renamed something to {@code from} pointed at
     * {@code to}, a rule left naming its own target dropped, and the rule {@code from → to}, at the parameter's type,
     * in place of any rule called {@code from}. Unchanged when the group has neither name as a parameter.
     */
    @SuppressWarnings("unchecked")
    public static String renamedInGroup(String source, String group, String from, String to) {
        CompilationUnit unit = BotParser.syntax(source);
        AST ast = unit.getAST();
        TypeDeclaration type = typeDeclaration(unit, group);
        if (type == null) return source;
        MethodDeclaration parameter = parameterOf(type, from);
        if (parameter == null) parameter = parameterOf(type, to);
        if (parameter == null) return source;
        ASTRewrite rewrite = ASTRewrite.create(ast);
        if (parameter.getName().getIdentifier().equals(from)) {
            rewrite.set(parameter, MethodDeclaration.NAME_PROPERTY, ast.newSimpleName(to), null);
        }
        ListRewrite members = rewrite.getListRewrite(type, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        for (MethodDeclaration method : type.getMethods()) {
            Optional<String> target = ParamsFitter.renameTarget(method);
            if (target.isEmpty()) continue;
            String name = method.getName().getIdentifier();
            String now = target.get().equals(from) ? to : target.get();
            if (name.equals(from) || name.equals(now)) {
                members.remove(method, null);
            } else if (!now.equals(target.get())) {
                ReturnStatement returned = (ReturnStatement) method.getBody().statements().getFirst();
                rewrite.set(returned.getExpression(), MethodInvocation.NAME_PROPERTY, ast.newSimpleName(now), null);
            }
        }
        members.insertLast(renameRule(ast, group, from, to, parameter.getReturnType2()), null);
        String renamed = SourceFormatter.formatChanged(JavaLevel.of(rewrite.getAST()), source, AstRewriteHelper.applyRewrite(rewrite, source));
        CompilationUnit reparsed = BotParser.syntax(renamed);
        ASTRewrite importing = ASTRewrite.create(reparsed.getAST());
        addImports(reparsed, importing, List.of(Migrate.class.getCanonicalName()));
        return AstRewriteHelper.applyRewrite(importing, renamed);
    }

    /** {@code @Migrate(Group.class) static <type> from(@Migrate.Self Group self) { return self.to(); }} */
    @SuppressWarnings("unchecked")
    private static MethodDeclaration renameRule(AST ast, String group, String from, String to,
                                                org.eclipse.jdt.core.dom.Type type) {
        String migrate = Migrate.class.getSimpleName();
        SingleMemberAnnotation mark = ast.newSingleMemberAnnotation();
        mark.setTypeName(ast.newSimpleName(migrate));
        TypeLiteral owner = ast.newTypeLiteral();
        owner.setType(ast.newSimpleType(ast.newSimpleName(group)));
        mark.setValue(owner);
        MarkerAnnotation selfMark = ast.newMarkerAnnotation();
        selfMark.setTypeName(ast.newName(migrate + "." + Migrate.Self.class.getSimpleName()));
        SingleVariableDeclaration self = ast.newSingleVariableDeclaration();
        self.modifiers().add(selfMark);
        self.setType(ast.newSimpleType(ast.newSimpleName(group)));
        self.setName(ast.newSimpleName("self"));
        MethodInvocation call = ast.newMethodInvocation();
        call.setExpression(ast.newSimpleName("self"));
        call.setName(ast.newSimpleName(to));
        ReturnStatement returned = ast.newReturnStatement();
        returned.setExpression(call);
        Block body = ast.newBlock();
        body.statements().add(returned);
        MethodDeclaration rule = ast.newMethodDeclaration();
        rule.modifiers().add(mark);
        rule.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.STATIC_KEYWORD));
        rule.setReturnType2((org.eclipse.jdt.core.dom.Type) JavaLevel.copy(ast, type));
        rule.setName(ast.newSimpleName(from));
        rule.parameters().add(self);
        rule.setBody(body);
        return rule;
    }

    /** Whether the group {@code group} in {@code source} has the parameter {@code name}; a rule of that name is none. */
    static boolean hasParameter(String source, String group, String name) {
        TypeDeclaration type = typeDeclaration(BotParser.syntax(source), group);
        return type != null && parameterOf(type, name) != null;
    }

    /** Where the name of the parameter {@code name} of {@code group} starts in {@code unit}, or -1. */
    static int parameterStart(CompilationUnit unit, String group, String name) {
        TypeDeclaration type = unit == null ? null : typeDeclaration(unit, group);
        MethodDeclaration parameter = type == null ? null : parameterOf(type, name);
        return parameter == null ? -1 : parameter.getName().getStartPosition();
    }

    /** The parameter {@code name} of a group: its no-argument instance method of that name, or {@code null}. */
    private static MethodDeclaration parameterOf(TypeDeclaration type, String name) {
        for (MethodDeclaration method : type.getMethods()) {
            if (method.getName().getIdentifier().equals(name) && method.parameters().isEmpty()
                    && !Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }

    /**
     * Changes the field's declared type, and resets its value to the new type's fresh one.
     *
     * <p><b>The value is reset rather than converted.</b> There is no conversion — a {@code Duration} is not
     * a {@code Rect} in any sense — and the alternative is an initialiser that does not compile. The caller
     * is the one that tells the user, and marks the uses for review.
     *
     * <p>A type with no fresh value — a class the bot declares, whose placeholder would be a value nobody
     * chose — is written with <b>no initialiser at all</b>, which is legal Java for every type.
     */
    public static String retype(String source, ValueGrammar grammar, String className, String fieldName,
                                Type form) {
        if (!writable(grammar, form)) return source;
        JavaValue initializer = grammar.freshSpelling(form).orElse(null);
        return edit(source, className, fieldName, (unit, ast, rewrite, field, fragment) -> {
            Set<String> imports = new LinkedHashSet<>();
            org.eclipse.jdt.core.dom.Type type = ValueTypes.node(ast, form, imports).orElse(null);
            if (type == null) return;
            rewrite.set(field, FieldDeclaration.TYPE_PROPERTY, type, null);
            rewrite.set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY,
                    initializer == null ? null : initializer.copyInto(ast), null);
            if (initializer != null) imports.addAll(initializer.imports());
            addImports(unit, rewrite, List.copyOf(imports));
        });
    }

    /**
     * Sets, changes or removes {@code @Param} members — a blank value removes the member.
     *
     * <p>Removing rather than writing {@code category = ""} keeps the annotation saying only what the
     * author chose: the default is already the empty answer, and an annotation full of empty strings is a
     * declaration that reads as if six decisions were made when none were.
     */
    public static String setMembers(String source, String className, String fieldName,
                                    Map<String, String> members) {
        if (members == null || members.isEmpty()) return source;
        return edit(source, className, fieldName, (unit, ast, rewrite, field, fragment) -> {
            Annotation existing = JavaParameterSource.paramAnnotation(field);
            if (existing == null) return;
            NormalAnnotation normal = asNormal(ast, rewrite, field, existing);
            ListRewrite pairs = rewrite.getListRewrite(normal, NormalAnnotation.VALUES_PROPERTY);
            for (Map.Entry<String, String> entry : members.entrySet()) {
                MemberValuePair present = pairOf(normal, entry.getKey());
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    if (present != null) pairs.remove(present, null);
                    continue;
                }
                MemberValuePair replacement = ast.newMemberValuePair();
                replacement.setName(ast.newSimpleName(entry.getKey()));
                replacement.setValue(memberValue(ast, existing, entry.getKey(), entry.getValue()));
                if (present != null) {
                    pairs.replace(present, replacement, null);
                } else {
                    pairs.insertLast(replacement, null);
                }
            }
        });
    }

    /** The {@code @Param} members contract 0.14 removed (2026-10-29): rules about the value, not the field. */
    public static final Set<String> REMOVED_MEMBERS = Set.of("min", "max", "options");

    /**
     * {@code source} with {@link #REMOVED_MEMBERS} taken off every {@code @Param} in it, and the names of the
     * fields that lost one — the upgrade that lets a bot written before 0.14 compile again. Nothing else in the
     * file is touched; a file with none of them comes back as it was, with no names.
     */
    public static Stripped stripRemovedMembers(String source) {
        CompilationUnit unit = BotParser.syntax(source);
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        List<String> fields = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration field) {
                // True either way: an initializer's anonymous class may declare a field of its own.
                if (!(JavaParameterSource.paramAnnotation(field) instanceof NormalAnnotation normal)) return true;
                ListRewrite pairs = rewrite.getListRewrite(normal, NormalAnnotation.VALUES_PROPERTY);
                boolean any = false;
                for (Object each : normal.values()) {
                    MemberValuePair pair = (MemberValuePair) each;
                    if (!REMOVED_MEMBERS.contains(pair.getName().getIdentifier())) continue;
                    pairs.remove(pair, null);
                    any = true;
                }
                if (any) {
                    for (Object fragment : field.fragments()) {
                        fields.add(((VariableDeclarationFragment) fragment).getName().getIdentifier());
                    }
                }
                return true;
            }
        });
        return fields.isEmpty() ? new Stripped(source, List.of())
                : new Stripped(AstRewriteHelper.applyRewrite(rewrite, source), List.copyOf(fields));
    }

    /** What {@link #stripRemovedMembers} answers: the new source, and the fields it changed, in file order. */
    public record Stripped(String source, List<String> fields) {}

    /**
     * Appends a new {@code @Param public static} field to {@code className}, importing what its type names.
     *
     * <p>Appended rather than inserted in sorted order: a source file is the author's, and a window that
     * reordered their declarations to suit its own list would be rewriting more than the user asked for.
     * No {@code initializer} declares the field with none, which is legal Java for every type — a class the
     * bot declares has no fresh value Studio may invent.
     *
     * <p><b>The declaration is a tree</b> (2026-09-24), and it is laid out in a second step:
     * {@code ASTRewrite} flattens a body declaration it generated onto one line with no spacing
     * ({@code @Param public static String label="hello";}), and the field a person just added is the one they
     * are most likely to look at. {@link SourceFormatter#formatChanged} lays out the lines the insert wrote and
     * nothing else; the imports are added after, so the region it formats is the field alone.
     */
    public static String add(String source, ValueGrammar grammar, String className, String fieldName,
                             Type form, JavaValue initializer, String category, String description) {
        if (!writable(grammar, form) || fieldName == null || fieldName.isBlank()) return source;
        CompilationUnit unit = BotParser.syntax(source);
        TypeDeclaration target = typeDeclaration(unit, className);
        if (target == null || declares(target, fieldName)) return source;

        AST ast = unit.getAST();
        Set<String> imports = new LinkedHashSet<>();
        // The annotation is written @Param, so the file must import it — unless it already imports a class of
        // that simple name, which a second import would collide with; the author's fields beside it use that.
        if (!importsSimpleName(unit, Param.class.getSimpleName())) imports.add(Param.class.getCanonicalName());
        org.eclipse.jdt.core.dom.Type type = ValueTypes.node(ast, form, imports).orElse(null);
        if (type == null) return source;
        VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
        fragment.setName(ast.newSimpleName(fieldName));
        if (initializer != null) {
            fragment.setInitializer(initializer.copyInto(ast));
            imports.addAll(initializer.imports());
        }
        FieldDeclaration field = ast.newFieldDeclaration(fragment);
        field.setType(type);
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> modifiers = field.modifiers();
        modifiers.add(annotation(ast, category, description));
        @SuppressWarnings("unchecked")
        List<Modifier> keywords = ast.newModifiers(Modifier.PUBLIC | Modifier.STATIC);
        modifiers.addAll(keywords);

        ASTRewrite rewrite = ASTRewrite.create(ast);
        rewrite.getListRewrite(target, TypeDeclaration.BODY_DECLARATIONS_PROPERTY).insertLast(field, null);
        String declared = SourceFormatter.formatChanged(JavaLevel.of(rewrite.getAST()), source, AstRewriteHelper.applyRewrite(rewrite, source));

        CompilationUnit reparsed = BotParser.syntax(declared);
        ASTRewrite importing = ASTRewrite.create(reparsed.getAST());
        addImports(reparsed, importing, List.copyOf(imports));
        return AstRewriteHelper.applyRewrite(importing, declared);
    }

    private static boolean importsSimpleName(CompilationUnit unit, String simple) {
        for (Object each : unit.imports()) {
            org.eclipse.jdt.core.dom.ImportDeclaration declaration = (org.eclipse.jdt.core.dom.ImportDeclaration) each;
            if (declaration.isStatic() || declaration.isOnDemand()) continue;
            Name name = declaration.getName();
            if (name instanceof org.eclipse.jdt.core.dom.QualifiedName qualified
                    && qualified.getName().getIdentifier().equals(simple)) {
                return true;
            }
        }
        return false;
    }

    /** {@code @Param}, with the members the author chose and no others. */
    private static Annotation annotation(AST ast, String category, String description) {
        List<MemberValuePair> members = new ArrayList<>();
        if (category != null && !category.isBlank()) members.add(member(ast, "category", category));
        if (description != null && !description.isBlank()) members.add(member(ast, "description", description));
        if (members.isEmpty()) {
            MarkerAnnotation marker = ast.newMarkerAnnotation();
            marker.setTypeName(ast.newSimpleName(Param.class.getSimpleName()));
            return marker;
        }
        NormalAnnotation normal = ast.newNormalAnnotation();
        normal.setTypeName(ast.newSimpleName(Param.class.getSimpleName()));
        @SuppressWarnings("unchecked")
        List<MemberValuePair> values = normal.values();
        values.addAll(members);
        return normal;
    }

    private static MemberValuePair member(AST ast, String name, String text) {
        MemberValuePair pair = ast.newMemberValuePair();
        pair.setName(ast.newSimpleName(name));
        StringLiteral literal = ast.newStringLiteral();
        literal.setLiteralValue(text);
        pair.setValue(literal);
        return pair;
    }

    /**
     * Removes the field's declaration — and the whole statement when it was the only fragment in it.
     *
     * <p>Nothing is done about the <em>uses</em>: that is the caller's, because what a use should become is
     * a judgement (a literal default plus a review mark) rather than a rewrite, and because a removal whose
     * uses were silently rewritten is a bot that compiles and behaves differently.
     */
    public static String remove(String source, String className, String fieldName) {
        CompilationUnit unit = BotParser.syntax(source);
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        boolean[] found = {false};

        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration field) {
                if (!className.equals(JavaParameterSource.enclosingTypeName(field))) return false;
                for (Object each : field.fragments()) {
                    VariableDeclarationFragment fragment = (VariableDeclarationFragment) each;
                    if (!fragment.getName().getIdentifier().equals(fieldName)) continue;
                    found[0] = true;
                    if (field.fragments().size() == 1) {
                        rewrite.remove(field, null);
                    } else {
                        rewrite.remove(fragment, null);
                    }
                }
                return false;
            }
        });
        return found[0] ? AstRewriteHelper.applyRewrite(rewrite, source) : source;
    }

    // ---- plumbing ---------------------------------------------------------------------------------------

    /** What an edit does to one field; {@code ast} and {@code rewrite} are {@code unit}'s own. */
    @FunctionalInterface
    private interface FieldEdit {
        void apply(CompilationUnit unit, AST ast, ASTRewrite rewrite, FieldDeclaration field,
                   VariableDeclarationFragment fragment);
    }

    /** Each import into {@code unit}'s rewrite, skipping one already there — {@code ImportManager}'s rule. */
    static void addImports(CompilationUnit unit, ASTRewrite rewrite, List<String> imports) {
        if (imports == null) return;
        for (String name : imports) ImportManager.addImport(unit, rewrite, name);
    }

    /** Finds {@code className.fieldName}, applies {@code edit}, and answers the rewritten source. */
    private static String edit(String source, String className, String fieldName, FieldEdit edit) {
        CompilationUnit unit = BotParser.syntax(source);
        AST ast = unit.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        boolean[] found = {false};

        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration field) {
                if (!className.equals(JavaParameterSource.enclosingTypeName(field))) return false;
                for (Object each : field.fragments()) {
                    VariableDeclarationFragment fragment = (VariableDeclarationFragment) each;
                    if (!fragment.getName().getIdentifier().equals(fieldName)) continue;
                    found[0] = true;
                    edit.apply(unit, ast, rewrite, field, fragment);
                }
                return false;
            }
        });
        return found[0] ? AstRewriteHelper.applyRewrite(rewrite, source) : source;
    }

    /**
     * The annotation as a {@link NormalAnnotation} that members can be added to — replacing a bare
     * {@code @Param} with {@code @Param()} in the rewrite when it was a marker.
     *
     * <p>The replacement is registered before any member is added, so the {@link ListRewrite} below is
     * taken over the node that will actually be written.
     */
    private static NormalAnnotation asNormal(AST ast, ASTRewrite rewrite, FieldDeclaration field,
                                             Annotation existing) {
        if (existing instanceof NormalAnnotation normal) return normal;
        NormalAnnotation normal = ast.newNormalAnnotation();
        // The name as the author wrote it: whichever import or qualification made it the annotation still does.
        normal.setTypeName((Name) JavaLevel.copy(ast, existing.getTypeName()));
        rewrite.getListRewrite(field, FieldDeclaration.MODIFIERS2_PROPERTY)
                .replace(existing, normal, null);
        return normal;
    }

    /**
     * A member's value as the author would write it: a string literal, except a visibility the annotation has
     * a constant for, which is written {@code Param.PUBLIC}.
     *
     * <p>The annotation declares {@code PUBLIC} and {@code EDITOR} precisely so nobody types the string, and
     * {@link JavaParameterSource} reads the constant back as the same id — so the field this window writes
     * reads like the ones beside it rather than like a tool wrote it. The qualifier is the annotation's own
     * name <i>as written</i>, so a file that imports {@code Param} and one that spells it out both compile.
     */
    private static Expression memberValue(AST ast, Annotation annotation, String member, String value) {
        if (member.equals("min") || member.equals("max")) {
            // Numbers since 2026-09-22, when @Param's bounds became double: a string here would not compile.
            String digits = value.strip();
            boolean negative = digits.startsWith("-");
            org.eclipse.jdt.core.dom.NumberLiteral number =
                    ast.newNumberLiteral(negative ? digits.substring(1) : digits);
            if (!negative) return number;
            org.eclipse.jdt.core.dom.PrefixExpression minus = ast.newPrefixExpression();
            minus.setOperator(org.eclipse.jdt.core.dom.PrefixExpression.Operator.MINUS);
            minus.setOperand(number);
            return minus;
        }
        if (member.equals("visibility")) {
            String constant = value.equals(Visibility.PUBLIC.id()) ? "PUBLIC"
                    : value.equals(Visibility.EDITOR_ONLY.id()) ? "EDITOR" : null;
            if (constant != null) {
                return ast.newQualifiedName(ast.newName(annotation.getTypeName().getFullyQualifiedName()),
                        ast.newSimpleName(constant));
            }
        }
        StringLiteral literal = ast.newStringLiteral();
        literal.setLiteralValue(value);
        return literal;
    }

    private static MemberValuePair pairOf(NormalAnnotation annotation, String name) {
        for (Object each : annotation.values()) {
            MemberValuePair pair = (MemberValuePair) each;
            if (pair.getName().getIdentifier().equals(name)) return pair;
        }
        return null;
    }

    /**
     * Whether a field of {@code form} may be declared: every leaf is one the grammar reads or a plugin
     * declares. A type with an unknown leaf anywhere makes every caller decline rather than write a field
     * naming a class that does not exist. The type itself is written as a tree ({@link ValueTypes#node}).
     */
    private static boolean writable(ValueGrammar grammar, Type form) {
        return form != null && grammar != null && grammar.known(form) && !(form instanceof ValueTypes.Unknown);
    }

    /** The named top-level or nested type declaration, or {@code null}. */
    private static TypeDeclaration typeDeclaration(CompilationUnit unit, String className) {
        List<TypeDeclaration> found = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration type) {
                if (type.getName().getIdentifier().equals(className)) found.add(type);
                return true;
            }
        });
        return found.isEmpty() ? null : found.getFirst();
    }

    private static boolean declares(TypeDeclaration type, String fieldName) {
        for (Object each : type.bodyDeclarations()) {
            if (!(each instanceof FieldDeclaration field)) continue;
            for (Object fragment : field.fragments()) {
                if (((VariableDeclarationFragment) fragment).getName().getIdentifier().equals(fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
