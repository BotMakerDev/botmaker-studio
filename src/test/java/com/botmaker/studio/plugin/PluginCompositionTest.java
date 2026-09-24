package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import javafx.scene.Node;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a set of plugins composes to when one of them will not compose with the rest.
 *
 * <p>The incident this is about: a project pinning an SDK older than the release that moved the nine JDK
 * value types into {@code botmaker-plugin-basics}, with basics declared beside it, has two plugins both
 * declaring {@code Duration}. The fold threw, the caller fell back to the bundled set — which is empty,
 * since Studio bundles no plugin — and the user lost <em>every</em> editor of <em>every</em> plugin, with
 * one line on stderr as the only trace. So the properties worth holding are the blast radius (one plugin
 * leaves, the rest bind) and that the reason reaches {@code failures()}, where a window can say it.
 *
 * <p>The identity is the declared class since 2026-09-22, where it was a {@code ValueType} id.
 *
 * <p>Nothing here loads a plugin: {@code compose} takes the set, which is what makes it assertable with no
 * jar, no classloader and no project — the same split as {@code PluginOwnersTest} beside it.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class PluginCompositionTest {

    /** One declaration of {@code type}, doing nothing a composition would call. */
    private static <T> PluginType<T> declared(Class<T> type) {
        return new PluginType<>() {
            @Override public Class<T> type() { return type; }
            @Override public T fresh() { return null; }
            @Override public Node editor(ValueContext ctx) { return null; }
        };
    }

    private static StudioPlugin plugin(String id, Class<?>... types) {
        List<PluginType<?>> declarations = new ArrayList<>();
        for (Class<?> type : types) declarations.add(declared(type));
        return new StudioPlugin() {
            @Override public String id() { return id; }
            @Override public List<PluginType<?>> types() { return List.copyOf(declarations); }
        };
    }

    /** A plugin whose own declarations throw — a broken build, not a clash. Rejected the same way. */
    private static StudioPlugin throwing(String id) {
        return new StudioPlugin() {
            @Override public String id() { return id; }
            @Override public List<PluginType<?>> types() { throw new IllegalStateException("no types here"); }
        };
    }

    record Channel(String name) {}

    record Image(String path) {}

    @Test
    void twoPluginsDeclaringOneTypeCostOnlyTheSecondOne() {
        StudioPlugin sdk = plugin("com.botmaker.sdk", Duration.class, Image.class);
        StudioPlugin basics = plugin("com.botmaker.basics", Duration.class, String.class);
        StudioPlugin other = plugin("com.example.other", Channel.class);

        PluginHost.Composition composed = PluginHost.compose(List.of(sdk, basics, other));

        assertEquals(List.of(sdk, other), composed.bound(),
                "classpath order decides, and only the clashing plugin leaves");
        assertEquals(List.of("com.botmaker.basics"),
                composed.rejected().stream().map(f -> f.provider()).toList());
        assertTrue(composed.grammar().type(Channel.class).isPresent(),
                "a plugin beside the clash keeps its whole vocabulary");
        assertTrue(composed.grammar().type(Image.class).isPresent());
        assertTrue(composed.grammar().type(String.class).isEmpty(),
                "the rejected plugin declares nothing at all, not even its uncontested types");
    }

    @Test
    void theRejectionSaysWhichTypesClashedAndWhoAlreadyDeclaredThem() {
        PluginHost.Composition composed = PluginHost.compose(
                List.of(plugin("com.botmaker.sdk", Duration.class), plugin("com.botmaker.basics", Duration.class)));

        String line = composed.rejected().getFirst().describe();
        assertTrue(line.contains("com.botmaker.basics"), line);
        assertTrue(line.contains("java.time.Duration"), line);
        assertTrue(line.contains("com.botmaker.sdk"), "the line names who already owns the type: " + line);
    }

    @Test
    void aPluginWhoseOwnDeclarationsThrowIsRejectedAndTheRestStillBind() {
        StudioPlugin good = plugin("com.example.good", Channel.class);

        PluginHost.Composition composed = PluginHost.compose(List.of(throwing("com.example.broken"), good));

        assertEquals(List.of(good), composed.bound());
        assertEquals(List.of("com.example.broken"),
                composed.rejected().stream().map(f -> f.provider()).toList());
        assertTrue(composed.grammar().type(Channel.class).isPresent());
    }

    @Test
    void aSetThatComposesRejectsNothing() {
        PluginHost.Composition composed = PluginHost.compose(
                List.of(plugin("a", Channel.class), plugin("b", Image.class)));

        assertEquals(List.of(), composed.rejected());
        assertEquals(2, composed.bound().size());
    }

    /**
     * A composite nothing declares on its own reaches the grammar through {@code componentTypes()} — the
     * SDK's {@code Flow} is five of these, and until 2026-09-23 there was no way for one to reach the host.
     */
    @Test
    void aComponentTypeReachesTheGrammarThroughItsOwnSurface() {
        ComponentType<Channel> channel = new ComponentType<>() {
            @Override public Class<Channel> type() { return Channel.class; }
            @Override public List<Class<?>> componentTypes() { return List.of(String.class); }
            @Override public List<Object> components(Channel value) { return List.of(value.name()); }
            @Override public Channel build(List<Object> parts) { return new Channel((String) parts.getFirst()); }
        };
        StudioPlugin plugin = new StudioPlugin() {
            @Override public String id() { return "com.example.chat"; }
            @Override public List<ComponentType<?>> componentTypes() { return List.of(channel); }
        };

        PluginHost.Composition composed = PluginHost.compose(List.of(plugin));

        String written = composed.grammar().initializerOfAny(new Channel("general")).orElseThrow();
        assertEquals("new " + Channel.class.getCanonicalName() + "(\"general\")", written);
        assertEquals(new Channel("general"), composed.grammar().valueOfAny(written).orElseThrow());
    }

    /**
     * A declared type is drawn by its own {@code editor}, so the host makes one slot editor of it — after
     * the plugin's own slot editors, which are the narrower matches.
     */
    @Test
    void aDeclaredTypeBecomesASlotEditorAfterThePluginsOwn() {
        StudioPlugin plugin = plugin("com.example.chat", Channel.class);

        List<PluginHost.OwnedEditor> editors = PluginHost.mergeOwnedSlotEditors(List.of(plugin));

        assertEquals(1, editors.size());
        assertEquals("com.example.chat", editors.getFirst().pluginId());
    }
}
