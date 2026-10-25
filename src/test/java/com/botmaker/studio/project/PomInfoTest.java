package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The pom's own name and description, read and written as text (2026-10-25, plan phase 4). */
class PomInfoTest {

    static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <!-- <name>not this</name> -->
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>p</groupId>
                    <artifactId>parent</artifactId>
                    <version>1</version>
                    <name>the parent's</name>
                </parent>
                <groupId>com.example</groupId>
                <artifactId>firestone</artifactId>
                <version>1.0.0</version>
                <description>Old &amp; tired</description>
                <dependencies>
                    <dependency>
                        <artifactId>x</artifactId>
                        <name>a dependency's</name>
                    </dependency>
                </dependencies>
            </project>
            """;

    @Test
    void readsOnlyTheProjectsOwn() {
        PomInfo.Info info = PomInfo.of(POM);
        assertEquals(Optional.empty(), info.name());
        assertEquals(Optional.of("Old & tired"), info.description());
    }

    @Test
    void aDescriptionIsReplacedInPlace() {
        String after = PomInfo.with(POM, "description", "Plays <Firestone>");
        assertEquals(POM.replace("Old &amp; tired", "Plays &lt;Firestone&gt;"), after);
    }

    @Test
    void aNameIsAddedAfterTheVersionAndABlankOneRemoved() {
        String named = PomInfo.with(POM, "name", "Firestone");
        assertEquals(POM.replace("    <version>1.0.0</version>\n",
                "    <version>1.0.0</version>\n    <name>Firestone</name>\n"), named);
        assertEquals(POM, PomInfo.with(named, "name", " "));
        assertSame(POM, PomInfo.with(POM, "description", "Old & tired"), "unchanged: nothing written");
    }

    @Test
    void emptySelfClosedCdataAndCrlfElementsStayWellFormed() {
        String empty = "<project>\n  <version>1</version>\n  <description></description>\n</project>\n";
        assertEquals("<project>\n  <version>1</version>\n</project>\n", PomInfo.with(empty, "description", ""));

        String closed = "<project>\n  <version>1</version>\n  <description/>\n</project>\n";
        assertEquals("<project>\n  <version>1</version>\n  <description>New</description>\n</project>\n",
                PomInfo.with(closed, "description", "New"));

        String cdata = "<project a=\"x>y\">\n  <description><![CDATA[Uses <b>bold</b>]]></description>\n"
                + "  <name>N</name>\n</project>\n";
        assertEquals(Optional.of("Uses <b>bold</b>"), PomInfo.of(cdata).description());
        assertEquals(Optional.of("N"), PomInfo.of(cdata).name());
        assertSame(cdata, PomInfo.with(cdata, "description", "Uses <b>bold</b>"));

        String crlf = "<project>\r\n  <version>1</version>\r\n</project>\r\n";
        String named = PomInfo.with(crlf, "name", "N");
        assertEquals("<project>\r\n  <version>1</version>\r\n  <name>N</name>\r\n</project>\r\n", named);
        assertEquals(crlf, PomInfo.with(named, "name", ""));
    }
}
