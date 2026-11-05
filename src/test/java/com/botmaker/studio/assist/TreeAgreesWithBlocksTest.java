package com.botmaker.studio.assist;

import com.botmaker.core.assist.AssistTurn;
import com.botmaker.core.assist.BlockView;
import com.botmaker.core.edit.DeclarableTypes;
import com.botmaker.core.edit.EditWorkspace;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.parser.guard.RefusalJournal;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectTemplate;
import com.botmaker.java.edit.BlockId;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.EditorState;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.ui.FxThread;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * read_tree reads the JDT tree through the core's classifier (2026-11-05), no longer the canvas's blocks: here the
 * blocks, walked as read_tree walked them before, give the same methods, bodies, ids and kinds.
 */
class TreeAgreesWithBlocksTest {

    private static final String SOURCE = """
            package com.mybot;
            import java.util.List;
            public class Subject {
                private int total;
                public void run(List<String> names) {
                    // a comment of the body's own
                    int count = 3;
                    int a = 1, b = 2;
                    count++;
                    total = count;
                    System.out.println(count);
                    helper();
                    Math.max(a, b);
                    new StringBuilder();
                    if (count > 1) {
                        helper();
                    } else if (count > 2) {
                        count--;
                    } else {
                        return;
                    }
                    if (count > 5) helper();
                    for (String name : names) {
                        System.out.println(name);
                    }
                    outer:
                    for (int i = 0; i < 3; i++) {
                        while (count < 10) {
                            count++;
                            continue outer;
                        }
                    }
                    do {
                        count--;
                    } while (count > 0);
                    try {
                        helper();
                    } catch (RuntimeException e) {
                        throw e;
                    } finally {
                        total = 0;
                    }
                    switch (count) {
                        case 1:
                            helper();
                            break;
                        default:
                            total = 1;
                    }
                    switch (count) {
                        case 1 -> {
                            helper();
                        }
                        default -> {
                        }
                    }
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        helper();
                    }).join();
                    synchronized (this) {
                        names.forEach(n -> {
                            System.out.println(n);
                        });
                    }
                    class Local {
                        void inside() {
                        }
                    }
                    assert count >= 0 : "negative";
                }
                void helper() {
                }
                static class Nested {
                    int twice(int x) {
                        return x * 2;
                    }
                }
                enum Mode { FAST }
            }
            """;

    @Test
    void theTreeIsTheBlocksTree() {
        EditWorkspace workspace = workspace();
        List<BlockView.Method> tree = new AssistTurn(workspace, SOURCE, DeclarableTypes.NONE).tree();
        assertEquals(fromBlocks(workspace, SOURCE), shape(tree));
        assertEquals(List.of("run", "helper", "twice"), tree.stream().map(BlockView.Method::name).toList());
    }

    /**
     * A body inside a variable's value — a lambda's, an arrow switch's — is listed too, which the block walk left
     * out: the canvas draws it, and an edit takes its id (2026-11-05).
     */
    @Test
    void aBodyInsideAVariablesValueIsListed() {
        String source = """
                package com.mybot;
                public class Subject {
                    public void run(int count) {
                        Runnable later = () -> {
                            helper();
                        };
                        int picked = switch (count) {
                            case 1 -> 4;
                            default -> {
                                yield 5;
                            }
                        };
                    }
                    void helper() {
                    }
                }
                """;
        List<BlockView.Statement> lines = new AssistTurn(workspace(), source, DeclarableTypes.NONE).tree()
                .getFirst().body().statements();
        assertEquals(List.of("types[0]/bodyDeclarations[0]/body/statements[0]/fragments[0]/initializer/body"),
                lines.get(0).bodies().stream().map(BlockView.Body::id).toList());
        assertEquals("Yield", lines.get(1).bodies().getFirst().statements().getFirst().kind());
    }

    private static EditWorkspace workspace() {
        return new EditWorkspace(
                ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects")),
                Paths.get("Subject.java").toAbsolutePath(),
                List.of(System.getProperty("java.class.path").split(File.pathSeparator)),
                Paths.get("src", "main", "java").toAbsolutePath(),
                ProjectTemplate.GAME_BOT, null, null,
                RefusalJournal.in(Path.of(System.getProperty("java.io.tmpdir"), "botmaker-test-refusals")),
                ValueGrammar.empty(), List.of());
    }

    /** What read_tree answered until 2026-11-05: the canvas's body blocks, walked. */
    private static List<String> fromBlocks(EditWorkspace workspace, String source) {
        EditorState state = workspace.fill(new EditorState(), source);
        Map<ASTNode, CodeBlock> registry = new HashMap<>();
        CompilationUnit cu = new BlockConverter(workspace.config(), state).convert(source, registry,
                new BlockDragAndDropManager(new EventBus(false, FxThread.INSTANCE)), false, false).cu();
        List<String> out = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (method.getBody() != null && registry.get(method.getBody()) instanceof BodyBlock body) {
                    String owner = ((AbstractTypeDeclaration) method.getParent()).getName().getIdentifier();
                    out.add(owner + "." + method.getName().getIdentifier());
                    body(body, "  ", out);
                }
                return true;
            }
        });
        return out;
    }

    private static void body(BodyBlock body, String indent, List<String> out) {
        out.add(indent + "{" + BlockId.of(body.getAstNode()));
        for (StatementBlock statement : body.getStatements()) {
            String name = statement.getClass().getSimpleName();
            out.add(indent + " " + name.substring(0, name.length() - "Block".length()) + " "
                    + BlockId.of(statement.getAstNode()));
            bodies(statement, indent + "  ", out);
        }
    }

    private static void bodies(CodeBlock block, String indent, List<String> out) {
        if (!(block instanceof BlockWithChildren parent)) return;
        for (CodeBlock child : parent.getChildren()) {
            if (child instanceof BodyBlock nested) body(nested, indent, out);
            else bodies(child, indent, out);
        }
    }

    private static List<String> shape(List<BlockView.Method> tree) {
        List<String> out = new ArrayList<>();
        for (BlockView.Method method : tree) {
            out.add(method.owner() + "." + method.name());
            body(method.body(), "  ", out);
        }
        return out;
    }

    private static void body(BlockView.Body body, String indent, List<String> out) {
        out.add(indent + "{" + body.id());
        for (BlockView.Statement statement : body.statements()) {
            out.add(indent + " " + statement.kind() + " " + statement.id());
            for (BlockView.Body nested : statement.bodies()) body(nested, indent + "  ", out);
        }
    }
}
