// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.docs;

import java.util.List;

/**
 * <b>The BotMaker workflow, written once.</b>
 *
 * <p>How you get from an empty project to a published bot existed only as tribal knowledge: the Getting
 * Started dialog listed features, and no file in any of the repos told the story end to end. This class is
 * that story, and it is the <em>only</em> copy of it. Two renderers read it —
 * {@link com.botmaker.studio.ui.app.GettingStartedDialog} (a walkthrough with a jump button per step) and
 * {@link WorkflowMarkdown} (the committed {@code WORKFLOW.md}) — so the doc and the dialog cannot drift.
 * {@code WorkflowDocTest} fails the build if the committed file stops matching what this class says.
 *
 * <p>What belongs here: what a step <em>is for</em> and what the person has to decide. What does not: how the
 * pixels get from a capture to a phone, which is {@code docs/display-pipeline.md}'s job and is linked from
 * {@link #furtherReading()} rather than summarised badly here — and how a bot <em>runs</em>, which is a loop
 * rather than a step and so lives in {@link RuntimeDiagram}, rendered by the same two renderers.
 *
 * <p>Rewritten 2026-11-20 against the Studio of that day: no templates, no palette panel, no Resource Manager
 * dialog, the game button and ⌖ for what were the launch and capture target dialogs.
 */
public final class Workflow {

    private Workflow() {
    }

    public static final String TITLE = "The BotMaker workflow";

    public static final String INTRO =
            "A BotMaker bot watches a game or app on your screen, decides what it sees, and clicks. You build "
            + "it out of visual blocks in Studio, and Studio writes the Java for you — a real Maven project you "
            + "could open in any IDE. The steps below are the order things are normally done in. A plain program "
            + "needs only the first and the blocks; a game bot needs the BotMaker SDK plugin and the steps "
            + "between.";

    /** A pointer to a document that answers something this guide deliberately does not. */
    public record Reference(String title, String path, String why) {}

