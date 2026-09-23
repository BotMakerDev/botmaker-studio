package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.ValueForm;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.ManagedConstants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value with no call site behind it — a Parameters row, a plugin's managed value — reads and writes the
 * bot's {@code @Managed} constants the way a slot in the source does: {@code Pictures.ORE} is the picture,
 * and a picture equal to one is written as the constant.
 */
class HostValueContextConstantsTest {

    record Picture(String path) {}

    private static final ComponentType<Picture> PICTURE = new ComponentType<>() {
        @Override public Class<Picture> type() { return Picture.class; }
        @Override public List<Class<?>> componentTypes() { return List.of(String.class); }
        @Override public List<Object> components(Picture p) { return List.of(p.path()); }
        @Override public Picture build(List<Object> parts) { return new Picture((String) parts.getFirst()); }
    };

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(), List.of(PICTURE));
    private static final ValueForm FORM = ValueForm.of(Picture.class);

    private static final List<ManagedConstants.Constant> CONSTANTS = List.of(
            new ManagedConstants.Constant("com.bot.Pictures", "ORE",
                    "new " + JavaNames.canonical(Picture.class) + "(\"images/ore.png\")"));

    @Test
    void aRowHoldingAConstantReadsAsItsValue() {
        HostValueContext context = HostValueContext.of(FORM, GRAMMAR, "Pictures.ORE", null, null, () -> CONSTANTS);

        assertEquals(new Picture("images/ore.png"), context.value(Picture.class).orElseThrow());
    }

    @Test
    void aValueEqualToAConstantIsWrittenAsTheConstantWithItsImport() {
        List<String> written = new ArrayList<>();
        HostValueContext context = HostValueContext.of(FORM, GRAMMAR, "", null,
                (source, imports) -> { written.add(source); written.addAll(imports); }, () -> CONSTANTS);

        context.set(new Picture("images/ore.png"));

        assertEquals(List.of("Pictures.ORE", "com.bot.Pictures"), written);
    }

    @Test
    void withNoConstantsANameStaysUnread() {
        HostValueContext context = HostValueContext.of(FORM, GRAMMAR, "Pictures.ORE", null, null);

        assertTrue(context.value(Picture.class).isEmpty());
    }
}
