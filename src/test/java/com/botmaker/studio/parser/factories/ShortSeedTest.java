package com.botmaker.studio.parser.factories;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jface.text.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A seed written with full class names lands short, its class imported, unless that simple name already names
 * another class in the file (2026-10-14: an inserted call read {@code com.botmaker.sdk.api.input.Key.A}).
 */
class ShortSeedTest {

    private record Seeded(String value, String file) {}

    private static Seeded seed(String source, String typeName) throws Exception {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        EditContext ctx = EditContext.of(cu, null, new ProjectState());
        String value = InitializerFactory.createDefaultInitializer(ctx, ResolvedType.named(typeName)).toString();
        Document document = new Document(source);
        ctx.rewriter().rewriteAST(document, null).apply(document);
        return new Seeded(value, document.get());
    }

    @Test
    void aFullNameIsWrittenShortAndImported() throws Exception {
        Seeded seeded = seed("package com.mybot;\n\npublic class Subject {\n}\n", "LocalTime");
        assertEquals("LocalTime.of(12,0)", seeded.value());
        assertTrue(seeded.file().contains("import java.time.LocalTime;"), seeded.file());
    }

    @Test
    void aSimpleNameAnotherClassHoldsKeepsTheFullName() throws Exception {
        Seeded seeded = seed("package com.mybot;\n\nimport com.other.LocalTime;\n\npublic class Subject {\n}\n",
                "LocalTime");
        assertEquals("java.time.LocalTime.of(12,0)", seeded.value());
        assertFalse(seeded.file().contains("import java.time.LocalTime;"), seeded.file());
    }
}
