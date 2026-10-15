package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AgentContext;
import com.botmaker.plugin.api.assist.AgentReply;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A plugin tool's schema as Studio serves it: nested records, lists of them, and an action tool flat on the wire. */
class PluginToolSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    enum Shape { BOX, CIRCLE }

    record Box(@Describe("its name") String name, int x, @Describe(value = "skipped", optional = true) Integer w) {
    }

    record Crops(List<Box> boxes, List<Shape> shapes, @Describe(value = "values", optional = true)
            Map<String, String> values) {
    }

    sealed interface MapEdit {
        @Describe("Renames a screen")
        record Rename(@Describe("the screen") String screen, String to) implements MapEdit {
        }

        record SetStart(String screen) implements MapEdit {
        }
    }

    static AgentReply none(Object params, AgentContext context) {
        return AgentReply.text("");
    }

    @Test
    void nestedRecordsAreObjectsAndListsHaveTheirItems() throws Exception {
        AssistantTool<Crops> tool = AssistantTool.named("crops").describedAs("Cuts").takes(Crops.class)
                .handledBy(PluginToolSchemaTest::none);
        JsonNode schema = JSON.readTree(McpTools.schemaOf(tool.schema()));
        JsonNode boxes = schema.at("/properties/boxes");
        assertEquals("array", boxes.get("type").asText());
        assertEquals("object", boxes.at("/items/type").asText());
        assertEquals("its name", boxes.at("/items/properties/name/description").asText());
        assertEquals("[\"name\",\"x\"]", boxes.at("/items/required").toString());
        assertFalse(boxes.at("/items/additionalProperties").asBoolean(true));
        assertEquals("[\"box\",\"circle\"]", schema.at("/properties/shapes/items/enum").toString());
        assertEquals("string", schema.at("/properties/values/additionalProperties/type").asText());
        assertEquals("[\"boxes\",\"shapes\"]", schema.get("required").toString());
    }

    @Test
    void anActionToolIsOneFlatObjectWhoseActionSaysWhatEachNeeds() throws Exception {
        AssistantTool<AssistantTool.OneOf<MapEdit>> tool = AssistantTool.named("edit_map").describedAs("Edits")
                .takesOneOf(MapEdit.class).handledBy(PluginToolSchemaTest::none);
        String written = McpTools.schemaOf(tool.schema());
        assertFalse(written.contains("oneOf"), written);
        JsonNode schema = JSON.readTree(written);
        assertEquals("[\"action\"]", schema.get("required").toString());
        assertEquals("[\"rename\",\"set_start\"]", schema.at("/properties/action/enum").toString());
        assertEquals("rename(screen, to): Renames a screen; set_start(screen)",
                schema.at("/properties/action/description").asText());
        assertEquals("the screen", schema.at("/properties/screen/description").asText());
        assertTrue(schema.at("/properties/to/description").asText().endsWith("(rename)"));
    }
}