    public static List<WorkflowStep> steps() {
        return List.of(
                WorkflowStep.of("Create a project",
                        "＋ New bot on the home screen: start from Blank, or from a bot someone published.",
                        null,
                        "A project is a normal Maven project under ~/BotMakerProjects/. Blank gives you a pom.xml "
                        + "and a main class that prints a greeting, and names no plugin. Starting from a "
                        + "published bot clones it at the release its listing names, and keeps a link to its "
                        + "author, so their later releases reach you as updates.",
                        "Every file is yours from the moment it lands, to rename, split up or delete: there is no "
                        + "generated half and nothing is locked."),

                WorkflowStep.of("Add the plugins it needs",
                        "A plugin brings blocks, value editors and windows; a game bot needs the BotMaker SDK.",
                        StudioAction.PLUGINS,
                        "Adding a plugin is a dependency in the pom, like any Maven library. The SDK brings the "
                        + "Basics plugin with it, and its own files land under plugins/ in your package, once. "
                        + "The rest of this guide, up to the blocks, is the SDK's."),

                WorkflowStep.of("Pick the game",
                        "Which game this bot plays on this computer: from Steam, Heroic, Faugus, Lutris, "
                        + "Waydroid or the app menu.",
                        StudioAction.LAUNCH_TARGET,
                        "This comes before what the bot reads for a practical reason: you cannot pick the "
                        + "window the bot watches until the game is on screen. The game button starts it "
                        + "without running the bot.",
                        "The bot lists the launchers it supports (launchTargets), preferred first, and that list "
                        + "is published with it. Which one a run starts (launchTarget) is a parameter of "
                        + "whoever runs the bot, so it stays on their computer."),

                WorkflowStep.of("Point the bot at what it reads",
                        "A window, a monitor, the whole desktop, or an Android emulator.",
                        StudioAction.CAPTURE_TARGETS,
                        "Everything visual is relative to this one choice. Picture search, reading text, colour "
                        + "checks and every click are measured inside it, so a bot written against a game "
                        + "window keeps working when that window moves or is resized.",
                        "It is a parameter (captureSource), so it belongs to the computer that runs the bot and "
                        + "is never published: whoever installs your bot picks their own."),

                WorkflowStep.of("Cut pictures",
                        "Little pictures of buttons, icons and text that the bot finds on the screen.",
                        StudioAction.CUT_PICTURE,
                        "An overlay opens over what the bot reads, so you can draw a rectangle or an ellipse, or "
                        + "cut an object out with a transparent background so the match ignores whatever is "
                        + "behind it. Each picture becomes a constant of your Pictures class (Pictures.COLLECT).",
                        "Pictures are filed by tag, not by folder: a picture two screens use carries both tags "
                        + "rather than being copied."),

                WorkflowStep.of("Manage your pictures",
                        "Elements, in the Screens window's rail, is the one list of what the bot finds things by.",
                        StudioAction.ELEMENTS,
                        "Rename, retag, replace, delete, import and export pictures there, and see the tree "
                        + "elements found in a page's or an app's tree."),

                WorkflowStep.of("Map the game's screens",
                        "Each screen the bot can be on, how it knows it is there, and the buttons that lead on.",
                        StudioAction.SCREENS,
                        "A screen is a class of your bot: the pictures that say the game is on it, and its "
                        + "inputs — the buttons that open another screen, and the actions you want the bot to "
                        + "take there (a Do field). The run walks this map to wherever an action is, so no "
                        + "method of yours ever clicks its way from one screen to the next.",
                        "Work a press cannot say — a puzzle, a fight — is a Custom input: a plain method of "
                        + "yours, run when the bot is on that screen. 🔭 Explore, in the same rail, runs the "
                        + "bot through the game to find screens you have not mapped yet."),

                WorkflowStep.of("Say what the bot wants — facts, goals and plans",
                        "A fact is what the bot knows of the game; a goal is a check on facts; a plan is tiers "
                        + "of goals.",
                        StudioAction.GOALS,
                        "A fact is a method of a @Facts group, and a screen's readout says where on the screen "
                        + "it is read and how long a reading holds. Goals.java holds the checks — gold at least "
                        + "100, today's reward claimed — and each action says which goal it serves. Plans.java "
                        + "orders them in tiers: the run works the first tier with a goal still unmet, then the "
                        + "next. \"" + RuntimeDiagram.TITLE + "\" above is what that looks like at run time; "
                        + "read it first, because a bot's actions do not run top to bottom, once each.",
                        "Which plan runs is a parameter, so whoever runs the bot can pick another. These are "
                        + "ordinary Java files, readable without opening the window."),

                WorkflowStep.of("Give the bot its parameters",
                        "The numbers, texts, durations and switches your logic reads — one list for the whole "
                        + "project, in cards by group.",
                        StudioAction.PARAMETERS,
                        "A parameter belongs to the project, not to one method: the delay two methods both "
                        + "wait for is one parameter they both read, rather than a copy each. Its group class "
                        + "says which card it is shown under, and nothing more.",
                        "Each one is a method of a @Param group in the package Studio keeps for it (params), "
                        + "its body the value — so your blocks say Mining.maxAttempts(), the compiler checks "
                        + "the type, and deleting a parameter something still reads is a compile error rather "
                        + "than a surprise at run time. The values are yours and private, never committed or "
                        + "published: the committed starting-params/ holds the values whoever runs the bot "
                        + "starts with.",
                        "Mark a parameter with @Param.Shown to offer it to whoever runs the bot: those appear "
                        + "in the Runner under their group's heading, and the rest stay yours."),

                WorkflowStep.of("Write the logic with blocks",
                        "The centre of the window is your program: loops, conditions, clicks and picture "
                        + "searches as blocks.",
                        null,
                        "Press + between two blocks, or + Add a block, and pick one from the Search blocks… "
                        + "menu, which lists Studio's blocks and those of every plugin the project has. A slot "
                        + "takes a value of its type and refuses one that would not compile.",
                        "Blocks are the Java, not a picture of it: every edit rewrites the real source, and an "
                        + "edit made in another editor comes back as blocks. Nothing is trapped in a format "
                        + "only Studio can read."),

                // "Author over the running game — the Overlay Editor" stood here until 2026-11-13, when the
                // overlay was hidden outside dev mode (spec 2026-11-13-studio-window-cleanup).

                WorkflowStep.of("Run and debug",
                        "▶ Run runs the bot; 🐞 Debug runs it and pauses at its breakpoints.",
                        null,
                        "The Run tab shows what the bot printed and its trace, and the status bar's ⚠ problems "
                        + "what did not compile, with the failing block marked on the canvas. A breakpoint is "
                        + "set in a block's gutter; while paused, the Debug tab shows the call stack and the "
                        + "variables.",
                        "If a bot needs to run without taking over your screen, it can run on a private display "
                        + "— a nested X server it owns — so you keep using your machine while it works."),

                WorkflowStep.of("Read the run's report",
                        "Every run keeps a log: what it did, what it read, and why it stopped.",
                        StudioAction.REPORTS,
                        "The Reports window lists past runs. A report shows what the run did step by step, charts "
                        + "of the values it read, and the problems it met."),

                WorkflowStep.of("Watch and drive it from your phone — Remote Pilot",
                        "Streams what the bot sees to a phone or a browser, and lets you start, stop and touch "
                        + "it from anywhere.",
                        StudioAction.REMOTE_PILOT,
                        "Scan the QR code to pair; no VPN and no port forwarding. Turning on Interact makes the "
                        + "stream two-way, so a tap on your phone lands as a click in the game at the right "
                        + "coordinate whatever the stream is scaled to."),

                WorkflowStep.of("Publish and share",
                        "Release the bot from your own GitHub repository, and list it in the catalog.",
                        StudioAction.PUBLISH,
                        "Publish tags a release, attaches a .bmk — the bot packed to run, with its sources, "
                        + "starting values and the jars it needs — and opens the catalog's pull request. A "
                        + "machine checks the listing and merges it; nobody reviews it by hand.",
                        "Your parameters are never in it: a parameter's value, the game you picked and what "
                        + "the bot reads stay on your computer. Project ▸ Export .bmk… makes the same file "
                        + "without publishing."),

                WorkflowStep.of("Get more bots",
                        "Run someone else's bot, or see how one is put together.",
                        StudioAction.GALLERY,
                        "A bot you get opens in the Runner — someone else's bot is something you run — with its "
                        + "own parameters to set, and a newer release offered when its author makes one. Its "
                        + "code is shown with every edit refused rather than hidden, so you can read how it "
                        + "works. \"Improve this bot\" turns it into an ordinary project of yours, for good."));
    }

    /**
     * Documents this guide points at instead of paraphrasing. Paths are relative to the Studio repo root,
     * where the rendered {@code WORKFLOW.md} lives; the two {@code ../} ones are in the umbrella repo that
     * has this one as a submodule.
     */
    public static List<Reference> furtherReading() {
        return List.of(
                new Reference("docs/display-pipeline.md", "../docs/display-pipeline.md",
                        "capture → encode → transport → render, and the path a touch takes back — for both the "
                        + "pilot's stream and a bot's own read of the same pixels"),
                new Reference("the umbrella CLAUDE.md", "../CLAUDE.md",
                        "how the modules fit together, the JitPack coordinate model, and how a release is cut"),
                new Reference("CHANGELOG.md", "CHANGELOG.md", "what landed in each release, and what is unreleased"));
    }
}
