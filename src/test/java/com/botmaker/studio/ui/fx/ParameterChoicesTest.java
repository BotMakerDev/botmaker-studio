package com.botmaker.studio.ui.fx;

import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.ui.app.params.ParametersDialog;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Choices row of a text parameter in the Parameters window: what is typed in its add field is what gets
 * declared, whether the author presses Enter or clicks Add.
 *
 * <p>The field is a plugin's editor, so a plugin is bound: a fixture compiled here whose text editor commits on
 * Enter and on focus loss, the way the toolkit's {@code Fields.committing} does. That commit-on-Enter is the
 * handler the window used to replace with its own, so Enter added the empty value it read before the commit
 * and did nothing, and the text stayed in the field to be glued onto the next choice.
 */
class ParameterChoicesTest extends FxHeadlessTest {

    private static final String PLUGIN = """
            package fixture.text;

            import com.botmaker.plugin.api.StudioPlugin;
            import com.botmaker.plugin.api.slot.SlotEditor;
            import javafx.scene.control.TextField;
            import java.util.List;

            public final class TextPlugin implements StudioPlugin {
                @Override public String id() { return "fixture.text"; }

                @Override public List<SlotEditor> slotEditors() {
                    return List.of(SlotEditor.forType(String.class).draw(() -> ctx -> {
                        TextField field = new TextField(ctx.value(String.class).orElse(""));
                        field.setOnAction(e -> ctx.set(field.getText()));
                        field.focusedProperty().addListener((o, had, has) -> {
                            if (!has) ctx.set(field.getText());
                        });
                        return field;
                    }));
                }
            }
            """;

    @TempDir Path root;
    private Path parameters;

    @Override
    public void start(Stage stage) {
    }

    @BeforeEach
    void bindTheFixture() throws IOException {
        PluginHost.bind(List.of(pluginJar().toString()), null);
    }

    /** Opens the window over a project whose one parameter is {@code field}, annotated {@code @Param}. */
    private void open(String imports, String field) throws IOException {
        open(imports, "@Param", field);
    }

