// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.docs;

/**
 * A place in Studio a {@link WorkflowStep} can point at — the closed set of destinations the workflow guide
 * knows how to send someone to.
 *
 * <p>Each constant carries <b>both</b> renderings of "go here", because the guide has two audiences and they
 * cannot use the same one: the in-app dialog gets a {@link #buttonLabel()} for a real button that runs the
 * matching action, and {@code WORKFLOW.md} gets a {@link #menuPath()} — a reader of the file has no button to
 * press, only a menu to find. Keeping the pair on one constant is what stops the doc from naming a menu entry
 * the dialog no longer opens.
 *
 * <p>Typed rather than a {@code String} key (repo convention): a step that points at a destination Studio
 * cannot open is a compile error, not a dead button. The set is exactly the destinations the guide points at
 * — {@code WorkflowDocTest} fails on a constant no step uses — so a menu entry Studio has but the workflow
 * does not walk you through (Manage Libraries, for one) simply isn't here.
 */
public enum StudioAction {

    PLUGINS("Open Plugins & Libraries ▸", "Project ▸ Plugins & Libraries…"),
    // CAPTURE_TARGETS was "🎯 Capture Targets" until 2026-11-20: the capture source is a parameter since
    // 2026-11-09, which the SDK's ⌖ button sets.
    CAPTURE_TARGETS("Point the Bot ▸", "⌖ Point bot at… on the toolbar (contributed by the BotMaker SDK)"),
    // LAUNCH_TARGET was "🚀 on the toolbar" until 2026-11-20: the SDK's game button replaced it.
    LAUNCH_TARGET("Pick the Game ▸", "the game button at the start of the toolbar, named after the game "
            + "(contributed by the BotMaker SDK)"),
    // CUT_PICTURE and ELEMENTS were CAPTURE_TEMPLATES and RESOURCES (the Resource Manager) until 2026-11-20.
    CUT_PICTURE("Cut a Picture ▸", "✂ Cut picture on the toolbar (contributed by the BotMaker SDK)"),
    ELEMENTS("Open Elements ▸", "🗺 Screens on the toolbar, then 🧩 Elements in its rail (contributed by the "
            + "BotMaker SDK)"),
    SCREENS("Open Screens ▸", "🗺 Screens on the toolbar (contributed by the BotMaker SDK)"),
    GOALS("Open Goals ▸", "🗺 Screens on the toolbar, then 🎯 Goals in its rail (contributed by the "
            + "BotMaker SDK)"),
    PARAMETERS("Open Parameters ▸", "Project ▸ Parameters… (or 🎚 Parameters on the toolbar)"),
    REPORTS("Open Reports ▸", "▤ Reports on the toolbar"),
    // OVERLAY_EDITOR went on 2026-11-13 with its step: the overlay shows only in a dev-mode project.
    REMOTE_PILOT("Enable Remote Pilot ▸", "🎮 Pilot on the toolbar (contributed by the BotMaker SDK)"),
    PUBLISH("Open Publish ▸", "Project ▸ Publish to Gallery…"),
    GALLERY("Get More Bots ▸", "Project ▸ Get More Bots… (the home's Get more bots)");

    private final String buttonLabel;
    private final String menuPath;

    StudioAction(String buttonLabel, String menuPath) {
        this.buttonLabel = buttonLabel;
        this.menuPath = menuPath;
    }

    /** What the in-app walkthrough's jump button says. */
    public String buttonLabel() {
        return buttonLabel;
    }

    /** Where a reader of {@code WORKFLOW.md} finds it by hand — no button available there. */
    public String menuPath() {
        return menuPath;
    }
}
