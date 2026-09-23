package com.botmaker.studio.project.managed;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.params.JavaParameterSource;
import com.botmaker.studio.services.BotSources;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * The constants of every {@code @Managed} type in the bot's own source — {@code Pictures.COLLECT} and its
 * siblings — so a value that equals one is written as its name rather than spelled out again.
 */
public final class ManagedConstants {

    private ManagedConstants() {}

    /**
     * One {@code public static final} field of a {@code @Managed} type.
     *
     * @param owner       the declaring class, fully qualified
     * @param field       the field's name
     * @param initializer its initialiser, as written
     */
    public record Constant(String owner, String field, String initializer) {

        /** The declaring class's simple name. */
        public String simpleOwner() {
            return owner.substring(owner.lastIndexOf('.') + 1);
        }
    }

    /** Every constant of every {@code @Managed} top-level type in the bot's sources, in file order. */
    public static List<Constant> scan(ProjectConfig config, ProjectState state) {
        List<Constant> out = new ArrayList<>();
        if (config == null) return out;
        BotSources.scan(config, state, (file, source) -> out.addAll(read(source)));
        return List.copyOf(out);
    }

    /** The constants of the {@code @Managed} top-level types in one file. */
    static List<Constant> read(String source) {
        CompilationUnit unit = JavaParameterSource.parse(source);
        String pkg = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName() + ".";
        List<Constant> out = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration type) {
                if (!type.isPackageMemberTypeDeclaration()
                        || JavaManagedSource.managedAnnotation(type) == null) return false;
                String owner = pkg + type.getName().getIdentifier();
                for (FieldDeclaration field : type.getFields()) {
                    int flags = field.getModifiers();
                    if (!Modifier.isPublic(flags) || !Modifier.isStatic(flags) || !Modifier.isFinal(flags)) continue;
                    for (Object each : field.fragments()) {
                        VariableDeclarationFragment fragment = (VariableDeclarationFragment) each;
                        if (fragment.getInitializer() == null) continue;
                        out.add(new Constant(owner, fragment.getName().getIdentifier(),
                                JavaParameterSource.text(source, fragment.getInitializer())));
                    }
                }
                return false;
            }
        });
        return out;
    }
}
