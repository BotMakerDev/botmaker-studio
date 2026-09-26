package com.botmaker.studio.ui.app;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.nav.SourceNavigation;
import com.botmaker.studio.nav.SourceNavigation.Declaration;
import com.botmaker.studio.nav.SourceNavigation.Entry;
import com.botmaker.studio.palette.SdkDocs;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Stage;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The Navigate menu's popups (2026-09-26): Go to Line, Go to File, File Structure, Go to Declaration and Quick
 * Documentation. Each reads the open file's tree through {@link SourceNavigation} and lands on a block with
 * {@link EditorCanvas#scrollToBlock}, which also selects it — so arriving somewhere looks the same as clicking
 * an error row. "The selected block" is the one the user last clicked ({@link ProjectState#getHighlightedBlock}).
 */
final class NavigationPopups {

    private final Stage owner;
    private final ProjectConfig config;
    private final ProjectState state;
    private final CodeEditorService editor;
    private final EditorCanvas canvas;

    NavigationPopups(Stage owner, ProjectConfig config, ProjectState state, CodeEditorService editor,
                     EditorCanvas canvas) {
        this.owner = owner;
        this.config = config;
        this.state = state;
        this.editor = editor;
        this.canvas = canvas;
    }

    /** Wires every {@link Shortcuts} item on {@code menuBar} to its popup. */
    void wire(MenuBarManager menuBar) {
        menuBar.setOnNavigate(Shortcuts.GO_TO_LINE, this::goToLine);
        menuBar.setOnNavigate(Shortcuts.GO_TO_FILE, this::goToFile);
        menuBar.setOnNavigate(Shortcuts.FILE_STRUCTURE, this::fileStructure);
        menuBar.setOnNavigate(Shortcuts.GO_TO_DECLARATION, this::goToDeclaration);
        menuBar.setOnNavigate(Shortcuts.QUICK_DOCUMENTATION, this::quickDocumentation);
    }

    // --- the five actions --------------------------------------------------------------------------------------

    void goToLine() {
        CompilationUnit cu = state.getCompilationUnit().orElse(null);
        if (cu == null) {
            flash("Open a file first.");
            return;
        }
        int lines = cu.getLineNumber(Math.max(0, cu.getLength() - 1));
        TextField field = new TextField();
        field.setPromptText("1 – " + lines);
        Label problem = new Label();
        problem.getStyleClass().add("nav-popup-note");
        Popup popup = popup("Go to line", field, problem);
        field.setOnAction(e -> {
            int line;
            try {
                line = Integer.parseInt(field.getText().trim());
            } catch (NumberFormatException notANumber) {
                problem.setText("Type a line number.");
                return;
            }
            Optional<CodeBlock> block = SourceNavigation.nodeAtLine(cu, line)
                    .flatMap(n -> SourceNavigation.blockFor(n, state.getNodeToBlockMap()));
            if (block.isEmpty()) {
                problem.setText("No block on line " + line + ".");
                return;
            }
            popup.hide();
            canvas.scrollToBlock(block.get());
        });
        show(popup, field);
    }

    void goToFile() {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(config.sourceRoot())) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException | RuntimeException unreadable) {
            flash("The project's sources could not be read.");
            return;
        }
        Path root = config.sourceRoot();
        choose("Go to file", "File name", files,
                p -> p.getFileName() + "   " + displayDir(root.relativize(p).getParent()),
                p -> p.getFileName().toString(),
                editor::switchToFile);
    }

    void fileStructure() {
        CompilationUnit cu = state.getCompilationUnit().orElse(null);
        List<Entry> entries = SourceNavigation.structure(cu);
        if (entries.isEmpty()) {
            flash("This file declares nothing to list.");
            return;
        }
        choose("File structure", "Type to filter", entries,
                e -> "    ".repeat(e.depth()) + e.kind().glyph() + "  " + e.label(),
                Entry::name,
                e -> SourceNavigation.blockFor(e.node(), state.getNodeToBlockMap()).ifPresent(canvas::scrollToBlock));
    }

    void goToDeclaration() {
        Optional<IBinding> binding = selectedBinding();
        if (binding.isEmpty()) return;
        CompilationUnit cu = state.getCompilationUnit().orElse(null);
        Optional<Declaration> declaration = SourceNavigation.declarationOf(binding.get(), cu, config.sourceRoot());
        switch (declaration.orElse(null)) {
            case Declaration.Here here -> SourceNavigation.blockFor(here.node(), state.getNodeToBlockMap())
                    .ifPresent(canvas::scrollToBlock);
            case Declaration.Elsewhere elsewhere -> {
                editor.switchToFile(elsewhere.file());
                // The other file's blocks exist from the next pulse on, as for a review row.
                Platform.runLater(() -> state.getCompilationUnit()
                        .map(other -> other.findDeclaringNode(elsewhere.key()))
                        .flatMap(node -> SourceNavigation.blockFor(node, state.getNodeToBlockMap()))
                        .ifPresent(canvas::scrollToBlock));
            }
            case null -> flash(binding.get().getName() + " is not declared in this bot — it comes from a library.");
        }
    }

    void quickDocumentation() {
        Optional<IBinding> binding = selectedBinding();
        if (binding.isEmpty()) return;
        String libraryDoc = null;
        if (binding.get() instanceof IMethodBinding m && m.getDeclaringClass() != null) {
            SdkDocs docs = editor.getSdkDocs();
            libraryDoc = docs.overloads(m.getDeclaringClass().getErasure().getName(), m.getName()).stream()
                    .map(SdkDocs.Overload::summary).filter(s -> s != null && !s.isBlank()).findFirst().orElse(null);
        }
        SourceNavigation.Doc doc = SourceNavigation.docOf(binding.get(),
                state.getCompilationUnit().orElse(null), libraryDoc);
        Label heading = new Label(doc.heading());
        heading.getStyleClass().add("nav-doc-heading");
        heading.setWrapText(true);
        Label body = new Label(doc.body().isBlank() ? "No documentation." : doc.body());
        body.setWrapText(true);
        body.getStyleClass().add("nav-doc-body");
        VBox box = new VBox(6, heading, body);
        box.setMaxWidth(520);
        Popup popup = popup(null, box);
        Node anchor = state.getHighlightedBlock().map(CodeBlock::getUINode).orElse(null);
        if (anchor != null && anchor.getScene() != null) {
            Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
            if (b != null) {
                popup.show(owner, b.getMinX(), b.getMaxY() + 4);
                return;
            }
        }
        show(popup, box);
    }

    /** What the selected block names, or empty after saying why there is nothing to act on. */
    private Optional<IBinding> selectedBinding() {
        Optional<CodeBlock> selected = state.getHighlightedBlock();
        if (selected.isEmpty()) {
            flash("Click a block first.");
            return Optional.empty();
        }
        ASTNode node = selected.get().getAstNode();
        Optional<IBinding> binding = SourceNavigation.bindingOf(node);
        if (binding.isEmpty()) flash("This block does not name a variable, a function or a type.");
        return binding;
    }

    // --- popups ------------------------------------------------------------------------------------------------

    /**
     * A filtered list: typing narrows it with {@link SourceNavigation#match}, the arrows move in it while the
     * field keeps focus, Enter or a click picks, Escape closes.
     */
    private <T> void choose(String title, String prompt, List<T> items, Function<T, String> label,
                            Function<T, String> key, Consumer<T> onPick) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        ListView<T> list = new ListView<>();
        list.setPrefSize(460, 320);
        list.getStyleClass().add("nav-popup-list");
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : label.apply(item));
            }
        });
        list.getItems().setAll(items);
        list.getSelectionModel().selectFirst();
        Popup popup = popup(title, field, list);
        Runnable pick = () -> {
            T chosen = list.getSelectionModel().getSelectedItem();
            if (chosen == null) return;
            popup.hide();
            onPick.accept(chosen);
        };
        field.textProperty().addListener((o, was, now) -> {
            list.getItems().setAll(now == null || now.isBlank() ? items : items.stream()
                    .filter(i -> SourceNavigation.match(now, key.apply(i)) >= 0)
                    .sorted(Comparator.comparingInt((T i) -> -SourceNavigation.match(now, key.apply(i))))
                    .toList());
            list.getSelectionModel().selectFirst();
        });
        field.setOnKeyPressed(e -> {
            int at = list.getSelectionModel().getSelectedIndex();
            if (e.getCode() == KeyCode.DOWN) {
                list.getSelectionModel().select(Math.min(at + 1, list.getItems().size() - 1));
                list.scrollTo(list.getSelectionModel().getSelectedIndex());
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                list.getSelectionModel().select(Math.max(at - 1, 0));
                list.scrollTo(list.getSelectionModel().getSelectedIndex());
                e.consume();
            }
        });
        field.setOnAction(e -> pick.run());
        list.setOnMouseClicked(e -> pick.run());
        show(popup, field);
    }

    /** A themed popup holding {@code content} under an optional title; closes on Escape and on a click away. */
    private Popup popup(String title, Node... content) {
        VBox box = new VBox(6);
        box.getStyleClass().add("nav-popup");
        if (title != null) {
            Label heading = new Label(title);
            heading.getStyleClass().add("nav-popup-title");
            box.getChildren().add(heading);
        }
        box.getChildren().addAll(content);
        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.getContent().add(box);
        ThemedWindows.addStylesheet(popup.getScene());
        ThemedWindows.applyThemeClass(box);
        return popup;
    }

    /** Shows {@code popup} centred near the top of the window, like IntelliJ's, focusing {@code focus}. */
    private void show(Popup popup, Node focus) {
        popup.show(owner);
        double x = owner.getX() + (owner.getWidth() - popup.getWidth()) / 2;
        double y = owner.getY() + owner.getHeight() * 0.18;
        popup.setX(x);
        popup.setY(y);
        focus.requestFocus();
    }

    /** One short sentence, gone after a moment or a click. */
    private void flash(String message) {
        Label label = new Label(message);
        Popup popup = popup(null, label);
        show(popup, label);
        javafx.animation.PauseTransition hide = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2.5));
        hide.setOnFinished(e -> popup.hide());
        hide.play();
    }

    private static String displayDir(Path dir) {
        return dir == null ? "" : dir.toString().replace('\\', '/');
    }
}
