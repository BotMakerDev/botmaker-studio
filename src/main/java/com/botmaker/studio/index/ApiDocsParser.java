package com.botmaker.studio.index;

import com.botmaker.studio.palette.ApiDocs;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds an {@link ApiDocs} by parsing a plugin's {@code .java} sources with Eclipse JDT, pulling each public
 * method's Javadoc summary, real parameter names, and {@code @param} descriptions. Studio compiles against no
 * plugin, so the input is the plugin's resolved {@code sources} jar (see {@code services/ApiDocsService});
 * nothing here loads a plugin's classes.
 */
public final class ApiDocsParser {

    private ApiDocsParser() {}

    /**
     * Parses every {@code .java} entry in a sources jar whose package is one of {@code packages}, or below
     * one — the packages the plugins catalogue, which carry the user-facing Javadoc worth showing. It was the
     * literal {@code com/botmaker/sdk/api/} until 2026-09-28.
     */
    public static ApiDocs fromSourcesJar(Path sourcesJar, Set<String> packages) {
        List<String> prefixes = packages.stream().map(p -> p.replace('.', '/') + "/").toList();
        Map<String, Map<String, List<ApiDocs.Overload>>> docs = new LinkedHashMap<>();
        try (InputStream fis = java.nio.file.Files.newInputStream(sourcesJar);
             ZipInputStream zip = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".java")) {
                    continue;
                }
                if (prefixes.stream().noneMatch(name::startsWith)) {
                    continue;
                }
                String source = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                parseSource(source, docs);
            }
        } catch (IOException e) {
            System.err.println("ApiDocsParser: could not read sources jar " + sourcesJar + ": " + e.getMessage());
            return ApiDocs.EMPTY;
        }
        return new ApiDocs(docs);
    }

    private static void parseSource(String source, Map<String, Map<String, List<ApiDocs.Overload>>> docs) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion());
        parser.setCompilerOptions(options);

        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.isConstructor() || !Modifier.isPublic(node.getModifiers())) {
                    return false;
                }
                String className = enclosingTypeName(node);
                if (className == null) {
                    return false;
                }
                docs.computeIfAbsent(className, k -> new LinkedHashMap<>())
                        .computeIfAbsent(node.getName().getIdentifier(), k -> new ArrayList<>())
                        .add(toOverload(node));
                return false;
            }
        });
    }

    private static String enclosingTypeName(MethodDeclaration node) {
        if (node.getParent() instanceof org.eclipse.jdt.core.dom.AbstractTypeDeclaration type) {
            return type.getName().getIdentifier();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static ApiDocs.Overload toOverload(MethodDeclaration node) {
        Javadoc javadoc = node.getJavadoc();
        String summary = "";
        String deprecated = "";
        Map<String, String> paramDocs = new LinkedHashMap<>();
        if (javadoc != null) {
            for (TagElement tag : (List<TagElement>) javadoc.tags()) {
                if (tag.getTagName() == null) {
                    summary = renderFragments(tag.fragments());
                } else if (TagElement.TAG_DEPRECATED.equals(tag.getTagName())) {
                    // The replacement sentence, not the fact — @Deprecated in bytecode is what marks a method
                    // as going away (see ProjectAnalyzer.isMemberDeprecated). This is only what to do about it, and
                    // the API contract requires it to name the replacement (docs/refactor/21-api-compat.md).
                    deprecated = renderFragments(tag.fragments());
                } else if (TagElement.TAG_PARAM.equals(tag.getTagName())) {
                    List<?> frags = tag.fragments();
                    if (!frags.isEmpty() && frags.get(0) instanceof SimpleName pname) {
                        paramDocs.put(pname.getIdentifier(), renderFragments(frags.subList(1, frags.size())));
                    }
                }
            }
        }

        List<ApiDocs.Param> params = new ArrayList<>();
        for (SingleVariableDeclaration p : (List<SingleVariableDeclaration>) node.parameters()) {
            String pname = p.getName().getIdentifier();
            String type = p.getType().toString() + (p.isVarargs() ? "..." : "");
            params.add(new ApiDocs.Param(pname, type, paramDocs.getOrDefault(pname, "")));
        }
        return new ApiDocs.Overload(summary, params, deprecated);
    }

    /** Flatten Javadoc fragments (text + inline {@code}/{@link} tags) into a single collapsed line. */
    private static String renderFragments(List<?> fragments) {
        StringBuilder sb = new StringBuilder();
        for (Object frag : fragments) {
            if (frag instanceof TextElement text) {
                sb.append(text.getText());
            } else if (frag instanceof TagElement inline) {
                sb.append(' ').append(renderFragments(inline.fragments()));
            } else {
                sb.append(frag);
            }
            sb.append(' ');
        }
        // Drop the handful of HTML formatting tags in the SDK Javadoc (<p>, <em>, <ul>…); deliberately
        // narrow so generic types inside {@code List<ImageTemplate>} are preserved.
        String text = sb.toString().replaceAll("(?i)</?(p|em|b|i|strong|code|pre|ul|ol|li|br)\\b[^>]*>", " ");
        return text.replaceAll("\\s+", " ").trim();
    }
}
