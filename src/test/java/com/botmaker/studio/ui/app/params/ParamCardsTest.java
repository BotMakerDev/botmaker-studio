package com.botmaker.studio.ui.app.params;

import com.botmaker.core.project.params.JavaParameter;
import com.botmaker.core.project.params.ParameterRow;
import com.botmaker.plugin.api.params.Param;
import com.botmaker.studio.ui.app.params.ParamCards.Chip;
import com.botmaker.studio.ui.app.params.ParamCards.Filter;
import com.botmaker.studio.ui.app.params.ParamCards.From;
import com.botmaker.studio.ui.app.params.ParamCards.Item;
import com.botmaker.studio.ui.app.params.ParamCards.Section;
import com.botmaker.studio.ui.app.params.ParamCards.When;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParamCardsTest {

    private static final From SDK = new From("com.github.botmakerdev.botmaker_sdk", "BotMaker SDK");

    /** The mockup's five: three of the SDK's, two of the bot's (one during a run). */
    private static final List<Item> ITEMS = List.of(
            item(SDK, "Running", "flow", "Flow", Param.Changes.OUTSIDE_RUN),
            item(SDK, "Game", "launchTarget", "LaunchChoice", Param.Changes.OUTSIDE_RUN),
            item(From.BOT, "Account", "server", "String", Param.Changes.OUTSIDE_RUN),
            item(SDK, "Game", "captureSource", "CaptureSource", Param.Changes.OUTSIDE_RUN),
            item(From.BOT, "Progress", "gold", "int", Param.Changes.DURING_RUN));

    @Test
    void filtersCombine() {
        Filter filter = Filter.NONE.with(When.OUTSIDE_RUN).with(From.BOT);
        assertEquals(List.of("server"), names(ParamCards.sections(ITEMS, List.of(), filter)));

        assertEquals(List.of("captureSource"),
                names(ParamCards.sections(ITEMS, List.of(), Filter.NONE.with(SDK).searching("CAPTURE"))));
        assertEquals(List.of(), names(ParamCards.sections(ITEMS, List.of(), Filter.NONE.with(When.DURING_RUN).with(SDK))));
        // Where a parameter comes from is the From chips' to say, not the search's.
        assertEquals(List.of(), names(ParamCards.sections(ITEMS, List.of(), Filter.NONE.searching("this bot"))));
    }

    @Test
    void countsFollowTheOtherFilter() {
        assertEquals(List.of(5, 4, 1), counts(ParamCards.whenChips(ITEMS, Filter.NONE)));
        assertEquals(List.of(2, 1, 1), counts(ParamCards.whenChips(ITEMS, Filter.NONE.with(From.BOT))));
        assertEquals(List.of(5, 2, 3), counts(ParamCards.fromChips(ITEMS, Filter.NONE)));
        assertEquals(List.of(4, 1, 3), counts(ParamCards.fromChips(ITEMS, Filter.NONE.with(When.OUTSIDE_RUN))));
        // A chip's own row does not narrow it: From's chips count under When, never under From.
        assertEquals(List.of(5, 2, 3), counts(ParamCards.fromChips(ITEMS, Filter.NONE.with(SDK))));
        assertEquals(List.of(true, false, false),
                ParamCards.whenChips(ITEMS, Filter.NONE).stream().map(Chip::on).toList());
    }

    @Test
    void sectionsArePluginsThenBot() {
        List<Section> sections = ParamCards.sections(ITEMS, List.of(), Filter.NONE);
        assertEquals(List.of(SDK, From.BOT), sections.stream().map(Section::from).toList());
    }

    @Test
    void groupsAreClassesInSourceOrder() {
        List<Section> sections = ParamCards.sections(ITEMS, List.of("Achievements", "Progress"), Filter.NONE);
        assertEquals(List.of("Running", "Game"), groups(sections.getFirst()));
        assertEquals(List.of("flow", "launchTarget", "captureSource"),
                sections.getFirst().groups().stream().flatMap(g -> g.cards().stream()).map(JavaParameter::name).toList());
        // The bot's empty group is headed after the ones holding parameters; one already holding some is not doubled.
        assertEquals(List.of("Account", "Progress", "Achievements"), groups(sections.getLast()));
        // A filter hides the empty one: it holds nothing that could match.
        assertEquals(List.of("Account"),
                groups(ParamCards.sections(ITEMS, List.of("Achievements"), Filter.NONE.with(When.OUTSIDE_RUN)).getLast()));
    }

    private static Item item(From from, String group, String name, String type, Param.Changes changes) {
        ParameterRow row = ParameterRow.named(name, type).changes(changes).build();
        JavaParameter parameter = new JavaParameter(Path.of(group + ".java"), group, row, null, "", true, "",
                from.equals(From.BOT) ? "" : "A plugin's", "", true);
        return new Item(parameter, from);
    }

    private static List<String> names(List<Section> sections) {
        return sections.stream().flatMap(s -> s.groups().stream()).flatMap(g -> g.cards().stream())
                .map(JavaParameter::name).toList();
    }

    private static List<String> groups(Section section) {
        return section.groups().stream().map(ParamCards.Group::className).toList();
    }

    private static List<Integer> counts(List<? extends Chip<?>> chips) {
        return chips.stream().map(Chip::count).toList();
    }
}
