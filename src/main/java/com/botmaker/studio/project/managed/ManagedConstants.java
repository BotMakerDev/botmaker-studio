package com.botmaker.studio.project.managed;

import com.botmaker.studio.plugin.grammar.ValueGrammar;
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
import java.util.Optional;
import java.util.regex.Pattern;

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
        try {
            BotSources.scan(config, state, (file, source) -> out.addAll(read(source)));
        } catch (RuntimeException unreadable) {
            // A project mid-save reads as having no constants: every value is then spelled out, which compiles.
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * The constants, read through a grammar in both directions: a reference the file writes to the value it
     * holds, and a value to the constant that holds it.
     *
     * <p>Two constants are the same value when the grammar writes their values the same way, which is the
     * comparison a plugin's own {@code equals} cannot be trusted to make.
     */
    public record Lookup(List<Constant> constants, ValueGrammar grammar) {

        public Lookup {
            constants = constants == null ? List.of() : List.copyOf(constants);
        }

        /** The value {@code source} names when it is {@code Owner.FIELD} of a known constant, qualified or not. */
        public Optional<Object> read(String source) {
            String name = source == null ? "" : source.strip();
            for (Constant constant : constants) {
                String field = "." + constant.field();
                if (name.equals(constant.owner() + field) || name.equals(constant.simpleOwner() + field)) {
                    return grammar.valueOfAny(constant.initializer());
                }
            }
            return Optional.empty();
        }

        /** {@code Owner.FIELD} and its import, for the first constant holding {@code value}; empty when none does. */
        public Optional<ValueGrammar.Written> spell(Object value) {
            Optional<String> canonical = value == null ? Optional.empty() : grammar.initializerOfAny(value);
            if (canonical.isEmpty()) return Optional.empty();
            for (Constant constant : constants) {
                Optional<String> theirs = grammar.valueOfAny(constant.initializer())
                        .flatMap(grammar::initializerOfAny);
                if (theirs.equals(canonical)) {
                    return Optional.of(new ValueGrammar.Written(
                            constant.simpleOwner() + "." + constant.field(), List.of(constant.owner())));
                }
            }
            return Optional.empty();
        }
    }

    /** Whether {@code source} is written as a dotted name — the only shape a constant reference can have. */
    public static boolean isName(String source) {
        return source != null && NAME.matcher(source.strip()).matches();
    }

    private static final Pattern NAME =
            Pattern.compile("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
                            + "(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)+");

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
