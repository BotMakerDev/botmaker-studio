package com.botmaker.studio.plugin.grammar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A value written into another of the bot's files: its classes by simple name, each imported. */
class ShortNamesTest {

    private static final List<String> BOT = List.of("com.bot.Research", "com.bot.plugins.sdk.Activities",
            "com.bot.Flow");

    private static JavaValue imported(String source, String... imports) {
        JavaValue kept = JavaValue.parse(source).orElseThrow();
        return ShortNames.imported(new JavaValue(kept.node(), List.of(imports), kept.source()), BOT);
    }

    @Test
    void aBodyOfTheBotsImportsItsClass() {
        JavaValue written = imported("Flow.activity(Activities.Research.class, Research::body)",
                "com.botmaker.sdk.api.flow.Flow");
        assertEquals("Flow.activity(Activities.Research.class,Research::body)", written.node().toString());
        // Flow is the SDK's, as the value imports it, though the bot has a Flow of its own.
        assertEquals(List.of("com.botmaker.sdk.api.flow.Flow", "com.bot.plugins.sdk.Activities", "com.bot.Research"),
                written.imports());
    }

    @Test
    void aFullNameIsWrittenShort() {
        JavaValue written = imported("Flow.activity(Activities.Research.class, "
                + "com.botmaker.sdk.api.flow.ActivityBody.none())");
        assertEquals("Flow.activity(Activities.Research.class,ActivityBody.none())", written.node().toString());
        assertEquals(List.of("com.bot.Flow", "com.bot.plugins.sdk.Activities", "com.botmaker.sdk.api.flow.ActivityBody"),
                written.imports());
    }

    @Test
    void aWholeValueIsANameToo() {
        assertEquals("ActivityBody", imported("com.botmaker.sdk.api.flow.ActivityBody").node().toString());
        JavaValue field = imported("settings.DEFAULT");
        assertEquals("settings.DEFAULT", field.node().toString(), "a variable's field, not a package's class");
        assertEquals(List.of(), field.imports());
    }

    @Test
    void aNameTakenByAnotherClassStaysFull() {
        JavaValue written = imported("other.Research.go(com.x.Research::body)", "com.y.Research");
        assertEquals("other.Research.go(com.x.Research::body)", written.node().toString());
        assertEquals(List.of("com.y.Research"), written.imports());
    }
}
