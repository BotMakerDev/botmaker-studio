package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.slot.SlotRun;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Studio's side of {@link SlotRun}: a tail of one call's arguments, edited as one list.
 *
 * <p>The host's half is the part a plugin cannot see: that these arguments are one list comes from the
 * resolved signature (a varargs parameter), and nothing in the values says so.
 *
 * <p><b>Every element crosses as a value</b>, read and written as {@link HostSlotContext} reads and writes one
 * slot — the grammar, and the bot's {@code @Managed} constants. An element the host cannot read crosses as
 * its source, and handed back it is kept exactly as written. Until 2026-09-23 every element was Java text the
 * plugin split and wrote itself.
 *
 * <p><b>The call is resolved on every use, never captured</b> — the same rule {@link HostSlotContext} follows
 * for its slot, and for the same reason: an editor's popup outlives the re-parse its own first edit caused,
 * and a node from the old syntax tree is what {@code ASTRewrite} refuses.
 */
public final class HostSlotRun implements SlotRun {

    private final CodeEditorService context;
    private final Supplier<MethodInvocation> call;
    private final int fromIndex;
    private final Type element;

    private HostSlotRun(CodeEditorService context, Supplier<MethodInvocation> call, int fromIndex,
                        Type element) {
        this.context = context;
        this.call = call;
        this.fromIndex = Math.max(0, fromIndex);
        this.element = element;
    }

    /**
     * The varargs tail of {@code call} from {@code fromIndex}, each element a {@code elementType}.
     *
     * @param call        the call whose arguments make up the run, resolved when asked
     * @param fromIndex   the first argument that belongs to the run — the varargs boundary
     * @param elementType the type of each element, or {@code null} to read each by its own spelling
     */
    public static HostSlotRun of(CodeEditorService context, Supplier<MethodInvocation> call, int fromIndex,
                                 ResolvedType elementType) {
        return new HostSlotRun(context, call, fromIndex, HostSlotContext.formOf(elementType));
    }

    @Override
    public List<Element> elements() {
        List<Element> out = new ArrayList<>();
        for (Expression argument : arguments()) {
            out.add(new Element(HostSlotContext.read(context, element, new SourceNode(argument, null)).orElse(null),
                    argument.toString()));
        }
        return out;
    }

    /** The run's arguments as the call holds them now. */
    private List<Expression> arguments() {
        MethodInvocation node = call == null ? null : call.get();
        if (node == null) return List.of();
        List<Expression> out = new ArrayList<>();
        List<?> arguments = node.arguments();
        for (int i = fromIndex; i < arguments.size(); i++) {
            if (arguments.get(i) instanceof Expression argument) out.add(argument);
        }
        return out;
    }

    /**
     * Writes the whole tail in one rewrite. The list is refused — the source left alone — when an item is a
     * value the grammar cannot write, since a run written half-way would lose an argument.
     *
     * <p>An {@link Element} handed back is one of {@link #elements()}' own, kept: it is found among the
     * arguments by being equal to the element the host made for it, and that argument's tree is written
     * back — never the text it was shown as.
     */
    @Override
    public void replace(List<?> items) {
        MethodInvocation node = call == null ? null : call.get();
        if (node == null || items == null || items.size() < minimum()) return;
        List<Expression> arguments = arguments();
        List<Element> current = elements();
        List<JavaValue> values = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Element kept) {
                int at = current.indexOf(kept);
                if (at < 0) return;
                values.add(JavaValue.kept(new SourceNode(arguments.get(at), null)));
                continue;
            }
            Optional<JavaValue> written = HostSlotContext.write(context, element, item);
            if (written.isEmpty()) return;
            values.add(written.get());
        }
        context.getCodeEditor().setTrailingArguments(node, fromIndex, values);
    }
}
