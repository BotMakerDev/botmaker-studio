package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.PluginState;
import com.botmaker.plugin.api.params.Param;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.java.grammar.BotAnnotation;
import com.botmaker.plugin.api.source.ManagedMethod;
import com.botmaker.plugin.api.source.ManagedOpenSet;
import com.botmaker.plugin.api.source.ManagedParam;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.java.grammar.SourceFormatter;
import com.botmaker.studio.plugin.DuplicatePlugins;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.java.grammar.ValueTypes;
import com.botmaker.studio.project.ProjectConfig;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NormalAnnotation;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The class a plugin's managed values live in, written by the host when a project has none
 * ({@code PluginValues.create}).
 *
 * <p>Written once and then the user's, as a file a template shipped is: {@code ProjectWrites.create} never
 * overwrites. The content is the least that compiles — the class, and per method-shaped value one method
 * marked with the plugin's annotation returning the value's {@code initial}, or else its type's fresh value,
 * written by the host grammar. A type-level value is an empty class carrying the annotation, or for an enum set
 * an empty enum carrying it and implementing the set's element type. Nothing here
 * reads or merges a file that exists.
 *
 * <p><b>A tree, since 2026-09-27</b>, as every other piece of Java the host writes: the unit is built from JDT
 * nodes — the return type from {@link ValueTypes#node}, the value the grammar's own node — and laid out by
 * {@link SourceFormatter}. It was joined as text, the value included, until then.
 */
public final class ManagedHolders {

    private ManagedHolders() {}

    /** What {@link #plan} decided: the file and its source, or why there is none. */
    public sealed interface Plan {
        /** The holder to write. */
        record Write(Path file, String relative, String source) implements Plan {}

        /** No holder can be written, and the sentence to show. */
        record Refused(String reason) implements Plan {}
    }

    /**
     * The holder for {@code value}, declared by the plugin {@code plugin} beside {@code declared}.
     *
     * @param declared every value the same plugin declares; those sharing {@code value}'s holder go in the
     *                 same class
     */
    public static Plan plan(ProjectConfig config, PluginCoordinate plugin, ManagedValue<?> value,
                            List<ManagedValue<?>> declared,
                            ValueGrammar grammar) {
        String holder = value.holder();
        if (holder == null || !SourceVersion.isName(holder) || holder.contains(".")) {
            return new Plan.Refused(ManagedIds.spelled(value.id(), List.of(value))
                                    + " is not a value this project can be given a file for.");
        }
        String segment = segment(plugin);
        if (segment == null) {
            return new Plan.Refused("The plugin " + plugin + " has no name a package can be spelled with.");
        }
        String folder = segment.replace('.', '/');
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        TreeSet<String> imports = new TreeSet<>();
        // A parameter is a method of its plugin's group in params/ (2026-11-04), never a field of the holder.
        if (value instanceof ManagedParam<?>) {
            String pkg = config.parametersPackage() + "." + segment;
            TypeDeclaration group = groupClass(ast, plugin, holder, declared, grammar, imports);
            if (group == null) {
                return new Plan.Refused("BotMaker cannot write " + holder + "'s parameters: no plugin in this"
                        + " project declares their types.");
            }
            return new Plan.Write(config.parametersPackageDir().resolve(folder).resolve(holder + ".java"),
                    "params/" + folder + "/" + holder + ".java", unit(ast, pkg, imports, group));
        }
        String pkg = config.mainPackage() + ".plugins." + segment;
        AbstractTypeDeclaration written;
        try {
            written = value instanceof ManagedOpenSet.OneEnum<?> ? enumHolder(ast, plugin, value, imports)
                    : classHolder(ast, plugin, value, declared, grammar, imports);
        } catch (Unwritable refused) {
            return new Plan.Refused(refused.getMessage());
        }
        if (written == null) {
            return new Plan.Refused("BotMaker cannot write " + holder + ": no plugin in this project declares"
                    + " what it holds.");
        }
        String relative = "plugins/" + folder + "/" + holder + ".java";
        return new Plan.Write(config.mainPackageDir().resolve("plugins").resolve(folder).resolve(holder + ".java"),
                relative, unit(ast, pkg, imports, written));
    }

    /** One file's unit — its package, the imports it needs and its one type — as source. */
    public static String unit(AST ast, String pkg, Set<String> imports, AbstractTypeDeclaration written) {
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
        types.add(written);
        return laidOut(unit);
    }

    /**
     * A plugin's group of parameters (2026-11-04): {@code @Param public class Sdk}, each of its parameters sharing
     * {@code holder} one marked method returning its first value. A parameter nobody can spell costs only itself;
     * null when none can be written.
     */
    private static TypeDeclaration groupClass(AST ast, PluginCoordinate plugin, String holder,
                                              List<ManagedValue<?>> declared, ValueGrammar grammar,
                                              TreeSet<String> imports) {
        TypeDeclaration type = ast.newTypeDeclaration();
        type.setName(ast.newSimpleName(holder));
        type.setJavadoc(javadoc(ast, List.of("Parameters of " + plugin + " for this bot, each one method, whole: its"
                        + " name, its type and,",
                "as what it returns, its value on this computer. This file is never versioned; its starting copy",
                "is, and <i>Make this the starting value</i> is what changes that.")));
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> modifiers = type.modifiers();
        MarkerAnnotation param = ast.newMarkerAnnotation();
        param.setTypeName(ast.newSimpleName(Param.class.getSimpleName()));
        modifiers.add(param);
        modifiers.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        imports.add(Param.class.getCanonicalName());
        @SuppressWarnings("unchecked")
        List<BodyDeclaration> members = type.bodyDeclarations();
        for (ManagedValue<?> each : declared) {
            if (!(each instanceof ManagedParam<?> sibling) || !holder.equals(sibling.holder())) continue;
            MethodDeclaration method = paramMethod(ast, sibling, grammar, imports);
            if (method == null) {
                System.err.println("Warning: BotMaker cannot write the parameter \"" + sibling.id() + "\": no"
                        + " plugin in this project declares " + sibling.form().getTypeName() + ".");
                continue;
            }
            members.add(method);
        }
        return members.isEmpty() ? null : type;
    }

    /**
     * An enum set's holder: {@code @SdkValue(…) public enum Outcomes implements Outcome {}} — no constant yet,
     * and nothing else, since a constant's name is all of it. Null when the element type has no node.
     */
    private static EnumDeclaration enumHolder(AST ast, PluginCoordinate plugin, ManagedValue<?> value, Set<String> imports) {
        org.eclipse.jdt.core.dom.Type element = ValueTypes.node(ast, value.type(), imports).orElse(null);
        if (element == null) return null;
        EnumDeclaration type = ast.newEnumDeclaration();
        type.setName(ast.newSimpleName(value.holder()));
        type.setJavadoc(javadoc(ast, plugin, markName(value)));
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> modifiers = type.modifiers();
        modifiers.add(mark(ast, value, imports));
        modifiers.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        @SuppressWarnings("unchecked")
        List<org.eclipse.jdt.core.dom.Type> supers = type.superInterfaceTypes();
        supers.add(element);
        return type;
    }

    /**
     * A class holder: a set's empty class carrying the mark, or one marked method per method-shaped value
     * sharing the holder; null when one of those has no starting value to write.
     */
    private static TypeDeclaration classHolder(AST ast, PluginCoordinate plugin, ManagedValue<?> value,
                                               List<ManagedValue<?>> declared, ValueGrammar grammar,
                                               TreeSet<String> imports) {
        String holder = value.holder();
        TypeDeclaration type = ast.newTypeDeclaration();
        type.setName(ast.newSimpleName(holder));
        type.setJavadoc(javadoc(ast, plugin, markName(value)));
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> typeModifiers = type.modifiers();
        boolean typeLevel = value instanceof ManagedOpenSet<?>;
        if (typeLevel) typeModifiers.add(mark(ast, value, imports));
        typeModifiers.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        typeModifiers.add(ast.newModifier(Modifier.ModifierKeyword.FINAL_KEYWORD));
        @SuppressWarnings("unchecked")
        List<BodyDeclaration> members = type.bodyDeclarations();
        if (!typeLevel) {
            for (ManagedValue<?> each : declared) {
                if (!(each instanceof ManagedMethod<?> sibling) || !holder.equals(sibling.holder())) continue;
                MethodDeclaration method = method(ast, sibling, grammar, imports);
                if (method == null) {
                    throw new Unwritable("BotMaker cannot write a starting value for \"" + sibling.id()
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
        return type;
    }

    /** Why a holder cannot be written, thrown out of {@link #classHolder} to {@link #plan}'s refusal. */
    private static final class Unwritable extends RuntimeException {
        Unwritable(String reason) {
            super(reason, null, false, false);
        }
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
    private static Javadoc javadoc(AST ast, PluginCoordinate plugin, String markName) {
        Javadoc javadoc = javadoc(ast, List.of("Values " + plugin + " keeps in step with its own windows.",
                "<p>Written by BotMaker because this project had none, and <b>yours from that moment</b>:",
                "it is never rewritten. BotMaker changes the expression a"));
        TagElement code = ast.newTagElement();
        code.setTagName(TagElement.TAG_CODE);
        code.fragments().add(text(ast, " @" + markName));
        TagElement last = (TagElement) javadoc.tags().getLast();
        last.fragments().add(code);
        last.fragments().add(text(ast, " method returns and nothing else."));
        return javadoc;
    }

    /** A doc comment of {@code lines}, one untagged element each: the flattener starts each on its own line. */
    @SuppressWarnings("unchecked")
    public static Javadoc javadoc(AST ast, List<String> lines) {
        Javadoc javadoc = ast.newJavadoc();
        for (String line : lines) {
            TagElement element = ast.newTagElement();
            element.fragments().add(text(ast, line));
            javadoc.tags().add(element);
        }
        return javadoc;
    }

    private static TextElement text(AST ast, String text) {
        TextElement element = ast.newTextElement();
        element.setText(text);
        return element;
    }

    /** The simple name of the plugin's annotation {@code value} is marked with. */
    private static String markName(ManagedValue<?> value) {
        return ManagedIds.Typed.of(value).markerSimple();
    }

    /** {@code value}'s mark, {@code @SdkValue(SdkValue.Id.FLOW)}, its import added to {@code imports}. */
    private static SingleMemberAnnotation mark(AST ast, ManagedValue<?> value, Set<String> imports) {
        SingleMemberAnnotation annotation = ast.newSingleMemberAnnotation();
        ManagedIds.Typed id = ManagedIds.Typed.of(value);
        annotation.setTypeName(ast.newSimpleName(id.markerSimple()));
        annotation.setValue(ast.newName(id.markerSimple() + "." + id.enumSimple() + "." + id.constant()));
        imports.add(id.markerCanonical());
        return annotation;
    }

    /**
     * Every holder the project lacks, one plan per holder, in plugin then declaration order.
     *
     * <p>A holder is lacking only when none of its values is declared anywhere in the bot
     * ({@code declaredIds}), the bot has no holder of that plugin and name ({@code present}, so a {@code Sdk.java}
     * the user moved to another package still counts while it carries the plugin's mark), and its file is not on
     * disk. A class of the name that is another plugin's or the bot's own is no holder (2026-10-21: one used to hide
     * it, and the holder was never written). What comes back is written by the caller; nothing here touches the
     * filesystem but to look.
     *
     * @param byPlugin    each plugin's coordinate and every value it declares
     * @param declaredIds the ids the bot's sources already declare
     * @param present     the holders the bot declares ({@link HolderFiles#find})
     */
    public static List<Plan> missing(ProjectConfig config, java.util.Map<PluginCoordinate, List<ManagedValue<?>>> byPlugin,
                                     java.util.Set<String> declaredIds, java.util.Set<HolderFiles.Key> present,
                                     ValueGrammar grammar) {
        List<Plan> out = new ArrayList<>();
        java.util.Set<HolderFiles.Key> planned = new java.util.HashSet<>(present);
        byPlugin.forEach((plugin, declared) -> {
            java.util.Set<String> holders = new java.util.LinkedHashSet<>();
            for (ManagedValue<?> value : declared) {
                if (value != null && value.holder() != null) holders.add(value.holder());
            }
            for (String holder : holders) {
                List<ManagedValue<?>> held = declared.stream()
                        .filter(v -> v != null && holder.equals(v.holder())).toList();
                // The holder's own values, then its parameters' group in params/ (2026-11-04), each planned alone.
                List<ManagedValue<?>> own = held.stream().filter(v -> !(v instanceof ManagedParam<?>)).toList();
                List<ManagedValue<?>> params = held.stream().filter(ManagedParam.class::isInstance).toList();
                HolderFiles.Key key = HolderFiles.Key.of(plugin, holder);
                if (!own.isEmpty() && !planned.contains(key) && own.stream().noneMatch(v -> declaredIds.contains(v.id()))) {
                    Plan plan = plan(config, plugin, own.getFirst(), declared, grammar);
                    if (!(plan instanceof Plan.Write write && java.nio.file.Files.exists(write.file()))) {
                        planned.add(key);
                        out.add(plan);
                    }
                }
                if (!params.isEmpty() && params.stream().noneMatch(v -> declaredIds.contains(v.id()))) {
                    Plan plan = plan(config, plugin, params.getFirst(), declared, grammar);
                    if (!(plan instanceof Plan.Write write && java.nio.file.Files.exists(write.file()))) out.add(plan);
                }
            }
        });
        return List.copyOf(out);
    }

    /**
     * The file a plugin's parameters sharing {@code holder} are a group in, {@code params/<group>/<artifact>/
     * <Holder>.java} (2026-11-04); null when the plugin spells no package.
     */
    public static Path groupFile(ProjectConfig config, PluginCoordinate plugin, String holder) {
        String segment = segment(plugin);
        return segment == null || holder == null ? null
                : config.parametersPackageDir().resolve(segment.replace('.', '/')).resolve(holder + ".java");
    }

    /**
     * One marked method returning its value — the grammar's node, copied in — its imports added to
     * {@code imports}; null when the type has no node or no fresh value.
     */
    private static MethodDeclaration method(AST ast, ManagedMethod<?> value, ValueGrammar grammar,
                                            TreeSet<String> imports) {
        Type type = value.type();
        JavaValue fresh = (value.initial() != null ? grammar.spell(type, value.initial())
                : grammar.freshSpelling(type)).orElse(null);
        if (fresh == null) return null;
        Set<String> needed = new LinkedHashSet<>();
        org.eclipse.jdt.core.dom.Type returnType = ValueTypes.node(ast, type, needed).orElse(null);
        if (returnType == null) return null;

        MethodDeclaration method = ast.newMethodDeclaration();
        method.setName(ast.newSimpleName(memberName(value)));
        method.setReturnType2(returnType);
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> modifiers = method.modifiers();
        modifiers.add(mark(ast, value, imports));
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

    /**
     * A plugin's parameter as a method of its group (2026-11-04) — {@code @SdkValue(SdkValue.Id.DO_SETUP)
     * @Param.Shown(category = "Run", …) public boolean doSetup() { return true; }} — its imports added to
     * {@code imports}; null when its type has no node or its first value no spelling.
     */
    private static MethodDeclaration paramMethod(AST ast, ManagedParam<?> value, ValueGrammar grammar,
                                                 Set<String> imports) {
        Type type = value.form();
        JavaValue first = (value.initial() != null ? grammar.spell(type, value.initial())
                : grammar.freshSpelling(type)).orElse(null);
        if (first == null) return null;
        Set<String> needed = new LinkedHashSet<>();
        org.eclipse.jdt.core.dom.Type returnType = ValueTypes.node(ast, type, needed).orElse(null);
        if (returnType == null) return null;

        MethodDeclaration method = ast.newMethodDeclaration();
        method.setName(ast.newSimpleName(memberName(value)));
        method.setReturnType2(returnType);
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> modifiers = method.modifiers();
        modifiers.add(mark(ast, value, imports));
        Annotation shown = shown(ast, value, imports);
        if (shown != null) modifiers.add(shown);
        modifiers.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        Block body = ast.newBlock();
        ReturnStatement returned = ast.newReturnStatement();
        returned.setExpression(first.copyInto(ast));
        @SuppressWarnings("unchecked")
        List<Statement> statements = body.statements();
        statements.add(returned);
        method.setBody(body);
        imports.addAll(needed);
        imports.addAll(first.imports());
        return method;
    }

    /** {@code @Param.Shown(…)} as the plugin declared it, each member only when it says something; null for none. */
    private static Annotation shown(AST ast, ManagedParam<?> value, Set<String> imports) {
        List<MemberValuePair> members = new ArrayList<>();
        if (!value.category().isBlank()) members.add(member(ast, "category", string(ast, value.category())));
        if (!value.description().isBlank()) {
            members.add(member(ast, "description", string(ast, value.description())));
        }
        if (members.isEmpty()) return null;
        imports.add(Param.class.getCanonicalName());
        NormalAnnotation annotation = ast.newNormalAnnotation();
        annotation.setTypeName(ast.newName(Param.class.getSimpleName() + "." + Param.Shown.class.getSimpleName()));
        @SuppressWarnings("unchecked")
        List<MemberValuePair> values = annotation.values();
        values.addAll(members);
        return annotation;
    }

    private static MemberValuePair member(AST ast, String name, Expression value) {
        MemberValuePair pair = ast.newMemberValuePair();
        pair.setName(ast.newSimpleName(name));
        pair.setValue(value);
        return pair;
    }

    private static StringLiteral string(AST ast, String text) {
        StringLiteral string = ast.newStringLiteral();
        string.setLiteralValue(text);
        return string;
    }

    /**
     * {@code source} with {@code value}'s marked method, its fresh value inside, added after the last method of
     * the class {@code className} — before its constructor when it has no other — and its imports: what a bind
     * writes into a holder the bot already has when the method is missing (2026-10-12). Unchanged when the class
     * is not in it, already declares a method of that name, or the value cannot be written.
     */
    public static String withMethod(String source, String className, ManagedMethod<?> value, ValueGrammar grammar) {
        return withMember(source, className, value, (ast, imports) -> method(ast, value, grammar, imports));
    }

    /**
     * {@code source} with the plugin's parameter {@code value} added to its group {@code className} as a marked
     * method returning its first value (2026-11-04): what a bind writes into a group the bot already has when the
     * parameter is missing, in {@code params/} and its starting copy alike. Unchanged as {@link #withMethod} is.
     */
    public static String withParamMethod(String source, String className, ManagedParam<?> value,
                                         ValueGrammar grammar) {
        return withMember(source, className, value, (ast, imports) -> paramMethod(ast, value, grammar, imports));
    }

    private static String withMember(String source, String className, ManagedValue<?> value,
                                     java.util.function.BiFunction<AST, TreeSet<String>, MethodDeclaration> written) {
        CompilationUnit unit = com.botmaker.studio.project.source.BotParser.syntax(source);
        AST ast = unit.getAST();
        TypeDeclaration holder = null;
        for (Object each : unit.types()) {
            if (each instanceof TypeDeclaration type && type.getName().getIdentifier().equals(className)) holder = type;
        }
        // A method of the name already there, unmarked (an upgrade keeps a removed id's method while the bot names
        // it), is the user's: a second one would not compile.
        String name = memberName(value);
        if (holder == null || java.util.Arrays.stream(holder.getMethods())
                .anyMatch(each -> each.getName().getIdentifier().equals(name))) {
            return source;
        }
        TreeSet<String> imports = new TreeSet<>();
        MethodDeclaration method = written.apply(ast, imports);
        if (method == null) return source;
        org.eclipse.jdt.core.dom.rewrite.ASTRewrite rewrite = org.eclipse.jdt.core.dom.rewrite.ASTRewrite.create(ast);
        org.eclipse.jdt.core.dom.rewrite.ListRewrite body =
                rewrite.getListRewrite(holder, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        MethodDeclaration lastMethod = null;
        MethodDeclaration constructor = null;
        for (MethodDeclaration each : holder.getMethods()) {
            if (each.isConstructor()) {
                if (constructor == null) constructor = each;
            } else {
                lastMethod = each;
            }
        }
        if (lastMethod != null) body.insertAfter(method, lastMethod, null);
        else if (constructor != null) body.insertBefore(method, constructor, null);
        else body.insertLast(method, null);
        String pkg = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName();
        for (String each : imports) {
            if (!isImplicit(each, pkg)) com.botmaker.studio.parser.ImportManager.addImport(unit, rewrite, each);
        }
        return SourceFormatter.formatChanged(source,
                com.botmaker.studio.parser.helpers.AstRewriteHelper.applyRewrite(rewrite, source));
    }

    /**
     * Which file each plugin value is kept in on a bind ({@link #keptValues}): every parameter, in the file whose
     * method carries its mark or else in its group ({@link #groupFile}, 2026-11-04) — and
     * every method-shaped value no declaration in the bot carries the mark of, in its holder (2026-10-12: a
     * {@code Sdk.java} an older plugin wrote lacked {@code run()}, and nothing ever wrote it). A value whose holder
     * or group the bot does not have is left out: {@link #missing} writes it with the file. Nothing here writes.
     *
     * @param byPlugin each plugin's coordinate and every value it declares
     */
    public static java.util.Map<Path, List<ManagedValue<?>>> valuesToKeep(
            ProjectConfig config, com.botmaker.studio.project.source.BotIndex index,
            java.util.Map<PluginCoordinate, List<ManagedValue<?>>> byPlugin) {
        List<ManagedValue<?>> known = byPlugin.values().stream().flatMap(List::stream).toList();
        java.util.Map<String, Path> marked = new java.util.HashMap<>();
        Set<String> inFields = new java.util.HashSet<>();
        java.util.Map<HolderFiles.Key, Path> holders = index.read(units -> {
            units.forEach((file, unit) -> unit.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
                @Override
                public boolean visit(FieldDeclaration field) {
                    // A marked field is the old shape, which the upgrade repairs: no value is kept in it, and a
                    // method value it holds is not written beside it (the repair makes the field that method).
                    String id = ManagedIds.idOf(ManagedIds.on(field, known), known);
                    if (!id.isEmpty()) inFields.add(id);
                    return false;
                }

                @Override
                public boolean visit(MethodDeclaration method) {
                    String id = ManagedIds.idOf(ManagedIds.on(method, known), known);
                    if (!id.isEmpty()) marked.putIfAbsent(id, file);
                    return false;
                }
            }));
            return HolderFiles.find(units, byPlugin);
        });
        java.util.Map<Path, List<ManagedValue<?>>> kept = new java.util.LinkedHashMap<>();
        byPlugin.forEach((plugin, declared) -> {
            for (ManagedValue<?> value : declared) {
                if (value == null) continue;
                Path file;
                if (value instanceof ManagedParam<?>) {
                    Path group = groupFile(config, plugin, value.holder());
                    group = group == null ? null : group.toAbsolutePath().normalize();
                    file = marked.getOrDefault(value.id(),
                            group != null && index.sources().containsKey(group) ? group : null);
                } else if (value instanceof ManagedMethod<?> && !marked.containsKey(value.id())
                        && !inFields.contains(value.id())) {
                    HolderFiles.Key key = HolderFiles.Key.of(plugin, value.holder());
                    file = key == null ? null : holders.get(key);
                } else {
                    continue;
                }
                if (file != null) kept.computeIfAbsent(file, f -> new ArrayList<>()).add(value);
            }
        });
        return java.util.Map.copyOf(kept);
    }

    /**
     * {@code source} with each of {@code values} as its plugin declares it. A parameter (2026-11-04): a method
     * carrying its mark gets the plugin's current {@code @Param.Shown} members back when they differ; else it is
     * appended to its group ({@link #withParamMethod}). A method-shaped value no method of the source carries the
     * mark of is appended with its fresh value ({@link #withMethod}, 2026-10-12); one that is there is never touched.
     * Unchanged when every one already is as declared.
     */
    public static String keptValues(String source, List<ManagedValue<?>> values, ValueGrammar grammar) {
        String out = source;
        for (ManagedValue<?> each : values) {
            Optional<MethodDeclaration> marked = markedMethod(out, each, values);
            if (each instanceof ManagedMethod<?> method) {
                if (marked.isEmpty()) out = withMethod(out, method.holder(), method, grammar);
                continue;
            }
            if (!(each instanceof ManagedParam<?> value)) continue;
            if (marked.isEmpty()) {
                out = withParamMethod(out, value.holder(), value, grammar);
                continue;
            }
            String className = com.botmaker.studio.project.params.JavaParameterSource.enclosingTypeName(marked.get());
            java.util.Map<String, String> drift = drift(marked.get(), value);
            if (className != null && !drift.isEmpty()) {
                out = com.botmaker.studio.project.params.JavaParameterEdits.setShown(out, className,
                        marked.get().getName().getIdentifier(), drift);
            }
        }
        return out;
    }

    /** The method of {@code source} carrying {@code value}'s mark, read against {@code known}. */
    private static Optional<MethodDeclaration> markedMethod(String source, ManagedValue<?> value,
                                                            List<ManagedValue<?>> known) {
        CompilationUnit unit = com.botmaker.studio.project.source.BotParser.syntax(source);
        MethodDeclaration[] found = {null};
        unit.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (found[0] == null && value.id().equals(ManagedIds.idOf(ManagedIds.on(method, known), known))) {
                    found[0] = method;
                }
                return false;
            }
        });
        return Optional.ofNullable(found[0]);
    }

    /**
     * The {@code @Param.Shown} members of {@code method} that differ from what {@code value} declares, as
     * {@code JavaParameterEdits.setShown} takes them: a blank value removes a member. How often it changes is the
     * author's, never the plugin's.
     */
    private static java.util.Map<String, String> drift(MethodDeclaration method, ManagedParam<?> value) {
        Annotation annotation = BotAnnotation.PARAM_SHOWN.on(method);
        java.util.Map<String, Object> members = annotation == null ? java.util.Map.of()
                : BotAnnotation.PARAM_SHOWN.members(annotation);
        java.util.Map<String, String> drift = new java.util.LinkedHashMap<>();
        if (!value.category().equals(String.valueOf(members.getOrDefault("category", "")))) {
            drift.put("category", value.category());
        }
        if (!value.description().equals(String.valueOf(members.getOrDefault("description", "")))) {
            drift.put("description", value.description());
        }
        return drift;
    }

    /** The member a value is written as: its id constant camel-cased, {@code FLOW_LAYOUT} as {@code flowLayout}. */
    public static String memberName(ManagedValue<?> value) {
        // FLOW_LAYOUT as flow-layout: no identifier, so methodName camel-cases it rather than keeping it.
        return methodName(ManagedIds.Typed.of(value).constant().toLowerCase(Locale.ROOT).replace('_', '-'));
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
     * The package under {@code plugins/} a plugin's files are in, which {@code PluginFiles} reads back: its groupId
     * then its artifactId, each one package name, lower-cased, every character no identifier takes made {@code _}
     * — {@code com.github.BotMakerDev:botmaker-sdk} is {@code com_github_botmakerdev.botmaker_sdk}. Null when a
     * part spells no package name. Until 2026-10-27 it was the last segment of the plugin's id, and for a day the
     * artifactId's last word, which two plugins could share; the coordinate is the plugin's one identity (the
     * user's call). BotMaker's former groupId spells the current one, so the folder stays where the 2026-10-05
     * coordinate move found it.
     */
    public static String segment(PluginCoordinate plugin) {
        if (plugin == null) return null;
        String group = DuplicatePlugins.isBotMakerGroup(plugin.groupId()) ? DuplicatePlugins.CURRENT_GROUP
                : plugin.groupId();
        String groupPart = packagePart(group);
        String artifactPart = packagePart(plugin.artifactId());
        return groupPart == null || artifactPart == null ? null : groupPart + "." + artifactPart;
    }

    /** The {@link #segment} of {@code file} when it lies in {@code plugins/<group>/<artifact>/}, else null. */
    public static String segmentOf(Path file) {
        Path artifact = file == null ? null : file.getParent();
        Path group = artifact == null ? null : artifact.getParent();
        Path plugins = group == null ? null : group.getParent();
        return plugins != null && plugins.getFileName() != null && plugins.getFileName().toString().equals("plugins")
                ? group.getFileName() + "." + artifact.getFileName() : null;
    }

    /** {@code text} as one package name, or null when it cannot be one. */
    private static String packagePart(String text) {
        String name = PluginState.part(text);
        return SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name) ? name : null;
    }

    private static boolean isImplicit(String name, String pkg) {
        int dot = name.lastIndexOf('.');
        String owner = dot < 0 ? "" : name.substring(0, dot);
        return owner.equals("java.lang") || owner.equals(pkg);
    }
}
