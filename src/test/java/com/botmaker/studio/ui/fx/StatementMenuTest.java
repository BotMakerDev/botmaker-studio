package com.botmaker.studio.ui.fx;

import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.ui.render.menu.StatementMenu;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the statement menu — the {@link ContextMenu} the "+" separator / empty-body placeholder shows to
 * insert a new block ({@link StatementMenu#create}). This is the search/rebuild logic the
 * user reported an interaction issue around; the companion {@link SeparatorInsertButtonTest} covers the button's
 * hover/visibility state machine.
 *
 * <p>The menu is asserted at the JavaFX-object level (its {@code MenuItem}s), not by showing a popup window, so the
 * tests are deterministic headless: constructing/mutating the menu still requires the FX toolkit (hence
 * {@link FxHeadlessTest}), and every mutation is driven on the FX thread via {@link #interact(Runnable)}.
 */
class StatementMenuTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) {
        // No scene needed — these tests operate on the ContextMenu object directly. ApplicationTest still starts
        // the FX runtime, which is all createStatementMenu() requires.
    }

    @Test
    void defaultMenuShowsCategorySubmenusWithControlRelocatedLast() {
        // Built with a null analyzer (no project resolved), so there are no generated SDK-facade submenus — only
        // the language-block categories, rendered as submenus (no flat promoted bot-actions row anymore).
        ContextMenu menu = build(type -> {});

        List<MenuItem> items = menu.getItems();
        assertTrue(items.get(0) instanceof CustomMenuItem, "first item is the search box");
        assertTrue(leafItems(menu).isEmpty(), "no flat leaf blocks promoted to the top level");

        List<Menu> submenus = items.stream().filter(i -> i instanceof Menu).map(i -> (Menu) i).toList();
        assertFalse(submenus.isEmpty(), "language category submenus are shown");

        // The language Control statements (break/continue/return) are grouped into a clearly-labelled "Control"
        // submenu, placed last. (Activity enable/disable and stop-the-bot are standard SDK facade calls now —
        // Activity.enable/disable / Bot.stop from the facade submenus — so they are no longer bespoke here.)
        assertEquals(BlockCategory.CONTROL.getLabel(), submenus.getLast().getText(), "Control group is placed last");
        List<String> control = submenus.getLast().getItems().stream().map(MenuItem::getText).toList();
        assertTrue(control.contains(BlockCatalog.RETURN.displayName()), control.toString());
        assertTrue(control.contains(BlockCatalog.BREAK.displayName()), control.toString());
        assertTrue(control.contains(BlockCatalog.CONTINUE.displayName()), control.toString());
    }

    @Test
    void searchFiltersToMatchingBlocksOnly() {
        ContextMenu menu = build(type -> {});
        String query = BlockCatalog.PRINT.displayName(); // "Print"

        setSearch(menu, query);

        // Active search collapses to a flat, filtered list — no submenus, and every leaf matches the query.
        assertFalse(menu.getItems().stream().anyMatch(i -> i instanceof Menu),
                "search results are flat (no category submenus)");
        List<String> leaves = leafTexts(menu);
        assertTrue(leaves.contains(BlockCatalog.PRINT.displayName()), "the matching block is listed: " + leaves);
        assertTrue(leaves.stream().allMatch(t -> t.toLowerCase().contains(query.toLowerCase())),
                "every result matches the query: " + leaves);
    }

    @Test
    void searchWithNoMatchShowsDisabledPlaceholder() {
        ContextMenu menu = build(type -> {});

        setSearch(menu, "zzzznotablock");

        List<MenuItem> body = menu.getItems().subList(1, menu.getItems().size());
        assertEquals(2, body.size(), "only the count and the placeholder remain");
        assertEquals("0 results", body.get(0).getText());
        assertTrue(body.get(1).isDisable(), "placeholder is disabled");
        assertEquals("No matching blocks", body.get(1).getText());
    }

    @Test
    void declareFunctionIsNotOfferedInABody() {
        ContextMenu menu = build(type -> {});
        setSearch(menu, "function");
        List<String> leaves = leafTexts(menu);
        assertTrue(leaves.contains(BlockCatalog.FUNCTION_CALL.displayName()), leaves.toString());
        assertFalse(leaves.contains(BlockCatalog.METHOD_DECLARATION.displayName()),
                "a class member has no statement form: " + leaves);
    }

    @Test
    void aSearchResultSaysWhatTheBlockDoes() {
        ContextMenu menu = build(type -> {});
        setSearch(menu, "while");
        CustomMenuItem doWhile = (CustomMenuItem) leafItems(menu).stream()
                .filter(i -> BlockCatalog.DO_WHILE.displayName().equals(i.getText())).findFirst().orElseThrow();
        List<String> lines = new ArrayList<>();
        collectLabels(doWhile.getContent(), lines);
        assertTrue(lines.contains(com.botmaker.studio.palette.PaletteDescriptions.of(BlockCatalog.DO_WHILE)), lines.toString());
    }

    @Test
    void thePickedBlockLeadsTheNextMenuUnderRecent() {
        ContextMenu first = build(type -> {});
        setSearch(first, BlockCatalog.IF.displayName());
        MenuItem ifItem = leafItems(first).stream()
                .filter(i -> BlockCatalog.IF.displayName().equals(i.getText())).findFirst().orElseThrow();
        interact(ifItem::fire);

        ContextMenu next = build(type -> {});
        List<MenuItem> items = next.getItems();
        assertEquals("RECENT", items.get(1).getText());
        assertEquals(BlockCatalog.IF.displayName(), items.get(2).getText());
    }

    private static void collectLabels(javafx.scene.Node node, List<String> out) {
        if (node instanceof javafx.scene.control.Label label) out.add(label.getText());
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) collectLabels(child, out);
        }
    }

    @Test
    void selectingAnItemFiresConsumerWithThatBlockType() {
        AtomicReference<BlockType> selected = new AtomicReference<>();
        ContextMenu menu = build(selected::set);
        setSearch(menu, BlockCatalog.PRINT.displayName());

        MenuItem printItem = leafItems(menu).stream()
                .filter(i -> BlockCatalog.PRINT.displayName().equals(i.getText()))
                .findFirst().orElse(null);
        assertNotNull(printItem, "the Print item should be present after filtering");

        interact(printItem::fire);

        assertEquals(BlockCatalog.PRINT, selected.get(),
                "firing the menu item should hand the exact BlockType to the insert callback");
    }

    // --- helpers ---

    @org.junit.jupiter.api.BeforeEach
    void forgetRecentPicks() {
        StatementMenu.forgetRecent();
    }

    private ContextMenu build(java.util.function.Consumer<BlockType> onSelection) {
        AtomicReference<ContextMenu> ref = new AtomicReference<>();
        // Null analyzer: exercises the language-block path (no project/SDK jar resolved in a headless test).
        interact(() -> ref.set(StatementMenu.create(null, onSelection)));
        return ref.get();
    }

    private void setSearch(ContextMenu menu, String query) {
        TextField search = (TextField) ((CustomMenuItem) menu.getItems().get(0)).getContent();
        interact(() -> search.setText(query)); // fires the textProperty listener -> rebuildStatementItems
    }

    /**
     * Block rows directly under the menu root: not the search box (index 0), a submenu, a separator, or a
     * disabled section title.
     */
    private static List<MenuItem> leafItems(ContextMenu menu) {
        List<MenuItem> out = new ArrayList<>();
        for (MenuItem item : menu.getItems().subList(1, menu.getItems().size())) {
            if (item instanceof SeparatorMenuItem || item instanceof Menu || item.isDisable()) continue;
            out.add(item);
        }
        return out;
    }

    private static List<String> leafTexts(ContextMenu menu) {
        return leafItems(menu).stream().map(MenuItem::getText).toList();
    }
}
