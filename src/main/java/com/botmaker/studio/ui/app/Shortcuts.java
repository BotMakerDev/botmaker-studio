package com.botmaker.studio.ui.app;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * The Navigate menu's actions and their keys, one table (2026-09-26). The keys are IntelliJ's, so a user who
 * knows one IDE knows this one; the menu reads its items from here, in this order, and nothing else binds
 * these keys.
 */
enum Shortcuts {
    GO_TO_LINE("go-to-line", "Go to Line…",
            new KeyCodeCombination(KeyCode.G, KeyCombination.SHORTCUT_DOWN)),
    GO_TO_FILE("go-to-file", "Go to File…",
            new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)),
    FILE_STRUCTURE("file-structure", "File Structure…",
            new KeyCodeCombination(KeyCode.F12, KeyCombination.SHORTCUT_DOWN)),
    GO_TO_DECLARATION("go-to-declaration", "Go to Declaration",
            new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN)),
    // F1, IntelliJ's macOS key, not its Ctrl+Q: Ctrl+Q is File ▸ Exit here.
    QUICK_DOCUMENTATION("quick-documentation", "Quick Documentation", new KeyCodeCombination(KeyCode.F1));

    private final String id;
    private final String displayName;
    private final KeyCombination accelerator;

    Shortcuts(String id, String displayName, KeyCombination accelerator) {
        this.id = id;
        this.displayName = displayName;
        this.accelerator = accelerator;
    }

    String id() { return id; }

    String displayName() { return displayName; }

    KeyCombination accelerator() { return accelerator; }
}
