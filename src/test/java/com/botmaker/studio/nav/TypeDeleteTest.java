package com.botmaker.studio.nav;

import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.services.ClassFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A file is deleted only when nothing is left pointing at what it declares. */
class TypeDeleteTest {

    private static final String HELPER = """
            package com.mybot;

            public class Helper {
                public static int twice(int x) { return x * 2; }
                public enum Mode { FAST, SLOW }
            }
            """;

    private static final String BOT = """
            package com.mybot;

            class Bot {
                void run() {
                    int a = Helper.twice(1);
                }
            }
            """;

    private static final String NESTED_USER = """
            package com.mybot;

            class Flows {
                Object mode = Helper.Mode.FAST;
            }
            """;

    private static final String UNUSED = """
            package com.mybot;

            public class Unused {
                static void alone() { Unused.alone(); }
            }
            """;

    @Test
    void aClassAnotherFileUsesIsKeptAndEachUseIsListed(@TempDir Path root) {
        Path helper = root.resolve("com/mybot/Helper.java");
        Refactor.Refused refused = TypeDelete.refusal(index(root, BOT), helper).orElseThrow();
        assertEquals(1, refused.uses().size(), refused.uses().toString());
        assertEquals(root.resolve("com/mybot/Bot.java"), refused.uses().getFirst().file());
        assertTrue(refused.reason().contains("Helper.java"), refused.reason());
    }

    @Test
    void aNestedTypeUsedElsewhereKeepsItsFile(@TempDir Path root) {
        Path helper = root.resolve("com/mybot/Helper.java");
        Refactor.Refused refused = TypeDelete.refusal(index(root, NESTED_USER), helper).orElseThrow();
        assertEquals(1, refused.uses().size(), "Helper.Mode.FAST is one place, though it names two types");
        assertEquals(root.resolve("com/mybot/Flows.java"), refused.uses().getFirst().file());
    }

    @Test
    void aClassOnlyItselfUsesGoes(@TempDir Path root) {
        Path unused = root.resolve("com/mybot/Unused.java");
        assertTrue(TypeDelete.refusal(index(root, BOT), unused).isEmpty());
    }

    @Test
    void eachKindIsADeclarationOfItsName() {
        assertEquals("package com.mybot.util;\n\npublic record Point() {\n}\n",
                ClassFiles.source("com.mybot.util", ClassFiles.Kind.RECORD, "Point"));
        assertEquals(ClassFiles.Kind.ENUM, ClassFiles.Kind.fromId(" Enum ").orElseThrow());
        assertTrue(ClassFiles.Kind.fromId("annotation").isEmpty());
    }

    private static BotIndex index(Path root, String user) {
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(root.resolve("com/mybot/Helper.java"), HELPER);
        sources.put(root.resolve("com/mybot/" + (user.contains("class Bot") ? "Bot" : "Flows") + ".java"), user);
        sources.put(root.resolve("com/mybot/Unused.java"), UNUSED);
        return BotIndex.over(sources, List.of(), root);
    }
}
