package com.botmaker.studio.nav;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.services.BotSources;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Find Usages (2026-09-26): every name in a unit that resolves to one binding, and {@link #across} the bot.
 *
 * <p><b>The one answer to "where is this used"</b> (2026-09-27). The Usages tab, a class rename from the
 * explorer and a parameter's rename and removal all ask here, so no caller counts uses by searching text —
 * which is how a removed {@code @Param} left {@code Parameters.j} in a file the window said nothing about.
 *
 * <p>Bindings are compared by {@link #keyOf key}, and a generic member by its declaration's key, so
 * {@code List<String>.add} and {@code List<Integer>.add} are one method — the question is "where is this
 * declaration used", not "where is this instantiation".
 */
public final class Usages {

    private Usages() {}

    /**
     * One occurrence.
     *
     * @param file        the source it is in
     * @param start       its offset in that source, which finds the block once the file is open
     * @param line        1-based
     * @param enclosing   the function it sits in ({@code main}), or the type for a field initialiser
     * @param text        the source line, trimmed
     * @param declaration whether this is the declaration itself rather than a use
     */
    public record Usage(Path file, int start, int line, String enclosing, String text, boolean declaration) {}

    /** What a binding is compared by. Null for a binding that has no stable key. */
    public static String keyOf(IBinding binding) {
        IBinding declared = switch (binding) {
            case IMethodBinding m -> m.getMethodDeclaration();
            case IVariableBinding v -> v.getVariableDeclaration();
            case ITypeBinding t -> t.getErasure();
            case null -> null;
            default -> binding;
        };
        return declared == null ? null : declared.getKey();
    }

    /** Every name in {@code cu} bound to {@code key}, in source order. */
    public static List<Usage> in(Path file, String source, CompilationUnit cu, String key) {
        List<Usage> out = new ArrayList<>();
        if (cu == null || key == null) return out;
        String[] lines = source.split("\n", -1);
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                IBinding binding = name.resolveBinding();
                if (binding == null || !key.equals(keyOf(binding))) return false;
                int line = cu.getLineNumber(name.getStartPosition());
                String text = line >= 1 && line <= lines.length ? lines[line - 1].trim() : "";
                out.add(new Usage(file, name.getStartPosition(), line, enclosing(name), text,
                        name.isDeclaration()));
                return false;
            }
        });
        return out;
    }

    /**
     * Every name bound to {@code key} across {@code sources}, file by file in the map's order. The one walk
     * every "where is this used" asks: the Usages tab, a class rename, a parameter's rename and removal.
     *
     * @param name the declaration's simple name — a file that never spells it cannot use it, so it is not
     *             parsed. A cheap guard, not a match: what is reported is decided by the binding alone
     */
    public static List<Usage> across(Map<Path, String> sources, BotParser parser, String key, String name) {
        List<Usage> out = new ArrayList<>();
        if (key == null) return out;
        sources.forEach((file, source) -> {
            if (name == null || source.contains(name)) out.addAll(in(file, source, parser.parse(file, source), key));
        });
        return out;
    }

    /** {@link #across} over the open project's sources, each read from its buffer before the disk. */
    public static List<Usage> inProject(ProjectConfig config, ProjectState state, String key, String name) {
        return across(sources(config, state), BotParser.of(config, state), key, name);
    }

    /** Every source of the bot, by path, the open buffer where there is one — what {@link #across} walks. */
    public static Map<Path, String> sources(ProjectConfig config, ProjectState state) {
        Map<Path, String> sources = new LinkedHashMap<>();
        if (config != null) BotSources.scan(config, state, sources::put);
        return sources;
    }

    /**
     * The key of field {@code fieldName} declared in the class {@code className} of {@code unit}, or null when
     * the unit declares no such field or its binding cannot be resolved.
     */
    public static String fieldKey(CompilationUnit unit, String className, String fieldName) {
        String[] key = {null};
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment fragment) {
                if (key[0] != null || !(fragment.getParent() instanceof FieldDeclaration)) return false;
                if (!fragment.getName().getIdentifier().equals(fieldName)) return false;
                if (!(fragment.getParent().getParent() instanceof AbstractTypeDeclaration type)
                        || !type.getName().getIdentifier().equals(className)) return false;
                key[0] = keyOf(fragment.resolveBinding());
                return false;
            }
        });
        return key[0];
    }

    /**
     * {@code text} with the name at each of {@code starts} changed from {@code oldName} to {@code newName}.
     * The offsets are the ones {@link #in} reported for this very text, so each is a whole identifier; they are
     * applied back to front so the earlier ones still hold. A start that does not spell {@code oldName} —
     * the text moved since it was parsed — is skipped.
     */
    public static String renamed(String text, List<Integer> starts, String oldName, String newName) {
        StringBuilder out = new StringBuilder(text);
        starts.stream().distinct().sorted(Comparator.reverseOrder()).forEach(start -> {
            if (text.startsWith(oldName, start)) out.replace(start, start + oldName.length(), newName);
        });
        return out.toString();
    }

    private static String enclosing(ASTNode node) {
        for (ASTNode n = node.getParent(); n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration m) return m.getName().getIdentifier();
            if (n instanceof AbstractTypeDeclaration t) return t.getName().getIdentifier();
        }
        return "";
    }
}
