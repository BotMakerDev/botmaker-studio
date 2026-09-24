package com.botmaker.studio.ui.render.menu;

import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.PaletteDescriptions;
import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.parser.StatementPlacement;
import com.botmaker.studio.services.SdkSurfaceService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.util.MethodSignature;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The statement insert menu: what can be added at a given point in a body. Split out of the former
 * {@code ExpressionMenuFactory}, whose other half is {@link ExpressionMenu} (which fills an expression
 * <em>slot</em>); the two shared nothing but plumbing, now in {@link MenuBuilders}.
 *
 * <p>Entries come from two sources: the language/structure blocks of {@link BlockCatalog}, grouped by
 * {@link BlockCategory}, and the {@linkplain FacadeEntry facades a plugin serves}, whose methods are
 * discovered at runtime through {@link ProjectAnalyzer} rather than mirrored in the palette.
 */
public final class StatementMenu {

    private StatementMenu() {}

    /**
     * Creates the statement insert menu. A search box filters a flat list across every insertable block — the
     * language/structure blocks <em>and</em> every SDK facade method; with no query the menu leads with a submenu
     * per catalogued facade, in the served catalog's order (methods discovered at runtime via
     * {@code ProjectAnalyzer}),
     * followed by the language-block category submenus, with the bot-{@code Control} group last.
     *
     * @param analyzer resolves each facade's static methods; may be {@code null} (headless / no project resolved),
     *                 in which case only the language blocks are shown.
     */
    public static ContextMenu create(ProjectAnalyzer analyzer, Consumer<BlockType> onSelection) {
        return create(analyzer, null, null, onSelection);
    }

    /**
     * As {@link #create(ProjectAnalyzer, Consumer)}, but only offering blocks legal in {@code targetBody} — a
     * {@code break} isn't listed where there's no loop or switch to break out of, so an illegal insert can't be
     * chosen in the first place. Pass {@code null} to offer everything.
     *
     * @param surface this project's SDK's curation ({@code @Palette}); {@code null} — headless, or no project
     *                resolved — offers every method the analyzer finds, as an uncurated jar does.
     */
    public static ContextMenu create(ProjectAnalyzer analyzer, SdkSurfaceService surface,
                                     org.eclipse.jdt.core.dom.ASTNode targetBody,
                                     Consumer<BlockType> onSelection) {
        Predicate<BlockType> allowed =
                targetBody == null ? b -> true : b -> StatementPlacement.allows(b, targetBody);
        ContextMenu menu = MenuTracker.track(new ContextMenu());
        MenuBuilders.withSearch(menu, "Search blocks…",
                (m, query) -> rebuildItems(m, query, analyzer, surface, allowed, onSelection));
        return menu;
    }

    /**
     * Language/structure block categories in statement-menu display order — the SDK facade calls are pulled out
     * into their own generated submenus (see {@link #rebuildItems}), so what remains here is the general
     * programming vocabulary. {@link BlockCategory#CONTROL} (enable/disable activity, stop bot, break/continue/
     * return) is placed last as a clearly-separated group.
     */
    private static final List<BlockCategory> LANGUAGE_CATEGORY_ORDER = List.of(
            BlockCategory.FLOW, BlockCategory.LOOPS, BlockCategory.VARIABLES, BlockCategory.BOT_VARIABLE,
            BlockCategory.FUNCTIONS, BlockCategory.OUTPUT, BlockCategory.INPUT, BlockCategory.GAME,
            BlockCategory.UTILITY, BlockCategory.CONTROL);

