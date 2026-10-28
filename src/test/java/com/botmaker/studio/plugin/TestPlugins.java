package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.AssistantHolder;
import com.botmaker.plugin.api.CatalogHolder;
import com.botmaker.plugin.api.DeclaredPlugin;
import com.botmaker.plugin.api.EditorHolder;
import com.botmaker.plugin.api.OverlayHolder;
import com.botmaker.plugin.api.PluginDeclaration;
import com.botmaker.plugin.api.RunOverlayHolder;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.ToolbarHolder;
import com.botmaker.plugin.api.TypeHolder;
import com.botmaker.plugin.api.ValueHolder;
import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.plugin.api.overlay.OverlayPart;
import com.botmaker.plugin.api.run.RunOverlayPart;
import com.botmaker.plugin.api.slot.SlotEditor;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.toolbar.ToolbarItem;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.PluginCoordinate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Plugins a test builds by hand: a contract plugin is a {@link DeclaredPlugin} (the interface is sealed), and the
 * host keeps each as a {@link LoadedPlugin} — the coordinate and name its pom would have given it.
 */
public final class TestPlugins {

    /** The groupId every hand-built plugin is under. */
    public static final String GROUP = "com.example";

    private TestPlugins() {
    }

    /** A plugin stated as {@code declaration}. */
    public static class Fake extends DeclaredPlugin {
        public Fake(PluginDeclaration declaration) {
            super(declaration);
        }
    }

    /** {@code declaration} as a plugin. */
    public static StudioPlugin plugin(PluginDeclaration declaration) {
        return new Fake(declaration);
    }

    /** {@code com.example:<artifactId>}. */
    public static PluginCoordinate coordinate(String artifactId) {
        return new PluginCoordinate(GROUP, artifactId);
    }

    /** {@code plugin} loaded as {@code com.example:<artifactId>}, named {@code artifactId}. */
    public static LoadedPlugin loaded(String artifactId, StudioPlugin plugin) {
        return loaded(artifactId, artifactId, plugin);
    }

    /** {@code plugin} loaded as {@code com.example:<artifactId>}, named {@code name}. */
    public static LoadedPlugin loaded(String artifactId, String name, StudioPlugin plugin) {
        return new LoadedPlugin(coordinate(artifactId), name, Path.of(artifactId).toAbsolutePath().normalize(),
                plugin);
    }

    /** {@code declaration} loaded as {@code com.example:<artifactId>}, named {@code artifactId}. */
    public static LoadedPlugin loaded(String artifactId, PluginDeclaration declaration) {
        return loaded(artifactId, plugin(declaration));
    }

    /**
     * A holder class whose surface answers {@code content} each time the host builds it — what a test hands a
     * declaration step ({@code .toolbar(TestPlugins.holder(() -> items))}) instead of writing a class per fixture.
     * A content that throws is a holder that throws when asked, as a broken plugin's does.
     *
     * <p>The host builds a holder by its class, so each call takes the next class of a small pool and ties
     * {@code content} to it: a test may hold up to {@value #POOL} at once.
     */
    @SuppressWarnings("unchecked")
    public static <H> Class<H> holder(Supplier<?> content) {
        return (Class<H>) stage(new Staging(content, false));
    }

    /** {@link #holder}, answering {@code content} as a type holder's {@code components()}, its types empty. */
    public static Class<TypeHolder> components(Supplier<List<ComponentType<?>>> content) {
        return stage(new Staging(content, true));
    }

    private static final int POOL = 16;
    private static final List<Class<? extends Staged>> CLASSES = List.of(H0.class, H1.class, H2.class, H3.class,
            H4.class, H5.class, H6.class, H7.class, H8.class, H9.class, H10.class, H11.class, H12.class, H13.class,
            H14.class, H15.class);
    private static final Map<Class<?>, Staging> STAGED = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger();

    private record Staging(Supplier<?> content, boolean asComponents) {}

    @SuppressWarnings("unchecked")
    private static <H> Class<H> stage(Staging staging) {
        Class<? extends Staged> type = CLASSES.get(Math.floorMod(NEXT.getAndIncrement(), POOL));
        STAGED.put(type, staging);
        return (Class<H>) type;
    }

    /** Every holder interface at once, each answering what was staged for this class. */
    @SuppressWarnings("unchecked")
    private abstract static class Staged implements TypeHolder, EditorHolder, ValueHolder, ToolbarHolder,
            RunOverlayHolder, AssistantHolder, OverlayHolder, CatalogHolder {

        private final Staging staging = STAGED.get(getClass());

        private <T> T content() {
            return (T) staging.content().get();
        }

        @Override public List<PluginType<?>> types() { return staging.asComponents() ? List.of() : content(); }

        @Override public List<ComponentType<?>> components() {
            return staging.asComponents() ? content() : List.of();
        }

        @Override public List<SlotEditor> editors() { return content(); }

        @Override public List<ManagedValue<?>> values() { return content(); }

        @Override public List<ToolbarItem> toolbar() { return content(); }

        @Override public List<RunOverlayPart> runOverlay() { return content(); }

        @Override public List<AssistantTool<?>> tools() { return content(); }

        @Override public OverlayPart overlay() { return content(); }

        @Override public PaletteCatalog catalog() { return content(); }
    }

    private static final class H0 extends Staged {}
    private static final class H1 extends Staged {}
    private static final class H2 extends Staged {}
    private static final class H3 extends Staged {}
    private static final class H4 extends Staged {}
    private static final class H5 extends Staged {}
    private static final class H6 extends Staged {}
    private static final class H7 extends Staged {}
    private static final class H8 extends Staged {}
    private static final class H9 extends Staged {}
    private static final class H10 extends Staged {}
    private static final class H11 extends Staged {}
    private static final class H12 extends Staged {}
    private static final class H13 extends Staged {}
    private static final class H14 extends Staged {}
    private static final class H15 extends Staged {}
}
