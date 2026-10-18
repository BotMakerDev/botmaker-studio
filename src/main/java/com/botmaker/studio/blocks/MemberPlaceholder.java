package com.botmaker.studio.blocks;

import com.botmaker.studio.core.CodeBlock;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Where a member of a type stands until the canvas draws it (2026-10-18): a blank about the member's height, so
 * the scroll range is near the real one, holding what draws the member in its place.
 *
 * <p>Opening a file built every member's node, and each node then paid CSS and layout: a file shaped like
 * Firestone's Screens.java (300 constants) was 16806 nodes, nearly all off screen. A type drawn on demand
 * ({@link TypeBlock#drawMembersOnDemand}) puts one of these where each member goes, and the canvas calls
 * {@link #draw} for those it scrolls near. The height is a guess from the member's source lines until the canvas
 * knows the drawn one ({@link #setEstimate}).
 */
public final class MemberPlaceholder extends Region {

    public static final String STYLE_CLASS = "member-placeholder";
    /** A one-line member drawn, about: a constant's card. Measured by {@code MembersOnDemandTest}, 2026-10-18. */
    static final double FIRST_LINE = 66;
    /** What each further source line adds, about: a 3-line method draws 110. */
    static final double PER_LINE = 22;

    private final CodeBlock member;
    private final Supplier<Node> drawer;

    MemberPlaceholder(CodeBlock member, Supplier<Node> drawer) {
        this.member = member;
        this.drawer = drawer;
        getStyleClass().add(STYLE_CLASS);
        setEstimate(estimate(member.getAstNode()));
    }

    /** The member this blank stands for. */
    public CodeBlock member() {
        return member;
    }

    /** Sets the height this blank stands for: the member's when it was last drawn, if the canvas knows it. */
    public void setEstimate(double height) {
        setMinHeight(height);
        setPrefHeight(height);
        setMaxHeight(height);
    }

    /** What a member is called across a re-parse, as a property on its node once drawn: see {@link #key}. */
    public static final String KEY = "botmaker.member.key";

    /**
     * The member's type, kind and name, which an edit elsewhere does not move: {@code Big field C1}, {@code Big
     * method run(int)}. Not its block id, a path that holds its index and so moves with every member added above it.
     */
    public String key() {
        String in = member.getAstNode().getParent() instanceof AbstractTypeDeclaration type
                ? type.getName().getIdentifier() + " " : "";
        return in + switch (member.getAstNode()) {
            case FieldDeclaration field -> "field " + field.fragments().stream()
                    .map(f -> ((VariableDeclarationFragment) f).getName().getIdentifier())
                    .collect(Collectors.joining(","));
            case MethodDeclaration method -> "method " + method.getName().getIdentifier() + "("
                    + method.parameters().stream().map(p -> ((SingleVariableDeclaration) p).getType().toString())
                    .collect(Collectors.joining(",")) + ")";
            case AbstractTypeDeclaration type -> "type " + type.getName().getIdentifier();
            default -> member.getId();
        };
    }

    /**
     * Draws the member in this blank's place, styled, and returns its node; the blank has left the tree after. Only
     * the new node is styled: {@code applyCss} on the canvas around it restyles every member drawn so far.
     */
    public Node draw() {
        Node node = drawer.get();
        node.getProperties().put(KEY, key());
        if (getParent() instanceof Pane pane) {
            int at = pane.getChildren().indexOf(this);
            if (at >= 0) pane.getChildren().set(at, node);
        }
        node.applyCss();
        return node;
    }

    /** About the height {@code declaration} draws at, from its source lines. */
    public static double estimate(ASTNode declaration) {
        if (declaration == null || !(declaration.getRoot() instanceof CompilationUnit cu)) return FIRST_LINE;
        int first = cu.getLineNumber(declaration.getStartPosition());
        int last = cu.getLineNumber(declaration.getStartPosition() + Math.max(0, declaration.getLength() - 1));
        return first < 0 || last < first ? FIRST_LINE : FIRST_LINE + PER_LINE * (last - first);
    }
}
