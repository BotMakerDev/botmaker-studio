package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.params.Param;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.source.ParameterWrite;
import com.botmaker.plugin.api.source.RunParameter;
import com.botmaker.java.index.nav.Refactor;
import com.botmaker.java.index.nav.Usages;
import com.botmaker.java.edit.helpers.AstRewriteHelper;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ParamValues;
import com.botmaker.java.grammar.ParamsFitter;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectWrites;
import com.botmaker.studio.project.managed.ManagedConstants;
import com.botmaker.studio.project.managed.ManagedHolders;
import com.botmaker.java.index.BotIndex;
import com.botmaker.studio.services.BotSources;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
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
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
        return over(BotSources.index(config, state), grammar);
    }

    /**
     * Every {@code @Param} field in {@code index} — the bot's one parse, so a field's annotation and type are
     * resolved against the same trees every rename and usage search reads.
     *
     * <p>Two walks over those trees: a field may be typed with a record declared in a file the parameter walk
     * has not reached yet, so what the bot declares has to be known in full before the first field is read.
     */
    public static List<JavaParameter> over(BotIndex index, ValueGrammar grammar) {
        // Once per index and bind: blocks ask it on every render (2026-10-18). A bind replaces both of these.
        List<ManagedValue<?>> managed = PluginHost.managedValues();
        return index.memo(managed, Arrays.asList(JavaParameters.class, grammar), units -> {
            // The bot's own constants, so Flows.MAIN or Activities.Rest.class in an initialiser reads as a value.
            ManagedConstants.Lookup names = new ManagedConstants.Lookup(ManagedConstants.over(index), grammar);
            BotRecords records = BotRecords.over(grammar, units.values());
            List<JavaParameter> out = new ArrayList<>();
            units.forEach((file, unit) -> out.addAll(
                    JavaParameterSource.read(file, index.sources().get(file), unit, grammar, records, names,
                            managed)));
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
     * The classes a new parameter may go into: those declaring one that is the bot's own (2026-11-04). A plugin's
     * file — {@code plugins/sdk/Sdk.java} — is the plugin's, so a parameter the bot's author adds never lands there.
     */
    public static List<String> ownClasses(ProjectConfig config, ProjectState state) {
        Set<String> names = new LinkedHashSet<>();
        Set<String> plugins = new HashSet<>();
        for (JavaParameter parameter : scan(config, state)) {
            if (parameter.declaredByPlugin()) plugins.add(parameter.className());
            else names.add(parameter.className());
        }
        names.removeAll(plugins);
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
        BotIndex was = BotSources.index(config, state);
        // A group's starting copy is outside the sources, so putBack does not hold it: kept here to put back too.
        Optional<Path> starting = parameter.group()
                ? startingFile(config, parameter.file()).filter(Files::isRegularFile) : Optional.empty();
        Optional<String> startingWas = starting.flatMap(file -> currentSource(null, file));
        Outcome outcome = apply(config, state, parameter, wanted, wantedForm, grammar);
        if (outcome instanceof Outcome.Refused) {
            putBack(config, state, was.sources());
            putBackStarting(config, starting, startingWas);
            return outcome;
        }
        if (!guarded) return outcome;
        Optional<String> broke = was.firstNewError(BotSources.index(config, state));
        if (broke.isEmpty()) return outcome;
        putBack(config, state, was.sources());
        putBackStarting(config, starting, startingWas);
        return new Outcome.Refused("That would stop the bot compiling, so nothing was changed: " + broke.get());
    }

    /** Writes {@code was} back to the starting copy {@code file} when it changed. */
    private static void putBackStarting(ProjectConfig config, Optional<Path> file, Optional<String> was) {
        if (file.isEmpty() || was.isEmpty()) return;
        if (currentSource(null, file.get()).filter(was.get()::equals).isPresent()) return;
        ProjectWrites.replace(config, file.get(), was.get(), "Put back the starting copy of " + file.get().getFileName());
    }

    /** {@link #declare}'s edits, one after the other, each re-reading the field the one before it moved. */
    private static Outcome apply(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                 ParameterRow wanted, Type wantedForm, ValueGrammar grammar) {
        String className = parameter.className();
        ParameterRow before = parameter.row();
        String name = before.name();
        // A plugin's parameter keeps its declaration: the bot's own code reads the field by name and type.
        boolean declarationKept = wanted.toBuilder().value(before.value()).build().equals(before)
                && (wantedForm == null || wantedForm.equals(parameter.form()));
        if (parameter.declaredByPlugin() && !declarationKept) {
            return new Outcome.Refused(parameter.qualified() + " is a plugin's parameter: only its value can be"
                    + " changed. " + parameter.owner());
        }
        if (parameter.group()) return applyToGroup(config, state, parameter, wanted, wantedForm, grammar);

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
            wanted = retyped(wanted, before);
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
        if (!members.isEmpty()) {
            setMembers(config, state, held, members);
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
     * What survives of {@code wanted} once the field is retyped: everything but the value, which the retype
     * reset, so the row's old one is not written back over it.
     */
    private static ParameterRow retyped(ParameterRow wanted, ParameterRow before) {
        return wanted.toBuilder().value(before.value()).build();
    }

    /**
     * Declares a new parameter in {@code className}: a method of the bot's group, created when the project has no
     * class of that name, or a field of an older field class.
     *
     * <p>A group's parameter is written to {@code params/} with {@code value} (its type's blank value when null), and
     * to its starting copy with the blank value only (2026-11-04): what the author typed stays on this computer.
     *
     * <p>Answers the row as it reads back, or empty when nothing was written — a name already taken in either copy, a
     * type with no blank value, or a class that could not be created.
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
        // A plugin's file takes no parameter of the author's.
        if (classes(config, state).contains(className) && !ownClasses(config, state).contains(className)) {
            return Optional.empty();
        }
        // A group, or a class the bot has none of yet (2026-11-04): a method in params/ and in its starting copy, so
        // the fitter keeps it. Only the declaration passes to the committed copy: it holds the type's blank value, and
        // this computer's stays private until Make this the starting value (the user, 2026-11-04).
        Optional<Path> group = groupFile(config, state, className, grammar);
        boolean asGroup = group.isPresent() || !classes(config, state).contains(className);
        Optional<JavaValue> blank = asGroup ? grammar.freshSpelling(form) : Optional.empty();
        if (asGroup && blank.isEmpty()) return Optional.empty();
        if (asGroup && group.isEmpty()) group = createGroup(config, state, className);
        if (group.isPresent()) {
            Function<JavaValue, UnaryOperator<String>> adding = start -> source -> JavaParameterEdits.addMethod(
                    source, grammar, className, name, form, start, category, description, Param.Changes.OUTSIDE_RUN);
            // The starting copy first: a parameter it lacks, or already holds under that name, the fit would drop or
            // reset, so the private copy is written only once the starting one took the declaration.
            if (!rewriteStarting(config, group.get(), adding.apply(blank.get()))) return Optional.empty();
            if (!rewrite(config, state, group.get(), adding.apply(value != null ? value : blank.get()))) {
                return Optional.empty();
            }
            return reread(config, state, className, name, grammar);
        }
        if (!classes(config, state).contains(className)) {
            Optional<Path> holder = createClass(config, className);
            if (holder.isEmpty()) return Optional.empty();
            // Known to the editor at once: a block naming it imports it from its package by this file.
            Path created = holder.get();
            if (state != null && state.getFile(created).isEmpty()) {
                try {
                    state.addFile(new ProjectFile(created, Files.readString(created)));
                } catch (IOException unreadable) {
                    // Read from disk by the next walk instead; only the import is lost meanwhile.
                }
            }
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
        if (parameter.declaredByPlugin()) {
            return new Outcome.Refused(parameter.qualified() + " is a plugin's parameter, and BotMaker writes it"
                    + " back while the plugin is in the project. " + parameter.owner());
        }
        if (parameter.group()) {
            return new Outcome.Refused(parameter.name() + " is a parameter of the group " + parameter.className()
                    + ": it cannot be removed here yet. Remove it from both " + parameter.className()
                    + ".java files, in params/ and starting-params/.");
        }
        BotIndex index = BotSources.index(config, state);
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
        ASTNode read = wholeUse(used);
        if (writes(read)) return false;
        rewriter.replace(read, ASTNode.copySubtree(read.getAST(), value), null);
        return true;
    }

    /** The whole expression a field's name is the end of: {@code j}, {@code Parameters.j}, {@code this.j}. */
    static ASTNode wholeUse(SimpleName used) {
        return used.getParent() instanceof QualifiedName qualified && qualified.getName() == used ? qualified
                : used.getParent() instanceof FieldAccess access && access.getName() == used ? access : used;
    }

    /** Whether {@code use}, a {@link #wholeUse}, assigns the field rather than reading it. */
    static boolean writes(ASTNode use) {
        ASTNode parent = use.getParent();
        return parent instanceof Assignment assignment && assignment.getLeftHandSide() == use
                || parent instanceof PostfixExpression
                || parent instanceof PrefixExpression prefix
                && (prefix.getOperator() == PrefixExpression.Operator.INCREMENT
                || prefix.getOperator() == PrefixExpression.Operator.DECREMENT);
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
     * Writes an empty {@code @Param} holder class into the parameters' package
     * ({@link ProjectConfig#parametersPackage()}, since 2026-10-13), and answers where it is; empty when it
     * could not be written.
     *
     * <p>A file appearing in a project is a bigger event than a field appearing in a file, so this is called
     * only when a person asks for a parameter and there is nowhere to put it. An existing file is never
     * overwritten — it is somebody's work, and a class that exists is a class a field can go into. The
     * write goes through {@link ProjectWrites}, which takes the history snapshot that makes a file the user
     * did not type undoable.
     */
    public static Optional<Path> createClass(ProjectConfig config, String className) {
        // A bot made before 2026-10-13 has its Parameters.java beside its classes: a class with no field left
        // is still the one a field goes into, never a second of the same name in params.
        Path beside = config.mainPackageDir().resolve(className + ".java");
        if (Files.isRegularFile(beside)) return Optional.of(beside);
        if (!isClassName(className)) return Optional.empty();
        Path file = config.parametersPackageDir().resolve(className + ".java");
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        TypeDeclaration holder = ast.newTypeDeclaration();
        holder.setName(ast.newSimpleName(className));
        holder.setJavadoc(ManagedHolders.javadoc(ast, List.of(
                "The bot's settings. Each field is one row of the Parameters window, and the bot reads it by",
                "name — <code>" + className + ".restBetween</code> — so a misspelling is a compile error and the type is the",
                "type.")));
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> modifiers = holder.modifiers();
        modifiers.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        modifiers.add(ast.newModifier(Modifier.ModifierKeyword.FINAL_KEYWORD));
        MethodDeclaration constructor = ast.newMethodDeclaration();
        constructor.setConstructor(true);
        constructor.setName(ast.newSimpleName(className));
        constructor.setBody(ast.newBlock());
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> constructorModifiers = constructor.modifiers();
        constructorModifiers.add(ast.newModifier(Modifier.ModifierKeyword.PRIVATE_KEYWORD));
        @SuppressWarnings("unchecked")
        List<BodyDeclaration> members = holder.bodyDeclarations();
        members.add(constructor);
        String source = ManagedHolders.unit(ast, config.parametersPackage(), Set.of(Param.class.getCanonicalName()), holder);
        return ProjectWrites.create(config, file, source, "Create " + className) ? Optional.of(file) : Optional.empty();
    }

    private static boolean isClassName(String name) {
        return SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name);
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
        BotIndex index = BotSources.index(config, state);
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
        // A group's value is this computer's, in params/ only: the starting copy changes by its own action.
        if (parameter.group()) {
            return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.setReturned(
                    source, parameter.className(), parameter.name(), initializer));
        }
        return rewrite(config, state, parameter.file(), source -> JavaParameterEdits.setValue(
                source, parameter.className(), parameter.name(), initializer));
    }

    // ---- a group's parameters (2026-11-04) -----------------------------------------------------------------
    //
    // A group lives whole in params/, never versioned; its starting copy, the same path under starting-params/ at
    // the bot's root, is committed. A value edit writes params/ only; what the parameter is (category, description,
    // when it changes) and a new parameter go to both, so the fitter keeps them.

    /** {@link #apply} for a method of a group: its value in params/, what it is shown as in both copies. */
    private static Outcome applyToGroup(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                        ParameterRow wanted, Type wantedForm, ValueGrammar grammar) {
        ParameterRow before = parameter.row();
        if (wantedForm != null && !wantedForm.equals(parameter.form())) {
            return new Outcome.Refused(parameter.name() + " is a parameter of the group " + parameter.className()
                    + ": its type cannot be changed here yet. Add a new one and stop using this one.");
        }
        if (!wanted.name().equals(before.name())) {
            Optional<String> refused = renameInGroup(config, state, parameter, wanted.name());
            if (refused.isPresent()) return new Outcome.Refused(refused.get());
            JavaParameter held = find(config, state, parameter.className(), wanted.name(), grammar).orElse(null);
            return held == null ? gone(parameter) : applyToGroup(config, state, held, wanted, wantedForm, grammar);
        }
        Map<String, String> members = new LinkedHashMap<>();
        if (!wanted.category().equals(before.category())) members.put("category", wanted.category());
        if (!wanted.description().equals(before.description())) members.put("description", wanted.description());
        if (wanted.changes() != before.changes()) members.put("changes", wanted.changes().id());
        if (!members.isEmpty()) {
            UnaryOperator<String> shown = source -> JavaParameterEdits.setShown(
                    source, parameter.className(), parameter.name(), members);
            if (!rewrite(config, state, parameter.file(), shown)) {
                return new Outcome.Refused("How " + parameter.name() + " is shown could not be written.");
            }
            rewriteStarting(config, parameter.file(), shown);
        }
        if (!wanted.value().equals(before.value())) {
            if (before.duringRun() || !parameter.editable()) {
                return new Outcome.Refused(parameter.name() + "'s value cannot be set here: "
                        + (before.duringRun() ? "the bot writes it during a run." : parameter.note()));
            }
            Optional<JavaValue> value = JavaValue.parse(wanted.value());
            if (value.isEmpty() || !writeValue(config, state, parameter, value.get())) {
                return new Outcome.Refused(parameter.name() + "'s value “" + wanted.value() + "” could not be written.");
            }
        }
        return reread(config, state, parameter.className(), parameter.name(), grammar)
                .<Outcome>map(Outcome.Stored::new).orElseGet(() -> gone(parameter));
    }

    /**
     * Renames a group's parameter (2026-11-04): the method and every use of it by binding ({@link Refactor#rename}),
     * then the same name in the starting copy, which no binding reaches, and in both copies the rename rule that moves
     * this computer's value on every other copy of the bot when it next fits ({@link JavaParameterEdits#renamedInGroup}).
     *
     * @return why nothing was renamed, or empty when it was
     */
    private static Optional<String> renameInGroup(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                                  String newName) {
        String group = parameter.className();
        String oldName = parameter.name();
        Optional<String> starting = startingFile(config, parameter.file()).filter(Files::isRegularFile)
                .flatMap(file -> currentSource(null, file));
        if (starting.isPresent() && JavaParameterEdits.hasParameter(starting.get(), group, newName)) {
            return Optional.of("The starting copy of " + group + " already has " + newName + ". Nothing was renamed.");
        }
        Path file = parameter.file().toAbsolutePath().normalize();
        Optional<String> refused = renameAt(config, state, parameter, newName,
                units -> JavaParameterEdits.parameterStart(units.get(file), group, oldName));
        if (refused.isPresent()) return refused;
        UnaryOperator<String> ruled = source -> JavaParameterEdits.renamedInGroup(source, group, oldName, newName);
        // A refusal puts every file back (declare): a starting copy left at the old name would drop the value.
        if (!rewrite(config, state, parameter.file(), ruled)
                || starting.isPresent() && !rewriteStarting(config, parameter.file(), ruled)) {
            return Optional.of("The rename rule for " + oldName + " could not be written, so nothing was renamed.");
        }
        return Optional.empty();
    }

    /**
     * Makes {@code parameter}'s value on this computer its starting value: the one value copied into its starting
     * copy, with the imports it needs ({@link ParamsFitter#withStartingValue}). Answers whether the starting copy changed.
     */
    public static boolean makeStarting(ProjectConfig config, ProjectState state, JavaParameter parameter) {
        if (config == null || parameter == null || !parameter.group()) return false;
        Optional<String> mine = currentSource(state, parameter.file());
        return mine.isPresent() && rewriteStarting(config, parameter.file(),
                starting -> ParamsFitter.withStartingValue(starting, mine.get(), parameter.name()));
    }

    /**
     * Sets {@code parameter} back to its starting value: what a during-a-run parameter the bot wrote is reset with.
     * Answers the row as it now reads, or empty when there is no starting copy to read it from.
     */
    public static Optional<ParameterRow> forget(ProjectConfig config, ProjectState state, JavaParameter parameter) {
        return forget(config, state, parameter, PluginHost.grammar());
    }

    /** The same, against a given grammar. */
    public static Optional<ParameterRow> forget(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                                ValueGrammar grammar) {
        if (config == null || parameter == null || !parameter.group()) return Optional.empty();
        Optional<String> starting = startingFile(config, parameter.file()).flatMap(file -> currentSource(null, file));
        if (starting.isEmpty()) return Optional.empty();
        rewrite(config, state, parameter.file(),
                source -> ParamsFitter.withValue(source, starting.get(), parameter.name()));
        return reread(config, state, parameter.className(), parameter.name(), grammar);
    }

    /**
     * The bot's during-a-run parameters as a plugin sees them (2026-11-04): those of a group, with their age. A
     * parameter changed outside a run is the person's, possibly a secret, and is never among them.
     */
    public static List<RunParameter> duringRun(ProjectConfig config, ProjectState state, ValueGrammar grammar) {
        if (config == null) return List.of();
        List<RunParameter> out = new ArrayList<>();
        for (JavaParameter parameter : scan(config, state, grammar)) {
            if (!parameter.group() || !parameter.row().duringRun()) continue;
            Optional<Instant> kept = currentSource(state, parameter.file()).flatMap(
                    source -> ParamValues.keptAt(source, parameter.className(), parameter.name()));
            out.add(new RunParameter(parameter.className(), parameter.name(), parameter.row().typeName(),
                    parameter.row().value(), kept));
        }
        return List.copyOf(out);
    }

    /**
     * Adds {@code write} as a during-a-run parameter of its group, in {@code params/} and the starting copy, the group
     * made when the bot has none of that name. Answers the sentence to show when nothing was written.
     */
    public static Optional<String> addDuringRun(ProjectConfig config, ProjectState state, ParameterWrite write,
                                                Function<ParameterWrite.ClassOf, Optional<JavaParameterEdits.ClassName>> classes,
                                                ValueGrammar grammar) {
        String group = write.group();
        String name = write.name();
        if (config == null) return Optional.of("No project is open.");
        if (!SourceVersion.isName(name) || !SourceVersion.isName(group)) {
            return Optional.of("“" + group + "." + name + "” is not a name Java accepts.");
        }
        if (find(config, state, group, name, grammar).isPresent()) {
            return Optional.of(group + " already has a parameter " + name + ".");
        }
        if (classes(config, state).contains(group) && !ownClasses(config, state).contains(group)) {
            return Optional.of(group + " is a plugin's file; it takes no parameter of the author's.");
        }
        Type form = switch (write.start()) {
            case Integer ignored -> int.class;
            case Boolean ignored -> boolean.class;
            default -> String.class;
        };
        Optional<JavaValue> start = grammar.spellData(form, write.start());
        if (start.isEmpty()) return Optional.of("The starting value of " + name + " cannot be written.");
        Optional<Path> file = groupFile(config, state, group, grammar);
        if (file.isEmpty() && !classes(config, state).contains(group)) file = createGroup(config, state, group);
        if (file.isEmpty()) return Optional.of(group + " is no group of parameters in this bot.");
        Optional<String> starting = startingFile(config, file.get()).flatMap(path -> currentSource(null, path));
        if (starting.isEmpty()) {
            return Optional.of(group + " has no starting copy, so a new parameter would not last.");
        }
        UnaryOperator<String> added = source -> {
            String declared = JavaParameterEdits.addMethod(source, grammar, group, name, form, start.get(), "", "",
                    Param.Changes.DURING_RUN);
            return declared.equals(source) ? source : JavaParameterEdits.withMarks(declared, group, name,
                    write.marks(), write.groupMarks(), classes).orElse(source);
        };
        // Asked of the starting copy first: the two files take the parameter together or not at all.
        if (added.apply(starting.get()).equals(starting.get())
                || !rewrite(config, state, file.get(), added)) {
            return Optional.of(name + " could not be written in " + group + ".");
        }
        return rewriteStarting(config, file.get(), added) ? Optional.empty()
                : Optional.of(name + " is in " + group + ", but its starting copy could not be written.");
    }

    /** {@link #forget} for a plugin, by name: answers the sentence to show when nothing was set back. */
    public static Optional<String> forgetDuringRun(ProjectConfig config, ProjectState state, String group, String name,
                                                   ValueGrammar grammar) {
        Optional<JavaParameter> parameter = find(config, state, group, name, grammar)
                .filter(found -> found.group() && found.row().duringRun());
        if (parameter.isEmpty()) return Optional.of(group + " has no during-a-run parameter " + name + ".");
        boolean inStarting = startingFile(config, parameter.get().file()).flatMap(path -> currentSource(null, path))
                .filter(source -> JavaParameterEdits.hasParameter(source, group, name)).isPresent();
        return inStarting && forget(config, state, parameter.get(), grammar).isPresent() ? Optional.empty()
                : Optional.of(group + " has no starting copy to read " + name + " from.");
    }

    /** Whether {@code className} is a group: a {@code @Param} class in params/, by any parameter read from it. */
    private static Optional<Path> groupFile(ProjectConfig config, ProjectState state, String className,
                                            ValueGrammar grammar) {
        for (JavaParameter parameter : scan(config, state, grammar)) {
            if (parameter.group() && parameter.className().equals(className)) return Optional.of(parameter.file());
        }
        Path own = ownGroupFile(config, className);
        return Files.isRegularFile(own) ? Optional.of(own) : Optional.empty();
    }

    /** Where the bot's own group {@code className} is: {@code params/bot/<Class>.java}. */
    static Path ownGroupFile(ProjectConfig config, String className) {
        return config.parametersPackageDir().resolve(JavaParameterSource.BOT).resolve(className + ".java");
    }

    /** The source of the bot's own group {@code className} with no parameter yet. */
    static String emptyGroup(ProjectConfig config, String className) {
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        TypeDeclaration group = ast.newTypeDeclaration();
        group.setName(ast.newSimpleName(className));
        group.setJavadoc(ManagedHolders.javadoc(ast, List.of(
                "Parameters of this bot. Each method is one, whole: its name, its type and, as what it returns, its",
                "value on this computer. This file is never versioned; its starting copy is.")));
        MarkerAnnotation param = ast.newMarkerAnnotation();
        param.setTypeName(ast.newSimpleName(Param.class.getSimpleName()));
        @SuppressWarnings("unchecked")
        List<IExtendedModifier> modifiers = group.modifiers();
        modifiers.add(param);
        modifiers.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        return ManagedHolders.unit(ast, config.parametersPackage() + "." + JavaParameterSource.BOT,
                Set.of(Param.class.getCanonicalName()), group);
    }

    /**
     * Creates the bot's own group {@code className}, an empty {@code @Param} class, in params/ and in its starting
     * copy, the params one known to the editor at once; answers its params file, or empty when it could not be
     * written. An existing file is kept.
     */
    private static Optional<Path> createGroup(ProjectConfig config, ProjectState state, String className) {
        if (!isClassName(className)) return Optional.empty();
        Path file = ownGroupFile(config, className);
        String source = emptyGroup(config, className);
        Optional<Path> starting = startingFile(config, file);
        if (starting.isEmpty() || !ProjectWrites.create(config, file, source, "Create " + className)) {
            return Optional.empty();
        }
        ProjectWrites.create(config, starting.get(), source, "Create " + className);
        if (state != null && state.getFile(file).isEmpty()) {
            try {
                state.addFile(new ProjectFile(file, Files.readString(file)));
            } catch (IOException unreadable) {
                // Read from disk by the next walk instead.
            }
        }
        return Optional.of(file);
    }

    /** The starting copy of the params file {@code file}: the same path under {@code starting-params/}. */
    public static Optional<Path> startingFile(ProjectConfig config, Path file) {
        Path params = config.parametersPackageDir().toAbsolutePath().normalize();
        Path at = file.toAbsolutePath().normalize();
        if (!at.startsWith(params)) return Optional.empty();
        return Optional.of(config.projectPath().resolve(ParamsFitter.STARTING).resolve(params.relativize(at)));
    }

    /**
     * Applies {@code edit} to the starting copy of {@code file}, on disk (it is outside the sources, so no editor
     * holds it), and answers whether it changed. Nothing is done when there is no starting copy.
     */
    public static boolean rewriteStarting(ProjectConfig config, Path file, UnaryOperator<String> edit) {
        Optional<Path> starting = startingFile(config, file).filter(Files::isRegularFile);
        if (starting.isEmpty()) return false;
        Optional<String> was = currentSource(null, starting.get());
        if (was.isEmpty()) return false;
        String now = edit.apply(was.get());
        return !now.equals(was.get()) && ProjectWrites.replace(config, starting.get(), now,
                "Change the starting copy of " + starting.get().getFileName());
    }

    /** {@code file} as the editor holds it, else as the disk does; empty when unreadable. */
    private static Optional<String> currentSource(ProjectState state, Path file) {
        if (state != null) {
            Optional<ProjectFile> open = state.getFile(file);
            if (open.isPresent()) return Optional.of(open.get().getContent());
        }
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
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
        Path file = parameter.file().toAbsolutePath().normalize();
        return renameAt(config, state, parameter, newName,
                units -> Usages.fieldStart(units.get(file), parameter.className(), parameter.name()));
    }

    /** The same for a declaration whose name {@code start} finds in the bot's parse, or -1. */
    private static Optional<String> renameAt(ProjectConfig config, ProjectState state, JavaParameter parameter,
                                             String newName, Function<Map<Path, CompilationUnit>, Integer> start) {
        BotIndex index = BotSources.index(config, state);
        int at = index.read(start);
        if (at < 0) {
            return Optional.of(parameter.qualified() + " could not be resolved, so the places that use it "
                    + "cannot be found. Nothing was renamed.");
        }
        return switch (Refactor.rename(index, parameter.file(), at, newName)) {
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

    /**
     * Takes {@code @Param}'s removed members ({@code min}, {@code max}, {@code options}) off every field in the
     * bot (2026-10-29), so a bot written against contract 0.13 compiles against 0.14. Answers one sentence per
     * file changed, naming its fields; none when there was nothing to take off. Run by the upgrade pass.
     */
    public static List<String> stripRemovedMembers(ProjectConfig config, ProjectState state) {
        if (config == null) return List.of();
        List<String> said = new ArrayList<>();
        BotSources.forEach(config, state, (visited, source) -> {
            if (!source.contains("Param")) return null;   // a cheap skip; the parse decides what an @Param is
            JavaParameterEdits.Stripped stripped = JavaParameterEdits.stripRemovedMembers(source);
            if (stripped.fields().isEmpty()) return null;
            said.add("@Param no longer takes min, max or options; removed from " + String.join(", ", stripped.fields())
                    + " in " + visited.getFileName() + ".");
            return stripped.source();
        });
        return List.copyOf(said);
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
