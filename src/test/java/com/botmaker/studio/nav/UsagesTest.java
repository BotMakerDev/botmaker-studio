package com.botmaker.studio.nav;

import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Find Usages: every name bound to one declaration, across the bot's files, the declaration flagged. */
class UsagesTest {

    @Test
    void aFunctionIsFoundWhereverItIsCalledAndWhereItIsDeclared(@TempDir Path root) throws Exception {
        Path helperFile = write(root, "com/mybot/Helper.java", """
                package com.mybot;
                public class Helper {
                    public static int twice(int x) { return x * 2; }
                    static int four() { return twice(2); }
                }
                """);
        Path botFile = write(root, "com/mybot/Bot.java", """
                package com.mybot;
                class Bot {
                    void run() {
                        int a = Helper.twice(1);
                        java.util.List<String> names = new java.util.ArrayList<>();
                        names.add("x");
                    }
                    int twice(String s) { return 0; }
                }
                """);
        CompilationUnit helper = parse(root, helperFile);
        MethodDeclaration twice = ((TypeDeclaration) helper.types().getFirst()).getMethods()[0];
        String key = Usages.keyOf(twice.resolveBinding());

        List<Usages.Usage> found = new ArrayList<>();
        found.addAll(Usages.in(helperFile, Files.readString(helperFile), helper, key));
        found.addAll(Usages.in(botFile, Files.readString(botFile), parse(root, botFile), key));

        assertEquals(List.of("twice:3:true", "four:4:false", "run:4:false"), found.stream()
                .map(u -> u.enclosing() + ":" + u.line() + ":" + u.declaration()).toList(),
                "the overload twice(String) is another function and is not listed");
        assertEquals("int a = Helper.twice(1);", found.get(2).text());
    }

    @Test
    void aGenericMembersUsesAreOneDeclaration(@TempDir Path root) throws Exception {
        Path file = write(root, "com/mybot/Bot.java", """
                package com.mybot;
                import java.util.*;
                class Bot {
                    void run() {
                        List<String> a = new ArrayList<>();
                        List<Integer> b = new ArrayList<>();
                        a.add("x");
                        b.add(1);
                    }
                }
                """);
        CompilationUnit cu = parse(root, file);
        org.eclipse.jdt.core.dom.MethodInvocation first = (org.eclipse.jdt.core.dom.MethodInvocation)
                ((org.eclipse.jdt.core.dom.ExpressionStatement) ((TypeDeclaration) cu.types().getFirst())
                        .getMethods()[0].getBody().statements().get(2)).getExpression();
        IBinding add = first.resolveMethodBinding();
        assertEquals(2, Usages.in(file, Files.readString(file), cu, Usages.keyOf(add)).size());
    }

    private static Path write(Path root, String relative, String source) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    private static CompilationUnit parse(Path root, Path file) throws Exception {
        return ProjectAnalyzer.createCompilationUnit(List.of(), Files.readString(file), root, file.toString());
    }
}
