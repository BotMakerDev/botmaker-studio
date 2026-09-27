package com.botmaker.studio.ui.app.viewers;

import com.botmaker.studio.ui.app.viewers.ResourceViewers.Format;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.scene.Node;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each viewer reads its file with the library made for it, and shows text under a reason when it cannot. */
class ResourceViewersTest extends FxHeadlessTest {

    @TempDir
    Path dir;

    @Test
    void theFormatIsTheExtensionsWhateverItsCase() {
        assertEquals(Optional.of(Format.IMAGE), Format.of(Path.of("images/ore.PNG")));
        assertEquals(Optional.of(Format.JSON), Format.of(Path.of("plugins/sdk/targets.json")));
        assertEquals(Optional.of(Format.PROPERTIES), Format.of(Path.of("bot.properties")));
        assertEquals(Optional.of(Format.TEXT), Format.of(Path.of("notes.yml")));
        assertEquals(Optional.empty(), Format.of(Path.of("Bot.java")));
        assertFalse(ResourceViewers.canView(Path.of("Makefile")));
    }

    @Test
    void jsonIsATreeOfItsMembers() throws Exception {
        TreeItem<String> root = ResourceViewers.jsonTree("",
                new ObjectMapper().readTree("{\"name\":\"ore\",\"at\":[1,2],\"deep\":{\"x\":true}}"));
        assertEquals("{3}", root.getValue());
        assertEquals(List.of("name: \"ore\"", "at [2]", "deep {1}"),
                root.getChildren().stream().map(TreeItem::getValue).toList());
        assertEquals(List.of("[0]: 1", "[1]: 2"),
                root.getChildren().get(1).getChildren().stream().map(TreeItem::getValue).toList());
    }

    @Test
    void propertiesAreRowsByKey() throws Exception {
        List<Map.Entry<String, String>> rows = ResourceViewers.propertyRows("# a comment\nz=last\na = first\n");
        assertEquals(List.of(Map.entry("a", "first"), Map.entry("z", "last")), rows);
    }

    @Test
    void eachFileOpensInItsViewer() throws Exception {
        Path json = Files.writeString(dir.resolve("a.json"), "{\"k\": 1}");
        Path props = Files.writeString(dir.resolve("a.properties"), "k=v\n");
        Path text = Files.writeString(dir.resolve("a.csv"), "a,b\n1,2\n");
        interact(() -> {
            assertInstanceOf(TreeView.class, ResourceViewers.view(json));
            assertInstanceOf(TableView.class, ResourceViewers.view(props));
            assertInstanceOf(TextArea.class, ResourceViewers.view(text));
        });
    }

    @Test
    void jsonThatDoesNotParseIsShownAsTextUnderTheReason() throws Exception {
        Path broken = Files.writeString(dir.resolve("broken.json"), "{\"k\": ");
        interact(() -> {
            Node shown = ResourceViewers.view(broken);
            BorderPane pane = assertInstanceOf(BorderPane.class, shown);
            assertInstanceOf(TextArea.class, pane.getCenter());
            assertTrue(((javafx.scene.control.Label) pane.getTop()).getText().startsWith("Not valid JSON"));
        });
    }
}
