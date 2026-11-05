package com.botmaker.studio.ui.fx;

import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.studio.ui.app.params.ParametersDialog;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A parameter's card in the Parameters window, driven as a user would. Its Choices row went with
 * {@code @Param(options)} on 2026-10-29; what stays is that a card offers no choices or range, and that a
 * parameter added from the bar is declared as the user left it.
 *
 * <p>The value field is a plugin's editor, so a plugin is bound: a fixture compiled here whose text editor
 * commits on Enter and on focus loss, the way the toolkit's {@code Fields.committing} does.
 */
class ParameterChoicesTest extends FxHeadlessTest {

    private static final String PLUGIN = """
            package fixture.text;

            import com.botmaker.plugin.api.DeclaredPlugin;
            import com.botmaker.plugin.api.EditorHolder;
            import com.botmaker.plugin.api.StudioPlugin;
            import com.botmaker.plugin.api.slot.SlotEditor;
            import javafx.scene.control.TextField;
            import java.util.List;

            public final class TextPlugin extends DeclaredPlugin {
                public TextPlugin() {
                    super(StudioPlugin.declare().editors(Editors.class));
                }

                public static final class Editors implements EditorHolder {
                    @Override
                    public List<SlotEditor> editors() {
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

    /** The same, with the annotation as given. */
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

    @AfterEach
    void closeTheWindow() {
        interact(() -> List.copyOf(Window.getWindows()).forEach(w -> {
            if (w instanceof Stage stage) stage.close();
        }));
        PluginHost.unbind();
    }

    /** No choices and no range on a card since 2026-10-29: a value's rules are its type's. */
    @Test
    void aCardOffersNoChoicesAndNoRange() throws IOException {
        open("", "public static int attempts = 3;");
        assertEquals(null, find(n -> n instanceof Label l && ("Choices".equals(l.getText())
                || "Range".equals(l.getText()))));
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

    /** The fixture plugin, compiled against the test classpath and named by its jar's pom ({@code botmaker.plugin}). */
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
        Path pom = out.resolve("META-INF/maven/fixture/text/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, "<project><groupId>fixture</groupId><artifactId>text</artifactId><properties>"
                + "<botmaker.plugin>fixture.text.TextPlugin</botmaker.plugin></properties></project>");

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
