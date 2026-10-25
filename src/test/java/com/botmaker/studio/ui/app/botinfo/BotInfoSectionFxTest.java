package com.botmaker.studio.ui.app.botinfo;

import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Project Settings' Bot section (2026-10-25, plan phase 4): what Apply writes, and only that. */
class BotInfoSectionFxTest extends FxHeadlessTest {

    private static final String POM = """
            <project>
                <artifactId>firestone</artifactId>
                <version>1.0.0</version>
                <description>Old</description>
                <dependencies/>
            </project>
            """;

    private Path dir;
    private BotInfoSection section;

    @Override
    public void start(Stage stage) {
        try {
            dir = Files.createTempDirectory("bot-info");
            Files.writeString(dir.resolve("pom.xml"), POM);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        section = new BotInfoSection(dir, stage);
        stage.setScene(new Scene(new StackPane(section.node()), 600, 400));
        stage.show();
    }

    @Test
    void aDescriptionSaveRewritesOnlyTheDescription() throws Exception {
        TextArea description = lookup(".text-area").queryAs(TextArea.class);
        assertEquals("Old", description.getText());
        interact(() -> description.setText("Plays Firestone's idle loop."));
        section.pending().save();
        assertEquals(POM.replace("<description>Old</description>",
                "<description>Plays Firestone's idle loop.</description>"), Files.readString(dir.resolve("pom.xml")));
    }

    @Test
    void noIconSaysTheLetterShowsAndRemoveIsOff() {
        assertTrue(lookup("Remove").queryButton().isDisabled());
        assertFalse(Files.exists(dir.resolve("icon.png")));
        assertTrue(lookup(".label").queryAll().stream().anyMatch(n -> n instanceof Label l
                && l.getText().startsWith("No icon: the first letter shows")));
    }
}
