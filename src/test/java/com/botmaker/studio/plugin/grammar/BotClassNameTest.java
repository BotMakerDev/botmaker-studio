package com.botmaker.studio.plugin.grammar;

import org.eclipse.jdt.core.dom.AST;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A class the bot declares is written as its file would name it, its outermost type imported — never by its full
 * name in the bot's code (2026-10-13).
 */
class BotClassNameTest {

    private static final AST AST_ = AST.newAST(AST.getJLSLatest(), false);

    @Test
    void aMemberTypeIsWrittenByItsOuterTypeWhichIsImported() {
        Set<String> imports = new LinkedHashSet<>();
        assertEquals("Activities.Research",
                ValueTypes.botName(AST_, "com.firestone.plugins.sdk.Activities.Research", imports)
                        .getFullyQualifiedName());
        assertEquals(Set.of("com.firestone.plugins.sdk.Activities"), imports);
    }

    @Test
    void aTopLevelTypeIsWrittenByItsSimpleName() {
        Set<String> imports = new LinkedHashSet<>();
        assertEquals("Point", ValueTypes.botName(AST_, "com.example.bot.Point", imports).getFullyQualifiedName());
        assertEquals(Set.of("com.example.bot.Point"), imports);
    }

    @Test
    void aNameWithNoPackageIsWrittenAsItIs() {
        Set<String> imports = new LinkedHashSet<>();
        assertEquals("Subject.Outcomes", ValueTypes.botName(AST_, "Subject.Outcomes", imports).getFullyQualifiedName());
        assertEquals(List.of(), List.copyOf(imports));
    }
}
