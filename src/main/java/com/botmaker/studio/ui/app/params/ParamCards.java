package com.botmaker.studio.ui.app.params;

import com.botmaker.core.project.params.JavaParameter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the Parameters window's cards show (layout A, 2026-11-05): the filters, as two rows of chips and a search
 * box, to sections — each plugin, then the bot — then a heading per group class, then the cards. Pure: the window
 * draws what this answers, and the tests read it without a screen.
 *
 * <p><b>The class is the category.</b> A group heading is a class name, in the order the scan found the classes,
 * never a free-text label; the bot's own empty groups (one just made with <i>+ New group…</i>) get a heading too,
 * while nothing filters them out.
 */
public final class ParamCards {

    private ParamCards() {
    }

    /** The <i>When</i> chips: when a parameter changes. */
    public enum When {
        ALL("all", "All"),
        OUTSIDE_RUN("outside", "Outside a run"),
        DURING_RUN("during", "During a run");

        private final String id;
        private final String displayName;

        When(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** The chip of {@code id}; {@link #ALL} for one this does not know. */
        public static When fromId(String id) {
            for (When when : values()) {
                if (when.id.equals(id)) return when;
            }
            return ALL;
        }

        boolean admits(JavaParameter parameter) {
            return switch (this) {
                case ALL -> true;
                case OUTSIDE_RUN -> !parameter.row().duringRun();
                case DURING_RUN -> parameter.row().duringRun();
            };
        }
    }

    /**
     * A <i>From</i> chip: every parameter, the bot's own, or one plugin's. A record, not an enum: the plugins are
     * whichever the bot has, so the set is open; {@link #ALL} and {@link #BOT} are the two that always exist.
     *
     * @param id          {@code all}, {@code bot}, or the plugin's folder segment ({@code com.github…botmaker_sdk})
     * @param displayName what the chip and the section heading say: the plugin's name
     */
    public record From(String id, String displayName) {
        public static final From ALL = new From("all", "All");
        public static final From BOT = new From("bot", "This bot");

        boolean admits(From source) {
            return id.equals(ALL.id) || id.equals(source.id);
        }
    }

    /** One parameter and where it comes from. */
    public record Item(JavaParameter parameter, From from) {
    }

    /** What the chips and the search box say. */
    public record Filter(When when, From from, String search) {
        public static final Filter NONE = new Filter(When.ALL, From.ALL, "");

        public Filter {
            when = when == null ? When.ALL : when;
            from = from == null ? From.ALL : from;
            search = search == null ? "" : search.strip();
        }

        public Filter with(When other) {
            return new Filter(other, from, search);
        }

        public Filter with(From other) {
            return new Filter(when, other, search);
        }

        public Filter searching(String text) {
            return new Filter(when, from, text);
        }

        /**
         * Whether {@code item} matches the search: its name, class, type or value, ignoring case. Not where it comes
         * from: the From chips say that, and "bot" would otherwise match every one of the bot's own.
         */
        boolean finds(Item item) {
            if (search.isEmpty()) return true;
            String wanted = search.toLowerCase(Locale.ROOT);
            JavaParameter p = item.parameter();
            return List.of(p.name(), p.className(), p.row().typeName(), p.row().valueText()).stream().anyMatch(text -> text != null && text.toLowerCase(Locale.ROOT).contains(wanted));
        }

        boolean admits(Item item) {
            return when.admits(item.parameter()) && from.admits(item.from()) && finds(item);
        }
    }

    /** A chip and how many parameters it would show, with the other row's choice and the search as they are. */
    public record Chip<T>(T key, String label, int count, boolean on) {
    }

    /** A group heading, its class name, and its cards in source order. */
    public record Group(String className, List<JavaParameter> cards) {
    }

    /** A section: one plugin's parameters, or the bot's, by group. */
    public record Section(From from, List<Group> groups) {
    }

    /** The <i>When</i> chips, each counted under the <i>From</i> chip and the search. */
    public static List<Chip<When>> whenChips(List<Item> items, Filter filter) {
        List<Chip<When>> out = new ArrayList<>();
        for (When when : When.values()) {
            Filter asked = filter.with(when);
            out.add(new Chip<>(when, when.displayName(), (int) items.stream().filter(asked::admits).count(),
                    when == filter.when()));
        }
        return out;
    }

    /** The <i>From</i> chips — all, this bot, then each plugin as first found — counted under <i>When</i> and the search. */
    public static List<Chip<From>> fromChips(List<Item> items, Filter filter) {
        List<From> froms = new ArrayList<>(List.of(From.ALL, From.BOT));
        for (Item item : items) {
            if (!froms.contains(item.from())) froms.add(item.from());
        }
        List<Chip<From>> out = new ArrayList<>();
        for (From from : froms) {
            Filter asked = filter.with(from);
            out.add(new Chip<>(from, from.displayName(), (int) items.stream().filter(asked::admits).count(),
                    from.equals(filter.from())));
        }
        return out;
    }

    /**
     * The sections the filter leaves: each plugin's as first found, then the bot's; inside one, a group per class in
     * the order its first parameter was found. {@code emptyGroups} are the bot's own groups with nothing in them yet,
     * headed while nothing filters them out; the bot's section is drawn then even when it holds nothing, so a bot
     * with no parameter still has a place that says so.
     */
    public static List<Section> sections(List<Item> items, List<String> emptyGroups, Filter filter) {
        Map<From, Map<String, List<JavaParameter>>> byFrom = new LinkedHashMap<>();
        for (Item item : items) {
            if (!filter.admits(item)) continue;
            byFrom.computeIfAbsent(item.from(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(item.parameter().className(), key -> new ArrayList<>())
                    .add(item.parameter());
        }
        boolean unfiltered = filter.when() == When.ALL && filter.search().isEmpty() && filter.from().admits(From.BOT);
        if (unfiltered) {
            Map<String, List<JavaParameter>> own = byFrom.computeIfAbsent(From.BOT, key -> new LinkedHashMap<>());
            for (String group : emptyGroups) {
                boolean taken = items.stream().anyMatch(item -> item.parameter().className().equals(group));
                if (!taken) own.putIfAbsent(group, List.of());
            }
        }
        List<Section> plugins = new ArrayList<>();
        Section bot = null;
        for (Map.Entry<From, Map<String, List<JavaParameter>>> entry : byFrom.entrySet()) {
            List<Group> groups = entry.getValue().entrySet().stream()
                    .map(group -> new Group(group.getKey(), List.copyOf(group.getValue()))).toList();
            Section section = new Section(entry.getKey(), groups);
            if (entry.getKey().equals(From.BOT)) bot = section;
            else plugins.add(section);
        }
        List<Section> out = new ArrayList<>(plugins);
        if (bot != null) out.add(bot);
        return List.copyOf(out);
    }
}