    /** Rebuilds the menu body (everything below the search box at index 0) for the current search {@code query}. */
    private static void rebuildItems(ContextMenu menu, String query, ProjectAnalyzer analyzer,
                                     SdkSurfaceService surface,
                                     Predicate<BlockType> allowed, Consumer<BlockType> onSelection) {
        MenuBuilders.clearBody(menu);

        String q = query == null ? "" : query.trim().toLowerCase();

        // Active search: flat, filtered list across every block — the language/structure blocks and every SDK
        // facade method — no submenus to dig through.
        if (!q.isEmpty()) {
            List<MenuItem> matches = new ArrayList<>();
            for (BlockType b : languageBlocks(allowed)) {
                if (matches(b, q)) matches.add(statementItem(b, onSelection, true));
            }
            for (SdkCall call : sdkCalls(analyzer, surface)) {
                if (matches(call.block(), q)) matches.add(callItem(call, onSelection, true));
            }
            menu.getItems().add(MenuRows.resultCount(matches.size()));
            if (matches.isEmpty()) menu.getItems().add(MenuBuilders.disabledItem("No matching blocks"));
            else menu.getItems().addAll(matches);
            return;
        }

        // What was inserted last, this session — the block a user reaches for again is usually one they just
        // used. Only entries legal here are shown, and only while the menu is not being searched.
        // A call on a facade this project's plugins do not serve (a block used in another project) is dropped.
        List<BlockType> recent = RECENT.stream()
                .filter(allowed)
                .filter(b -> !(b instanceof BlockType.LibraryCall call)
                        || PluginHost.isFacadeClass(call.facade().getSimpleName()))
                .toList();
        if (!recent.isEmpty()) {
            menu.getItems().add(MenuBuilders.sectionHeader("RECENT"));
            for (BlockType block : recent) menu.getItems().add(statementItem(block, onSelection, false));
            menu.getItems().add(new SeparatorMenuItem());
        }

        // Default: one submenu per SDK facade class (in catalog order), enumerating that class's static methods.
        //
        // PRESENCE is gated implicitly here and must stay so: sdkFacadeSubmenu returns null when the facade
        // resolves no methods, and the analyzer resolves against the bot's own jar — so a class its SDK
        // doesn't have contributes nothing. Do not add an SdkSurfaceService *presence* filter; it would be a
        // second answer to the same question, from the same jar. Do not "optimize away" the null return,
        // which is the gate.
        //
        // CURATION is a different question and does need the explicit filter the served catalog supplies (see
        // facadeMethodNames). "Is this method here?" and "should we lead with it?" are not the same, and
        // nothing enumerable answers the second — a method the analyzer resolves perfectly well may still not
        // be one we propose. The rule that keeps the two apart: filter what is OFFERED, never what is
        // RESOLVED. Blocks already in the file resolve through the analyzer, untouched.
        List<Menu> facades = new ArrayList<>();
        for (FacadeEntry facade : menuFacades(surface)) {
            MenuBuilders.addIfNonNull(facades, sdkFacadeSubmenu(facade, analyzer, surface, onSelection));
        }
        if (!facades.isEmpty()) {
            menu.getItems().add(MenuBuilders.sectionHeader("FROM PLUGINS"));
            menu.getItems().addAll(facades);
            menu.getItems().add(new SeparatorMenuItem());
        }

        // Then the language/structure block categories (SDK-facade calls excluded — reached via the submenus
        // above). A category with nothing legal here is left out rather than shown empty.
        Map<BlockCategory, List<BlockType>> grouped = languageBlocks(allowed).stream()
                .collect(Collectors.groupingBy(BlockType::category, LinkedHashMap::new, Collectors.toList()));
        menu.getItems().add(MenuBuilders.sectionHeader("JAVA"));
        for (BlockCategory category : LANGUAGE_CATEGORY_ORDER) {
            addCategoryMenu(menu, category, grouped, onSelection);
        }

        if (menu.getItems().size() == 1) menu.getItems().add(MenuBuilders.disabledItem("(No blocks available)"));
    }

    /** The last few blocks inserted from this menu, newest first, for the whole session. */
    private static final int RECENT_LIMIT = 5;
    private static final java.util.Deque<BlockType> RECENT = new java.util.ArrayDeque<>();

    /** Remembers {@code block} as the most recent pick. Package-private for the menu tests. */
    static synchronized void remember(BlockType block) {
        RECENT.removeIf(b -> b.id().equals(block.id()));
        RECENT.addFirst(block);
        while (RECENT.size() > RECENT_LIMIT) RECENT.removeLast();
    }

    /** Forgets every recent pick. For tests, which share the session. */
    public static synchronized void forgetRecent() {
        RECENT.clear();
    }

    private static boolean matches(BlockType block, String query) {
        return block.displayName().toLowerCase().contains(query)
                || PaletteDescriptions.of(block).toLowerCase().contains(query);
    }

    /**
     * The insertable language/structure blocks: every {@link BlockCatalog#all()} entry except the SDK-facade
     * calls (a {@link BlockType.LibraryCall} / {@link BlockType.LambdaCall} on a catalogued facade), which are
     * offered through the generated per-class submenus instead.
     */
    private static List<BlockType> languageBlocks(Predicate<BlockType> allowed) {
        // Declare Function is a class member: it has no statement form, and offered in a body it inserted nothing.
        return BlockCatalog.all().stream()
                .filter(BlockType::isStatement)
                .filter(b -> !isSdkFacadeCall(b))
                .filter(allowed)
                .collect(Collectors.toList());
    }

    private static boolean isSdkFacadeCall(BlockType block) {
        return switch (block) {
            case BlockType.LibraryCall l -> PluginHost.isFacadeClass(l.facade().getSimpleName());
            case BlockType.LambdaCall l -> PluginHost.isFacadeClass(l.facade().getSimpleName());
            default -> false;
        };
    }

