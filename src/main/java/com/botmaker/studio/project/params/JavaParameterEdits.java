package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.plugin.api.value.Visibility;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
     * Replaces the value of {@code className.fieldName} with the Java for {@code value}.
     *
     * <p>The initialiser is the catalog's ({@code ValueCatalog.initializer}), which is the same call the
     * canvas makes — so a value written here and a value written there are the same text, and reading it
     * back is the codec's {@code wireOfLiteral}, which is what makes the round trip a fixed point.
     */
    public static String setValue(String source, ValueCatalog catalog, String className, String fieldName,
                                  ValueChoice choice, List<String> value) {
        String initializer = catalog.initializer(choice, value).orElse(null);
        if (initializer == null) return source;            // an unknown type has no source spelling
        return edit(source, className, fieldName, (ast, rewrite, field, fragment) ->
                rewrite.set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY,
                        expression(ast, rewrite, initializer), null));
    }

    /**
     * Renames the field, and repoints every {@code className.oldName} written anywhere in {@code source}.
     *
     * <p>The uses are repointed by name because this parser has no bindings: {@code Parameters.restBetween}
     * is matched as a qualified name whose qualifier is the class and whose name is the field, which is
     * exactly what a bot writes. A bare {@code restBetween} inside the declaring class is repointed too —
     * it resolves to the same field — and a {@code restBetween} that is somebody else's local variable is
     * not, because it is not a field access of this class.
     */
    public static String rename(String source, String className, String fieldName, String newName) {
        if (newName == null || newName.isBlank() || newName.equals(fieldName)) return source;
        CompilationUnit unit = JavaParameterSource.parse(source);
        AST ast = unit.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        boolean[] found = {false};

        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (!name.getIdentifier().equals(fieldName)) return true;
                if (!isReferenceTo(name, className, fieldName)) return true;
                found[0] = true;
                rewrite.replace(name, ast.newSimpleName(newName), null);
                return true;
            }
        });
        return found[0] ? AstRewriteHelper.applyRewrite(rewrite, source) : source;
    }

    /**
     * Changes the field's declared type, and resets its value to the new type's default.
     *
     * <p><b>The value is reset rather than converted.</b> There is no conversion — a {@code Duration} is not
     * a {@code Rect} in any sense a codec could express — and the alternative is an initialiser that does
     * not compile. The caller is the one that tells the user, and marks the uses for review.
     */
    public static String retype(String source, ValueCatalog catalog, String className, String fieldName,
                                ValueChoice choice) {
        String typeName = typeName(choice);
        String initializer = catalog.initializer(choice, List.of(catalog.defaultItem(choice.type().id())))
                .orElse(null);
        if (typeName == null || initializer == null) return source;
        return edit(source, className, fieldName, (ast, rewrite, field, fragment) -> {
            rewrite.set(field, FieldDeclaration.TYPE_PROPERTY, type(ast, rewrite, typeName), null);
            rewrite.set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY,
                    expression(ast, rewrite, initializer), null);
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
        return edit(source, className, fieldName, (ast, rewrite, field, fragment) -> {
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

    /**
     * Sets {@code @Param(options = {...})}, or removes the member when the list is empty.
     *
     * <p>Separate from {@link #setMembers} because this member is an array and every other one is a string;
     * one method taking {@code Object} would be the kind of signature that compiles for a caller passing
     * the wrong shape.
     */
    public static String setOptions(String source, String className, String fieldName,
                                    List<String> options) {
        return edit(source, className, fieldName, (ast, rewrite, field, fragment) -> {
            Annotation existing = JavaParameterSource.paramAnnotation(field);
            if (existing == null) return;
            NormalAnnotation normal = asNormal(ast, rewrite, field, existing);
            ListRewrite pairs = rewrite.getListRewrite(normal, NormalAnnotation.VALUES_PROPERTY);
            MemberValuePair present = pairOf(normal, "options");
            if (options == null || options.isEmpty()) {
                if (present != null) pairs.remove(present, null);
                return;
            }
            ArrayInitializer array = ast.newArrayInitializer();
            for (String option : options) {
                StringLiteral literal = ast.newStringLiteral();
                literal.setLiteralValue(option);
                @SuppressWarnings("unchecked")
                List<Expression> expressions = array.expressions();
                expressions.add(literal);
            }
            MemberValuePair replacement = ast.newMemberValuePair();
            replacement.setName(ast.newSimpleName("options"));
            replacement.setValue(array);
            if (present != null) {
                pairs.replace(present, replacement, null);
            } else {
                pairs.insertLast(replacement, null);
            }
        });
    }

    /**
     * Appends a new {@code @Param public static} field to {@code className}.
     *
     * <p>Appended rather than inserted in sorted order: a source file is the author's, and a window that
     * reordered their declarations to suit its own list would be rewriting more than the user asked for.
     */
    public static String add(String source, ValueCatalog catalog, String className, String fieldName,
                             ValueChoice choice, List<String> value, String category, String description) {
        String typeName = typeName(choice);
        String initializer = catalog.initializer(choice, value).orElse(null);
        if (typeName == null || initializer == null || fieldName == null || fieldName.isBlank()) {
            return source;
        }
        CompilationUnit unit = JavaParameterSource.parse(source);
        TypeDeclaration target = typeDeclaration(unit, className);
        if (target == null || declares(target, fieldName)) return source;

        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        // The declaration is written as TEXT and inserted as a placeholder, rather than built out of AST
        // nodes: ASTRewrite's flattener emits a node it generated with no spacing at all
        // (`public static String label="hello";`, all on one line), and the field a person just added is
        // the one they are most likely to look at. A placeholder is copied through exactly as written.
        String declaration = declaration(typeName, fieldName, initializer, category, description);
        rewrite.getListRewrite(target, TypeDeclaration.BODY_DECLARATIONS_PROPERTY)
                .insertLast(rewrite.createStringPlaceholder(declaration, ASTNode.FIELD_DECLARATION), null);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    /** The source of a new parameter, spelled the way the rest of a bot's file is. */
    private static String declaration(String typeName, String fieldName, String initializer,
                                      String category, String description) {
        List<String> members = new ArrayList<>();
        if (category != null && !category.isBlank()) members.add("category = " + quote(category));
        if (description != null && !description.isBlank()) {
            members.add("description = " + quote(description));
        }
        String annotation = members.isEmpty() ? "@" + JavaParameterSource.ANNOTATION
                : "@" + JavaParameterSource.ANNOTATION + "(" + String.join(", ", members) + ")";
        return annotation + "\npublic static " + typeName + " " + fieldName + " = " + initializer + ";";
    }

    /** A Java string literal. The members written here are ours, but an author's text is not. */
    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /**
     * Removes the field's declaration — and the whole statement when it was the only fragment in it.
     *
     * <p>Nothing is done about the <em>uses</em>: that is the caller's, because what a use should become is
     * a judgement (a literal default plus a review mark) rather than a rewrite, and because a removal whose
     * uses were silently rewritten is a bot that compiles and behaves differently.
     */
    public static String remove(String source, String className, String fieldName) {
        CompilationUnit unit = JavaParameterSource.parse(source);
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

    /** What an edit does to one field; {@code ast} and {@code rewrite} are the unit's own. */
    @FunctionalInterface
    private interface FieldEdit {
        void apply(AST ast, ASTRewrite rewrite, FieldDeclaration field, VariableDeclarationFragment fragment);
    }

    /** Finds {@code className.fieldName}, applies {@code edit}, and answers the rewritten source. */
    private static String edit(String source, String className, String fieldName, FieldEdit edit) {
        CompilationUnit unit = JavaParameterSource.parse(source);
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
                    edit.apply(ast, rewrite, field, fragment);
                }
                return false;
            }
        });
        return found[0] ? AstRewriteHelper.applyRewrite(rewrite, source) : source;
    }

    /**
     * Whether this name is a reference to {@code className.fieldName}.
     *
     * <p>Three shapes count and nothing else: the declaration itself, a qualified {@code Class.name}, and a
     * bare {@code name} written inside the declaring class. The last one is deliberately narrow — a bare
     * name elsewhere is somebody else's variable, and renaming it would be a refactor nobody asked for.
     */
    private static boolean isReferenceTo(SimpleName name, String className, String fieldName) {
        ASTNode parent = name.getParent();
        if (parent instanceof VariableDeclarationFragment fragment && fragment.getName() == name) {
            return className.equals(JavaParameterSource.enclosingTypeName(fragment));
        }
        if (parent instanceof org.eclipse.jdt.core.dom.QualifiedName qualified) {
            return qualified.getName() == name && qualified.getQualifier().toString().equals(className);
        }
        if (parent instanceof org.eclipse.jdt.core.dom.FieldAccess access) {
            return access.getName() == name && className.equals(access.getExpression().toString());
        }
        if (parent instanceof org.eclipse.jdt.core.dom.MethodInvocation invocation
                && invocation.getName() == name) {
            return false;                                   // a method call that happens to share the name
        }
        return className.equals(JavaParameterSource.enclosingTypeName(name));
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
        normal.setTypeName(ast.newSimpleName(JavaParameterSource.ANNOTATION));
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
     * The Java type a choice is written as — {@code List<Rect>} for a list shape.
     *
     * <p>Null for a type nothing registers, which is what makes every caller decline rather than write a
     * field naming a class that does not exist.
     */
    private static String typeName(ValueChoice choice) {
        if (choice == null || !choice.type().known()) return null;
        String java = choice.type().javaName();
        if (java == null || java.isBlank()) return null;
        return choice.isList() ? "java.util.List<" + boxed(java) + ">" : java;
    }

    /** A list's element type is a reference type: {@code List<int>} does not compile. */
    private static String boxed(String java) {
        return switch (java) {
            case "int" -> "Integer";
            case "long" -> "Long";
            case "double" -> "Double";
            case "float" -> "Float";
            case "boolean" -> "Boolean";
            case "char" -> "Character";
            case "byte" -> "Byte";
            case "short" -> "Short";
            default -> java;
        };
    }

    /** Java source as an expression node, placed into the rewrite verbatim. */
    private static Expression expression(AST ast, ASTRewrite rewrite, String java) {
        return (Expression) rewrite.createStringPlaceholder(java, ASTNode.SIMPLE_NAME);
    }

    /** Java source as a type node, placed into the rewrite verbatim. */
    private static org.eclipse.jdt.core.dom.Type type(AST ast, ASTRewrite rewrite, String java) {
        return (org.eclipse.jdt.core.dom.Type)
                rewrite.createStringPlaceholder(java, ASTNode.SIMPLE_TYPE);
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
