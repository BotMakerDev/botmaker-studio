package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.SourceNames;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.params.JavaParameterSource;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.services.BotSources;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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
     * One {@code public static final} field of a marked type, or one constant of a marked enum.
     *
     * @param owner       the declaring class, fully qualified
     * @param field       the field's name
     * @param initializer its initialiser, as written; null for an enum's constant
     * @param member      what an enum's constant stands for, made by its plugin from the name
     *                    ({@code ManagedValue.byName}); null for a field, whose initialiser says
     */
    public record Constant(String owner, String field, String initializer, Object member) {

        /** A field, read through its initialiser. */
        public Constant(String owner, String field, String initializer) {
            this(owner, field, initializer, null);
        }

        /** The value it holds, read through {@code grammar}; empty when its initialiser is not one it reads. */
        Optional<Object> value(ValueGrammar grammar) {
            return member != null ? Optional.of(member)
                    : initializer == null ? Optional.empty() : grammar.valueOfAny(initializer);
        }

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

    /**
     * The declarations {@link #PARSED} was read against, by identity. A bind replaces the list, and a file read
     * before its plugin was bound has to be read again for its marks to count, so the cache is dropped with
     * the old list. Only the current list is held: an entry holding its own would keep every earlier bind's
     * plugin classes, and their closed class loader, reachable.
     */
    private static Collection<ManagedValue<?>> readAgainst;

    /** How many files have been parsed — what a test asks to see that an unchanged file is not. */
    private static final AtomicInteger PARSES = new AtomicInteger();

    /** Every constant of every marked top-level type in the bot's sources, in file order. */
    public static List<Constant> scan(ProjectConfig config, ProjectState state) {
        return scan(config, state, PluginHost.managedValues());
    }

    /** The same, its marks matched against {@code known} ({@link ManagedIds}). */
    static List<Constant> scan(ProjectConfig config, ProjectState state, Collection<ManagedValue<?>> known) {
        List<Constant> out = new ArrayList<>();
        if (config == null) return out;
        synchronized (PARSED) {
            if (readAgainst != known) {
                PARSED.clear();
                readAgainst = known;
            }
        }
        try {
            BotSources.scan(config, state, (file, source) -> out.addAll(cached(file, source, known)));
        } catch (RuntimeException unreadable) {
            // A project mid-save reads as having no constants: every value is then spelled out, which compiles.
            return List.of();
        }
        return List.copyOf(out);
    }

    private static List<Constant> cached(Path file, String source, Collection<ManagedValue<?>> known) {
        Parsed last = PARSED.get(file);
        if (last != null && last.source().equals(source)) return last.constants();
        PARSES.incrementAndGet();
        List<Constant> constants = List.copyOf(read(source, known));
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

        /**
         * The value {@code name} names when it is {@code Owner.FIELD} of a known constant — the owner resolved
         * the way javac would, through the file's imports ({@link SourceNames}), not matched by its spelling.
         */
        public Optional<Object> read(QualifiedName name) {
            return constant(name).flatMap(constant -> constant.value(grammar));
        }

        /** The known constant {@code name} refers to, resolved as {@link #read} resolves it; empty for none. */
        public Optional<Constant> constant(QualifiedName name) {
            if (name == null) return Optional.empty();
            for (Constant constant : constants) {
                if (name.getName().getIdentifier().equals(constant.field())
                        && SourceNames.refersTo(name.getQualifier(), constant.owner())) {
                    return Optional.of(constant);
                }
            }
            return Optional.empty();
        }

        /**
         * {@code Owner.FIELD} and its import, for the first constant holding {@code value}; empty when none does.
         * Two values are the same when the grammar writes them as the same tree.
         */
        public Optional<JavaValue> spell(Object value) {
            Optional<JavaValue> canonical = value == null ? Optional.empty() : grammar.initializerOfAny(value);
            if (canonical.isEmpty()) return Optional.empty();
            for (Constant constant : constants) {
                Optional<JavaValue> theirs = constant.value(grammar).flatMap(grammar::initializerOfAny);
                if (theirs.isPresent() && theirs.get().sameJava(canonical.get())) {
                    AST ast = AST.newAST(AST.getJLSLatest(), false);
                    QualifiedName reference = ast.newQualifiedName(
                            ast.newSimpleName(constant.simpleOwner()), ast.newSimpleName(constant.field()));
                    return Optional.of(JavaValue.built(reference, List.of(constant.owner())));
                }
            }
            return Optional.empty();
        }
    }

    /** The constants of the top-level types in one file marked as one of {@code known} ({@link ManagedIds}). */
    static List<Constant> read(String source, Collection<ManagedValue<?>> known) {
        CompilationUnit unit = BotParser.syntax(source);
        String pkg = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName() + ".";
        List<Constant> out = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration type) {
                if (!type.isPackageMemberTypeDeclaration()
                        || ManagedIds.on(type, known) == null) return false;
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

            /** A marked enum's constants, each what its set's plugin makes of the name. */
            @Override
            public boolean visit(EnumDeclaration type) {
                if (!type.isPackageMemberTypeDeclaration()) return false;
                Annotation mark = ManagedIds.on(type, known);
                if (mark == null) return false;
                String id = ManagedIds.idOf(mark, known);
                ManagedValue<?> set = known.stream()
                        .filter(value -> value != null && value.isEnum() && value.id().equals(id))
                        .findFirst().orElse(null);
                if (set == null) return false;
                String owner = pkg + type.getName().getIdentifier();
                for (Object each : type.enumConstants()) {
                    String name = ((EnumConstantDeclaration) each).getName().getIdentifier();
                    Object member = set.byName(name);
                    if (member != null) out.add(new Constant(owner, name, null, member));
                }
                return false;
            }
        });
        return out;
    }
}
