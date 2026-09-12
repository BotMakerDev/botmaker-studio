package com.botmaker.studio.plugin;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Who draws a type two plugins both claim.
 *
 * <p>Three walks over the plugin editors exist ({@code PluginPickers.dispatch},
 * {@code ValueEditors.fromPlugin}, {@code ValueEditors.previewFromPlugin}) and every one of them takes the
 * first editor that claims the value. So the whole verdict reduces to <b>ordering the claimants</b>, which is
 * why this is a pure function over a list and an owner function rather than anything that knows what an editor
 * is — and why it is testable with no JavaFX and no loaded plugin.
 *
 * <p>The property that matters most is the last one: a verdict naming a plugin that is not installed must
 * leave the order alone rather than empty it. That is the ordinary state of a project whose plugin was
 * removed, and the failure mode would be a slot with no editor at all.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class EditorContestTest {

    /** Stands for a claimant; the real one is a PluginHost.OwnedEditor, which needs a plugin to build. */
    private record Claimant(String owner, String name) {}

    private static final Function<Claimant, String> OWNER = Claimant::owner;

    private static List<String> names(List<Claimant> claimants) {
        return claimants.stream().map(Claimant::name).toList();
    }

    private static final Claimant SDK = new Claimant("com.botmaker.sdk", "sdk-rect");
    private static final Claimant VISION = new Claimant("com.example.vision", "vision-rect");
    private static final Claimant BASICS = new Claimant("com.botmaker.basics", "basics-rect");

    @Test
    void withNoVerdictTheOrderIsUntouched() {
        assertEquals(List.of("sdk-rect", "vision-rect"),
                names(EditorContest.ordered(List.of(SDK, VISION), OWNER, null)));
    }

    @Test
    void thePreferredPluginLeads() {
        assertEquals(List.of("vision-rect", "sdk-rect"),
                names(EditorContest.ordered(List.of(SDK, VISION), OWNER, "com.example.vision")));
    }

    @Test
    void theLosersKeepTheirRelativeOrder() {
        // A stable partition, not a sort: the fallback order is plugin load order, and a verdict about one
        // plugin must not silently reorder the two it says nothing about.
        assertEquals(List.of("vision-rect", "sdk-rect", "basics-rect"),
                names(EditorContest.ordered(List.of(SDK, VISION, BASICS), OWNER, "com.example.vision")));
    }

    @Test
    void everyEditorAPreferredPluginOffersLeads() {
        // One plugin may ship two editors that both claim a type — a general one and a call-site one.
        Claimant visionCallSite = new Claimant("com.example.vision", "vision-steam-id");
        assertEquals(List.of("vision-steam-id", "vision-rect", "sdk-rect"),
                names(EditorContest.ordered(List.of(SDK, visionCallSite, VISION), OWNER, "com.example.vision")));
    }

    @Test
    void aVerdictForAPluginThatIsGoneIsInertRatherThanEmpty() {
        assertEquals(List.of("sdk-rect", "vision-rect"),
                names(EditorContest.ordered(List.of(SDK, VISION), OWNER, "com.example.uninstalled")));
    }

    @Test
    void anEmptyContestStaysEmpty() {
        assertEquals(List.of(), EditorContest.ordered(List.<Claimant>of(), OWNER, "com.botmaker.sdk"));
    }
}
