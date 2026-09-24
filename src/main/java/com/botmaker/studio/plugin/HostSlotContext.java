package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.slot.SlotContext;
import com.botmaker.plugin.api.slot.SlotRun;
import com.botmaker.plugin.api.slot.TypeRef;
import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.managed.ManagedConstants;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.Expression;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A {@link SlotContext} over one slot of a bot's Java source — the second of the two places Studio edits a
 * value, and the one a plugin's editor could not reach until now.
 *
 * <p>{@link HostValueContext} is the other: a value with no call site behind it — a row of the Parameters
 * window, or the expression a {@code @Managed} method returns. The pair is the whole of the host's side of
 * the contract, and the one thing a slot has that the other does not is the <b>names</b> of its call site.
 * It reads a constant of the bot's {@code @Managed} types ({@code Pictures.ORE}) as that constant's value, and
 * writes a value equal to one as the constant.
 *
 * <p><b>The slot is asked, never captured.</b> {@link ValueSlot} resolves its expression on every call, so an
 * editor whose popup outlives the re-parse its own edit caused writes into the <em>new</em> node. Handing a
 * stale one to {@code ASTRewrite} throws {@code Node is not inside the AST}; this makes that unreachable
 * rather than guarded against, and it is why this class holds the slot rather than the expression.
 */
public final class HostSlotContext implements SlotContext {

    private final CodeEditorService context;
    private final ValueSlot slot;
    private final ResolvedType paramType;
    private final String className;
    private final String methodName;
    private final int argIndex;
    private final StudioServices services;
    private final SlotRun run;

    public HostSlotContext(CodeEditorService context, ValueSlot slot, ResolvedType paramType,
                           String className, String methodName, int argIndex, StudioServices services) {
        this(context, slot, paramType, className, methodName, argIndex, services, null);
    }

    /**
     * As above, for a slot the host knows to be one of a <b>run</b> of sibling arguments.
     *
     * <p>The run is passed in rather than worked out here, because working it out is not one question: an
     * image varargs tail is found from the resolved signature, and a {@code Matches} case's pictures from the
     * shape of the guard around them. Both are decisions the block that draws the row has already taken, and
     * re-deriving them in the context would be a second answer to each.
     */
    public HostSlotContext(CodeEditorService context, ValueSlot slot, ResolvedType paramType,
                           String className, String methodName, int argIndex, StudioServices services,
                           SlotRun run) {
        this.context = context;
        this.slot = slot == null ? ValueSlot.empty() : slot;
        this.paramType = paramType;
        this.className = className;
        this.methodName = methodName;
        this.argIndex = argIndex;
        this.services = services;
        this.run = run;
    }

    @Override
    public Optional<SlotRun> siblingRun() {
        return Optional.ofNullable(run);
    }

    @Override
    public TypeRef type() {
        return typeRef(paramType);
    }

    /**
     * A leaf of the slot's declared type.
     *
     * <p>A slot is one argument of one call, so its type is whatever the resolved signature says the
     * parameter is — there is no declaration to read type arguments off, which is what
     * {@code ValueTypeResolver} does for a field. A slot whose type did not resolve reads its value by the
     * expression's own spelling instead.
     */
    private Type form() {
        return formOf(paramType);
    }

    /**
     * The class {@code type} resolved to, looked up by its exact canonical name — or an unknown type that
     * reads a value by its own spelling. The name is the binding's, so no spelling is guessed at.
     */
    static Type formOf(ResolvedType type) {
        if (type == null) return ValueTypes.NONE;
        String qualified = nullToEmpty(type.qualifiedName());
        Optional<Class<?>> known = grammar().named(qualified);
        if (known.isPresent()) return known.get();
        return new ValueTypes.Unknown(qualified.isEmpty() ? nullToEmpty(type.simpleName()) : qualified);
    }

    /**
     * The value the slot holds: read by the grammar, or — for {@code Pictures.ORE} and its like — the value
     * of the bot's {@code @Managed} constant the slot names.
     */
    @Override
    public <T> Optional<T> value(Class<T> type) {
        return read(context, form(), new SourceNode(slot.node(), null)).flatMap(value -> ValueGrammar.as(value, type));
    }

    /**
     * Writes {@code value} with every type by its simple name and the imports that needs — a bot's source is
     * a file a person reads — or as the bot's {@code @Managed} constant holding it. A value the grammar
     * cannot spell is ignored rather than written half-way.
     */
    @Override
    public void set(Object value) {
        if (slot.node() == null) return;
        write(context, form(), value).ifPresent(written ->
                rewrite(slot.node(), written.source(), written.imports().toArray(String[]::new)));
    }

    /**
     * {@code node} as a {@code form}, with a constant reference read as the constant's value. A node of the
     * editor's tree carries no text of its own, so a part nothing reads is shown as JDT prints it.
     */
    static Optional<Object> read(CodeEditorService context, Type form, SourceNode node) {
        return ConstantValues.read(grammar(), constants(context), form, node);
    }

    /** {@code value} as the constant holding it when the bot has one, and spelled out otherwise. */
    static Optional<ValueGrammar.Written> write(CodeEditorService context, Type form, Object value) {
        return ConstantValues.write(grammar(), constants(context), form, value);
    }

    /**
     * The bot's {@code @Managed} constants as they stand now — a picture captured a moment ago is one of
     * them. {@link ManagedConstants#scan} parses only a file whose text changed since it last did.
     */
    private static Supplier<List<ManagedConstants.Constant>> constants(CodeEditorService context) {
        return context == null ? ConstantValues.NONE
                : () -> ManagedConstants.scan(context.getConfig(), context.getState());
    }

    private static ValueGrammar grammar() {
        return PluginHost.grammar();
    }

    @Override
    public String source() {
        return slot.source();
    }

    @Override
    public Optional<String> enclosingClassName() {
        return Optional.ofNullable(className);
    }

    @Override
    public Optional<String> enclosingMethodName() {
        return Optional.ofNullable(methodName);
    }

    @Override
    public int argIndex() {
        return argIndex;
    }

    // enclosingCall() and replaceEnclosingCall(…) left the contract on 2026-09-23: they handed a plugin the
    // call as text. What a slot knows of its call is its names, above.

    /** One rewrite of the slot, with every import it needs. */
    private void rewrite(Expression target, String javaExpression, String... importsNeeded) {
        if (importsNeeded == null || importsNeeded.length == 0) {
            context.getCodeEditor().replaceWithRawExpression(target, javaExpression);
            return;
        }
        // One rewrite for every import. This was one rewrite per import until 2026-09-23, which was harmless
        // while an editor wrote one type; the grammar writes `new Rect(new Point(…), …)` with several, and a
        // second rewrite would be handed the node the first had already replaced.
        context.getCodeEditor().replaceWithRawExpression(target, javaExpression, List.of(importsNeeded));
    }

    @Override
    public StudioServices services() {
        return services;
    }

    /**
     * A {@link ResolvedType} as the contract's names-only view of it.
     *
     * <p>Never a {@code Class}: the host resolves a type out of the <em>bot's</em> classpath, which may hold a
     * different version of it or not hold it at all. An unresolved type still answers a simple name, and an
     * editor keying off that still fires — refusing to edit a value because the rest of the file does not
     * compile is the worse failure.
     */
    public static TypeRef typeRef(ResolvedType type) {
        String simple = type == null ? "" : nullToEmpty(type.simpleName());
        String qualified = type == null ? "" : nullToEmpty(type.qualifiedName());
        return new TypeRef() {
            @Override public String simpleName() { return simple; }

            @Override public String qualifiedName() { return qualified; }
        };
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
