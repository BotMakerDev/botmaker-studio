package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.parser.helpers.SourceFormatter;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.ProjectConfig;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import javax.lang.model.SourceVersion;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The class a plugin's {@code @Managed} values live in, written by the host when a project has none
 * ({@code PluginValues.create}).
 *
 * <p>Written once and then the user's, as a file a template shipped is: {@code ProjectWrites.create} never
 * overwrites. The content is the least that compiles — the class, and per method-shaped value one
 * {@code @Managed} method returning the value's {@code initial}, or else its type's fresh value, written by
 * the host grammar. A type-level value is an
 * empty class carrying the annotation. Nothing here reads or merges a file that exists.
 *
 * <p><b>A tree, since 2026-09-27</b>, as every other piece of Java the host writes: the unit is built from JDT
 * nodes — the return type from {@link ValueTypes#node}, the value the grammar's own node — and laid out by
 * {@link SourceFormatter}. It was joined as text, the value included, until then.
 */
public final class ManagedHolders {

    /** The annotation every holder names, by the class the contract ships it as. */
    static final String MANAGED = "com.botmaker.plugin.api.managed.Managed";

    private ManagedHolders() {}

    /** What {@link #plan} decided: the file and its source, or why there is none. */
    public sealed interface Plan {
        /** The holder to write. */
        record Write(Path file, String relative, String source) implements Plan {}

        /** No holder can be written, and the sentence to show. */
        record Refused(String reason) implements Plan {}
    }

    /**
     * The holder for {@code value}, declared by the plugin {@code pluginId} beside {@code declared}.
     *
     * @param declared every value the same plugin declares; those sharing {@code value}'s holder go in the
     *                 same class
     */
    public static Plan plan(ProjectConfig config, String pluginId, ManagedValue<?> value,
                            List<ManagedValue<?>> declared,
                            ValueGrammar grammar) {
        String holder = value.holder();
        if (holder == null || !SourceVersion.isName(holder) || holder.contains(".")) {
            return new Plan.Refused("\"" + value.id() + "\" is not a value this project can be given a file for.");
        }
        String segment = segment(pluginId);
        if (segment == null) {
            return new Plan.Refused("The plugin " + pluginId + " has no name a package can be spelled with.");
        }
        String pkg = config.mainPackage() + ".plugins." + segment;
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        TreeSet<String> imports = new TreeSet<>();
        imports.add(MANAGED);

        TypeDeclaration type = ast.newTypeDeclaration();
        type.setName(ast.newSimpleName(holder));
        type.setJavadoc(javadoc(ast, pluginId));
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> typeModifiers = type.modifiers();
        boolean typeLevel = value.isOpenSet();
        if (typeLevel) typeModifiers.add(managed(ast, value.id()));
        typeModifiers.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        typeModifiers.add(ast.newModifier(Modifier.ModifierKeyword.FINAL_KEYWORD));
        @SuppressWarnings("unchecked")
        List<BodyDeclaration> members = type.bodyDeclarations();
        if (!typeLevel) {
            for (ManagedValue<?> sibling : declared) {
                if (!holder.equals(sibling.holder()) || sibling.isOpenSet()) continue;
                MethodDeclaration method = method(ast, sibling, grammar, imports);
                if (method == null) {
                    return new Plan.Refused("BotMaker cannot write a starting value for \"" + sibling.id()
                            + "\": no plugin in this project declares " + ValueTypes.sourceName(sibling.type())
                            + ".");
                }
                members.add(method);
            }
        }
        MethodDeclaration constructor = ast.newMethodDeclaration();
        constructor.setConstructor(true);
        constructor.setName(ast.newSimpleName(holder));
        constructor.setBody(ast.newBlock());
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> constructorModifiers = constructor.modifiers();
        constructorModifiers.add(ast.newModifier(Modifier.ModifierKeyword.PRIVATE_KEYWORD));
        members.add(constructor);

        CompilationUnit unit = ast.newCompilationUnit();
        PackageDeclaration declaration = ast.newPackageDeclaration();
        declaration.setName(ast.newName(pkg));
        unit.setPackage(declaration);
        @SuppressWarnings("unchecked")
        List<ImportDeclaration> importList = unit.imports();
        for (String name : imports) {
            if (isImplicit(name, pkg)) continue;
            ImportDeclaration each = ast.newImportDeclaration();
            each.setName(ast.newName(name));
            importList.add(each);
        }
        @SuppressWarnings("unchecked")
        List<AbstractTypeDeclaration> types = unit.types();
        types.add(type);

        String relative = "plugins/" + segment + "/" + holder + ".java";
        return new Plan.Write(config.mainPackageDir().resolve("plugins").resolve(segment).resolve(holder + ".java"),
                relative, laidOut(unit));
    }

    /**
     * The unit as source, laid out by the formatter — which leaves comments alone, so the space the flattener
     * writes after {@code /**} is trimmed here with every other line's trailing blanks.
     */
    private static String laidOut(CompilationUnit unit) {
        StringBuilder out = new StringBuilder();
        SourceFormatter.format(unit.toString()).lines().forEach(line -> out.append(line.stripTrailing()).append('\n'));
        return out.toString();
    }

    /** The holder's doc comment: whose values these are, and that the file is the user's from now on. */
    @SuppressWarnings("unchecked")
    private static Javadoc javadoc(AST ast, String pluginId) {
        Javadoc javadoc = ast.newJavadoc();
        // One untagged element per line: the flattener starts each on its own " * " line.
        for (String line : List.of("Values " + pluginId + " keeps in step with its own windows.",
                "<p>Written by BotMaker because this project had none, and <b>yours from that moment</b>:",
                "it is never rewritten. BotMaker changes the expression a")) {
            TagElement element = ast.newTagElement();
            element.fragments().add(text(ast, line));
            javadoc.tags().add(element);
        }
        TagElement code = ast.newTagElement();
        code.setTagName(TagElement.TAG_CODE);
        code.fragments().add(text(ast, " @Managed"));
        TagElement last = (TagElement) javadoc.tags().getLast();
        last.fragments().add(code);
        last.fragments().add(text(ast, " method returns and nothing else."));
        return javadoc;
    }

    private static TextElement text(AST ast, String text) {
        TextElement element = ast.newTextElement();
        element.setText(text);
        return element;
    }

    /** {@code @Managed("id")}. */
    private static SingleMemberAnnotation managed(AST ast, String id) {
        SingleMemberAnnotation annotation = ast.newSingleMemberAnnotation();
        annotation.setTypeName(ast.newSimpleName("Managed"));
        StringLiteral literal = ast.newStringLiteral();
        literal.setLiteralValue(id);
        annotation.setValue(literal);
        return annotation;
    }

    /**
     * Every holder the project lacks, one plan per holder, in plugin then declaration order.
     *
     * <p>A holder is lacking only when none of its values is declared anywhere in the bot
     * ({@code declaredIds}), no source file already carries its class name ({@code fileNames}, so a
     * {@code Sdk.java} the user moved to another package still counts), and its file is not on disk. What
     * comes back is written by the caller; nothing here touches the filesystem but to look.
     *
     * @param byPlugin    each plugin's id and every value it declares
     * @param declaredIds the ids the bot's sources already declare
     * @param fileNames   the file names of every bot source, {@code Sdk.java} and the like
     */
    public static List<Plan> missing(ProjectConfig config, java.util.Map<String, List<ManagedValue<?>>> byPlugin,
                                     java.util.Set<String> declaredIds, java.util.Set<String> fileNames,
                                     ValueGrammar grammar) {
        List<Plan> out = new ArrayList<>();
        java.util.Set<String> planned = new java.util.HashSet<>(fileNames);
        byPlugin.forEach((pluginId, declared) -> {
            java.util.Set<String> holders = new java.util.LinkedHashSet<>();
            for (ManagedValue<?> value : declared) {
                if (value != null && value.holder() != null) holders.add(value.holder());
            }
            for (String holder : holders) {
                List<ManagedValue<?>> held = declared.stream()
                        .filter(v -> v != null && holder.equals(v.holder())).toList();
                if (planned.contains(holder + ".java")) continue;
                if (held.stream().anyMatch(v -> declaredIds.contains(v.id()))) continue;
                Plan plan = plan(config, pluginId, held.getFirst(), declared, grammar);
                if (plan instanceof Plan.Write write && java.nio.file.Files.exists(write.file())) continue;
                planned.add(holder + ".java");
                out.add(plan);
            }
        });
        return List.copyOf(out);
    }

    /**
     * One {@code @Managed} method returning its value — the grammar's node, copied in — its imports added to
     * {@code imports}; null when the type has no node or no fresh value.
     */
    private static MethodDeclaration method(AST ast, ManagedValue<?> value, ValueGrammar grammar,
                                            TreeSet<String> imports) {
        Type type = value.type();
        JavaValue fresh = (value.initial() != null ? grammar.spell(type, value.initial())
                : grammar.freshSpelling(type)).orElse(null);
        if (fresh == null) return null;
        Set<String> needed = new LinkedHashSet<>();
        org.eclipse.jdt.core.dom.Type returnType = ValueTypes.node(ast, type, needed).orElse(null);
        if (returnType == null) return null;

        MethodDeclaration method = ast.newMethodDeclaration();
        method.setName(ast.newSimpleName(methodName(value.id())));
        method.setReturnType2(returnType);
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> modifiers = method.modifiers();
        modifiers.add(managed(ast, value.id()));
        modifiers.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        modifiers.add(ast.newModifier(Modifier.ModifierKeyword.STATIC_KEYWORD));
        Block body = ast.newBlock();
        ReturnStatement returned = ast.newReturnStatement();
        returned.setExpression(fresh.copyInto(ast));
        @SuppressWarnings("unchecked")
        List<Statement> statements = body.statements();
        statements.add(returned);
        method.setBody(body);

        imports.addAll(needed);
        imports.addAll(fresh.imports());
        return method;
    }

    /** The id as a method name: itself when it is one, else its letters and digits in lower camel case. */
    static String methodName(String id) {
        if (SourceVersion.isIdentifier(id) && !SourceVersion.isKeyword(id)) return id;
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : id.toCharArray()) {
            if (!Character.isLetterOrDigit(c)) {
                upper = out.length() > 0;
                continue;
            }
            if (out.isEmpty() && Character.isDigit(c)) out.append("value");
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        String name = out.isEmpty() ? "value" : out.toString();
        return SourceVersion.isKeyword(name) ? name + "Value" : name;
    }

    /**
     * The last segment of a plugin id, lower-cased, or null when it is no package name — the folder under
     * {@code plugins/} a plugin's files are in, which {@code PluginFiles} reads back.
     */
    public static String segment(String pluginId) {
        if (pluginId == null) return null;
        String last = pluginId.substring(pluginId.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return SourceVersion.isIdentifier(last) && !SourceVersion.isKeyword(last) ? last : null;
    }

    private static boolean isImplicit(String name, String pkg) {
        int dot = name.lastIndexOf('.');
        String owner = dot < 0 ? "" : name.substring(0, dot);
        return owner.equals("java.lang") || owner.equals(pkg);
    }
}
