package com.botmaker.studio.blocks;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A type block's header says what the type is and what it extends or implements, as the source does. */
class TypeClausesTest {

    private static AbstractTypeDeclaration first(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        parser.setSource(source.toCharArray());
        return (AbstractTypeDeclaration) ((CompilationUnit) parser.createAST(null)).types().getFirst();
    }

    @Test
    void anActivityEnumSaysItImplementsOutcome() {
        AbstractTypeDeclaration type = first("enum Collect implements Outcome { NEXT }");
        assertEquals("Enum", TypeClauses.kind(type));
        assertEquals(List.of("implements Outcome"), TypeClauses.inheritance(type));
    }

    @Test
    void aClassSaysWhatItExtendsThenImplements() {
        AbstractTypeDeclaration type = first("class Box<T> extends Base implements Runnable, Comparable<Box<T>> {}");
        assertEquals("Class", TypeClauses.kind(type));
        assertEquals("<T>", TypeClauses.typeParameters(type));
        assertEquals(List.of("extends Base", "implements Runnable, Comparable<Box<T>>"), TypeClauses.inheritance(type));
    }

    @Test
    void anInterfaceExtendsItsSuperInterfaces() {
        AbstractTypeDeclaration type = first("interface Step extends Runnable, AutoCloseable {}");
        assertEquals("Interface", TypeClauses.kind(type));
        assertEquals(List.of("extends Runnable, AutoCloseable"), TypeClauses.inheritance(type));
    }

    @Test
    void aRecordSaysWhatItImplements() {
        AbstractTypeDeclaration type = first("record Spot(int x, int y) implements Point {}");
        assertEquals("Record", TypeClauses.kind(type));
        assertEquals("(int x, int y)", TypeClauses.components(type));
        assertEquals(List.of("implements Point"), TypeClauses.inheritance(type));
    }

    @Test
    void aPlainTypeHasNoClause() {
        assertEquals(List.of(), TypeClauses.inheritance(first("enum Colour { RED }")));
        assertEquals("", TypeClauses.typeParameters(first("class Plain {}")));
    }
}
