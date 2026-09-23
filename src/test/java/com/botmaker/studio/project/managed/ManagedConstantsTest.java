package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot's {@code @Managed} constants, both ways: a reference read as the constant's value, and a value
 * written as the constant that holds it.
 */
class ManagedConstantsTest {

    record Picture(String path) {}

    static final ComponentType<Picture> PICTURE = new ComponentType<>() {
        @Override public Class<Picture> type() { return Picture.class; }
        @Override public List<Class<?>> componentTypes() { return List.of(String.class); }
        @Override public List<Object> components(Picture p) { return List.of(p.path()); }
        @Override public Picture build(List<Object> parts) { return new Picture((String) parts.getFirst()); }
    };

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(), List.of(PICTURE));

    private static final String PICTURES = """
            package com.bot.plugins.sdk;

            @Managed("pictures")
            public final class Pictures {
                public static final Picture ORE = new %s("images/ore.png");
                public static final Picture GOLD = new %s("images/gold.png");
                static final Picture HIDDEN = new %s("images/hidden.png");
            }
            """.formatted(name(), name(), name());

    private static String name() {
        return JavaNames.canonical(Picture.class);
    }

    private static ManagedConstants.Lookup lookup() {
        return new ManagedConstants.Lookup(ManagedConstants.read(PICTURES), GRAMMAR);
    }

    @Test
    void onlyPublicStaticFinalFieldsOfAManagedTypeAreConstants() {
        assertEquals(List.of("ORE", "GOLD"),
                ManagedConstants.read(PICTURES).stream().map(ManagedConstants.Constant::field).toList());
    }

    @Test
    void aReferenceReadsAsTheConstantsValueQualifiedOrNot() {
        assertEquals(new Picture("images/ore.png"), lookup().read("Pictures.ORE").orElseThrow());
        assertEquals(new Picture("images/ore.png"), lookup().read("com.bot.plugins.sdk.Pictures.ORE").orElseThrow());
        assertTrue(lookup().read("Pictures.SILVER").isEmpty());
    }

    @Test
    void aValueEqualToAConstantIsWrittenAsTheConstant() {
        ValueGrammar.Written written = lookup().spell(new Picture("images/gold.png")).orElseThrow();

        assertEquals("Pictures.GOLD", written.source());
        assertEquals(List.of("com.bot.plugins.sdk.Pictures"), written.imports());
        assertTrue(lookup().spell(new Picture("images/new.png")).isEmpty(), "no constant holds it");
    }

    /** An unchanged file is not parsed again; a file whose text changed is, and its new constant is found. */
    @Test
    void aScanParsesOnlyWhatChanged(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyFarmer", root);
        Path pkg = config.mainSourceFile().getParent();
        Files.createDirectories(pkg);
        Path pictures = pkg.resolve("Pictures.java");
        Files.writeString(pictures, PICTURES);

        assertEquals(2, ManagedConstants.scan(config, null).size());
        int afterFirst = ManagedConstants.parses();
        assertEquals(2, ManagedConstants.scan(config, null).size());
        assertEquals(afterFirst, ManagedConstants.parses(), "nothing changed, nothing parsed");

        Files.writeString(pictures, PICTURES.replace("static final Picture HIDDEN", "public static final Picture HIDDEN"));
        assertEquals(List.of("ORE", "GOLD", "HIDDEN"),
                ManagedConstants.scan(config, null).stream().map(ManagedConstants.Constant::field).toList());
        assertEquals(afterFirst + 1, ManagedConstants.parses(), "one file changed, one parsed");
    }
}
