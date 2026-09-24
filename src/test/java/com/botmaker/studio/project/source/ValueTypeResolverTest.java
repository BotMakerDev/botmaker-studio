package com.botmaker.studio.project.source;

import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.params.TestValues;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A type written in a bot's source becomes a value's {@link Type} once: through its binding, or through the
 * unit's imports — never through a spelling that merely ends the right way.
 */
class ValueTypeResolverTest {

    private static final BotParser BOUND = new BotParser(
            Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)), null);

    /** The type of the one field {@code source} declares. */
    private static Type fieldType(BotParser parser, String source, Set<String> botClasses) {
        CompilationUnit unit = parser.parse(null, source);
        Type[] found = new Type[1];
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration field) {
                found[0] = ValueTypeResolver.of(TestValues.GRAMMAR, field.getType(), 0, botClasses);
                return false;
            }
        });
        return found[0];
    }

    private static Type fieldType(BotParser parser, String source) {
        return fieldType(parser, source, Set.of());
    }

    @Test
    void anImportedDeclaredTypeIsItsClass() {
        assertEquals(Duration.class, fieldType(BotParser.SYNTAX, """
                package com.example.bot;
                import java.time.Duration;
                class P { static Duration rest; }
                """));
    }

    @Test
    void aClassOfTheSameNameFromAnotherPackageIsNot() {
        // The suffix match read this as java.time.Duration.
        assertEquals(new ValueTypes.Unknown("Duration"), fieldType(BotParser.SYNTAX, """
                package com.example.bot;
                import com.example.other.Duration;
                class P { static Duration rest; }
                """));
    }

    @Test
    void aNameNothingImportsIsNot() {
        assertEquals(new ValueTypes.Unknown("Duration"), fieldType(BotParser.SYNTAX, """
                package com.example.bot;
                class P { static Duration rest; }
                """));
    }

    @Test
    void javaLangNeedsNoImportAndAPrimitiveIsItself() {
        assertEquals(String.class, fieldType(BotParser.SYNTAX, "class P { static String name; }"));
        assertEquals(int.class, fieldType(BotParser.SYNTAX, "class P { static int count; }"));
    }

    @Test
    void aContainerIsReadRecursivelyThroughTheImports() {
        assertEquals(ValueTypes.mapOf(String.class, ValueTypes.listOf(Duration.class)), fieldType(BotParser.SYNTAX, """
                import java.time.Duration;
                import java.util.List;
                import java.util.Map;
                class P { static Map<String, List<Duration>> rests; }
                """));
    }

    @Test
    void aBindingDecidesWhenTheClasspathHasOne() {
        assertEquals(ValueTypes.listOf(Duration.class), fieldType(BOUND, """
                package com.example.bot;
                import java.time.*;
                import java.util.*;
                class P { static List<Duration> rests; }
                """));
    }

    @Test
    void aBotsOwnClassInItsOwnPackageOrNestedIsABotClass() {
        Set<String> bot = Set.of("com.example.bot.Point", "com.example.bot.Shapes.Corner");
        assertEquals(new ValueTypes.BotClass("com.example.bot.Point", List.of()), fieldType(BotParser.SYNTAX, """
                package com.example.bot;
                class P { static Point origin; }
                """, bot));
        assertEquals(new ValueTypes.BotClass("com.example.bot.Shapes.Corner", List.of()), fieldType(BotParser.SYNTAX, """
                package com.example.bot;
                class Shapes {
                    record Corner(int x, int y) {}
                    static Corner first;
                }
                """, bot));
    }

    @Test
    void anArrayOrAWildcardIsUnknownAndKeepsItsSpelling() {
        assertEquals(new ValueTypes.Unknown("int[]"), fieldType(BotParser.SYNTAX, "class P { static int[] xs; }"));
    }
}
