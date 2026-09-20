package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueCodec;
import com.botmaker.plugin.api.value.ValueType;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a set of plugins composes to when one of them will not compose with the rest.
 *
 * <p>The incident this is about: a project pinning an SDK older than the release that moved the nine JDK
 * value types into {@code botmaker-plugin-basics}, with basics declared beside it, has two plugins both
 * registering {@code DURATION}. The fold threw, the caller fell back to the bundled set — which is empty,
 * since Studio bundles no plugin — and the user lost <em>every</em> editor of <em>every</em> plugin, with
 * one line on stderr as the only trace. So the properties worth holding are the blast radius (one plugin
 * leaves, the rest bind) and that the reason reaches {@code failures()}, where a window can say it.
 *
 * <p>Nothing here loads a plugin: {@code compose} takes the set, which is what makes it assertable with no
 * jar, no classloader and no project — the same split as {@code PluginOwnersTest} beside it.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class PluginCompositionTest {

    /** A codec that does nothing interesting: composition never calls one. */
    private static ValueCodec<String> passthrough() {
        return new ValueCodec<>() {
            @Override
            public String parse(String wire) {
                return wire == null ? "" : wire;
            }

            @Override
            public String store(String value) {
                return value;
            }

            @Override
            public String literal(String value) {
                return "\"" + value + "\"";
            }

            @Override
            public Optional<String> valueOfLiteral(String java) {
                return Optional.empty();
            }
        };
    }

    private static StudioPlugin plugin(String id, String... valueTypeIds) {
        ValueCatalog.Builder catalog = ValueCatalog.builder();
        for (String typeId : valueTypeIds) {
            // One Java name each: a builder refuses two types claiming one, which is a different mistake
            // from the id clash under test here.
            catalog.add(ValueType.of(typeId).label(typeId).source("com.example." + typeId).build(),
                    passthrough());
        }
        ValueCatalog built = catalog.build();
        return new StudioPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public ValueCatalog valueTypes() {
                return built;
            }
        };
    }

    /** A plugin whose own catalog throws — a broken build, not a clash. Rejected the same way. */
    private static StudioPlugin throwing(String id) {
        return new StudioPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public ValueCatalog valueTypes() {
                throw new IllegalStateException("no catalog here");
            }
        };
    }

    @Test
    void twoPluginsClaimingOneValueTypeIdCostOnlyTheSecondOne() {
        StudioPlugin sdk = plugin("com.botmaker.sdk", "DURATION", "IMAGE");
        StudioPlugin basics = plugin("com.botmaker.basics", "DURATION", "TEXT");
        StudioPlugin other = plugin("com.example.other", "CHANNEL");

        PluginHost.Composition composed = PluginHost.compose(List.of(sdk, basics, other));

        assertEquals(List.of(sdk, other), composed.bound(),
                "classpath order decides, and only the clashing plugin leaves");
        assertEquals(List.of("com.botmaker.basics"),
                composed.rejected().stream().map(f -> f.provider()).toList());
        assertTrue(composed.valueTypes().type("CHANNEL").known(),
                "a plugin beside the clash keeps its whole vocabulary");
        assertTrue(composed.valueTypes().type("IMAGE").known());
        assertFalse(composed.valueTypes().type("TEXT").known(),
                "the rejected plugin registers nothing at all, not even its uncontested types");
    }

    @Test
    void theRejectionSaysWhichIdsClashedAndWhoAlreadyClaimedThem() {
        PluginHost.Composition composed = PluginHost.compose(
                List.of(plugin("com.botmaker.sdk", "DURATION"), plugin("com.botmaker.basics", "DURATION")));

        String line = composed.rejected().getFirst().describe();
        assertTrue(line.contains("com.botmaker.basics"), line);
        assertTrue(line.contains("DURATION"), line);
        assertTrue(line.contains("com.botmaker.sdk"), "the line names who already owns the id: " + line);
    }

    @Test
    void aPluginWhoseOwnCatalogThrowsIsRejectedAndTheRestStillBind() {
        StudioPlugin good = plugin("com.example.good", "CHANNEL");

        PluginHost.Composition composed = PluginHost.compose(List.of(throwing("com.example.broken"), good));

        assertEquals(List.of(good), composed.bound());
        assertEquals(List.of("com.example.broken"),
                composed.rejected().stream().map(f -> f.provider()).toList());
        assertTrue(composed.valueTypes().type("CHANNEL").known());
    }

    @Test
    void aSetThatComposesRejectsNothing() {
        PluginHost.Composition composed = PluginHost.compose(
                List.of(plugin("a", "ONE"), plugin("b", "TWO")));

        assertEquals(List.of(), composed.rejected());
        assertEquals(2, composed.bound().size());
    }
}
