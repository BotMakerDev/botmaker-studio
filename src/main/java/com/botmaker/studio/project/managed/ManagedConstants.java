package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.BotNames;
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
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeLiteral;
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
 * The constants of every managed type in the bot's own source — {@code Pictures.COLLECT} and its
 * siblings, or an enum's {@code Outcomes.WON} — so a value that equals one is written as its name rather than
 * spelled out again.
 */
public final class ManagedConstants {

    private ManagedConstants() {}

    /**
     * One {@code public static final} field of a marked type, one constant of a marked enum, or — in a set of
     * nested enums — one of those enums as a whole, which a bot names by its class literal
     * ({@code Activities.Collect.class}).
     *
     * @param owner       the declaring class, fully qualified — {@code com.bot.Activities.Collect} for a nested
     *                    enum's constant, and for the nested enum itself
     * @param field       the field's name; null for a whole nested enum
     * @param initializer its initialiser, as written; null for an enum's constant
     * @param member      what an enum's constant stands for, made by its plugin from the name
     *                    ({@code ManagedValue.byName}), or a nested enum ({@code ManagedValue.byGroup}); null for
     *                    a field, whose initialiser says
     * @param topLevel    the top-level class {@code owner} is in, fully qualified — what a bot imports
     * @param set         the id of the open set whose mark {@code topLevel} carries; null when not known
     */
    public record Constant(String owner, String field, String initializer, Object member, String topLevel,
                           String set) {

        /** A field, read through its initialiser. */
        public Constant(String owner, String field, String initializer) {
            this(owner, field, initializer, null, owner, null);
        }

        /** A top-level enum's constant. */
        public Constant(String owner, String field, String initializer, Object member) {
            this(owner, field, initializer, member, owner, null);
        }

        /** The same, as a constant of the open set {@code id}. */
        Constant inSet(String id) {
            return new Constant(owner, field, initializer, member, topLevel, id);
        }

        /**
         * What {@code PluginValues} calls it: {@code ORE}, or {@code Collect.WON} in a nested enum; null for a
         * whole nested enum.
         */
        public String memberName() {
            if (isGroup()) return null;
            return owner.equals(topLevel) ? field : owner.substring(topLevel.length() + 1) + "." + field;
        }

        /** The value it holds, read through {@code grammar}; empty when its initialiser is not one it reads. */
        public Optional<Object> value(ValueGrammar grammar) {
            return member != null ? Optional.of(member)
                    : initializer == null ? Optional.empty() : grammar.valueOfAny(initializer);
        }

        /** Whether this is a whole nested enum, written as its class literal, rather than a constant. */
        public boolean isGroup() {
            return field == null;
        }

        /** How a bot writes the owner once {@link #topLevel()} is imported: {@code Pictures}, {@code Activities.Collect}. */
        public String simpleOwner() {
            return owner.substring(topLevel.lastIndexOf('.') + 1);
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
        if (config == null) return new ArrayList<>();
        return walked(known, each -> BotSources.scan(config, state, each));
    }

    /**
     * The same over {@code index}'s sources — the bot a parameter scan already holds, so a {@code @Param} field
     * initialised with {@code Flows.MAIN} reads as that flow (2026-10-11).
     */
    public static List<Constant> over(com.botmaker.studio.project.source.BotIndex index) {
        return walked(PluginHost.managedValues(), each -> index.sources().forEach(each));
    }

    /** Every constant of the sources {@code walk} hands over, each file read through the cache, against {@code known}. */
    private static List<Constant> walked(Collection<ManagedValue<?>> known,
                                         java.util.function.Consumer<java.util.function.BiConsumer<Path, String>> walk) {
        synchronized (PARSED) {
            if (readAgainst != known) {
                PARSED.clear();
                readAgainst = known;
            }
        }
        List<Constant> out = new ArrayList<>();
        try {
            walk.accept((file, source) -> out.addAll(cached(file, source, known)));
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
    public static final class Lookup implements BotNames {

        private final List<Constant> constants;
        private final ValueGrammar grammar;
        /** Each constant's value as the grammar writes it, computed once: every part of every write asks. */
        private List<Optional<JavaValue>> written;

        public Lookup(List<Constant> constants, ValueGrammar grammar) {
            this.constants = constants == null ? List.of() : List.copyOf(constants);
            this.grammar = grammar;
        }

        public List<Constant> constants() {
            return constants;
        }

        public ValueGrammar grammar() {
            return grammar;
        }

        /**
         * The value {@code name} names when it is {@code Owner.FIELD} of a known constant — the owner resolved
         * the way javac would, through the file's imports ({@link SourceNames}), not matched by its spelling.
         */
        public Optional<Object> read(QualifiedName name) {
            return constant(name).flatMap(constant -> constant.value(grammar));
        }

        @Override
        public Optional<Object> named(QualifiedName written) {
            return read(written);
        }

        /** What {@code Activities.Collect.class} stands for when {@code Activities.Collect} is a managed nested enum. */
        @Override
        public Optional<Object> type(TypeLiteral written) {
            Name name = switch (written == null ? null : written.getType()) {
                case SimpleType simple -> simple.getName();
                case null, default -> null;
            };
            if (name == null) return Optional.empty();
            for (Constant constant : constants) {
                if (constant.isGroup() && SourceNames.refersTo(name, constant.owner())) {
                    return Optional.ofNullable(constant.member());
                }
            }
            return Optional.empty();
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
         * {@code Owner.FIELD} and its import, for the first constant holding {@code value} — or
         * {@code Outer.Group.class} for a nested enum's value — empty when none does. Two values are the same
         * when the grammar writes them as the same tree; a nested enum's value, which no grammar writes, when it
         * equals the plugin's.
         */
        @Override
        public Optional<JavaValue> spell(Object value) {
            if (value == null) return Optional.empty();
            AST ast = AST.newAST(AST.getJLSLatest(), false);
            for (Constant constant : constants) {
                if (constant.isGroup() && value.equals(constant.member())) {
                    TypeLiteral literal = ast.newTypeLiteral();
                    literal.setType(ast.newSimpleType(ast.newName(constant.simpleOwner())));
                    return Optional.of(JavaValue.built(literal, List.of(constant.topLevel())));
                }
            }
            Optional<JavaValue> canonical = grammar.initializerOfAny(value);
            if (canonical.isEmpty()) return Optional.empty();
            // A value with a name of its own (Flow.NONE) keeps it: a bot field that merely holds it
            // (EZF = Flow.NONE) is a name for something else that happens to start equal.
            boolean named = canonical.get().node() instanceof Name;
            List<Optional<JavaValue>> theirs = written();
            for (int i = 0; i < constants.size(); i++) {
                Constant constant = constants.get(i);
                if (constant.isGroup() || (named && constant.member() == null)
                        || theirs.get(i).isEmpty() || !theirs.get(i).get().sameJava(canonical.get())) {
                    continue;
                }
                return Optional.of(reference(constant));
            }
            return Optional.empty();
        }

        /**
         * The constant of the open set {@code id} that {@code written} names — {@code Flows.DAILY}, resolved as
         * {@link #read} resolves it — or empty when it names none of that set's.
         */
        public Optional<Constant> member(String id, Expression written) {
            if (id == null || !(written instanceof QualifiedName name)) return Optional.empty();
            return constant(name).filter(constant -> id.equals(constant.set()));
        }

        /** The constant {@code member} of the open set {@code id}, by {@link Constant#memberName()}. */
        public Optional<Constant> member(String id, String member) {
            if (id == null || member == null) return Optional.empty();
            return constants.stream()
                    .filter(constant -> id.equals(constant.set()) && member.equals(constant.memberName()))
                    .findFirst();
        }

        /** {@code Owner.FIELD} for {@code constant}, with the import of its top-level class. */
        public static JavaValue reference(Constant constant) {
            AST ast = AST.newAST(AST.getJLSLatest(), false);
            QualifiedName reference = ast.newQualifiedName(ast.newName(constant.simpleOwner()),
                    ast.newSimpleName(constant.field()));
            return JavaValue.built(reference, List.of(constant.topLevel()));
        }

        private synchronized List<Optional<JavaValue>> written() {
            if (written == null) {
                List<Optional<JavaValue>> out = new ArrayList<>(constants.size());
                for (Constant constant : constants) {
                    out.add(constant.isGroup() ? Optional.empty()
                            : constant.value(grammar).flatMap(grammar::initializerOfAny));
                }
                written = List.copyOf(out);
            }
            return written;
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
                String id = ManagedIds.idOf(ManagedIds.on(type, known), known);
                grouped(type, owner, known, out);
                for (FieldDeclaration field : type.getFields()) {
                    int flags = field.getModifiers();
                    if (!Modifier.isPublic(flags) || !Modifier.isStatic(flags) || !Modifier.isFinal(flags)) continue;
                    for (Object each : field.fragments()) {
                        VariableDeclarationFragment fragment = (VariableDeclarationFragment) each;
                        if (fragment.getInitializer() == null) continue;
                        out.add(new Constant(owner, fragment.getName().getIdentifier(),
                                JavaParameterSource.text(source, fragment.getInitializer())).inSet(id));
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
                    if (member != null) out.add(new Constant(owner, name, null, member).inSet(id));
                }
                return false;
            }
        });
        return out;
    }

    /**
     * A grouped set's nested enums ({@code ManagedValue.isGrouped}): each enum as a whole, then each of its
     * constants, every one what the set's plugin makes of the names. Nothing for a class of any other set.
     */
    private static void grouped(TypeDeclaration type, String owner, Collection<ManagedValue<?>> known,
                                List<Constant> out) {
        String id = ManagedIds.idOf(ManagedIds.on(type, known), known);
        ManagedValue<?> set = known.stream()
                .filter(value -> value != null && value.isGrouped() && value.id().equals(id))
                .findFirst().orElse(null);
        if (set == null) return;
        for (Object body : type.bodyDeclarations()) {
            if (!(body instanceof EnumDeclaration nested)) continue;
            String group = nested.getName().getIdentifier();
            String nestedOwner = owner + "." + group;
            Object whole = set.byGroup(group);
            if (whole != null) out.add(new Constant(nestedOwner, null, null, whole, owner, id));
            for (Object each : nested.enumConstants()) {
                String name = ((EnumConstantDeclaration) each).getName().getIdentifier();
                Object member = set.byName(group, name);
                if (member != null) out.add(new Constant(nestedOwner, name, null, member, owner, id));
            }
        }
    }
}
