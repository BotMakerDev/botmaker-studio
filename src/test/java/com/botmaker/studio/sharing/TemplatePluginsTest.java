package com.botmaker.studio.sharing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-12: New Project said gamebot needs SDK 1.2.3, a gallery entry's copy; its release's pom said 1.4.4. */
class TemplatePluginsTest {

    private static final String POM = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.botmaker.gamebot</groupId>
                <artifactId>gamebot</artifactId>
                <version>0.0.1-SNAPSHOT</version>
                <properties>
                    <botmaker.sdk.version>1.4.4</botmaker.sdk.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>com.github.BotMakerDev</groupId>
                        <artifactId>botmaker-sdk</artifactId>
                        <version>${botmaker.sdk.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>5.9.3</version>
                    </dependency>
                </dependencies>
            </project>
            """;

    private static final List<PluginRegistry.Plugin> REGISTRY = List.of(new PluginRegistry.Plugin("com.botmaker.sdk",
            "SDK", "com.github.BotMakerDev:botmaker-sdk", "", "", List.of(), "", List.of(), "v1.4.2"));

    @Test
    void theRegistrysPluginsInThePomWithTheirInterpolatedVersion() {
        assertEquals(List.of(new TemplatePlugins.Pin("com.botmaker.sdk", "1.4.4")), TemplatePlugins.of(POM, REGISTRY));
    }

    @Test
    void anUnreadablePomBringsNothing() {
        assertEquals(List.of(), TemplatePlugins.of("", REGISTRY));
        assertEquals(List.of(), TemplatePlugins.of("<not a pom", REGISTRY));
        assertEquals(List.of(), TemplatePlugins.of(POM, List.of()));
    }
}
