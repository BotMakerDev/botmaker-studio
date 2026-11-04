package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.params.Param;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.host.grammar.BotNames;
import com.botmaker.plugin.host.grammar.JavaExpressions;
import com.botmaker.plugin.host.grammar.ParamsFitter;
import com.botmaker.plugin.host.grammar.SourceNode;
import com.botmaker.plugin.host.grammar.ValueGrammar;
import com.botmaker.plugin.host.grammar.ValueTypes;
import com.botmaker.studio.project.managed.ManagedIds;
import com.botmaker.studio.project.source.BotAnnotation;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.project.source.ValueTypeResolver;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads {@code @Param} fields out of <b>one</b> Java source, with no project, no filesystem and no host.
 *
 * <p><b>Pure on purpose.</b> Everything about walking a project — buffers before files, which files are the
 * bot's, writing both copies back — belongs to {@link JavaParameters} and to {@code BotSources}, and none
 * of it is what goes wrong when a field is read incorrectly. Splitting the parse out is what lets the
 * reading be tested over source text, headlessly, without a project on disk.
 *
 * <p><b>Bindings when the caller has a project</b> ({@link BotParser}, 2026-09-24): {@code @Param} and its
 * constants are identified by class ({@link BotAnnotation#PARAM}), through the unit's imports when there is
 * no classpath, never by a name that ends in {@code Param}. A bot whose dependencies do not resolve still
 * shows its parameters: binding recovery keeps the tree, and the imports still say which annotation it is.
 * A field's type is resolved the same way, once, by {@link ValueTypeResolver}.
 */
public final class JavaParameterSource {

    private JavaParameterSource() {}

    /**
     * One expression parsed on its own, or {@code null} when the text is not one.
     *
     * <p>A real parser rather than a bracket-matching split, because reading a value means reading Java this
     * host did not necessarily write: {@code new Point(x + 1, f(2, 3))} is two arguments, and a comma count
     * says three. The one parser configuration for an expression, as {@link BotParser#syntax} is for a unit.
     */
    public static Expression expression(String source) {
        return JavaExpressions.parse(source);
    }

    /**
     * Every {@code @Param} field in {@code source}, in the order they are written.
     *
     * @param file    the path the rows are attributed to; may be {@code null} in a test
     * @param grammar the bound plugins' value grammar, which decides what a field's Java type <em>is</em>
     */
    public static List<JavaParameter> read(Path file, String source, ValueGrammar grammar) {
        return read(file, source, grammar, BotRecords.none());
    }

    /**
     * The same, told what the bot declares itself — the form a window reads, since a field may be typed with
     * one of the bot's own records.
     */
    public static List<JavaParameter> read(Path file, String source, ValueGrammar grammar,
                                           BotRecords records) {
        return read(file, source, grammar, records, BotParser.SYNTAX);
    }

    /**
     * The same, parsed by {@code parser} — the project's, so the annotation and its constants are the ones the
     * classpath declares.
     */
    public static List<JavaParameter> read(Path file, String source, ValueGrammar grammar,
                                           BotRecords records, BotParser parser) {
        return read(file, source, parser.parse(file, source), grammar, records);
    }

    /** The same over {@code unit}, already parsed from {@code source} — one of a {@code BotIndex}'s. */
    public static List<JavaParameter> read(Path file, String source, CompilationUnit unit, ValueGrammar grammar,
                                           BotRecords records) {
        return read(file, source, unit, grammar, records, BotNames.NONE, List.of());
    }

    /**
     * The same, reading the bot's own constants wherever an initialiser names one — {@code Flows.MAIN},
     * {@code Activities.Rest.class} — so such a field's value is editable rather than kept as written, and told
     * the plugins' declared values ({@code known}), which name a field's mark in a unit without bindings and say
     * why a plugin owns it.
     */
    public static List<JavaParameter> read(Path file, String source, CompilationUnit unit, ValueGrammar grammar,
                                           BotRecords records, BotNames names, List<ManagedValue<?>> known) {
        List<JavaParameter> out = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration type) {
                // A group is a top-level class marked @Param (2026-11-04): each of its parameters one method, whole.
                boolean groupShaped = !type.isInterface() && type.isPackageMemberTypeDeclaration();
                Annotation group = groupShaped ? BotAnnotation.PARAM.on(type) : null;
                if (group == null) return true;
                Map<String, Object> shared = BotAnnotation.PARAM.members(group);
                String owner = pluginFolder(file).map(folder -> "A parameter of the " + folder + " plugin, in its"
                        + " folder of params/: only its value is yours to change.").orElse("");
                for (MethodDeclaration method : type.getMethods()) {
                    if (!isParameter(method)) continue;
                    // A plugin's own parameter carries its mark, which names its declaration (2026-11-04).
                    String id = ManagedIds.idOf(ManagedIds.on(method, known), known);
                    String whose = !owner.isEmpty() || id.isEmpty() ? owner
                            : "A plugin's parameter, marked as its own: only its value is yours to change.";
                    out.add(readMethod(file, source, grammar, records, names, type.getName().getIdentifier(), shared,
                            method).owned(whose, id));
                }
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration field) {
                Annotation annotation = paramAnnotation(field);
                if (annotation == null) return false;
                String className = enclosingTypeName(field);
                if (className == null) return false;
                for (Object each : field.fragments()) {
                    VariableDeclarationFragment fragment = (VariableDeclarationFragment) each;
                    out.add(read(file, source, grammar, records, names, className, field, annotation, fragment)
                            .owned(owner(field, known), ManagedIds.idOf(ManagedIds.on(field, known), known)));
                }
                return false;
            }
        });
        return List.copyOf(out);
    }

    /** One fragment of one field — {@code @Param public static int a = 1, b = 2;} declares two. */
    private static JavaParameter read(Path file, String source, ValueGrammar grammar, BotRecords records,
                                      BotNames names, String className, FieldDeclaration field, Annotation annotation,
                                      VariableDeclarationFragment fragment) {
        Map<String, Object> members = BotAnnotation.PARAM.members(annotation);
        String name = fragment.getName().getIdentifier();
        String initializer = fragment.getInitializer() == null ? ""
                : text(source, fragment.getInitializer());

        Type form = ValueTypeResolver.of(grammar, field.getType(), fragment.getExtraDimensions(),
                records.declaredNames());
        // The grammar declines a bot's own class by design — only the host's view of the bot's own source can
        // read one — so the second reader is asked for exactly that case and for no other.
        boolean readable = form instanceof ValueTypes.BotClass declared
                ? records.partsOf(declared, initializer).isPresent()
                : fragment.getInitializer() != null
                        && grammar.read(form, new SourceNode(fragment.getInitializer(), source), names).isPresent();

        String note = whyNotEditable(grammar, field, form, records, readable, initializer);
        // The row's value is the initialiser *as the author wrote it*, readable or not. A cell that cannot
        // be edited still has to show what the field holds, and the reading above decides only whether it
        // may be replaced.
        ParameterRow row = ParameterRow.named(name, ValueTypes.sourceName(form))
                .value(initializer)
                .description(string(members, "description"))
                .category(string(members, "category"))
                .visibility(Visibility.fromId(string(members, "visibility")))
                .build();
        return new JavaParameter(file, className, row, form, initializer, note.isEmpty(), note, "", "");
    }

    /**
     * One parameter of a {@code @Param} group (2026-11-04): the method's name, its return type and, as its value, what
     * its body returns. Its {@link Param.Shown} members win over the group's own ({@code shared}). One the bot writes
     * during a run is read-only here.
     */
    private static JavaParameter readMethod(Path file, String source, ValueGrammar grammar, BotRecords records,
                                            BotNames names, String group, Map<String, Object> shared,
                                            MethodDeclaration method) {
        Annotation shownOn = BotAnnotation.PARAM_SHOWN.on(method);
        Map<String, Object> shown = shownOn == null ? Map.of() : BotAnnotation.PARAM_SHOWN.members(shownOn);
        String name = method.getName().getIdentifier();
        Param.Changes changes = changesOf(shownOn);

        Expression value = returned(method).orElse(null);
        String initializer = value == null ? "" : text(source, value);

        Type form = ValueTypeResolver.of(grammar, method.getReturnType2(),
                method.getExtraDimensions(), records.declaredNames());
        boolean readable = value != null && (form instanceof ValueTypes.BotClass declared
                ? records.partsOf(declared, initializer).isPresent()
                : grammar.read(form, new SourceNode(value, source), names).isPresent());
        String note = changes == Param.Changes.DURING_RUN ? DURING_RUN_NOTE
                : whyNotEditable(grammar, form, records, readable, initializer, value != null);

        ParameterRow row = ParameterRow.named(name, ValueTypes.sourceName(form))
                .value(initializer)
                .description(string(shown, "description"))
                .category(either(shown, shared, "category"))
                .changes(changes)
                .build();
        return new JavaParameter(file, group, row, form, initializer, note.isEmpty(), note, "", "", true);
    }

    /** Why a during-a-run parameter's cell is read-only. */
    public static final String DURING_RUN_NOTE = "the bot writes it during a run; Forget sets it back to its starting"
            + " value";

    /** Whether {@code method} is one parameter of its group: a no-argument, non-void instance method with a body. */
    static boolean isParameter(MethodDeclaration method) {
        int modifiers = method.getModifiers();
        return !method.isConstructor() && method.parameters().isEmpty() && method.getBody() != null
                && method.getReturnType2() != null && !method.getReturnType2().toString().equals("void")
                && !Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers);
    }

    /** {@code @Param.Shown(changes = …)} as written, by its constant's last name; outside a run without one. */
    private static Param.Changes changesOf(Annotation shown) {
        if (!(shown instanceof NormalAnnotation normal)) return Param.Changes.OUTSIDE_RUN;
        for (Object each : normal.values()) {
            MemberValuePair pair = (MemberValuePair) each;
            if (!pair.getName().getIdentifier().equals("changes")) continue;
            String written = pair.getValue().toString();
            return Param.Changes.fromId(written.substring(written.lastIndexOf('.') + 1));
        }
        return Param.Changes.OUTSIDE_RUN;
    }

    /**
     * The plugin whose folder of {@code params/} holds {@code file} ({@code params/sdk/Sdk.java} is the
     * {@code sdk} plugin's), or empty for the bot's own: {@code params/bot/} or {@code params/} itself.
     */
    static Optional<String> pluginFolder(Path file) {
        if (file == null) return Optional.empty();
        Path dir = file.toAbsolutePath().normalize().getParent();
        for (Path at = dir; at != null && at.getParent() != null; at = at.getParent()) {
            // The nearest params/ decides: a file directly in it is the bot's, whatever folders sit above.
            if (at.getFileName() != null && at.getFileName().toString().equals(ParamsFitter.PARAMS)) {
                return Optional.empty();
            }
            if (at.getParent().getFileName() != null
                    && at.getParent().getFileName().toString().equals(ParamsFitter.PARAMS)) {
                String folder = at.getFileName().toString();
                return folder.equals(BOT) ? Optional.empty() : Optional.of(folder);
            }
        }
        return Optional.empty();
    }

    /** The folder of {@code params/} that holds the bot's own groups. */
    public static final String BOT = "bot";

    /** The expression a method's body is exactly {@code return <expr>;} of, when it takes no parameter. */
    static Optional<Expression> returned(MethodDeclaration method) {
        Block body = method.getBody();
        if (body == null || !method.parameters().isEmpty() || body.statements().size() != 1) return Optional.empty();
        return body.statements().getFirst() instanceof ReturnStatement ret && ret.getExpression() != null
                ? Optional.of(ret.getExpression()) : Optional.empty();
    }

    /** {@code member} as {@code first} sets it, else as {@code second} does. */
    private static String either(Map<String, Object> first, Map<String, Object> second, String member) {
        String own = string(first, member);
        return own.isBlank() ? string(second, member) : own;
    }

    /** Why a schema method's value cell is read-only, or {@code ""}: the field rules a method has no use for left out. */
    private static String whyNotEditable(ValueGrammar grammar, Type form, BotRecords records,
                                         boolean readable, String initializer, boolean oneReturn) {
        if (form instanceof ValueTypes.BotClass declared) {
            String why = records.whyNotEditable(declared);
            if (why != null) return why;
        }
        String unknown = grammar.firstUnknown(form);
        if (unknown != null) return unknownReason(form, unknown);
        if (!oneReturn) return "its body is not one return of a value, so it is kept rather than replaced";
        if (!readable) return "written by hand as " + initializer + ", which is kept rather than replaced";
        return "";
    }

    /**
     * Why {@code field}'s declaration is a plugin's — the reason its {@code ManagedValue.param} gives — when it
     * carries a plugin's mark, {@code @SdkValue(SdkValue.Id.DO_SETUP)}; {@code ""} for the bot's own field. A
     * mark no loaded plugin declares still locks it: the bot's code was written to read that field by name.
     */
    private static String owner(FieldDeclaration field, List<ManagedValue<?>> known) {
        String id = ManagedIds.idOf(ManagedIds.on(field, known), known);
        if (id.isEmpty()) return "";
        return known.stream().filter(value -> value != null && value.id().equals(id)).map(ManagedValue::reason)
                .findFirst().orElse("A plugin declared this parameter, and the bot's code reads it by name.");
    }

    /**
     * Why this field's value cell is read-only, or {@code ""} when it is not.
     *
     * <p>Each answer is a sentence a person can act on, because the alternative — a grey cell with no
     * reason — is the failure this whole design exists to avoid. A field is never <em>rejected</em>: it is
     * still listed, still shows what it holds, and still tells the author what to change.
     */
    private static String whyNotEditable(ValueGrammar grammar, FieldDeclaration field, Type form,
                                         BotRecords records, boolean readable, String initializer) {
        int modifiers = field.getModifiers();
        if (!Modifier.isPublic(modifiers)) {
            return "not public: the bot can read it, the window cannot show it being changed";
        }
        if (!Modifier.isStatic(modifiers)) {
            return "not static: a parameter belongs to the bot, not to an instance of a class";
        }
        if (Modifier.isFinal(modifiers)) {
            return "final: its value is fixed at compile time";
        }
        if (form instanceof ValueTypes.BotClass declared) {
            // 32-generic-values.md §A bot's own generic class. A record's canonical constructor is what makes
            // `new Point(1, 2)` writable without guessing; anything else keeps whatever its author wrote, and
            // no placeholder is ever invented for it.
            String why = records.whyNotEditable(declared);
            if (why != null) return why;
        }
        String unknown = grammar.firstUnknown(form);
        if (unknown != null) return unknownReason(form, unknown);
        if (initializer.isBlank()) {
            return "no initialiser: there is no value to show or replace";
        }
        if (!readable) {
            return "written by hand as " + initializer + ", which is kept rather than replaced";
        }
        return "";
    }

    /** The sentence for a type with a leaf nothing declares: the type itself, or one of its arguments. */
    public static String unknownReason(Type type, String unknown) {
        return ValueTypes.isLeaf(type)
                ? "no installed plugin declares " + unknown + " as a value type"
                : "type argument " + unknown + " is not a known value type";
    }

    /**
     * The {@code @Param} annotation on this field, or {@code null}. Public for {@code LockResolver}: what makes
     * a field a parameter is stated here once, and the canvas refusing to edit one asks the same question.
     */
    public static Annotation paramAnnotation(FieldDeclaration field) {
        return BotAnnotation.PARAM.on(field);
    }

    private static String string(Map<String, Object> members, String name) {
        Object value = members.get(name);
        return value instanceof String text ? text : "";
    }

    /** The source text a node occupies, exactly as written — whitespace, comments and all. */
    public static String text(String source, ASTNode node) {
        int start = node.getStartPosition();
        if (start < 0 || start + node.getLength() > source.length()) return "";
        return source.substring(start, start + node.getLength());
    }

    /** The name of the type a node is declared in, or {@code null} for one declared nowhere. */
    public static String enclosingTypeName(ASTNode node) {
        for (ASTNode parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof TypeDeclaration type) return type.getName().getIdentifier();
        }
        return null;
    }

    /** Whether any modifier list already carries {@code @Param} — used when adding one. */
    static boolean isParam(List<IExtendedModifier> modifiers) {
        return BotAnnotation.PARAM.isOn(modifiers);
    }
}