    /** The same, with the annotation as given — {@code @Param(options = …)} for a field picked from a set. */
    private void open(String imports, String annotation, String field) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("refbot", root);
        Files.createDirectories(config.mainPackageDir());
        parameters = config.mainPackageDir().resolve("Parameters.java");
        Files.writeString(parameters, """
                package com.refbot;

                import com.botmaker.plugin.api.params.Param;
                %s
                public final class Parameters {
                    %s
                    %s
                }
                """.formatted(imports, annotation, field));
        interact(() -> new ParametersDialog(null, config, null, null).show());
    }

    /** A text parameter already picked "one of" a set holding only its own value. */
    private void openText() throws IOException {
        open("", "@Param(options = {\"fast\"})", "public static String mode = \"fast\";");
    }

    @AfterEach
    void closeTheWindow() {
        interact(() -> List.copyOf(Window.getWindows()).forEach(w -> {
            if (w instanceof Stage stage) stage.close();
        }));
        PluginHost.unbind();
    }

    @Test
    void enterDeclaresTheTypedChoice() throws IOException {
        openText();
        clickOn(addField()).write("slow");
        type(KeyCode.ENTER);

        String source = Files.readString(parameters);
        assertTrue(source.contains("\"fast\", \"slow\""), source);
    }

    @Test
    void enterThenAddDeclaresTwoSeparateChoices() throws IOException {
        openText();
        clickOn(addField()).write("slow");
        type(KeyCode.ENTER);
        clickOn(addField()).write("turbo");
        clickOn(find(n -> n instanceof Button b && "Add".equals(b.getText())));

        String source = Files.readString(parameters);
        assertTrue(source.contains("\"fast\", \"slow\", \"turbo\""), source);
    }

    @Test
    void aListOfTextTakesChoicesTheSameWay() throws IOException {
        open("import java.util.List;\n", "@Param(options = {\"fast\"})",
                "public static List<String> modes = List.of();");
        clickOn(addField()).write("slow");
        type(KeyCode.ENTER);

        String source = Files.readString(parameters);
        assertTrue(source.contains("\"fast\", \"slow\""), source);
    }

    /** A free value has no add field; "One of" declares a set holding the value it already had. */
    @Test
    void oneOfStartsTheSetWithTheCurrentValue() throws IOException {
        open("", "public static String mode = \"fast\";");
        assertEquals(null, find(n -> n instanceof TextField tf && "new choice".equals(tf.getPromptText())),
                "a value typed freely offers no choices to add to");
        clickOn(find(n -> n instanceof javafx.scene.control.ToggleButton b && "One of".equals(b.getText())));

        String source = Files.readString(parameters);
        assertTrue(source.contains("options = {\"fast\"}") || source.contains("options = { \"fast\" }"), source);
        assertTrue(source.contains("String mode = \"fast\""), source);
    }

    /** A parameter added from the bar is shown to the user unless its tick is cleared first. */
    @Test
    void aNewParameterIsShownToTheUserByDefault() throws IOException {
        open("", "public static String mode = \"fast\";");
        TextField name = (TextField) find(n -> n instanceof TextField tf && "parameter name".equals(tf.getPromptText()));
        interact(() -> {
            name.setText("speed");
            name.fireEvent(new javafx.event.ActionEvent());
        });

        String source = Files.readString(parameters);
        assertTrue(source.contains("speed"), source);
        assertTrue(source.contains("visibility"), source);
    }

    /** "Any of" is the one mode that retypes: the field becomes a list, the old value its one tick. */
    @Test
    void anyOfMakesTheFieldAListWithTheOldValueTicked() throws IOException {
        open("", "public static String mode = \"fast\";");
        clickOn(find(n -> n instanceof javafx.scene.control.ToggleButton b && "Any of".equals(b.getText())));

        String source = Files.readString(parameters);
        assertTrue(source.contains("List<String> mode"), source);
        assertTrue(source.contains("\"fast\""), source);
        assertTrue(source.contains("List.of(\"fast\")"), source);
    }

    private Node addField() {
        Node field = find(n -> n instanceof TextField tf && "new choice".equals(tf.getPromptText()));
        assertNotNull(field, "the text parameter's card offers a Choices add field");
        assertEquals("", ((TextField) field).getText(), "the add field starts empty");
        return field;
    }

    private static Node find(Predicate<Node> test) {
        Window dialog = Window.getWindows().stream().filter(Window::isShowing).reduce((a, b) -> b).orElseThrow();
        Node[] hit = new Node[1];
        walk(dialog.getScene().getRoot(), n -> {
            if (hit[0] == null && test.test(n)) hit[0] = n;
        });
        return hit[0];
    }

    private static void walk(Node node, Consumer<Node> visit) {
        visit.accept(node);
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) walk(child, visit);
    }

    /** The fixture plugin, compiled against the test classpath and registered the way {@code ServiceLoader} reads. */
    private Path pluginJar() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no platform compiler on this JRE; the fixture plugin cannot be built");
        Path src = root.resolve("fixture-src/fixture/text/TextPlugin.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, PLUGIN);
        Path out = root.resolve("fixture-classes");
        Files.createDirectories(out);
        assertEquals(0, compiler.run(null, null, null, "-d", out.toString(), src.toString()),
                "the fixture plugin must compile");
        Path service = out.resolve("META-INF/services/com.botmaker.plugin.api.StudioPlugin");
        Files.createDirectories(service.getParent());
        Files.writeString(service, "fixture.text.TextPlugin\n");

        Path jar = root.resolve("fixture-text.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> walk = Files.walk(out)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                jos.putNextEntry(new JarEntry(out.relativize(p).toString().replace('\\', '/')));
                jos.write(Files.readAllBytes(p));
                jos.closeEntry();
            }
        }
        return jar;
    }
}
