package com.botmaker.studio.project.managed;

import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.helpers.SourceFormatter;
import com.botmaker.studio.plugin.grammar.SourceNames;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import javax.lang.model.SourceVersion;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The bot's own {@code public static} methods with no parameter, found by what they return — what a plugin
 * offers where a value names "the method that does this" ({@code PluginValues.methods}) — and one written
 * ({@code PluginValues.createMethod}).
 *
 * <p>Pure over the bot's one parse, as {@link ManagedSets} is: a {@link BotIndex} in, references or a
 * {@link Refactor.Planned plan} out. A method is matched by its declared return type as javac would resolve it
 * ({@link SourceNames#refersTo}), never by how it is spelled.
 */
public final class BotMethods {

    private BotMethods() {}

    /**
     * {@code Class::method} for every top-level class's {@code public static} method with no parameter
     * returning {@code canonical} — or, when it is null, returning {@code void}, {@code main} excepted — in
     * file order, then declaration order.
     */
    public static List<String> returning(BotIndex index, String canonical) {
        return index.read(units -> {
            List<String> out = new ArrayList<>();
            units.forEach((file, unit) -> {
                for (Object each : unit.types()) {
                    if (!(each instanceof TypeDeclaration type) || type.isInterface()) continue;
                    for (MethodDeclaration method : type.getMethods()) {
                        if (eligible(method, canonical)) {
                            out.add(type.getName().getIdentifier() + "::" + method.getName().getIdentifier());
                        }
                    }
                }
            });
            return List.copyOf(out);
        });
    }

    /**
     * The simple names of the top-level classes a method can be written into, in file order: every class but an
     * interface, and none in a file under {@code holders} — the plugins' value holders, {@code plugins/}.
     */
    public static List<String> classes(BotIndex index, Path holders) {
        Path skipped = holders == null ? null : holders.toAbsolutePath().normalize();
        return index.read(units -> {
            List<String> out = new ArrayList<>();
            units.forEach((file, unit) -> {
                if (skipped != null && file.toAbsolutePath().normalize().startsWith(skipped)) return;
                for (Object each : unit.types()) {
                    if (each instanceof TypeDeclaration type && !type.isInterface()) {
                        out.add(type.getName().getIdentifier());
                    }
                }
            });
            return List.copyOf(out);
        });
    }

    /** The canonical names of the bot's top-level types, in file order: what a value written into it may import. */
    public static List<String> topLevelTypes(BotIndex index) {
        return index.read(units -> {
            List<String> out = new ArrayList<>();
            units.forEach((file, unit) -> {
                for (Object each : unit.types()) {
                    if (each instanceof AbstractTypeDeclaration type) out.add(SourceNames.canonicalOf(type));
                }
            });
            return List.copyOf(out);
        });
    }

    private static boolean eligible(MethodDeclaration method, String canonical) {
        int flags = method.getModifiers();
        if (method.isConstructor() || !Modifier.isPublic(flags) || !Modifier.isStatic(flags)
                || !method.parameters().isEmpty()) return false;
        Type returned = method.getReturnType2();
        if (canonical == null) {
            return returned instanceof PrimitiveType primitive && primitive.getPrimitiveTypeCode() == PrimitiveType.VOID
                    && !method.getName().getIdentifier().equals("main");
        }
        return returned instanceof SimpleType simple && SourceNames.refersTo(simple.getName(), canonical);
    }

    /**
     * {@code public static <returns> name() { return <returns>.<first>; }} — or {@code public static void name()
     * {}} when {@code returns} is null — added to the bot's top-level class {@code className}, which is created
     * in {@code mainPackage} at {@code newFile} when no source declares it.
     *
     * @param returns  how the bot writes the returned type once {@code imported} is imported —
     *                 {@code Activities.Collect} — or null for {@code void}
     * @param imported the top-level class to import for it, or null
     * @param first    the constant the new method returns; ignored for {@code void}
     */
    public static Refactor.Outcome create(BotIndex index, String mainPackage, Path newFile, String className,
                                          String name, String returns, String imported, String first) {
        if (!isJavaName(className)) return new Refactor.Refused("“" + className + "” is not a class name Java accepts.");
        if (!isJavaName(name)) return new Refactor.Refused("“" + name + "” is not a method name Java accepts.");
        if (returns != null && !isJavaName(first)) return new Refactor.Refused(returns + " has no constant to return.");
        Path file = index.read(units -> {
            for (Map.Entry<Path, CompilationUnit> entry : units.entrySet()) {
                for (Object each : entry.getValue().types()) {
                    if (each instanceof AbstractTypeDeclaration type
                            && type.getName().getIdentifier().equals(className)) return entry.getKey();
                }
            }
            return null;
        });
        boolean fresh = file == null;
        String source = fresh ? skeleton(mainPackage, className) : index.sources().get(file);
        String written = withMethod(source, className, name, returns, imported, first);
        if (written == null) return new Refactor.Refused(className + " already has a method " + name + ".");
        if (written.equals(source)) return new Refactor.Refused(className + "." + name + " could not be written.");
        Path target = fresh ? newFile : file;
        return Refactor.checked(index, new Refactor.Planned("Added " + className + "." + name,
                Map.of(target, written), Map.of()), "Adding " + className + "." + name);
    }

    /** A class with nothing in it yet, for {@link #create} to add to. */
    static String skeleton(String mainPackage, String className) {
        String declared = mainPackage == null || mainPackage.isEmpty() ? "" : "package " + mainPackage + ";\n\n";
        return declared + "public final class " + className + " {\n}\n";
    }

    /** {@code source} with the method added at the end of {@code className}; null when the name is taken. */
    static String withMethod(String source, String className, String name, String returns, String imported,
                             String first) {
        CompilationUnit unit = BotParser.syntax(source);
        TypeDeclaration holder = null;
        for (Object each : unit.types()) {
            if (each instanceof TypeDeclaration type && type.getName().getIdentifier().equals(className)) holder = type;
        }
        if (holder == null) return source;
        for (MethodDeclaration method : holder.getMethods()) {
            if (method.getName().getIdentifier().equals(name) && method.parameters().isEmpty()) return null;
        }
        AST ast = unit.getAST();
        MethodDeclaration method = ast.newMethodDeclaration();
        method.setName(ast.newSimpleName(name));
        @SuppressWarnings("unchecked")
        List<Object> modifiers = method.modifiers();
        modifiers.addAll(ast.newModifiers(Modifier.PUBLIC | Modifier.STATIC));
        Block body = ast.newBlock();
        if (returns == null) {
            method.setReturnType2(ast.newPrimitiveType(PrimitiveType.VOID));
        } else {
            method.setReturnType2(ast.newSimpleType(ast.newName(returns)));
            ReturnStatement result = ast.newReturnStatement();
            result.setExpression(ast.newQualifiedName(ast.newName(returns), ast.newSimpleName(first)));
            @SuppressWarnings("unchecked")
            List<Object> statements = body.statements();
            statements.add(result);
        }
        method.setBody(body);
        ASTRewrite rewrite = ASTRewrite.create(ast);
        ListRewrite members = rewrite.getListRewrite(holder, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        members.insertLast(method, null);
        if (imported != null) ImportManager.addImport(unit, rewrite, imported);
        // The rewrite writes a new node flat: only what it added is laid out, the rest of the file kept as written.
        return SourceFormatter.formatChanged(source, AstRewriteHelper.applyRewrite(rewrite, source));
    }

    private static boolean isJavaName(String name) {
        return name != null && SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name);
    }
}
