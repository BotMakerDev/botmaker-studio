package com.botmaker.studio.ui.app.upgrade;

import com.botmaker.studio.services.upgrade.PluginUpgradeService.Break;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.CallSite;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Candidate;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Choice;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Decision;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Deprecation;
import com.botmaker.studio.services.upgrade.PluginUpgradeService;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Highlight;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Site;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One upgrade report, laid out — the body of {@link com.botmaker.studio.ui.app.ProjectUpgradeDialog}.
 *
 * <p>It was {@code SdkUpgradeDialog}'s own {@code render} until 2026-09-15, and it moved out for the reason
 * that dialog's javadoc already gave for sharing a class between its two modes: <b>every sentence here
 * describes what the repair will do</b>, and two copies of that description drift the first time the repair
 * changes. The project upgrade window renders the same records for every plugin, so the second copy would
 * have been a second answer to <i>what happens to my bot</i>. That dialog itself is gone since 2026-09-19;
 * this class is what outlived it, and an upgrade and a removal are the same layout in two modes.
 *
 * <p>It <b>collects one thing</b> rather than only displaying: {@link #picks()} is what the user asked for at
 * each call site — a {@link Decision}, filled in as each card is built. So the map is complete before the
 * user has touched anything, and closing the window without a click produces exactly the upgrade an empty map
 * would.
 *
 * <p>The menu at a site offers what can be written there and nothing else: the candidates that fit,
 * <b>Default it out</b> always, and <b>Discard this call</b> only where the call stands as a statement —
 * deleting an expression would leave a hole where its value sat, which is the one thing no action may
 * produce. There is no <i>leave it alone</i>, because a call whose member is gone does not compile.
 *
 * <p>It owns no buttons, no version control and no status line. Whether the apply button is enabled is the
 * window's question — {@link Report#canMigrate()} and {@link Report#canModernise()} answer it — because the
 * project window asks it across several reports at once and this one only ever sees one.
 */
public final class ReportView {

    /** Which question the report is answering, which is the only thing that changes the layout. */
    public enum Mode {
        /**
         * Moving to another version: what breaks, what is new, what will be repaired. A <b>downgrade</b> is
         * this mode too — it is the same report with the jars the other way round, and what it needs is one
         * sentence saying so rather than a layout of its own. See {@link Report#operation()}.
         */
        UPGRADE,
        /** Moving off what the pinned version already deprecates. Nothing here is broken, so no break list. */
        MODERNISE,
        /**
         * Taking the plugin out. There is no version to move to, so every list that describes one — what is
         * new, what is deprecated there, the release's own changelog — is empty by construction and would
         * render as a wall of "nothing between 1.2.0 and ". What is left is the break list, which is the
         * whole answer.
         */
        REMOVAL
    }

    private final VBox box = new VBox(14);
    private final Map<CallSite, Decision> picks = new LinkedHashMap<>();

    public ReportView() {
        box.setPadding(new Insets(4, 2, 4, 2));
    }

    /** The node to put in a scroll pane. */
    public Node node() {
        return box;
    }

    /** The per-site answers the cards collected. A copy — the caller hands it to the service. */
    public Map<CallSite, Decision> picks() {
        return Map.copyOf(picks);
    }

    /** One line in place of a report: before the first check, and after a failed one. */
    public void placeholder(String text) {
        picks.clear();
        box.getChildren().setAll(new Label(text));
    }

    /** Lays {@code report} out, replacing whatever was shown before. */
    public void render(Report r, Mode mode) {
        box.getChildren().clear();
        picks.clear();

        if (r.isIncomplete()) {
            box.getChildren().add(section("⚠ What this check could not determine", r.problems()));
        }
        // Above every cost section, and above the scaffolding warning, because it is the only thing here that
        // answers "why would I". Everything below it answers "what will this take", which is a question the
        // user is only asking because they have already decided the first one is worth it.
        Node highlights = highlightsSection(r);
        if (highlights != null) box.getChildren().add(highlights);
        // Before anything else, including the modernise layout: this is the one thing that can stop the
        // upgrade for a reason the user cannot act on, and learning it after pressing the button is the
        // failure this section exists to prevent.
        if (!r.scaffolding().isEmpty()) {
            List<String> scaffold = new ArrayList<>();
            for (String element : r.scaffolding()) {
                scaffold.add(element + " — Studio writes this into your generated files, which are rendered "
                        + "from Studio's own templates rather than migrated.");
            }
            box.getChildren().add(section("⚠ What this release moves that Studio writes for you", scaffold));
        }
        if (mode == Mode.MODERNISE) {
            renderModernise(r);
            return;
        }
        if (mode == Mode.REMOVAL) {
            renderRemoval(r);
            return;
        }
        // A downgrade runs the same diff with the jars swapped, and the difference has to be said rather than
        // inferred: @ReplacedBy points forward, so nothing pairs in this direction and every member the older
        // release lacks arrives with no redirect. A report full of defaults and no moves reads as the engine
        // having given up, when it is in fact the correct answer.
        if (r.operation() == PluginUpgradeService.Operation.DOWNGRADE) {
            Label note = new Label("Going back to " + r.to() + ". A plugin's pointers say where something "
                    + "went, never where it came from, so nothing can be redirected in this direction: each "
                    + "call the older release does not have gets a default value or is discarded, and its "
                    + "function is marked for review.");
            note.setWrapText(true);
            note.getStyleClass().add("sdk-upgrade-card");
            box.getChildren().add(note);
        }

        List<String> breaks = new ArrayList<>();
        for (Break b : r.breaks()) {
            breaks.add(b.display() + describe(b));
            for (var site : b.sites()) breaks.add("        " + site);
        }
        box.getChildren().add(section("What breaks in this bot", breaks,
                r.isIncomplete()
                        ? "Nothing in the files that could be read."
                        : "Nothing — every call in this bot still exists on " + r.to() + "."));

        if (!r.unrepairable().isEmpty()) {
            List<String> byHand = new ArrayList<>();
            for (Break b : r.unrepairable()) {
                byHand.add(b.display() + " — no type in " + r.to() + " takes its place, and this bot writes "
                        + "the name itself, so there is nothing to stand in for it.");
                for (var site : b.sites()) byHand.add("        " + site);
            }
            box.getChildren().add(section("What you have to change yourself", byHand));
        }

        List<String> deprecated = new ArrayList<>();
        for (Deprecation d : r.deprecated()) {
            deprecated.add(d.display() + " — deprecated on " + r.to()
                    + (d.isMovable() ? "; use " + d.becomes() : ""));
            for (var site : d.sites()) deprecated.add("        " + site);
        }
        box.getChildren().add(section("What this bot uses that is now deprecated", deprecated,
                "Nothing this bot calls is deprecated on " + r.to() + "."));

        // Grouped by the release each thing arrived in, newest first — a bot several versions behind is
        // reading a span, not a single release, and which one a thing came from is most of what makes the
        // list worth reading. A jar with no @Since has one unlabelled group, which is the old flat list.
        List<String> added = new ArrayList<>();
        r.addedBySince().forEach((era, entries) -> {
            if (!era.isBlank()) added.add("new in " + era);
            for (String entry : entries) added.add(era.isBlank() ? entry : "        " + entry);
        });
        // Kept, and kept exhaustive — but retitled, because it is no longer the only "what's new" here and
        // the two answer different questions: the section above is the release talking, this one is the
        // diff. Under a jar with no changelog it is still the only answer there is.
        box.getChildren().add(section("What's new in the API", added,
                "No new public API between " + r.from() + " and " + r.to() + "."));

        // Last, because it is the only thing here addressed *to* the user.
        for (Choice choice : r.splits()) box.getChildren().add(splitCard(choice));

        if (!r.repairable().isEmpty()) {
            box.getChildren().add(repairCard(r));
        }
    }

    /**
     * One member that became two, and a row per call of it — the only question this view asks.
     *
     * <p>Which candidate a call meant is a property of the call, not of the member, so there is no
     * project-wide answer to offer: {@code scroll(3)} and {@code scroll(-3)} want different ones and a single
     * pick would be wrong in half of them by construction. Hence a row per site, each showing the call
     * <em>as written</em> — the line number alone cannot tell those two apart, which is exactly the
     * distinction being asked about.
     *
     * <p><b>Nothing is required.</b> Every combo arrives on the author's preferred candidate, and a site
     * where none of the candidates fits gets no combo at all: that is today's default value and review mark,
     * and offering an empty menu would imply a choice that does not exist.
     *
     * <p>Modernise does not render these — it takes the preferred candidate everywhere. Moving off a
     * deprecation is not a change the user came here to make, and it is the one path where declining to
     * answer must not cost anything.
     */
    private Node splitCard(Choice choice) {
        Label heading = new Label(choice.display() + " became " + choice.candidates().size()
                + " members — pick one per call");
        heading.setStyle("-fx-font-weight: bold;");

        VBox card = new VBox(6, heading);
        if (!choice.note().isBlank()) {
            // The plugin author's own sentence, verbatim. Nobody here is entitled to paraphrase it.
            Label note = new Label(choice.note());
            note.setWrapText(true);
            card.getChildren().add(note);
        }
        Label why = new Label("Each call is already answered with the first choice. Change one only where "
                + "that is not what the call meant — or say what to do with the call instead.");
        why.setWrapText(true);
        why.getStyleClass().add("sdk-upgrade-empty");
        card.getChildren().add(why);

        for (Site site : choice.sites()) card.getChildren().add(siteRow(site));
        card.getStyleClass().add("sdk-upgrade-card");
        return card;
    }

    /**
     * One call, and the menu of what may be written there.
     *
     * <p>The order is the order of preference and it is deliberate: the candidates that fit, then the
     * engine's own fallback, then deleting the call. A site with no fitting candidate still gets a menu —
     * it is <em>already</em> going to be defaulted, and offering the discard beside that is the whole point
     * of asking per site rather than per member.
     */
    private Node siteRow(Site site) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        Label where = new Label(site.site() + "   " + site.site().text());
        where.getStyleClass().add("sdk-upgrade-detail");
        row.getChildren().add(where);

        List<Decision> values = new ArrayList<>();
        ComboBox<String> combo = new ComboBox<>();
        for (int i = 0; i < site.candidates().size(); i++) {
            Candidate candidate = site.candidates().get(i);
            combo.getItems().add(candidate.display());
            values.add(Decision.redirect(i));
        }
        combo.getItems().add(site.candidates().isEmpty()
                // Said as a fact rather than as an option, because it is what happens either way here.
                ? "Default it out — nothing that fits, so a default value is written and the function is "
                + "marked for review"
                : "Default it out — write a default value and mark the function for review");
        values.add(Decision.DEFAULT);
        if (site.statement()) {
            combo.getItems().add("Discard this call — delete the line");
            values.add(Decision.DISCARD);
        }

        combo.getSelectionModel().select(0);
        picks.put(site.site(), values.getFirst());
        combo.getSelectionModel().selectedIndexProperty().addListener((o, was, now) -> {
            int index = now.intValue();
            if (index >= 0 && index < values.size()) picks.put(site.site(), values.get(index));
        });
        row.getChildren().add(combo);
        return row;
    }

    /**
     * The modernise layout: what will move, and what is deprecated with nowhere named to move it to.
     *
     * <p>Nothing here is a break — every one of these calls compiles today and would go on compiling — so
     * there is no "what breaks" list and no "what's new". The split that matters instead is between what
     * the plugin answered and what it only warned about, because the second list is the one addressed to the
     * user rather than to the button.
     */
    /**
     * What taking the plugin out would do — the break list and nothing else.
     *
     * <p>Two sections, and the split between them is the operation's one rule. Anything Studio can repair is
     * a call, and a call goes: a literal default where its value is used, a deleted line where it is not. A
     * type the bot writes <b>down</b> is not repairable and refuses the removal outright, because a
     * declaration has no value to stand in for — so that list is not a warning, it is the reason the button
     * will not run.
     */
    private void renderRemoval(Report r) {
        List<String> going = new ArrayList<>();
        for (Break b : r.repairable()) {
            going.add(b.display() + " — " + b.repair());
            for (var site : b.sites()) going.add("        " + site);
        }
        box.getChildren().add(section("What Studio will rewrite", going,
                r.isIncomplete()
                        ? "Nothing in the files that could be read."
                        : "Nothing — this bot never calls this plugin, so removing it changes no source."));

        if (!r.unrepairable().isEmpty()) {
            List<String> byHand = new ArrayList<>();
            for (Break b : r.unrepairable()) {
                byHand.add(b.display() + " — this bot writes the type itself, and once the plugin is gone "
                        + "there is no type to write. Change these, then remove it.");
                for (var site : b.sites()) byHand.add("        " + site);
            }
            box.getChildren().add(section("Why this removal is refused", byHand));
        }

        Label note = new Label(!r.unrepairable().isEmpty()
                ? "The plugin stays until every use above is gone from your source. Nothing has been changed."
                : r.isIncomplete()
                ? "Some of this project could not be read, so nothing will be rewritten."
                : "Removing saves a version of your project first, so all of this is one restore "
                + "away. Every call above is replaced by a default value or deleted, the import lines that "
                + "name this plugin go with them, and each function that changed is marked for you to "
                + "review.");
        note.setWrapText(true);
        note.getStyleClass().add("sdk-upgrade-card");
        box.getChildren().add(note);
    }

    private void renderModernise(Report r) {
        List<String> moving = new ArrayList<>();
        for (Deprecation d : r.movable()) {
            moving.add(d.display() + " — deprecated");
            moving.add("        → " + d.repair());
            for (var site : d.sites()) moving.add("        " + site);
        }
        box.getChildren().add(section("What Studio will move for you", moving,
                r.isIncomplete()
                        ? "Nothing in the files that could be read."
                        : "Nothing — this bot calls nothing that " + r.to() + " both deprecates and points "
                        + "somewhere else."));

        List<String> byHand = new ArrayList<>();
        for (Deprecation d : r.deprecated()) {
            if (d.isMovable()) continue;
            byHand.add(d.display() + " — deprecated, with nothing on " + r.to() + " named to take its place, "
                    + "so what it becomes is your call.");
            for (var site : d.sites()) byHand.add("        " + site);
        }
        if (!byHand.isEmpty()) {
            box.getChildren().add(section("What you have to decide yourself", byHand));
        }

        Label note = new Label(r.canModernise()
                ? "\"Snapshot & modernise\" saves a version of your project first, so all of this is "
                + "one restore away. The version this bot pins does not change, and nothing that could not "
                + "be moved cleanly is touched — a deprecated call still compiles, so it is left as it is "
                + "rather than replaced by a default. Any function whose calls did not come through "
                + "unchanged is marked for you to review."
                : r.isIncomplete()
                ? "Some of this project could not be read, so nothing will be rewritten."
                : "There is nothing to do here.");
        note.setWrapText(true);
        note.getStyleClass().add("sdk-upgrade-empty");
        box.getChildren().add(note);
    }

    private static String describe(Break b) {
        return switch (b.kind()) {
            case TYPE_REMOVED -> " — the whole class is gone";
            case TYPE_RENAMED -> " — " + b.detail();
            case MEMBER_REMOVED -> " — removed";
            case FIELD_REMOVED -> " — the constant is gone";
            case SIGNATURE_CHANGED -> " — " + b.detail();
        };
    }

    /**
     * What Studio will write in place of each break, and whether the button below will actually do it.
     *
     * <p>It says the model out loud, because the model is the surprising part: the repair makes the bot
     * <em>compile</em>, not behave the same. Where the plugin says what a member became and the target jar
     * confirms the shapes line up, the call is pointed there; where it does not, the call becomes a default
     * value, or is deleted if it stood on its own. Either way the function around it is marked for the user
     * to go through afterwards. Promising more would be guessing — a redirect nobody checked is a bot that
     * compiles and behaves differently.
     *
     * <p>There is deliberately no second Apply button here. The repair is not a separate operation the user
     * could run on its own — it happens between the snapshot and the pom bump, and running it without either
     * would leave a project rewritten for a version it does not yet pin. One button, one revert away.
     */
    private Node repairCard(Report r) {
        Label heading = new Label("What Studio will change for you");
        heading.setStyle("-fx-font-weight: bold;");

        Label why = new Label("These are repaired so the bot compiles again — a renamed class is renamed "
                + "everywhere, something that moved is pointed at where it went, and anything with nowhere "
                + "to go is replaced by a default value (or deleted, where the call was a line of its own). "
                + "That can leave the bot doing something different, so every function whose calls did not "
                + "come through unchanged is marked for you to review afterwards.");
        why.setWrapText(true);

        List<String> lines = new ArrayList<>();
        for (Break b : r.repairable()) {
            lines.add(b.display() + describe(b));
            lines.add("        → " + b.repair());
        }
        VBox card = new VBox(6, heading, why);
        card.getChildren().add(section("", lines));

        Label note = new Label(r.canMigrate()
                ? "\"Snapshot, repair & switch\" below does all of it: your project is committed to Project "
                + "History first, so the whole upgrade is one revert away."
                : reasonApplyIsOff(r));
        note.setWrapText(true);
        note.getStyleClass().add("sdk-upgrade-empty");
        card.getChildren().add(note);

        card.getStyleClass().add("sdk-upgrade-card");
        return card;
    }

    /**
     * Why the whole span is off, not just the break that caused it. One unrepairable change disables the
     * lot: rewriting some of the call sites and leaving the rest would produce a project in neither shape,
     * with nothing telling the user which half was touched.
     */
    public static String reasonApplyIsOff(Report r) {
        if (r.isIncomplete()) {
            return "Some of this project could not be read, so nothing will be rewritten automatically.";
        }
        return "A class this bot uses is gone with nothing to take its place, so none of these will be "
                + "applied automatically. Change those uses by hand first.";
    }

    /**
     * The target release's own changelog for the span being crossed, or {@code null} when it carries none.
     *
     * <p>{@code null} rather than an empty section, and this is the one place here where absence must not be
     * stated: every SDK up to v1.0.26 ships no changelog, so "this release says nothing about itself" would
     * be the <em>usual</em> message and would read as a defect in the release rather than in the reader. The
     * rest of the layout says what an empty list means because there an empty list is a finding.
     *
     * <p>A bot several versions behind sees each release it is moving through, newest first, so the span is
     * legible as a span rather than flattened into one undifferentiated list.
     */
    private static Node highlightsSection(Report r) {
        if (r.highlights().isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        for (Highlight h : r.highlights()) {
            lines.add(h.version() + (h.date().isBlank() ? "" : "  ·  " + h.date()));
            for (String line : h.lines()) lines.add("        " + line);
        }
        return section("What this release gives you", lines);
    }

    private static Node section(String title, List<String> lines) {
        return section(title, lines, "");
    }

    /** A heading and its lines; {@code emptyText} is shown in place of an empty list (and says why it is ok). */
    private static Node section(String title, List<String> lines, String emptyText) {
        VBox box = new VBox(2);
        if (!title.isBlank()) {
            Label heading = new Label(title);
            heading.setStyle("-fx-font-weight: bold;");
            box.getChildren().add(heading);
        }
        if (lines.isEmpty()) {
            Label empty = new Label(emptyText);
            empty.setWrapText(true);
            empty.getStyleClass().add("sdk-upgrade-empty");
            box.getChildren().add(empty);
        } else {
            for (String line : lines) {
                Label label = new Label(line);
                label.setWrapText(true);
                if (line.startsWith("    ")) label.getStyleClass().add("sdk-upgrade-detail");
                box.getChildren().add(label);
            }
        }
        return box;
    }
}
