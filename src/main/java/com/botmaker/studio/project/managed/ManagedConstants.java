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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** One file's text as it was last parsed, and the constants it declared then. */
    private record Parsed(String source, List<Constant> constants) {}

    /**
     * The last parse of every file, keyed on its path and checked against its text.
     *
     * <p><b>Keyed on the text itself, not on a revision counter</b> (2026-09-23): every read and write of a
     * slot asks for the constants, and parsing every file each time was the cost. Comparing a file's text
     * with the text last parsed is a {@code String.equals} — the length first, then characters — where
     * parsing it is a JDT run, so a file nobody touched costs a comparison and one that changed by a
     * character is parsed again. There is no counter to forget to bump: a picture captured a moment ago is
     * text in {@code Pictures.java}, buffer and disk, and that is what is compared.
     */
    private static final Map<Path, Parsed> PARSED = Collections.synchronizedMap(new HashMap<>());

    /** How many files have been parsed — what a test asks to see that an unchanged file is not. */
    private static final AtomicInteger PARSES = new AtomicInteger();

    /** Every constant of every {@code @Managed} top-level type in the bot's sources, in file order. */
    public static List<Constant> scan(ProjectConfig config, ProjectState state) {
        List<Constant> out = new ArrayList<>();
        if (config == null) return out;
        try {
            BotSources.scan(config, state, (file, source) -> out.addAll(cached(file, source)));
        } catch (RuntimeException unreadable) {
            // A project mid-save reads as having no constants: every value is then spelled out, which compiles.
            return List.of();
        }
        return List.copyOf(out);
    }

    private static List<Constant> cached(Path file, String source) {
        Parsed last = PARSED.get(file);
        if (last != null && last.source().equals(source)) return last.constants();
        PARSES.incrementAndGet();
        List<Constant> constants = List.copyOf(read(source));
        PARSED.put(file, new Parsed(source, constants));
        return constants;
    }

    static int parses() {
        return PARSES.get();
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
