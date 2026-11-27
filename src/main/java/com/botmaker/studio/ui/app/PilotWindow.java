// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.ui.app;

import com.botmaker.core.config.AppVersion;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.pilot.PilotQr;
import com.botmaker.core.pilot.PilotServer;
import com.botmaker.core.pilot.PilotState;
import com.botmaker.core.pilot.PilotState.Background;
import com.botmaker.core.pilot.PilotState.Option;
import com.botmaker.core.pilot.PilotState.Phase;
import com.botmaker.core.pilot.transport.FunnelTransport;
import com.botmaker.core.pilot.transport.TransportKind;
import com.botmaker.core.server.CoreClient;
import com.botmaker.core.server.CoreLauncher;
import com.botmaker.core.server.CoreLink;
import com.botmaker.studio.services.CoreEdits;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.event.EventHandler;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Remote Pilot's window (pilot in core, phase 5b, layout A): what the core's pilot is doing, one pairing QR, the
 * phones connected, the bot's Background mode, and the setup tucked into folds. The pilot is the core's, so this holds
 * no server, tunnel or token: it asks over a channel of its own ({@code Request.PilotWatch}) and shows the
 * {@link PilotState} it is told, and every ask goes through one worker thread, never the FX thread.
 *
 * <p>The state holds the pairing link, which is shown here and nowhere else: it is never logged, put in an event or
 * given to an MCP tool.
 */
final class PilotWindow {

    private static final String APP_URL =
            "https://github.com/BotMakerDev/botmaker-pilot/releases/latest/download/botpilot.apk";
    private static final int QR_CELL = 6;
    private static final String GREEN = "-fx-text-fill: #2e9d4f;";
    private static final String AMBER = "-fx-text-fill: #c98a12;";
    private static final String RED = "-fx-text-fill: #d04040;";

