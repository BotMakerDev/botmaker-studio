package com.botmaker.studio.plugin;

import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.ManagedConstants;
import org.eclipse.jdt.core.dom.QualifiedName;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A value read and written through the grammar <em>and</em> the bot's {@code @Managed} constants — the one
 * rule a slot in the source ({@link HostSlotContext}, {@link HostSlotRun}) and a value with no call site
 * behind it ({@link HostValueContext}: a Parameters row, a plugin's managed value) both follow.
 *
 * <p>{@code Pictures.ORE} reads as the picture it holds, and a picture equal to one of the constants is
 * written as the constant. The constants are asked for through a supplier so a source the grammar reads on
 * its own never costs a scan.
 */
public final class ConstantValues {

    private ConstantValues() {}

    /** No constants: a context with no project behind it. */
    public static final Supplier<List<ManagedConstants.Constant>> NONE = List::of;

    /** {@code node} as a {@code form}, with a constant reference read as the constant's value. */
    public static Optional<Object> read(ValueGrammar grammar, Supplier<List<ManagedConstants.Constant>> constants,
                                        Type form, SourceNode node) {
        if (node == null || node.node() == null) return Optional.empty();
        Optional<Object> read = grammar.read(form, node);
        // A constant reference is only ever written Owner.FIELD, qualified or not.
        if (read.isPresent() || !(node.node() instanceof QualifiedName name)) return read;
        return lookup(grammar, constants).read(name);
    }

    /** {@link #read(ValueGrammar, Supplier, Type, SourceNode)} over stored text, parsed once. */
    public static Optional<Object> read(ValueGrammar grammar, Supplier<List<ManagedConstants.Constant>> constants,
                                        Type form, String source) {
        return SourceNode.parse(source).flatMap(node -> read(grammar, constants, form, node));
    }

    /** {@code value} as the constant holding it when the bot has one, and written out otherwise. */
    public static Optional<JavaValue> write(ValueGrammar grammar,
                                            Supplier<List<ManagedConstants.Constant>> constants,
                                            Type form, Object value) {
        return lookup(grammar, constants).spell(value).or(() -> grammar.write(form, value));
    }

    private static ManagedConstants.Lookup lookup(ValueGrammar grammar,
                                                  Supplier<List<ManagedConstants.Constant>> constants) {
        return new ManagedConstants.Lookup(constants == null ? List.of() : constants.get(), grammar);
    }
}