    /**
     * A submenu of {@code facade}'s static methods (one entry per distinct method name — overloads collapse, and
     * the default overload is chosen at insert time by {@code StatementFactory}). Returns {@code null} when the
     * analyzer is absent or the facade resolves no static methods (e.g. the SDK jar isn't on the classpath yet).
     */
    private static Menu sdkFacadeSubmenu(FacadeEntry facade, ProjectAnalyzer analyzer, SdkSurfaceService surface,
                                         Consumer<BlockType> onSelection) {
        if (analyzer == null) return null;
        Menu sub = new Menu(facade.simpleName());
        for (String method : facadeMethodNames(facade, analyzer, surface)) {
            sub.getItems().add(callItem(new SdkCall(facade, sdkCall(facade, method, method)), onSelection, false));
        }
        return sub.getItems().isEmpty() ? null : MenuIcons.decorate(sub, MenuIcons.iconFor(facade));
    }

    /** A plugin call's row: the facade's glyph, and the plugin that offers it when the list is a search. */
    private static MenuItem callItem(SdkCall call, Consumer<BlockType> onSelection, boolean inline) {
        String from = PluginHost.pluginNameFor(call.facade().simpleName()).map(name -> " From " + name + ".")
                .orElse("");
        return MenuRows.entry(MenuIcons.iconFor(call.facade()), MenuRows.categoryClass(call.block().category()),
                call.block().displayName(), PaletteDescriptions.of(call.block()) + from, inline,
                () -> pick(call.block(), onSelection));
    }

    /** An SDK facade method as a statement block, paired with its facade so the search view can icon it. */
    private record SdkCall(FacadeEntry facade, BlockType block) {}

    /**
     * The facades to lead with: this project's served catalog ∩ its own jar, or — with no surface wired
     * (headless, no project) — the bundled catalog's superset, which is the same answer Studio gave when it
     * mirrored the SDK's class list by hand.
     */
    private static List<FacadeEntry> menuFacades(SdkSurfaceService surface) {
        return surface == null ? PluginHost.menuFacades() : surface.menuFacades();
    }

    /** Every SDK facade method as a flat list of class-qualified statement blocks, for the search view. */
    private static List<SdkCall> sdkCalls(ProjectAnalyzer analyzer, SdkSurfaceService surface) {
        List<SdkCall> out = new ArrayList<>();
        if (analyzer == null) return out;
        for (FacadeEntry facade : menuFacades(surface)) {
            for (String method : facadeMethodNames(facade, analyzer, surface)) {
                out.add(new SdkCall(facade, sdkCall(facade, method, facade.simpleName() + "." + method)));
            }
        }
        return out;
    }

    /**
     * Distinct static method names of {@code facade}, sorted — the entries of its statement submenu, and (via
     * {@link #sdkCalls}) of the search view, so both are curated by this one method.
     *
     * <p>A name survives when the SDK offers at least one of its overloads. Which overload the insert then
     * creates is {@code StatementFactory}'s business, and it applies the same set.
     */
    private static List<String> facadeMethodNames(FacadeEntry facade, ProjectAnalyzer analyzer,
                                                  SdkSurfaceService surface) {
        return analyzer.getMethods(facade.simpleName(), true).stream()
                .map(MethodSignature::name)
                .distinct()
                .filter(name -> surface == null || surface.isOffered(facade.simpleName(), name))
                .sorted()
                .collect(Collectors.toList());
    }

    /** A synthetic {@code facade.method(<defaults>)} statement block; args are seeded from the resolved overload. */
    private static BlockType sdkCall(FacadeEntry facade, String method, String displayName) {
        return new BlockType.LibraryCall("SDK_" + facade.simpleName() + "_" + method, displayName,
                BlockCategory.INPUT, facade.type(), method, List.of());
    }

    private static void addCategoryMenu(ContextMenu menu, BlockCategory category,
                                        Map<BlockCategory, List<BlockType>> grouped, Consumer<BlockType> onSelection) {
        List<BlockType> blocks = grouped.get(category);
        if (blocks == null || blocks.isEmpty()) return;
        Menu categoryMenu = MenuIcons.decorate(new Menu(category.getLabel()), MenuIcons.iconFor(category));
        for (BlockType block : blocks) categoryMenu.getItems().add(statementItem(block, onSelection, false));
        menu.getItems().add(categoryMenu);
    }

    private static MenuItem statementItem(BlockType block, Consumer<BlockType> onSelection, boolean inline) {
        if (block instanceof BlockType.LibraryCall call && PluginHost.ownerOf(call.facade().getSimpleName()).isPresent()) {
            return callItem(new SdkCall(PluginHost.ownerOf(call.facade().getSimpleName()).get(), block),
                    onSelection, inline);
        }
        return MenuRows.entry(MenuIcons.iconFor(block.category()), MenuRows.categoryClass(block.category()),
                block.displayName(), PaletteDescriptions.of(block), inline, () -> pick(block, onSelection));
    }

    private static void pick(BlockType block, Consumer<BlockType> onSelection) {
        remember(block);
        onSelection.accept(block);
    }
}