    private final String project;
    private final Runnable changed;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "pilot-window");
        thread.setDaemon(true);
        return thread;
    });
    private final Stage stage = new Stage();

    private final Label stateLabel = new Label();
    private final Button power = new Button();
    private final Label problem = new Label();
    private final VBox pairing = new VBox(6);
    private final VBox phones = new VBox(4);
    private final VBox backgroundBox = new VBox(6);
    private final VBox appFold = new VBox(6);
    private final VBox wayFold = new VBox(6);
    private final VBox funnelFold = new VBox(4);
    private final TitledPane funnelPane = new TitledPane("Tailscale Funnel setup", funnelFold);
    private final Label note = new Label();

    private volatile PilotState state;
    private volatile CoreLink link;
    private boolean built;
    private boolean watching;
    private boolean disposed;
    private Window owner;
    private EventHandler<WindowEvent> closeWithOwner;

    /** {@code changed} runs on the FX thread whenever the state differs, for the toolbar's mark. */
    PilotWindow(String project, Runnable changed) {
        this.project = project;
        this.changed = changed;
    }

    /** The toolbar's mark: whether the pilot is reachable now. */
    boolean running() {
        PilotState now = state;
        return now != null && now.phase() == Phase.ON;
    }

    boolean starting() {
        PilotState now = state;
        return now != null && now.phase() == Phase.STARTING;
    }

    /** Shows the window, in front; the first time, opens its channel to the core. */
    void show(Window owner) {
        if (disposed) return;
        if (!built) build(owner);
        stage.show();
        stage.toFront();
        watch();
    }

    /** Starts hearing the pilot, off the FX thread, so the toolbar can show its state before the window opens. */
    void watch() {
        if (disposed || watching) return;
        watching = true;
        worker.execute(() -> {
            try {
                CoreLink tied = CoreLink.connect(CoreEdits.coreDir(), AppVersion.label(), CoreLauncher.process(),
                        event -> { }, call -> { }, new CoreLink.Listener() {
                            @Override
                            public void stopped() {
                                say("BotMaker's core stopped; starting it again…");
                            }

                            @Override
                            public void restarted() {
                                say("");
                            }

                            @Override
                            public void lost(IOException why) {
                                link = null;
                                Platform.runLater(() -> watching = false);
                                say("Lost BotMaker's core: " + why.getMessage() + ". Reopen the window to try again.");
                            }
                        });
                tied.rejoin(client -> hear(client));
                hear(tied.client());
                if (disposed) {
                    tied.close();
                    return;
                }
                link = tied;
            } catch (IOException e) {
                Platform.runLater(() -> watching = false);
                say("Could not reach BotMaker's core: " + e.getMessage());
            }
        });
    }

    private void hear(CoreClient client) throws IOException {
        if (client.watchPilot(this::told) instanceof EditResult.Refused refused) throw new IOException(refused.reason());
    }

    void dispose() {
        disposed = true;
        worker.shutdownNow();
        stage.close();
        if (owner != null) owner.removeEventHandler(WindowEvent.WINDOW_HIDDEN, closeWithOwner);
        owner = null;
        CoreLink tied = link;
        link = null;
        if (tied != null) tied.close();
    }

    // ---- What the core tells ---------------------------------------------------------------------------------

    private void told(PilotState now) {
        state = now;
        Platform.runLater(() -> {
            if (disposed) return;
            if (built) render(now);
            changed.run();
        });
    }

    private void say(String text) {
        Platform.runLater(() -> {
            if (!disposed) note.setText(text);
        });
    }

    // ---- Asking the core -------------------------------------------------------------------------------------

    /** One ask of the core's client. */
    private interface Ask {
        EditResult of(CoreClient client) throws IOException;
    }

    /** Runs {@code ask} on the worker; a refusal or a failure is said in the note, never thrown at the FX thread. */
    private void ask(Ask ask) {
        worker.execute(() -> {
            CoreLink tied = link;
            if (tied == null) {
                say("Not connected to BotMaker's core yet.");
                return;
            }
            try {
                say(ask.of(tied.client()) instanceof EditResult.Refused refused ? refused.reason() : "");
            } catch (IOException e) {
                say(e.getMessage());
            }
        });
    }

    // ---- The window ------------------------------------------------------------------------------------------

    private void build(Window owner) {
        built = true;

        Label title = new Label("Remote Pilot");
        title.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        power.setOnAction(e -> {
            PilotState now = state;
            if (now != null && now.phase() != Phase.OFF) ask(CoreClient::stopPilot);
            else ask(client -> client.startPilot(null));
        });
        HBox header = new HBox(10, title, stateLabel, spacer, power);
        header.setAlignment(Pos.CENTER_LEFT);

        problem.setWrapText(true);
        note.setWrapText(true);

        TitledPane app = new TitledPane("Get the phone app", appFold);
        TitledPane way = new TitledPane("How it connects", wayFold);
        for (TitledPane fold : List.of(app, way, funnelPane)) fold.setExpanded(false);

        Button reset = new Button("Reset the pairing token…");
        reset.setOnAction(e -> confirmReset());
        VBox resetFold = new VBox(6, new Label("A phone paired before must scan the code again."), reset);
        TitledPane token = new TitledPane("Pairing token", resetFold);
        token.setExpanded(false);

        VBox body = new VBox(12, header, problem, pairing, section("Phones", phones), backgroundBox, app, way,
                funnelPane, token, note);
        body.setPadding(new Insets(12));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);

        // Not owned, so it can sit beside Studio on another screen; it goes when the project's window does.
        stage.initModality(Modality.NONE);
        if (owner != null) {
            this.owner = owner;
            closeWithOwner = e -> stage.close();
            owner.addEventHandler(WindowEvent.WINDOW_HIDDEN, closeWithOwner);
            if (!Double.isNaN(owner.getX()) && !Double.isNaN(owner.getY())) {
                stage.setX(owner.getX() + 80);
                stage.setY(owner.getY() + 60);
            }
        }
        stage.setTitle("Remote Pilot — " + project);
        stage.setScene(ThemedWindows.scene(scroll, 520, 720));
        PilotState now = state;
        render(now);
    }

    private static Node section(String heading, Node content) {
        Label label = new Label(heading);
        label.getStyleClass().add("section-title");
        return new VBox(4, label, content);
    }

    private void render(PilotState now) {
        if (now == null) {
            stateLabel.setText("Reaching the core…");
            stateLabel.setStyle(AMBER);
            power.setDisable(true);
            return;
        }
        power.setDisable(false);
        switch (now.phase()) {
            case ON -> {
                stateLabel.setText("● On, over " + now.kind().displayName());
                stateLabel.setStyle(GREEN);
                power.setText("Stop");
            }
            case STARTING -> {
                stateLabel.setText("● Starting…");
                stateLabel.setStyle(AMBER);
                power.setText("Stop");
            }
            case OFF -> {
                stateLabel.setText("○ Off");
                stateLabel.setStyle("");
                power.setText("Start");
            }
        }
        boolean said = now.problem() != null;
        problem.setText(said ? now.problem() + (now.fix() == null ? "" : "\n" + now.fix()) : "");
        problem.setStyle(AMBER);
        problem.setManaged(said);
        problem.setVisible(said);

        renderPairing(now);
        renderPhones(now);
        renderBackground(now.background());
        renderApp();
        renderWays(now);
        renderFunnel(now);
    }

    private void renderPairing(PilotState now) {
        pairing.getChildren().clear();
        boolean on = now.phase() == Phase.ON && now.url() != null;
        pairing.setManaged(on);
        pairing.setVisible(on);
        if (!on) return;
        Label how = new Label("Scan with the BotPilot app, or open the address on the phone.");
        how.setWrapText(true);
        TextField address = new TextField(now.url());
        address.setEditable(false);
        HBox.setHgrow(address, Priority.ALWAYS);
        Button copy = new Button("Copy");
        copy.setOnAction(e -> copy(now.url()));
        Label warn = new Label(now.kind() != null && now.kind().publicInternet()
                ? "This link is reachable from the internet. Anyone who has it can drive this computer."
                : "This link lets a phone drive this computer: share it with nobody.");
        warn.setWrapText(true);
        warn.setStyle(now.kind() != null && now.kind().publicInternet() ? RED : "");
        VBox box = new VBox(6, how, qr(now.qr()), new HBox(6, address, copy), warn);
        box.setAlignment(Pos.CENTER_LEFT);
        pairing.getChildren().add(box);
    }

    private void renderPhones(PilotState now) {
        phones.getChildren().clear();
        if (now.phones().isEmpty()) {
            phones.getChildren().add(new Label(now.phase() == Phase.ON ? "No phone connected yet." : "None."));
            return;
        }
        for (PilotServer.Connected phone : now.phones()) {
            Label what = new Label(phone.address() + " · " + since(phone.sinceMillis())
                    + (phone.interact() ? " · touch controls the PC" : " · view only"));
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            Button off = new Button("Disconnect");
            off.setOnAction(e -> ask(client -> client.disconnectPhone(phone.id())));
            HBox row = new HBox(8, what, gap, off);
            row.setAlignment(Pos.CENTER_LEFT);
            phones.getChildren().add(row);
        }
    }

    private void renderBackground(Background background) {
        backgroundBox.getChildren().clear();
        boolean shown = background.offered() || background.running() || background.message() != null;
        backgroundBox.setManaged(shown);
        backgroundBox.setVisible(shown);
        if (!shown) return;
        Label heading = new Label("Background mode");
        heading.getStyleClass().add("section-title");
        Label line = new Label(background.running()
                ? "Running on display " + background.display()
                        + (background.game() == null ? "" : " · " + background.game())
                        + (background.size() == null ? "" : " · " + background.size())
                : "The game is not in a private display: the pilot drives the real desktop.");
        line.setWrapText(true);
        line.setStyle(background.running() ? GREEN : AMBER);
        Button start = new Button("Start in a private display");
        start.setDisable(background.running() || !background.offered());
        start.setOnAction(e -> ask(CoreClient::startBackground));
        Button stop = new Button("Stop");
        stop.setDisable(!background.running());
        stop.setOnAction(e -> ask(CoreClient::stopBackground));
        backgroundBox.getChildren().addAll(heading, line, new HBox(6, start, stop));
        if (background.message() != null) {
            Label message = new Label(background.message());
            message.setWrapText(true);
            message.setStyle(background.failed() ? RED : "");
            backgroundBox.getChildren().add(message);
        }
    }

    private void renderApp() {
        appFold.getChildren().clear();
        TextField link = new TextField(APP_URL);
        link.setEditable(false);
        appFold.getChildren().addAll(new Label("Scan to download BotPilot for Android."), qr(PilotQr.rows(APP_URL)), link);
    }

    private void renderWays(PilotState now) {
        wayFold.getChildren().clear();
        ToggleGroup group = new ToggleGroup();
        for (Option option : now.options()) {
            if (option.kind() == TransportKind.UNKNOWN) continue;
            RadioButton pick = new RadioButton(option.kind().displayName()
                    + (option.kind().publicInternet() ? " (public internet)" : ""));
            pick.setToggleGroup(group);
            pick.setSelected(option.kind() == now.asked());
            pick.setDisable(!option.available());
            Label why = new Label(option.available() ? "" : option.reason() + (option.fix() == null ? "" : " " + option.fix()));
            why.setWrapText(true);
            why.setStyle(AMBER);
            wayFold.getChildren().add(new VBox(2, pick, why));
        }
        if (now.options().isEmpty()) {
            wayFold.getChildren().add(new Label("The ways to connect are probed when the pilot first starts."));
        }
        Button use = new Button(now.phase() == Phase.OFF ? "Start with this way" : "Switch to this way…");
        use.setDisable(now.options().isEmpty());
        use.setOnAction(e -> {
            int index = group.getToggles().indexOf(group.getSelectedToggle());
            List<Option> pickable = now.options().stream().filter(o -> o.kind() != TransportKind.UNKNOWN).toList();
            if (index < 0 || index >= pickable.size()) return;
            TransportKind kind = pickable.get(index).kind();
            if (!now.phones().isEmpty() && !confirm("Switching restarts the pilot and disconnects "
                    + now.phones().size() + " phone(s), which must scan the new code. Switch?")) return;
            ask(client -> client.startPilot(kind));
        });
        wayFold.getChildren().add(use);
        if (!now.tailnet().isEmpty()) {
            Label heading = new Label("Phones on your Tailscale network");
            heading.getStyleClass().add("section-title");
            wayFold.getChildren().add(heading);
            for (PilotState.TailnetPhone phone : now.tailnet()) {
                wayFold.getChildren().add(new Label((phone.online() ? "● " : "○ ") + phone.name()));
            }
        }
    }

    private void renderFunnel(PilotState now) {
        funnelFold.getChildren().clear();
        FunnelTransport.Diag diag = now.funnel();
        boolean relevant = diag != null;
        funnelPane.setManaged(relevant);
        funnelPane.setVisible(relevant);
        if (!relevant) return;
        funnelFold.getChildren().addAll(
                tick(diag.cliPresent(), "Tailscale is installed"),
                tick(diag.loggedIn(), "Tailscale is signed in"),
                tick(diag.issue() == FunnelTransport.Issue.NONE, "Funnel is allowed for this computer"));
        for (Option option : now.options()) {
            if (option.kind() == TransportKind.FUNNEL && option.fix() != null && !option.available()) {
                Label fix = new Label(option.fix());
                fix.setWrapText(true);
                funnelFold.getChildren().add(fix);
            }
        }
    }

    private static Label tick(boolean ok, String text) {
        Label label = new Label((ok ? "✓ " : "✗ ") + text);
        label.setStyle(ok ? GREEN : RED);
        return label;
    }

    // ---- Small things ----------------------------------------------------------------------------------------

    /** The code, drawn from the core's modules ({@code '1'} dark), black on white whatever the theme. */
    private static Canvas qr(List<String> rows) {
        int size = rows.size();
        Canvas canvas = new Canvas(Math.max(1, size) * QR_CELL, Math.max(1, size) * QR_CELL);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.WHITE);
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.setFill(Color.BLACK);
        for (int y = 0; y < size; y++) {
            String row = rows.get(y);
            for (int x = 0; x < row.length(); x++) {
                if (row.charAt(x) == '1') g.fillRect(x * QR_CELL, y * QR_CELL, QR_CELL, QR_CELL);
            }
        }
        return canvas;
    }

    private static String since(long sinceMillis) {
        long minutes = Math.max(0, (System.currentTimeMillis() - sinceMillis) / 60_000);
        return minutes < 1 ? "just now" : minutes + " min";
    }

    private static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private boolean confirm(String question) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, question, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(stage);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private void confirmReset() {
        if (confirm("Every phone paired so far stops working until it scans the new code. Reset the token?")) {
            ask(CoreClient::resetPilot);
        }
    }
}
