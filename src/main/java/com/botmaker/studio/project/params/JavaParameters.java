package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.params.Param;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.Usages;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectWrites;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.services.BotSources;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The project's parameters: {@code @Param} fields, read off the bot's own sources and written back to them.
 *
 * <p><b>The declaration is the Java</b> (2026-09-17). Until then a user parameter was a row in a plugin's
 * JSON file that a bot read back by name, so a typo compiled and answered the type's fallback and the
 * declaration lived where the bot's author could not see it.
 *
 * <p><b>And it is the only source of a row</b> (2026-09-22). A second one stood beside it for five days:
 * {@code ParameterSurface} merged these fields with the rows a plugin declared through
 * {@code StudioPlugin.parameterRows}, which is why a section had an id ({@code java:<Class>} or a plugin's
 * group) rather than simply being a class. Nothing ever declared a plugin row — basics'
 * {@code ParameterStore.declare} had no caller anywhere — so that half read a pre-2026-09-17 project's JSON
 * and nothing else, and it is deleted with the contract surface under it. **A plugin that wants a row of its
 * own puts a {@code @Param} field in the file it ships**, and {@link BotSources#scan} finds it here with no
 * special case: {@code plugins/sdk/Sdk.java} is one of the bot's sources.
 *
 * <p><b>A section is a class, and the handle is the pair {@code (className, fieldName)}.</b> Two classes may
 * each declare a {@code timeout}, which javac already allows and already keeps apart, so nothing here is
 * keyed by a name alone.
 *
 * <p><b>Buffers before files, both written.</b> Everything here goes through {@link BotSources}, which is
 * the walk that reads a file's open editor buffer where there is one and writes a rewrite to both copies.
 * A scan that read the disk would miss the user's last ten minutes; a write that touched only the disk
 * would be undone by the next save.
 *
 * <p><b>Nothing is cached.</b> The source file is the truth and it is cheap to re-read — a bot has a
 * handful of files and a parameters window is opened by a person, not by a loop. A cache here would be a
 * second answer to "what does this bot declare", which is the failure the JSON arrangement had.
 */
public final class JavaParameters {

    /** The class a new parameter goes into when the project has no {@code @Param} class yet. */
    public static final String DEFAULT_CLASS = "Parameters";

    private JavaParameters() {}

    // ---- reading ----------------------------------------------------------------------------------------

    /** Every {@code @Param} field the bot declares, file by file, in the order the walk visits them. */
    public static List<JavaParameter> scan(ProjectConfig config, ProjectState state) {
        return scan(config, state, PluginHost.grammar());
    }

    /**
     * The same, against a given grammar — the seam a test uses, and the one place the vocabulary enters.
     *
     * <p>Every edit below takes one too, for a reason worth stating: the grammar is what turns a value into
     * the Java a field is initialised with, so a window and a test that disagreed about it would write two
     * different files from the same click.
     */
    public static List<JavaParameter> scan(ProjectConfig config, ProjectState state, ValueGrammar grammar) {
        if (config == null) return List.of();
        return over(BotIndex.of(config, state), grammar);
    }

    /**
     * Every {@code @Param} field in {@code index} — the bot's one parse, so a field's annotation and type are
     * resolved against the same trees every rename and usage search reads.
     *
     * <p>Two walks over those trees: a field may be typed with a record declared in a file the parameter walk
     * has not reached yet, so what the bot declares has to be known in full before the first field is read.
     */
    public static List<JavaParameter> over(BotIndex index, ValueGrammar grammar) {
        return index.read(units -> {
            BotRecords records = BotRecords.over(grammar, units.values());
            List<JavaParameter> out = new ArrayList<>();
            units.forEach((file, unit) -> out.addAll(
                    JavaParameterSource.read(file, index.sources().get(file), unit, grammar, records)));
            return List.copyOf(out);
        });
    }

    /**
     * The classes that declare at least one parameter, in the order they were found — the window's sections.
     *
     * <p>A class with no fields left still gets its section for as long as the window is open; that is the
     * window's doing, not this method's. What is listed here is what the project currently declares.
     */
    public static List<String> classes(ProjectConfig config, ProjectState state) {
        Set<String> names = new LinkedHashSet<>();
        for (JavaParameter parameter : scan(config, state)) names.add(parameter.className());
        return List.copyOf(names);
    }

    /**
     * Whether {@code qualifier} is a class that declares parameters — whether {@code <qualifier>.<name>} in
     * the user's source is a reference to one at all.
     */
    public static boolean isQualifier(ProjectConfig config, ProjectState state, String qualifier) {
        return qualifier != null && !qualifier.isBlank() && classes(config, state).contains(qualifier);
    }

    /**
     * Every category the window may file a parameter under: every string the bot's own fields actually use.
     *
     * <p>A {@code @Param}'s category is free text — the six the SDK used to declare were a vocabulary, and a
     * bot's author names their own — so the only way to know one exists is that something is filed under it.
     */
    public static List<String> categories(ProjectConfig config, ProjectState state) {
        return categories(config, state, PluginHost.grammar());
    }

    /** The same, against a given grammar. */
    public static List<String> categories(ProjectConfig config, ProjectState state, ValueGrammar grammar) {
        Set<String> out = new LinkedHashSet<>();
        for (JavaParameter parameter : scan(config, state, grammar)) {
            if (!parameter.row().category().isBlank()) out.add(parameter.row().category());
        }
        return List.copyOf(out);
    }

    // ---- changing ---------------------------------------------------------------------------------------
    //
    // Each one answers the row as it reads back, or empty when nothing was written. Empty is an ordinary
    // outcome — a field somebody else renamed while the window was open, a value the type cannot spell —
    // and the window says so rather than failing.

    /**
     * Sets a field's value and answers the row as it now stands.
     *
     * <p>The initialiser is re-read from the source afterwards rather than assumed, for the same reason a
     * plugin's answer used to be rendered rather than the edit: what the file says is what the bot runs.
     */
    public static Optional<ParameterRow> setValue(ProjectConfig config, ProjectState state,
                                                  JavaParameter parameter, JavaValue value) {
        return setValue(config, state, parameter, value, PluginHost.grammar());
    }

    /** The same, against a given grammar. */
    public static Optional<ParameterRow> setValue(ProjectConfig config, ProjectState state,
                                                  JavaParameter parameter, JavaValue value, ValueGrammar grammar) {
        if (parameter == null || !parameter.editable()) return Optional.empty();
        writeValue(config, state, parameter, value);
        return reread(config, state, parameter.className(), parameter.name(), grammar);
    }

    /**
     * Applies {@code wanted} to the field {@code parameter} names — name, type, category, note, visibility,
     * bounds, choices and value, each through the edit that owns it.
     *
     * <p><b>Only what differs is written, and the order matters.</b> The name first, because everything
     * after it is found by name; the type next, because retyping resets the value; the annotation members
     * together, because they are one annotation; the value last, because everything before it changes what a
     * value may be.
     */
    public static Outcome declare(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                  ParameterRow wanted, Type wantedForm) {
        return declare(config, state, parameter, wanted, wantedForm, PluginHost.grammar());
    }

    /**
     * The same, against a given grammar. {@code wantedForm} is the type the field should have, which the
     * row cannot say for itself — it carries the type's written name, not its tree.
     */
    public static Outcome declare(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                  ParameterRow wanted, Type wantedForm, ValueGrammar grammar) {
        return declare(config, state, parameter, wanted, wantedForm, grammar, guarded(state));
    }

    /**
     * The same, held to the compile check when {@code guarded}.
     *
     * <p><b>All or nothing, and never a bot that stops compiling</b> (2026-09-27). The declaration is several
     * edits in a row; a refusal part-way puts every file back as it was. And an edit that compiles in its own
     * file can break another — {@code Parameters.j} retyped to {@code String} breaks {@code int x =
     * Parameters.j} in {@code Base.java} — so the whole bot is compiled before and after ({@link BotIndex}),
     * and a new error anywhere puts everything back. An error the bot already had never refuses.
     */
    static Outcome declare(ProjectConfig config, ProjectState state, JavaParameter parameter, ParameterRow wanted,
                           Type wantedForm, ValueGrammar grammar, boolean guarded) {
        if (parameter == null || wanted == null) return gone(parameter);
        BotIndex was = BotIndex.of(config, state);
        Outcome outcome = apply(config, state, parameter, wanted, wantedForm, grammar);
        if (outcome instanceof Outcome.Refused) {
            putBack(config, state, was.sources());
            return outcome;
        }
        if (!guarded) return outcome;
        Optional<String> broke = was.firstNewError(BotIndex.of(config, state));
        if (broke.isEmpty()) return outcome;
        putBack(config, state, was.sources());
        return new Outcome.Refused("That would stop the bot compiling, so nothing was changed: " + broke.get());
    }

    /** {@link #declare}'s edits, one after the other, each re-reading the field the one before it moved. */
    private static Outcome apply(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                 ParameterRow wanted, Type wantedForm, ValueGrammar grammar) {
        String className = parameter.className();
        ParameterRow before = parameter.row();
        String name = before.name();

        if (!wanted.name().equals(name)) {
            Optional<String> refused = rename(config, state, parameter, wanted.name());
            if (refused.isPresent()) return new Outcome.Refused(refused.get());
            name = wanted.name();
        }
        JavaParameter held = find(config, state, className, name, grammar).orElse(null);
        if (held == null) return gone(parameter);

        if (wantedForm != null && !wantedForm.equals(parameter.form())) {
            retype(config, state, held, wantedForm, grammar);
            held = find(config, state, className, name, grammar).orElse(null);
            if (held == null) return gone(parameter);
            wanted = retyped(wanted, before, wantedForm, grammar);
        }

        Map<String, String> members = new LinkedHashMap<>();
        if (!wanted.category().equals(before.category())) members.put("category", wanted.category());
        if (!wanted.description().equals(before.description())) {
            members.put("description", wanted.description());
        }
        if (wanted.visibility() != before.visibility()) {
            // The annotation spells a visibility as the plugin's own id string — the contract's enum is off a
            // bot's classpath — and EDITOR is the default, so the editor-only case removes the member rather
            // than writing the default down.
            members.put("visibility", wanted.visibility() == Visibility.PUBLIC
                    ? Visibility.PUBLIC.id() : "");
        }
        // An infinite bound is the annotation's default, so it removes the member rather than writing
        // Double.NEGATIVE_INFINITY down.
        if (Double.compare(wanted.min(), before.min()) != 0) members.put("min", bound(wanted.min()));
        if (Double.compare(wanted.max(), before.max()) != 0) members.put("max", bound(wanted.max()));
        if (!members.isEmpty()) {
            setMembers(config, state, held, members);
            held = find(config, state, className, name, grammar).orElse(null);
            if (held == null) return gone(parameter);
        }

        if (!wanted.options().equals(before.options())) {
            setOptions(config, state, held, wanted.options());
            held = find(config, state, className, name, grammar).orElse(null);
            if (held == null) return gone(parameter);
        }

        if (!wanted.value().equals(before.value()) && held.editable()) {
            // The row's value is Java some version of this file held — a snapshot being restored — so it is
            // written back as that expression, kept.
            JavaParameter target = held;
            JavaValue.parse(wanted.value()).ifPresent(value -> writeValue(config, state, target, value));
        }
        return reread(config, state, className, name, grammar)
                .<Outcome>map(Outcome.Stored::new).orElseGet(() -> gone(parameter));
    }

    /**
     * What survives of {@code wanted} once the field is {@code to} rather than {@code from}.
     *
     * <p>The value was reset by the retype, so the row's old one is not written back over it. A choice stays
     * when it is still a value of the new type, or of its element ({@code int} to {@code List<Integer>} keeps
     * them as the ticks), and goes otherwise — which is also how a caller hands the new type's own choices
     * over with the retype (2026-09-27). A bound means something only for a number. Kept, a choice of the
     * old type drew as a disabled row holding the old type's Java.
     */
    private static ParameterRow retyped(ParameterRow wanted, ParameterRow before, Type to, ValueGrammar grammar) {
        ParameterRow.Builder kept = wanted.toBuilder().value(before.value())
                .options(ChoiceMode.kept(grammar, to, wanted.options()));
        Type leaf = ValueTypes.leaf(to);
        if (!(leaf instanceof Class<?> cls && NUMBERS.contains(cls))) {
            kept.bounds(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
        return kept.build();
    }

    /** The types a declared range means anything for: Java's numbers, boxed or not. */
    public static final Set<Class<?>> NUMBERS = Set.of(
            byte.class, short.class, int.class, long.class, float.class, double.class,
            Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class);

    /** A bound as the literal {@code @Param} takes — {@code 0}, {@code 2.5} — or {@code ""} for none. */
    static String bound(double value) {
        if (Double.isInfinite(value) || Double.isNaN(value)) return "";
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return Long.toString((long) value);
        return Double.toString(value);
    }

    /**
     * Declares a new field in {@code className}, creating that class when the project has none.
     *
     * <p>Answers the row as it reads back, or empty when nothing was written — a name already taken, a type
     * whose default cannot be spelled as Java, or a class that could not be created.
     */
    public static Optional<ParameterRow> add(ProjectConfig config, ProjectState state, String className,
                                             String name, Type form, JavaValue value,
                                             String category, String description) {
        return add(config, state, className, name, form, value, category, description,
                PluginHost.grammar());
    }

    /** The same, against a given grammar. {@code value} may be null: the field is declared with none. */
    public static Optional<ParameterRow> add(ProjectConfig config, ProjectState state, String className,
                                             String name, Type form, JavaValue value,
                                             String category, String description, ValueGrammar grammar) {
        if (config == null || className == null || className.isBlank()) return Optional.empty();
        if (find(config, state, className, name, grammar).isPresent()) return Optional.empty();
        if (!classes(config, state).contains(className) && !createClass(config, className)) {
            return Optional.empty();
        }
        rewriteAll(config, state, source -> JavaParameterEdits.add(
                source, grammar, className, name, form, value, category, description));
        return reread(config, state, className, name, grammar);
    }

    /**
     * Removes a parameter's declaration — <b>only when nothing uses it</b> (2026-09-27).
     *
     * <p>The same rule a function's delete has ({@code SignatureEdits.delete}): a use of a field that no
     * longer exists has no honest edit to become, so the uses come out first, by hand, and this says where
     * they are. Removing anyway and warning, which this did until then, left {@code Parameters.j} in a file
     * the user was not looking at and a bot that did not compile. A field whose uses cannot be told — its
     * file does not parse — is refused for the same reason.
     */
    public static Outcome remove(ProjectConfig config, ProjectState state, JavaParameter parameter) {
        if (parameter == null) return gone(null);
        BotIndex index = BotIndex.of(config, state);
        int start = fieldStart(index, parameter);
        Optional<List<Usages.Usage>> uses = start < 0 ? Optional.empty()
                : Refactor.uses(index, parameter.file(), start);
        if (uses.isEmpty()) {
            return new Outcome.Refused(parameter.qualified() + " could not be resolved, so whether anything "
                    + "still uses it cannot be told. Nothing was removed.");
        }
        if (!uses.get().isEmpty()) {
            return new Outcome.Refused(stillUsed(parameter, uses.get()), uses.get(),
                    inlined(index, parameter, start, uses.get().size()).stream().toList());
        }
        return rewrite(config, state, parameter.file(),
                source -> JavaParameterEdits.remove(source, parameter.className(), parameter.name()))
                ? new Outcome.Removed() : gone(parameter);
    }

    /** Why a remove cannot happen yet: how many uses, and which files they are in. */
    private static String stillUsed(JavaParameter parameter, List<Usages.Usage> uses) {
        List<String> where = uses.stream().map(use -> use.file().getFileName() + ":" + use.line())
                .distinct().limit(3).toList();
        return parameter.qualified() + " is still used " + uses.size() + (uses.size() == 1 ? " time" : " times")
                + ", in " + String.join(", ", where) + (uses.size() > where.size() ? ", …" : "")
                + ". Remove " + (uses.size() == 1 ? "that use" : "those uses") + " first.";
    }

    /**
     * The fix a refused remove offers when the parameter's value is a constant: every use replaced by that
     * value, a static import of it dropped, and the declaration removed — one plan, compiled as the whole bot
     * before it is offered. Nothing is offered when the value is a computation (each use would run it anew),
     * when a use writes the field, or when the copy would not compile where it lands (a constant needing an
     * import that file lacks).
     */
    private static Optional<Refactor.Fix> inlined(BotIndex index, JavaParameter parameter, int start, int count) {
        Path file = parameter.file().toAbsolutePath().normalize();
        Refactor.Planned plan = index.read(units -> {
            CompilationUnit declaring = units.get(file);
            if (declaring == null
                    || !(NodeFinder.perform(declaring, start, 0) instanceof SimpleName name)
                    || !(name.getParent() instanceof VariableDeclarationFragment fragment)
                    || !(fragment.getParent() instanceof FieldDeclaration field)
                    || !isConstant(fragment.getInitializer())) {
                return null;
            }
            String key = Usages.keyOf(fragment.resolveBinding());
            if (key == null) return null;
            Map<Path, String> rewrites = new LinkedHashMap<>();
            for (Map.Entry<Path, CompilationUnit> entry : units.entrySet()) {
                String text = index.sources().get(entry.getKey());
                if (!text.contains(parameter.name())) continue;
                ASTRewrite rewriter = ASTRewrite.create(entry.getValue().getAST());
                for (Usages.Usage use : Usages.in(entry.getKey(), text, entry.getValue(), key)) {
                    if (use.declaration()) continue;
                    ASTNode at = NodeFinder.perform(entry.getValue(), use.start(), 0);
                    if (!(at instanceof SimpleName used)) return null;
                    if (!inline(rewriter, used, fragment.getInitializer())) return null;
                }
                if (entry.getKey().equals(file)) {
                    rewriter.remove(field.fragments().size() == 1 ? field : fragment, null);
                }
                String rewritten = AstRewriteHelper.applyRewrite(rewriter, text);
                if (!rewritten.equals(text)) rewrites.put(entry.getKey(), rewritten);
            }
            return new Refactor.Planned("Replaced " + parameter.qualified() + " with its value and removed it",
                    rewrites);
        });
        if (plan == null) return Optional.empty();
        String value = initializerOf(index, file, start);
        return Refactor.checked(index, plan, "Replacing " + parameter.qualified() + " with its value")
                instanceof Refactor.Planned checked
                ? Optional.of(new Refactor.Fix("Replace " + (count == 1 ? "the use" : "the " + count + " uses")
                        + " with " + value + " and remove it", checked))
                : Optional.empty();
    }

    /** A value that is the same wherever it is written: a literal, a negative number, a named constant. */
    private static boolean isConstant(Expression value) {
        return switch (value) {
            case NumberLiteral ignored -> true;
            case StringLiteral ignored -> true;
            case TextBlock ignored -> true;
            case CharacterLiteral ignored -> true;
            case BooleanLiteral ignored -> true;
            case NullLiteral ignored -> true;
            case Name ignored -> true;
            case PrefixExpression prefix -> prefix.getOperator() == PrefixExpression.Operator.MINUS
                    && prefix.getOperand() instanceof NumberLiteral;
            case null, default -> false;
        };
    }

    /**
     * Records, in {@code rewriter}, what one use of the field becomes: a static import of it is dropped, and a
     * read — {@code j}, {@code Parameters.j}, {@code this.j} — is replaced by a copy of {@code value}. False for
     * a use that writes the field, which a value cannot stand in for.
     */
    private static boolean inline(ASTRewrite rewriter, SimpleName used, Expression value) {
        for (ASTNode n = used; n != null; n = n.getParent()) {
            if (n instanceof ImportDeclaration imported) {
                rewriter.remove(imported, null);
                return true;
            }
            if (n instanceof Statement || n instanceof BodyDeclaration) break;
        }
        ASTNode read = used.getParent() instanceof QualifiedName qualified && qualified.getName() == used ? qualified
                : used.getParent() instanceof FieldAccess access && access.getName() == used ? access : used;
        ASTNode parent = read.getParent();
        boolean written = parent instanceof Assignment assignment && assignment.getLeftHandSide() == read
                || parent instanceof PostfixExpression
                || parent instanceof PrefixExpression prefix
                && (prefix.getOperator() == PrefixExpression.Operator.INCREMENT
                || prefix.getOperator() == PrefixExpression.Operator.DECREMENT);
        if (written) return false;
        rewriter.replace(read, ASTNode.copySubtree(read.getAST(), value), null);
        return true;
    }

    private static String initializerOf(BotIndex index, Path file, int start) {
        return index.read(units -> NodeFinder.perform(units.get(file), start, 0) instanceof SimpleName name
                && name.getParent() instanceof VariableDeclarationFragment fragment
                && fragment.getInitializer() != null ? fragment.getInitializer().toString() : "");
    }

    /**
     * Writes a plan a refusal offered — every file it rewrites, buffer and disk. What the window does with a
     * fix the user picked.
     */
    public static void write(ProjectConfig config, ProjectState state, Refactor.Planned plan) {
        BotSources.forEach(config, state, (file, source) -> plan.rewrites().get(file));
    }

    /**
     * Writes an empty {@code @Param} holder class into the bot's package, and answers whether it is there.
     *
     * <p>A file appearing in a project is a bigger event than a field appearing in a file, so this is called
     * only when a person asks for a parameter and there is nowhere to put it. An existing file is never
     * overwritten — it is somebody's work, and a class that exists is a class a field can go into. The
     * write goes through {@link ProjectWrites}, which takes the history snapshot that makes a file the user
     * did not type undoable.
     */
    public static boolean createClass(ProjectConfig config, String className) {
        Path file = config.mainPackageDir().resolve(className + ".java");
        String source = """
                package %s;

                import %s;

                /**
                 * The bot's settings. Each field is one row of the Parameters window, and the bot reads it by
                 * name — <code>%s.restBetween</code> — so a misspelling is a compile error and the type is the
                 * type.
                 */
                public final class %s {

                    private %s() {}
                }
                """.formatted(config.mainPackage(), Param.class.getCanonicalName(), className,
                className, className);
        return ProjectWrites.create(config, file, source, "Create " + className);
    }

    /**
     * Every place in the bot that reads or writes this parameter, the declaration itself left out — found by
     * binding ({@link Usages}), so a local variable spelled the same and the name inside a string are not
     * uses, and a static import and a bare name inside the declaring class are. Empty when the field cannot
     * be resolved, which is not the same answer as "nothing uses it".
     */
    public static Optional<List<Usages.Usage>> uses(ProjectConfig config, ProjectState state,
                                                    JavaParameter parameter) {
        if (config == null || parameter == null) return Optional.empty();
        BotIndex index = BotIndex.of(config, state);
        int start = fieldStart(index, parameter);
        return start < 0 ? Optional.empty() : Refactor.uses(index, parameter.file(), start);
    }

    /** Where {@code parameter}'s name is declared in its file as the index read it, or -1. */
    private static int fieldStart(BotIndex index, JavaParameter parameter) {
        Path file = parameter.file().toAbsolutePath().normalize();
        return index.read(units -> Usages.fieldStart(units.get(file), parameter.className(), parameter.name()));
    }

    /** What an edit through this class did. */
    public sealed interface Outcome {

        /** Written, and the row as the source now reads it. */
        record Stored(ParameterRow row) implements Outcome {}

        /** The declaration is gone. */
        record Removed() implements Outcome {}

        /**
         * Nothing was written, and the sentence that says why — {@link Refactor.Refused}'s shape, so the window
         * shows it through the one refusal dialog.
         *
         * @param uses  where the bot still uses the field, when that is the reason — for the window to offer
         * @param fixes what could be done instead, each a checked plan ({@link #write})
         */
        record Refused(String reason, List<Usages.Usage> uses, List<Refactor.Fix> fixes) implements Outcome {
            public Refused {
                uses = List.copyOf(uses);
                fixes = List.copyOf(fixes);
            }

            public Refused(String reason) {
                this(reason, List.of(), List.of());
            }

            /** As the refusal every refactor shows. */
            public Refactor.Refused asRefactor() {
                return new Refactor.Refused(reason, uses, fixes);
            }
        }

        /** The row a {@link Stored} holds, and empty for anything else. */
        default Optional<ParameterRow> stored() {
            return this instanceof Stored stored ? Optional.of(stored.row()) : Optional.empty();
        }
    }

    /** The refusal for a field that is not where the caller last saw it. */
    private static Outcome gone(JavaParameter parameter) {
        return new Outcome.Refused(parameter == null ? "There is no such parameter."
                : "“" + parameter.name() + "” could not be found. It may have been changed in the meantime.");
    }

    /** Puts every file that differs from {@code before} back, buffer and disk. */
    private static void putBack(ProjectConfig config, ProjectState state, Map<Path, String> before) {
        BotSources.forEach(config, state, (file, source) -> {
            String was = before.get(file);
            return was == null || was.equals(source) ? null : was;
        });
    }

    /**
     * Whether {@link #declare} holds an edit to the compile check: only with a resolved classpath. Without one
     * every type a plugin declares reads as unknown, so a retype to one would refuse — the same condition
     * {@code CodeEditor.wouldNotCompile} applies to a canvas edit.
     */
    private static boolean guarded(ProjectState state) {
        return state != null && !state.getResolvedClasspath().isEmpty();
    }

    // ---- the single-field edits ---------------------------------------------------------------------
    //
    // Private, because each one leaves the project in a state only declare() knows is finished: a retype
    // resets the value, an annotation rewrite has to be re-read before the next edit finds the field. Each
    // answers whether anything changed.

    /** Replaces a parameter's value with {@code initializer}'s tree. */
    private static boolean writeValue(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                      JavaValue initializer) {
        return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.setValue(
                source, parameter.className(), parameter.name(), initializer));
    }

    /**
     * Renames a parameter and repoints every reference to it, in every file — {@link Refactor#rename}, the
     * one way a name changes, which renames exactly what binds to the field (the declaration,
     * {@code Parameters.x}, a static import, a bare {@code x} inside the class) and refuses a rename that
     * would not compile. A field that cannot be resolved is not renamed at all rather than renamed by guess.
     *
     * @return why nothing was renamed, or empty when it was
     */
    private static Optional<String> rename(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                           String newName) {
        BotIndex index = BotIndex.of(config, state);
        int start = fieldStart(index, parameter);
        if (start < 0) {
            return Optional.of(parameter.qualified() + " could not be resolved, so the places that use it "
                    + "cannot be found. Nothing was renamed.");
        }
        return switch (Refactor.rename(index, parameter.file(), start, newName)) {
            case Refactor.Refused refused -> Optional.of(refused.reason());
            case Refactor.Planned plan -> {
                write(config, state, plan);
                yield Optional.empty();
            }
        };
    }

    /** Changes a parameter's type, resetting its value to that type's default. */
    private static boolean retype(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                  Type form, ValueGrammar grammar) {
        return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.retype(
                source, grammar, parameter.className(), parameter.name(), form));
    }

    /** Sets or clears {@code @Param} members — a blank value removes the member. */
    private static boolean setMembers(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                      Map<String, String> members) {
        return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.setMembers(
                source, parameter.className(), parameter.name(), members));
    }

    /** Sets or clears {@code @Param(options = …)}. */
    private static boolean setOptions(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                      List<String> options) {
        return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.setOptions(
                source, parameter.className(), parameter.name(), options));
    }

    // ---- plumbing ---------------------------------------------------------------------------------------

    /** The field called {@code name} in {@code className}, as the source reads <em>now</em>. */
    public static Optional<JavaParameter> find(ProjectConfig config, ProjectState state, String className,
                                               String name, ValueGrammar grammar) {
        for (JavaParameter parameter : scan(config, state, grammar)) {
            if (parameter.is(className, name)) return Optional.of(parameter);
        }
        return Optional.empty();
    }

    private static Optional<ParameterRow> reread(ProjectConfig config, ProjectState state, String className,
                                                 String name, ValueGrammar grammar) {
        return find(config, state, className, name, grammar).map(JavaParameter::row);
    }

    /** Applies {@code edit} to one file, buffer and disk, and answers whether it changed anything. */
    private static boolean rewrite(ProjectConfig config, ProjectState state, Path file,
                                   UnaryOperator<String> edit) {
        if (config == null || file == null) return false;
        boolean[] changed = {false};
        BotSources.forEach(config, state, (visited, source) -> {
            if (!visited.equals(file.toAbsolutePath().normalize())) return null;
            String rewritten = edit.apply(source);
            if (rewritten.equals(source)) return null;
            changed[0] = true;
            return rewritten;
        });
        return changed[0];
    }

    /** Applies {@code edit} to every file — a rename, and an add whose target class could be anywhere. */
    private static boolean rewriteAll(ProjectConfig config, ProjectState state, UnaryOperator<String> edit) {
        if (config == null) return false;
        boolean[] changed = {false};
        BotSources.forEach(config, state, (visited, source) -> {
            String rewritten = edit.apply(source);
            if (rewritten.equals(source)) return null;
            changed[0] = true;
            return rewritten;
        });
        return changed[0];
    }
}
