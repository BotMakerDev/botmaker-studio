package com.botmaker.studio.docs;

import java.util.List;

/**
 * <b>How a bot actually runs</b> — the control flow of a run, as a diagram rather than a paragraph.
 *
 * <p>This is the one thing the workflow guide could not explain in prose. Every other step is a thing you
 * <em>do</em>, in an order, and a numbered list is the right shape for that. The runtime is not a list: it is a
 * loop with a side channel — the plan picks the goal that matters most, the run walks to the action that serves
 * it, presses, checks, and picks again — and a sentence saying so is how people come away believing their actions
 * run top to bottom, once each.
 *
 * <p>Like {@link Workflow}, the words live here and only here — {@link WorkflowMarkdown} renders them as a
 * Mermaid graph (which GitHub draws) and
 * {@link com.botmaker.studio.ui.app.GettingStartedDialog} as a column of boxes. Neither renderer holds any
 * text of its own, so the drawn diagram and the committed one cannot disagree.
 *
 * <p><b>The model is deliberately a straight chain plus two annotations</b> (a loop-back edge and one aside),
 * not a general graph. That is genuinely the runtime's shape, and it is what lets a renderer with no layout
 * engine — a {@code VBox} with arrows between the rows — draw it faithfully rather than approximately.
 */
public final class RuntimeDiagram {

    private RuntimeDiagram() {
    }

    public static final String TITLE = "How a bot runs";

    public static final String INTRO =
            "Your bot is not a script that runs top to bottom. Each round, the plan you picked looks at what the "
            + "bot knows of the game, takes the goal that matters most and is not met yet, walks the screen map "
            + "to an action that serves it, and presses it — so the shape of a run is a loop, and it is your "
            + "plan's tiers that decide what comes next. Goals, plans and screens are ordinary Java under "
            + "plugins/: nothing is generated, and you can read what the bot will do without opening a window.";

    /**
     * One box in the chain.
     *
     * @param id     the Mermaid node id — short, stable, and never shown to a reader
     * @param title  what the box says
     * @param detail one sentence under it, in the dialog and in the doc's legend
     * @param shape  how the box is drawn
     */
    public record Node(String id, String title, String detail, Shape shape) {}

    /** A box's outline — the three roles the runtime has: an endpoint, a step, and the branching driver. */
    public enum Shape {
        /** A rounded terminal: where a run begins, and the value that comes back out of an activity. */
        TERMINAL("([", "])"),
        /** An ordinary step that does something. */
        STEP("[", "]"),
        /** The decision — the only node with more than one way out. */
        DECISION("{{", "}}");

        private final String open;
        private final String close;

        Shape(String open, String close) {
            this.open = open;
            this.close = close;
        }

        /** The Mermaid delimiters around the label. */
        String wrap(String label) {
            return open + '"' + label + '"' + close;
        }
    }

    /** The run, top to bottom. The last node loops back to {@link #loopTarget()} — see {@link #LOOP_NOTE}. */
    public static List<Node> chain() {
        return List.of(
                new Node("start", "main() — your bot class",
                        "Calls Bot.run(Sdk.class) — \"Run the bot's plan\" in the insert menu — which "
                        + "installs the map, the plan and the capture source, then supervises the whole run and "
                        + "gets the game back to a known screen through the bot's home method if it crashes or "
                        + "gets stuck.",
                        Shape.TERMINAL),
                new Node("launch", "Launch target",
                        "The game or app you declared is started if it isn't already running. Nothing is "
                        + "captured or clicked until it is up.",
                        Shape.STEP),
                new Node("driver", "The plan — which goal now?",
                        "The first tier with a goal that is not met and not only waiting, and in it the goal "
                        + "with an action ready. This is the only place that decides what runs next.",
                        Shape.DECISION),
                new Node("run", "Walk there and press",
                        "The run walks the screen map to the screen holding the action that serves that goal, "
                        + "then presses it: a button, or a method of yours run on that screen (a Custom input).",
                        Shape.STEP),
                new Node("outcome", "Read and record",
                        "The facts the action changes are read again, and when it went through, the run "
                        + "records it — what a daily or a timer looks at next time.",
                        Shape.TERMINAL));
    }

    /** The id the last node of the {@link #chain()} goes back to, closing the loop. */
    public static String loopTarget() {
        return "driver";
    }

    public static final String LOOP_NOTE =
            "Then the plan picks again, from what the bot knows now. When nothing is due it waits for the "
            + "soonest goal that will be; a run ends when every goal is met for good or nothing can move a "
            + "tier forward, and says why.";

    /** The side channel: the popup guard, which interrupts vision rather than sitting in the chain. */
    public static Node guard() {
        return new Node("guard", "Popup guard",
                "Before every vision step, whatever is being pressed, the popups of the bot's screen map that "
                + "show are closed by their way out, so a daily reward covering the button is dismissed in one "
                + "place instead of by every action that might trip over it. A method of yours can switch it "
                + "off around a step that works through a popup itself.",
                Shape.STEP);
    }

    /** Which node of the chain the guard attaches to, and the label on that edge. */
    public static String guardAttachesTo() {
        return "run";
    }

    public static final String GUARD_EDGE_LABEL = "before every vision step";

    /**
     * The whole diagram as a Mermaid {@code flowchart}, generated from the model above so a node added here
     * appears in the doc without anyone editing Markdown.
     */
    public static String mermaid() {
        List<Node> chain = chain();
        StringBuilder out = new StringBuilder("flowchart TD\n");
        for (Node node : chain) {
            out.append("    ").append(node.id()).append(node.shape().wrap(node.title())).append('\n');
        }
        for (int i = 0; i < chain.size() - 1; i++) {
            out.append("    ").append(chain.get(i).id()).append(" --> ").append(chain.get(i + 1).id()).append('\n');
        }
        out.append("    ").append(chain.getLast().id()).append(" -- \"the next round\" --> ")
                .append(loopTarget()).append('\n');
        Node guard = guard();
        out.append("    ").append(guard.id()).append(guard.shape().wrap(guard.title())).append('\n');
        out.append("    ").append(guard.id()).append(" -. \"").append(GUARD_EDGE_LABEL).append("\" .-> ")
                .append(guardAttachesTo()).append('\n');
        return out.toString();
    }
}
