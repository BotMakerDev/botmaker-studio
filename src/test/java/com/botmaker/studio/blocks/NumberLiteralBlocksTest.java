package com.botmaker.studio.blocks;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.blocks.expr.LiteralBlock;
import com.botmaker.studio.blocks.var.DeclareClassVariableBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.parser.BlockConverter;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code botmaker-gamebot}'s {@code Parameters.java} drew as an empty canvas: {@code 60000L} reached
 * {@code Integer.parseInt}, and the throw took the whole file with it.
 */
class NumberLiteralBlocksTest {

    private static final String SOURCE = """
            package test;

            public class Subject {
                public static java.time.Duration rest = java.time.Duration.ofMillis(60000L);
                public static long mask = 0xFFL;
                public static int many = 1_000;
                public static int plain = 20;
            }
            """;

    /** The conversion and every block it registered. */
    private record Drawn(BlockConverter.ConvertResult result, Collection<CodeBlock> blocks) {
        CodeBlock root() {
            return result.root();
        }
    }

    private static Drawn convert(String source) {
        ProjectState state = new ProjectState();
        Path path = Paths.get("Subject.java").toAbsolutePath();
        state.addFile(new ProjectFile(path, source));
        state.setActiveFile(path);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(TestSupport.runtimeClassPath());
        BlockConverter.ConvertResult result = TestSupport.convertAndPublish(new BlockConverter(null, state),
                state, source, new BlockDragAndDropManager(new EventBus(false)), false, false);
        return new Drawn(result, state.getNodeToBlockMap().values());
    }

    @Test
    void aFileWithLongHexAndSeparatedLiteralsDrawsEveryField() {
        Drawn result = convert(SOURCE);

        assertNotNull(result.root(), "the whole canvas was blanked");
        assertTrue(result.result().problems().isEmpty(), result.result().problems().toString());
        List<CodeBlock> fields = ((ClassBlock) result.root()).getChildren().stream()
                .filter(DeclareClassVariableBlock.class::isInstance).toList();
        assertEquals(4, fields.size());
    }

    @Test
    void aLongLiteralIsANumberFieldHoldingALong() {
        LiteralBlock<?> literal = convert(SOURCE).blocks().stream()
                .filter(LiteralBlock.class::isInstance).map(b -> (LiteralBlock<?>) b)
                .filter(b -> b.getAstNode() instanceof NumberLiteral n && n.getToken().equals("60000L"))
                .findFirst().orElseThrow();
        assertInstanceOf(Long.class, literal.getValue());
        assertEquals(60000L, literal.getValue());
    }

    @Test
    void aSpellingANumberFieldCannotWriteBackIsKeptVerbatim() {
        // 0xFFL and 1_000 are shown as written rather than as a decimal field that would rewrite them.
        assertTrue(convert(SOURCE).blocks().stream()
                .noneMatch(b -> b instanceof LiteralBlock<?> && b.getAstNode() instanceof NumberLiteral n
                        && (n.getToken().equals("0xFFL") || n.getToken().equals("1_000"))));
    }
}
