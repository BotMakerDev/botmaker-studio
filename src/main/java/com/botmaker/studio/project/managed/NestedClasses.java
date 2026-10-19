package com.botmaker.studio.project.managed;

import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.Usages;
import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.helpers.SourceFormatter;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.params.JavaParameterSource;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.project.source.ValueTypeResolver;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import javax.lang.model.SourceVersion;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A set of nested classes (the contract's {@code ManagedClasses}, 2026-10-16): a holder of {@code public static final}
 * classes, each with the constant holding its one instance, its {@code public final} fields and the one method it
 * overrides ({@code ManagedClasses.setup}).
 *
 * <pre>{@code
 * public final class Screens {
 *     public static final class Market extends Screen {
 *         public final Button beerBuy = Button.of(Pictures.BEER_BUY);
 *         @Override public Setup setup() { return Setup.named("Market"); }
 *     }
 *     public static final Market MARKET = new Market();
 * }
 * }</pre>
 *
 * <p>Told apart from a plain set by its shape, never by a flag: a constant whose declared type is a class nested in
 * the holder is a member class. Pure, as {@link ManagedSets} is: a plan or a refusal out, every plan compiled as the
 * whole bot, nothing written.
 */
public final class NestedClasses {

    private NestedClasses() {}

    /**
     * One class of the set.
     *
     * @param file          the file the holder is declared in
     * @param holder        the holder's simple name — {@code Screens}
     * @param constant      the constant holding its instance — {@code MARKET}, the member's name
     * @param constantStart where the constant's name starts
     * @param className     the nested class's simple name — {@code Market}
     * @param classStart    where the class's name starts
     * @param from          where the class's declaration starts
     * @param to            where it ends
     * @param constantFrom  where the constant's declaration starts
     * @param constantTo    where it ends
     * @param fields        its fields, each a {@link ManagedSets.Member} whose group is {@code constant}
     * @param setup         its setup's {@code return} expression as written, or null when it has none of one return
     */
    public record Nested(Path file, String holder, String constant, int constantStart, String className,
                         int classStart, int from, int to, int constantFrom, int constantTo,
                         List<ManagedSets.Member> fields, String setup) {

        /** What a bot writes to reach it: {@code Screens.MARKET}. */
        public String qualified() {
            return holder + "." + constant;
        }

        /** Whether {@code offset} is inside the class's declaration or its constant's, which go with it. */
        boolean inside(int offset) {
            return offset >= from && offset < to || offset >= constantFrom && offset < constantTo;
        }
    }

    /** The classes of the holder {@code type}, in the order their constants are written; empty for another set. */
    static List<Nested> of(Path file, String source, TypeDeclaration type, ValueGrammar grammar, String setupName) {
        String holder = type.getName().getIdentifier();
        List<Nested> out = new ArrayList<>();
        for (FieldDeclaration field : type.getFields()) {
            if (!isConstant(field)) continue;
            TypeDeclaration nested = field.getType() instanceof SimpleType declared
                    ? memberClass(type, declared.getName().getFullyQualifiedName()) : null;
            // Only a holder whose every constant holds one nested class's instance is a set of classes: a plain set
            // with a helper class among its constants stays a plain set.
            if (nested == null) return List.of();
            // One instance per class: a declaration of two constants of one class is no member of the set.
            if (field.fragments().size() != 1) continue;
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) field.fragments().getFirst();
            String constant = fragment.getName().getIdentifier();
            out.add(new Nested(file, holder, constant, fragment.getName().getStartPosition(),
                    nested.getName().getIdentifier(), nested.getName().getStartPosition(),
                    nested.getStartPosition(), nested.getStartPosition() + nested.getLength(),
                    field.getStartPosition(), field.getStartPosition() + field.getLength(),
                    fields(file, source, holder, constant, nested, grammar),
                    setupText(source, nested, setupName)));
        }
        return List.copyOf(out);
    }

    /** The classes of the set {@code id}, in the order their constants are written; empty when none is marked so. */
    public static List<Nested> all(BotIndex index, String id, ValueGrammar grammar, String setupName) {
        if (id == null || id.isBlank()) return List.of();
        // Once per index, set and bind: the setup window and a block ask it on every render (2026-10-18).
        return index.memo(PluginHost.managedValues(), Arrays.asList(NestedClasses.class, id, grammar, setupName),
                units -> {
            for (Map.Entry<Path, CompilationUnit> entry : units.entrySet()) {
                for (Object each : entry.getValue().types()) {
                    if (each instanceof TypeDeclaration type && ManagedSets.carries(type, id)) {
                        return of(entry.getKey(), index.sources().get(entry.getKey()), type, grammar, setupName);
                    }
                }
            }
            return List.<Nested>of();
        });
    }

    /** The class whose constant is {@code constant}, or empty. */
    public static Optional<Nested> named(BotIndex index, String id, String constant, ValueGrammar grammar,
                                         String setupName) {
        return all(index, id, grammar, setupName).stream().filter(c -> c.constant().equals(constant)).findFirst();
    }

    /** {@code FAVORED_SHOP} as a class name: {@code FavoredShop}. */
    public static String className(String constant) {
        StringBuilder out = new StringBuilder();
        for (String word : constant.split("_")) {
            if (word.isEmpty()) continue;
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    // --- plans ---------------------------------------------------------------------------------------------------

    /**
     * The class {@code className(constant)} extending {@code element}, overriding {@code setup} to return
     * {@code value}, and its constant, after the set's last class or else after the holder's last field — refused
     * when a name is not a Java name or is taken, or the bot would not compile.
     */
    public static Refactor.Outcome add(BotIndex index, String id, String constant, Class<?> element, Method setup,
                                       JavaValue value, ValueGrammar grammar) {
        if (!isJavaName(constant)) return new Refactor.Refused("“" + constant + "” is not a name Java accepts.");
        String className = className(constant);
        if (!isJavaName(className)) return new Refactor.Refused("“" + constant + "” makes no class name.");
        List<ManagedSets.Holder> holders = ManagedSets.holders(index, id);
        String mark = ManagedIds.spelled(id, PluginHost.managedValues());
        if (holders.size() != 1) {
            return new Refactor.Refused(holders.isEmpty() ? "The project has no class marked " + mark + "."
                    : "More than one class is marked " + mark + ", so which one gets " + constant + " is not clear.");
        }
        ManagedSets.Holder holder = holders.getFirst();
        String source = index.sources().get(holder.file());
        String added = added(source, holder.className(), constant, className, element, setup, value);
        if (added == null) {
            return new Refactor.Refused(holder.className() + " already has " + constant + " or " + className + ".");
        }
        if (added.equals(source)) return new Refactor.Refused(holder.className() + "." + constant + " could not be written.");
        return Refactor.checked(index, new Refactor.Planned("Added " + holder.className() + "." + constant,
                Map.of(holder.file(), added)), "Adding " + holder.className() + "." + constant);
    }

    /** The field {@code name} of the class {@code into}, declared {@code public final <type> name = <value>;}. */
    public static Refactor.Outcome addField(BotIndex index, Nested into, String name, Class<?> type, JavaValue value) {
        if (!isJavaName(name)) return new Refactor.Refused("“" + name + "” is not a name Java accepts.");
        if (into.fields().stream().anyMatch(field -> field.name().equals(name))) {
            return new Refactor.Refused(into.qualified() + "." + name + " already exists.");
        }
        String source = index.sources().get(into.file());
        String added = addedField(source, into.holder(), into.constant(), name, type, value);
        if (added.equals(source)) return new Refactor.Refused(into.qualified() + "." + name + " could not be written.");
        return Refactor.checked(index, new Refactor.Planned("Added " + into.qualified() + "." + name,
                Map.of(into.file(), added)), "Adding " + into.qualified() + "." + name);
    }

    /** The field deleted — refused, with the uses, while anything uses it ({@link ManagedSets#remove}). */
    static String removedField(String source, String holder, String constant, String name) {
        CompilationUnit unit = BotParser.syntax(source);
        TypeDeclaration nested = classOf(ManagedSets.topLevel(unit, holder), constant);
        FieldDeclaration field = nested == null ? null : field(nested, name);
        if (field == null) return source;
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        rewrite.remove(field.fragments().size() == 1 ? field : fragment(field, name), null);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    /**
     * The class and its constant renamed to {@code newName} by binding; the class to {@link #className} of it only
     * when it was so named from the old constant, so a class its author named is kept.
     */
    public static Refactor.Outcome rename(BotIndex index, Nested nested, String newName) {
        Refactor.Outcome first = Refactor.rename(index, nested.file(), nested.constantStart(), newName);
        if (!(first instanceof Refactor.Planned constant) || !nested.className().equals(className(nested.constant()))
                || className(newName).equals(nested.className()) || !isJavaName(className(newName))) {
            return first;
        }
        BotIndex renamed = index.with(constant.rewrites());
        int classStart = renamed.read(units -> {
            CompilationUnit unit = units.get(nested.file().toAbsolutePath().normalize());
            TypeDeclaration declared = memberClass(unit == null ? null : ManagedSets.topLevel(unit, nested.holder()),
                    nested.className());
            return declared == null ? -1 : declared.getName().getStartPosition();
        });
        if (classStart < 0) return first;
        Refactor.Outcome second = Refactor.rename(renamed, nested.file(), classStart, className(newName));
        // A class name taken by another class keeps the old one: the constant's rename alone still holds.
        if (!(second instanceof Refactor.Planned type)) return first;
        Map<Path, String> rewrites = new LinkedHashMap<>(constant.rewrites());
        rewrites.putAll(type.rewrites());
        return new Refactor.Planned("Renamed " + nested.qualified() + " to " + nested.holder() + "." + newName,
                rewrites);
    }

    /**
     * The class and its constant deleted — refused, with the uses, while anything outside the class uses the
     * constant, the class or one of its fields.
     */
    public static Refactor.Outcome remove(BotIndex index, Nested nested) {
        List<Usages.Usage> uses = new ArrayList<>(Refactor.uses(index, nested.file(), nested.constantStart())
                .orElse(List.of()));
        uses.addAll(Refactor.uses(index, nested.file(), nested.classStart()).orElse(List.of()));
        for (ManagedSets.Member field : nested.fields()) uses.addAll(ManagedSets.uses(index, field));
        Path file = nested.file().toAbsolutePath().normalize();
        String source = index.sources().get(nested.file());
        // The class's own body, and the constant's `= new Market()`, use them; neither outlives the removal.
        uses.removeIf(use -> use.declaration()
                || use.file().toAbsolutePath().normalize().equals(file) && nested.inside(use.start()));
        if (!uses.isEmpty()) {
            List<String> where = uses.stream().map(use -> use.file().getFileName() + ":" + use.line())
                    .distinct().limit(3).toList();
            return new Refactor.Refused(nested.qualified() + " is still used " + uses.size()
                    + (uses.size() == 1 ? " time" : " times") + ", in " + String.join(", ", where)
                    + (uses.size() > where.size() ? ", …" : "") + ".", uses, List.of());
        }
        String removed = removed(source, nested.holder(), nested.constant(), nested.className());
        if (removed.equals(source)) return new Refactor.Refused(nested.qualified() + " could not be removed.");
        return Refactor.checked(index, new Refactor.Planned("Removed " + nested.qualified(),
                Map.of(nested.file(), removed)), "Removing " + nested.qualified());
    }

    // --- pure edits ----------------------------------------------------------------------------------------------

    /**
     * {@code source} with the class and its constant added; null when the holder already has either name,
     * unchanged when the holder or a type's node is missing.
     */
    static String added(String source, String holderName, String constant, String className, Class<?> element,
                        Method setup, JavaValue value) {
        CompilationUnit unit = BotParser.syntax(source);
        AST ast = unit.getAST();
        if (!(ManagedSets.topLevel(unit, holderName) instanceof TypeDeclaration holder) || value == null) return source;
        if (memberClass(holder, className) != null || field(holder, constant) != null) return null;
        Set<String> imports = new LinkedHashSet<>();
        Optional<Type> extended = ValueTypes.node(ast, element, imports);
        Optional<Type> returned = ValueTypes.node(ast, setup.getGenericReturnType(), imports);
        if (extended.isEmpty() || returned.isEmpty()) return source;

        TypeDeclaration nested = ast.newTypeDeclaration();
        nested.setName(ast.newSimpleName(className));
        nested.setSuperclassType(extended.get());
        @SuppressWarnings("unchecked")
        List<Object> classModifiers = nested.modifiers();
        classModifiers.addAll(ast.newModifiers(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL));
        MethodDeclaration method = ast.newMethodDeclaration();
        method.setName(ast.newSimpleName(setup.getName()));
        method.setReturnType2(returned.get());
        @SuppressWarnings("unchecked")
        List<Object> methodModifiers = method.modifiers();
        MarkerAnnotation override = ast.newMarkerAnnotation();
        override.setTypeName(ast.newSimpleName("Override"));
        methodModifiers.add(override);
        methodModifiers.addAll(ast.newModifiers(Modifier.PUBLIC));
        Block body = ast.newBlock();
        ReturnStatement returns = ast.newReturnStatement();
        returns.setExpression(value.copyInto(ast));
        @SuppressWarnings("unchecked")
        List<Object> statements = body.statements();
        statements.add(returns);
        method.setBody(body);
        @SuppressWarnings("unchecked")
        List<Object> members = nested.bodyDeclarations();
        members.add(method);

        VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
        fragment.setName(ast.newSimpleName(constant));
        ClassInstanceCreation creation = ast.newClassInstanceCreation();
        creation.setType(ast.newSimpleType(ast.newSimpleName(className)));
        fragment.setInitializer(creation);
        FieldDeclaration instance = ast.newFieldDeclaration(fragment);
        instance.setType(ast.newSimpleType(ast.newSimpleName(className)));
        @SuppressWarnings("unchecked")
        List<Object> fieldModifiers = instance.modifiers();
        fieldModifiers.addAll(ast.newModifiers(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL));

        ASTRewrite rewrite = ASTRewrite.create(ast);
        ListRewrite list = rewrite.getListRewrite(holder, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        ASTNode last = lastOfSet(holder);
        if (last == null) {
            list.insertFirst(instance, null);
            list.insertFirst(nested, null);
        } else {
            list.insertAfter(instance, last, null);
            list.insertAfter(nested, last, null);
        }
        imports.addAll(value.imports());
        for (String each : imports) ImportManager.addImport(unit, rewrite, each);
        // The rewrite writes new nodes flat: only what it added is laid out.
        return SourceFormatter.formatChanged(source, AstRewriteHelper.applyRewrite(rewrite, source));
    }

    /** {@code source} with {@code public final <type> name = <value>;} after the class's last field, or first. */
    static String addedField(String source, String holderName, String constant, String name, Class<?> type,
                             JavaValue value) {
        CompilationUnit unit = BotParser.syntax(source);
        AST ast = unit.getAST();
        TypeDeclaration nested = classOf(ManagedSets.topLevel(unit, holderName), constant);
        if (nested == null || value == null) return source;
        Set<String> imports = new LinkedHashSet<>();
        Optional<Type> declared = ValueTypes.node(ast, type, imports);
        if (declared.isEmpty()) return source;
        VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
        fragment.setName(ast.newSimpleName(name));
        fragment.setInitializer(value.copyInto(ast));
        FieldDeclaration field = ast.newFieldDeclaration(fragment);
        field.setType(declared.get());
        @SuppressWarnings("unchecked")
        List<Object> modifiers = field.modifiers();
        modifiers.addAll(ast.newModifiers(Modifier.PUBLIC | Modifier.FINAL));
        ASTRewrite rewrite = ASTRewrite.create(ast);
        ListRewrite body = rewrite.getListRewrite(nested, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        FieldDeclaration[] fields = nested.getFields();
        if (fields.length == 0) body.insertFirst(field, null);
        else body.insertAfter(field, fields[fields.length - 1], null);
        imports.addAll(value.imports());
        for (String each : imports) ImportManager.addImport(unit, rewrite, each);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    /** {@code source} with the field's initialiser replaced by {@code value}; unchanged when it is not there. */
    public static String fieldSet(String source, String holderName, String constant, String name, JavaValue value) {
        CompilationUnit unit = BotParser.syntax(source);
        TypeDeclaration nested = classOf(ManagedSets.topLevel(unit, holderName), constant);
        FieldDeclaration field = nested == null ? null : field(nested, name);
        if (field == null || value == null) return source;
        AST ast = unit.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        rewrite.set(fragment(field, name), VariableDeclarationFragment.INITIALIZER_PROPERTY, value.copyInto(ast), null);
        for (String each : value.imports()) ImportManager.addImport(unit, rewrite, each);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    /** {@code source} with the class's setup returning {@code value}; unchanged when it has no single return. */
    public static String setupSet(String source, String holderName, String constant, String setupName,
                                  JavaValue value) {
        CompilationUnit unit = BotParser.syntax(source);
        ReturnStatement returns = singleReturn(classOf(ManagedSets.topLevel(unit, holderName), constant), setupName);
        if (returns == null || value == null) return source;
        AST ast = unit.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        rewrite.set(returns, ReturnStatement.EXPRESSION_PROPERTY, value.copyInto(ast), null);
        for (String each : value.imports()) ImportManager.addImport(unit, rewrite, each);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    /** {@code source} with the class and its constant deleted; unchanged when either is missing. */
    static String removed(String source, String holderName, String constant, String className) {
        CompilationUnit unit = BotParser.syntax(source);
        if (!(ManagedSets.topLevel(unit, holderName) instanceof TypeDeclaration holder)) return source;
        TypeDeclaration nested = memberClass(holder, className);
        FieldDeclaration field = field(holder, constant);
        if (nested == null || field == null) return source;
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        rewrite.remove(nested, null);
        rewrite.remove(field.fragments().size() == 1 ? field : fragment(field, constant), null);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    // --- reading -------------------------------------------------------------------------------------------------

    private static List<ManagedSets.Member> fields(Path file, String source, String holder, String constant,
                                                   TypeDeclaration nested, ValueGrammar grammar) {
        List<ManagedSets.Member> out = new ArrayList<>();
        for (FieldDeclaration field : nested.getFields()) {
            int flags = field.getModifiers();
            if (!Modifier.isPublic(flags) || !Modifier.isFinal(flags) || Modifier.isStatic(flags)) continue;
            for (Object each : field.fragments()) {
                VariableDeclarationFragment fragment = (VariableDeclarationFragment) each;
                if (fragment.getInitializer() == null) continue;
                out.add(new ManagedSets.Member(file, holder, fragment.getName().getIdentifier(),
                        fragment.getName().getStartPosition(),
                        ValueTypeResolver.of(grammar, field.getType(), fragment.getExtraDimensions(), Set.of()),
                        JavaParameterSource.text(source, fragment.getInitializer()), constant));
            }
        }
        return List.copyOf(out);
    }

    private static String setupText(String source, TypeDeclaration nested, String setupName) {
        ReturnStatement returns = singleReturn(nested, setupName);
        return returns == null || returns.getExpression() == null ? null
                : JavaParameterSource.text(source, returns.getExpression());
    }

    /** The one {@code return} of the method {@code name} taking nothing, when its body is only that. */
    private static ReturnStatement singleReturn(TypeDeclaration nested, String name) {
        if (nested == null || name == null) return null;
        for (MethodDeclaration method : nested.getMethods()) {
            if (!method.getName().getIdentifier().equals(name) || !method.parameters().isEmpty()) continue;
            Block body = method.getBody();
            if (body != null && body.statements().size() == 1
                    && body.statements().getFirst() instanceof ReturnStatement returns) {
                return returns;
            }
        }
        return null;
    }

    /** The class {@code name} nested in {@code holder}, or null. */
    static TypeDeclaration memberClass(AbstractTypeDeclaration holder, String name) {
        if (!(holder instanceof TypeDeclaration type) || name == null) return null;
        for (TypeDeclaration nested : type.getTypes()) {
            if (!nested.isInterface() && nested.getName().getIdentifier().equals(name)) return nested;
        }
        return null;
    }

    /** The class whose instance the constant {@code constant} of {@code holder} holds, or null. */
    static TypeDeclaration classOf(AbstractTypeDeclaration holder, String constant) {
        if (!(holder instanceof TypeDeclaration type) || constant == null) return null;
        FieldDeclaration field = field(type, constant);
        return field != null && field.getType() instanceof SimpleType declared
                ? memberClass(type, declared.getName().getFullyQualifiedName()) : null;
    }

    /** The last class of the set or its constant, whichever is written later; else the holder's last field. */
    private static ASTNode lastOfSet(TypeDeclaration holder) {
        ASTNode last = null;
        for (Object each : holder.bodyDeclarations()) {
            if (each instanceof FieldDeclaration || each instanceof TypeDeclaration) last = (ASTNode) each;
        }
        return last;
    }

    private static FieldDeclaration field(TypeDeclaration type, String name) {
        for (FieldDeclaration field : type.getFields()) if (fragment(field, name) != null) return field;
        return null;
    }

    private static VariableDeclarationFragment fragment(FieldDeclaration field, String name) {
        for (Object each : field.fragments()) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) each;
            if (fragment.getName().getIdentifier().equals(name)) return fragment;
        }
        return null;
    }

    private static boolean isConstant(FieldDeclaration field) {
        int flags = field.getModifiers();
        return Modifier.isPublic(flags) && Modifier.isStatic(flags) && Modifier.isFinal(flags);
    }

    private static boolean isJavaName(String name) {
        return name != null && SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name);
    }

}
