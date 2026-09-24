package com.botmaker.studio.parser;

import com.botmaker.studio.blocks.ClassBlock;
import com.botmaker.studio.blocks.expr.*;
import com.botmaker.studio.blocks.flow.*;
import com.botmaker.studio.blocks.func.ConstructorBlock;
import com.botmaker.studio.blocks.func.LibraryCallBlock;
import com.botmaker.studio.blocks.func.MainBlock;
import com.botmaker.studio.blocks.func.MethodDeclarationBlock;
import com.botmaker.studio.blocks.func.MethodInvocationBlock;
import com.botmaker.studio.blocks.func.ConstructorCallBlock;
import com.botmaker.studio.blocks.loop.ClassicForBlock;
import com.botmaker.studio.blocks.loop.DoWhileBlock;
import com.botmaker.studio.blocks.loop.ForBlock;
import com.botmaker.studio.blocks.loop.WhileBlock;
import com.botmaker.studio.blocks.misc.CommentBlock;
import com.botmaker.studio.blocks.misc.ExpressionStatementBlock;
import com.botmaker.studio.blocks.misc.InitializerBlock;
import com.botmaker.studio.blocks.misc.PrintBlock;
import com.botmaker.studio.blocks.misc.SourceStatementBlock;
import com.botmaker.studio.blocks.var.AssignmentBlock;
import com.botmaker.studio.blocks.var.DeclareClassVariableBlock;
import com.botmaker.studio.blocks.var.DeclareEnumBlock;
import com.botmaker.studio.blocks.var.VariableDeclarationBlock;
import com.botmaker.studio.core.*;
import com.botmaker.studio.palette.InputKind;
import com.botmaker.studio.parser.handlers.BranchChainHandler;
import com.botmaker.studio.parser.handlers.LambdaCallHandler;
import com.botmaker.studio.parser.helpers.FileTypeDetector;
import com.botmaker.studio.parser.helpers.NumberLiterals;
import com.botmaker.studio.project.LockResolver;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.types.JdkType;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import org.eclipse.jdt.core.dom.*;

import java.nio.file.Path;
import java.util.*;

import static com.botmaker.studio.suggestions.ProjectAnalyzer.createCompilationUnit;

/**
 * Converts Java source into a tree of {@link CodeBlock}s. Stateless: all per-parse state is
 * carried in an immutable {@link ParseContext} threaded through the recursion, so a single
 * instance is safe to reuse across files and edits.
 */
public class BlockConverter {

    private final ProjectState state;
    private final ProjectConfig config;

    public BlockConverter(ProjectConfig config, ProjectState state) {
        this.config = config;
        this.state = state;
    }

    /** Result of a {@link #convert} call: the root block plus the binding-resolved CU it was built from. */
    /**
     * @param problems one sentence per member that could not be drawn, or why the whole file could not be —
     *                 the caller shows them, because an empty or partial canvas with no sentence is
     *                 indistinguishable from a file that is empty
     */
    public record ConvertResult(AbstractCodeBlock root, CompilationUnit cu, List<String> problems) {

        public ConvertResult(AbstractCodeBlock root, CompilationUnit cu) {
            this(root, cu, List.of());
        }
    }

    // =========================================================================
    // ENTRY POINT
    // =========================================================================

    public ConvertResult convert(String javaCode,
                                 Map<ASTNode, CodeBlock> nodeToBlockMap,
                                 BlockDragAndDropManager manager,
                                 boolean isReadOnly,
                                 boolean markNewIdentifiersAsUnedited) {
        return convert(null, javaCode, nodeToBlockMap, manager, isReadOnly, markNewIdentifiersAsUnedited);
    }

    /**
     * The same conversion, over a {@code CompilationUnit} the caller has already parsed.
     *
     * <p>{@code CodeEditorService} needs the parsed file in {@code ProjectState} <em>before</em> the blocks are
     * built — every screen that reacts to a write by re-reading the file depends on it — but it must not pay for
     * a second parse to get there. Passing {@code null} parses here, exactly as this always did.
     */
    public ConvertResult convert(CompilationUnit parsed,
                                 String javaCode,
                                 Map<ASTNode, CodeBlock> nodeToBlockMap,
                                 BlockDragAndDropManager manager,
                                 boolean isReadOnly,
                                 boolean markNewIdentifiersAsUnedited) {
        return convert(parsed, javaCode, nodeToBlockMap, manager, isReadOnly, markNewIdentifiersAsUnedited,
                BlockReuse.NONE);
    }

    /**
     * The same conversion, offering the blocks of a previous parse back to this one.
     *
     * <p>An overload rather than a widened signature because {@link BlockReuse#NONE} has to stay the thing a
     * caller gets without asking: reuse changes which objects are on screen after an edit, and a caller that
     * has not thought about that must keep the behaviour it has. See
     * {@code docs/refactor/30-block-reuse.md} §3.
     */
    public ConvertResult convert(CompilationUnit parsed,
                                 String javaCode,
                                 Map<ASTNode, CodeBlock> nodeToBlockMap,
                                 BlockDragAndDropManager manager,
                                 boolean isReadOnly,
                                 boolean markNewIdentifiersAsUnedited,
                                 BlockReuse reuse) {
        try {
            CompilationUnit ast = parsed != null ? parsed : parse(javaCode);

            List<Comment> comments = new ArrayList<>();
            for (Object obj : ast.getCommentList()) {
                if (obj instanceof Comment c && !(obj instanceof Javadoc)) comments.add(c);
            }

            ParseContext ctx = new ParseContext(
                    ast, javaCode, comments, nodeToBlockMap, manager, isReadOnly,
                    LockResolver.forActiveFile(config, state),
                    markNewIdentifiersAsUnedited, reuse);

            if (ast.types().isEmpty()) return new ConvertResult(null, ast);

            AbstractTypeDeclaration rootNode = (AbstractTypeDeclaration) ast.types().getFirst();
            List<String> problems = new ArrayList<>();
            AbstractCodeBlock root = parseRoot(rootNode, ctx, problems);
            return new ConvertResult(root, ast, List.copyOf(problems));

        } catch (Exception e) {
            System.err.println("Critical error in BlockConverter.convert: " + e.getMessage());
            e.printStackTrace();
            return new ConvertResult(null, null, List.of("This file could not be drawn ("
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")."));
        }
    }

    /**
     * The file as the editor parses it: bindings resolved against the project's classpath, named after the file
     * on disk so the resolver can find its siblings.
     *
     * <p>Public because the parse and the block build are now two steps that can happen a moment apart — see the
     * {@code parsed} overload above.
     */
    public CompilationUnit parse(String javaCode) {
        String unitName = state.getActiveFile() != null
                ? state.getActiveFile().getPath().toAbsolutePath().toString() : null;
        return createCompilationUnit(state.getResolvedClasspath(), javaCode, state.getSourcePath(), unitName);
    }

    private AbstractCodeBlock parseRoot(AbstractTypeDeclaration rootNode, ParseContext rootCtx,
                                        List<String> problems) {
        // --- CASE A: Standard Class File ---
        if (rootNode instanceof TypeDeclaration typeDecl) {
            // A class another window owns whole (only a plugin's managed constants in it) draws every member
            // read-only, the same way a file open for reading does — LockResolver says which.
            ParseContext ctx = signatureEditable(typeDecl, rootCtx) ? rootCtx : rootCtx.withReadOnly(true);
            ClassBlock classBlock = new ClassBlock(
                    BlockId.of(typeDecl), typeDecl, ctx.manager());
            applyReadOnly(classBlock, ctx);
            ctx.nodeToBlockMap().put(typeDecl, classBlock);

            for (Object obj : typeDecl.bodyDeclarations()) {
                // One member that cannot be drawn costs that member, not the file. Until 2026-09-19 the throw
                // reached convert()'s outer catch, which answered "no root" — so one `60000L` drew the whole
                // file as an empty canvas and said nothing. The member is left out and named instead.
                try {
                    parseMember(obj, classBlock, ctx);
                } catch (RuntimeException e) {
                    problems.add(memberName(obj) + " could not be drawn ("
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")");
                }
            }
            return classBlock;
        }
        // --- CASE B: Standalone Enum File ---
        else if (rootNode instanceof EnumDeclaration enumDecl) {
            DeclareEnumBlock rootEnumBlock = new DeclareEnumBlock(
                    BlockId.of(enumDecl), enumDecl);
            applyReadOnly(rootEnumBlock, rootCtx);
            rootCtx.nodeToBlockMap().put(enumDecl, rootEnumBlock);
            return rootEnumBlock;
        }
        return null;
    }

    /** What a member is called in a status line — {@code Parameters.restBetween}, {@code run()}. */
    private static String memberName(Object member) {
        return switch (member) {
            case MethodDeclaration method -> method.getName().getIdentifier() + "()";
            case FieldDeclaration field when !field.fragments().isEmpty() ->
                    ((VariableDeclarationFragment) field.fragments().getFirst()).getName().getIdentifier();
            case EnumDeclaration enumDecl -> enumDecl.getName().getIdentifier();
            case Initializer ignored -> "an initializer block";
            default -> "a member";
        };
    }

    /** One member of a class, added to {@code classBlock}. */
    private void parseMember(Object obj, ClassBlock classBlock, ParseContext ctx) {
        // Every member of every file is the user's since 2026-08-29, so none is filtered out of the
        // tree. What used to be dropped here — an activity's Outcome enum, its INSTANCE static, its
        // isEnabled() wiring — was dropped because BotMaker wrote it and rewrote it; nothing does.
        if (obj instanceof MethodDeclaration method) {
            MethodDeclarationBlock methodBlock;
            if (method.isConstructor()) {
                methodBlock = new ConstructorBlock(
                        BlockId.of(method), method, ctx.manager());
            } else if (FileTypeDetector.isMainMethod(method)) {
                methodBlock = new MainBlock(
                        BlockId.of(method), method, ctx.manager());
            } else {
                methodBlock = new MethodDeclarationBlock(
                        BlockId.of(method), method, ctx.manager());
            }
            // LockResolver is the one authority on whether an edit is allowed — bundled library
            // source, or a bot open for reading. Don't re-derive either from a path here.
            methodBlock.setReadOnly(!signatureEditable(method, ctx));
            ctx.nodeToBlockMap().put(method, methodBlock);

            if (method.getBody() != null) {
                methodBlock.setBody(parseBodyBlock(method.getBody(),
                        ctx.withReadOnly(!bodyEditable(method, ctx))));
            }
            classBlock.addBodyDeclaration(methodBlock);
        } else if (obj instanceof Initializer initializer) {
            // static { … } / { … }. Modelled by JDT as neither a method nor a field, so without this
            // branch the whole construct vanished from the tree — see blocks/misc/InitializerBlock.
            InitializerBlock initBlock = new InitializerBlock(BlockId.of(initializer), initializer);
            applyReadOnly(initBlock, ctx);
            ctx.nodeToBlockMap().put(initializer, initBlock);

            if (initializer.getBody() != null) {
                initBlock.setBody(parseBodyBlock(initializer.getBody(), ctx));
            }
            classBlock.addBodyDeclaration(initBlock);
        } else if (obj instanceof EnumDeclaration enumDecl) {
            DeclareEnumBlock enumBlock = new DeclareEnumBlock(
                    BlockId.of(enumDecl), enumDecl);
            applyReadOnly(enumBlock, ctx);
            // An activity's Outcome enum is generated from the flow dialog inside a file the user
            // otherwise owns, so the file's own verdict is not the answer here.
            if (!signatureEditable(enumDecl, ctx)) enumBlock.setReadOnly(true);
            ctx.nodeToBlockMap().put(enumDecl, enumBlock);
            classBlock.addBodyDeclaration(enumBlock);
        } else if (obj instanceof FieldDeclaration field) {
            // A @Param field, or a constant a plugin manages, is drawn read-only — its value included, which
            // is why the initializer is parsed under the field's verdict and not the file's.
            ParseContext fieldCtx = signatureEditable(field, ctx) ? ctx : ctx.withReadOnly(true);
            DeclareClassVariableBlock fieldBlock = new DeclareClassVariableBlock(
                    BlockId.of(field), field);
            applyReadOnly(fieldBlock, fieldCtx);
            ctx.nodeToBlockMap().put(field, fieldBlock);

            VariableDeclarationFragment fragment = (VariableDeclarationFragment) field.fragments().getFirst();
            if (fragment.getInitializer() != null) {
                parseExpression(fragment.getInitializer(), fieldCtx).ifPresent(fieldBlock::setInitializer);
            }
            classBlock.addBodyDeclaration(fieldBlock);
        }
    }

    private void applyReadOnly(CodeBlock block, ParseContext ctx) {
        if (ctx.readOnly()) block.setReadOnly(true);
    }

    /** True when {@code node}'s name/params/return type — or class-level structure — may be changed. */
    private boolean signatureEditable(ASTNode node, ParseContext ctx) {
        return ctx.resolver() == null ? !ctx.readOnly() : ctx.resolver().signatureEditable(node);
    }

    /** True when statements inside {@code method} may be changed. */
    private boolean bodyEditable(MethodDeclaration method, ParseContext ctx) {
        return ctx.resolver() == null ? !ctx.readOnly() : ctx.resolver().bodyEditable(method);
    }

    // =========================================================================
    // BODY
    // =========================================================================

    public BodyBlock parseBodyBlock(Block astBlock, ParseContext ctx) {
        if (ctx.reuse().take(astBlock, ctx) instanceof BodyBlock kept) return kept;

        BodyBlock bodyBlock = new BodyBlock(BlockId.of(astBlock), astBlock, ctx.manager());
        applyReadOnly(bodyBlock, ctx);
        ctx.nodeToBlockMap().put(astBlock, bodyBlock);

        List<CodeBlock> allChildren = new ArrayList<>();
        for (Object statementObj : astBlock.statements()) {
            parseStatement((Statement) statementObj, ctx).ifPresent(allChildren::add);
        }

        int blockStart = astBlock.getStartPosition() + 1;
        int blockEnd = astBlock.getStartPosition() + astBlock.getLength() - 1;

        for (Comment comment : ctx.comments()) {
            int cPos = comment.getStartPosition();
            if (cPos > blockStart && cPos < blockEnd) {
                boolean isInsideChild = false;
                for (Object stmtObj : astBlock.statements()) {
                    Statement s = (Statement) stmtObj;
                    if (cPos >= s.getStartPosition() && cPos <= s.getStartPosition() + s.getLength()) {
                        isInsideChild = true;
                        break;
                    }
                }
                if (!isInsideChild) {
                    allChildren.add(parseCommentBlock(comment, ctx));
                }
            }
        }

        allChildren.sort(Comparator.comparingInt(b -> b.getAstNode().getStartPosition()));
        for (CodeBlock cb : allChildren) {
            if (cb instanceof StatementBlock) bodyBlock.addStatement((StatementBlock) cb);
        }
        return bodyBlock;
    }

    private CommentBlock parseCommentBlock(Comment astNode, ParseContext ctx) {
        String text = "Comment";
        if (ctx.sourceCode() != null) {
            try {
                String raw = ctx.sourceCode().substring(astNode.getStartPosition(), astNode.getStartPosition() + astNode.getLength());
                text = astNode.isLineComment() ? raw.substring(2).trim() : raw.substring(2, raw.length() - 2).trim();
            } catch (Exception ignored) {}
        }
        CommentBlock commentBlock = new CommentBlock(BlockId.of(astNode), astNode, text);
        applyReadOnly(commentBlock, ctx);
        ctx.nodeToBlockMap().put(astNode, commentBlock);
        return commentBlock;
    }

    // =========================================================================
    // STATEMENTS
    // =========================================================================

    public Optional<StatementBlock> parseStatement(Statement stmt, ParseContext ctx) {
        // Reuse first: a block whose source text is unchanged at an unchanged structural path keeps its own
        // JavaFX node, and is handed back here so the parent assigning it into its own field never learns the
        // difference. BlockReuse.NONE -- the default -- always answers null, and null matches no pattern.
        if (ctx.reuse().take(stmt, ctx) instanceof StatementBlock kept) return Optional.of(kept);

        Optional<StatementBlock> result = dispatchStatement(stmt, ctx);
        result.ifPresent(b -> applyReadOnly(b, ctx));
        return result;
    }

    /**
     * The block for {@code stmt} — never empty since 2026-09-24. Every arm below that cannot draw its statement
     * faithfully, and every statement kind no arm names, ends in {@link #sourceStatement}: the statement drawn
     * as the Java it is, and editable as text. The empty this method used to return was dropped by every
     * caller, which took the statement off the canvas while it stayed in the file (see
     * {@code StatementRoundTripTest}).
     */
    private Optional<StatementBlock> dispatchStatement(Statement stmt, ParseContext ctx) {
        Optional<StatementBlock> block = Optional.empty();
        try {
            block = dispatchModelled(stmt, ctx);
        } catch (Exception e) {
            System.err.println("Error parsing statement: " + stmt);
            e.printStackTrace();
        }
        return block.isPresent() ? block
                : Optional.of(sourceStatement(stmt, ctx, "This statement has no block of its own yet."));
    }

    private Optional<StatementBlock> dispatchModelled(Statement stmt, ParseContext ctx) {
        if (stmt instanceof Block b) return Optional.of(parseBodyBlock(b, ctx));
        if (stmt instanceof TypeDeclarationStatement t) return parseTypeDeclaration(t, ctx);
        if (stmt instanceof VariableDeclarationStatement v) return parseVariableDecl(v, ctx);
        // A body that is one bare statement is braced when the file opens (SwitchNormalizer); a file that is
        // not normalised — locked, read-only — still arrives with one, and drawing the block without its body
        // would hide that statement. It is drawn as written instead.
        if (hasBareBody(stmt)) {
            return Optional.of(sourceStatement(stmt, ctx,
                    "Its body is a single statement without braces, and this file is not rewritten to add them."));
        }
        if (stmt instanceof IfStatement i) return parseIf(i, ctx);
        if (stmt instanceof WhileStatement w) return parseWhile(w, ctx);
        if (stmt instanceof EnhancedForStatement f) return parseFor(f, ctx);
        if (stmt instanceof ForStatement f) return parseClassicFor(f, ctx);
        if (stmt instanceof DoStatement d) return parseDoWhile(d, ctx);
        // A try arm stood here until 2026-09-13 that matched one shape — `try { Thread.sleep(n) } catch …` —
        // and drew it as a bespoke WaitBlock. Waiting is a plugin's verb (`Wait.time(Duration)`), so that went;
        // this arm draws every try as the try it is, the raw sleep included.
        if (stmt instanceof TryStatement t) return parseTry(t, ctx);
        if (stmt instanceof ThrowStatement t) return parseThrow(t, ctx);
        if (stmt instanceof SynchronizedStatement s) return parseSynchronized(s, ctx);
        if (stmt instanceof AssertStatement a) return parseAssert(a, ctx);
        if (stmt instanceof LabeledStatement l) return parseLabeled(l, ctx);
        if (stmt instanceof YieldStatement y) return parseYield(y, ctx);
        if (stmt instanceof ConstructorInvocation c) return parseConstructorCall(c, c.arguments(), ctx);
        if (stmt instanceof SuperConstructorInvocation c) return parseConstructorCall(c, c.arguments(), ctx);
        // A guarded arrow switch had its own arm here until 2026-09-01, ahead of this one. Nothing composes one
        // now — branching on what was found is a chain of calls (BranchChainHandler) — so a switch of any form
        // is an ordinary switch again, which is what this arm always handled.
        if (stmt instanceof SwitchStatement s) return parseSwitch(s, ctx);
        if (stmt instanceof BreakStatement b) return Optional.of(new BreakBlock(BlockId.of(b), b));
        if (stmt instanceof ContinueStatement c) return Optional.of(new ContinueBlock(BlockId.of(c), c));
        if (stmt instanceof ReturnStatement r) return parseReturn(r, ctx);
        if (stmt instanceof ExpressionStatement e) return parseExprStmt(e, ctx);
        return Optional.empty();
    }

    /** {@code stmt} drawn as its own source text; see {@link SourceStatementBlock}. */
    private SourceStatementBlock sourceStatement(Statement stmt, ParseContext ctx, String reason) {
        String source = null;
        if (ctx.sourceCode() != null) {
            int start = stmt.getStartPosition();
            int end = start + stmt.getLength();
            if (start >= 0 && end <= ctx.sourceCode().length()) source = ctx.sourceCode().substring(start, end);
        }
        SourceStatementBlock block = new SourceStatementBlock(BlockId.of(stmt), stmt, source, reason);
        ctx.nodeToBlockMap().put(stmt, block);
        return block;
    }

    /** Whether {@code stmt} is an if or a loop with a one-statement body (or else) written without braces. */
    private static boolean hasBareBody(Statement stmt) {
        return switch (stmt) {
            case IfStatement i -> !(i.getThenStatement() instanceof Block)
                    || (i.getElseStatement() != null && !(i.getElseStatement() instanceof Block)
                    && !(i.getElseStatement() instanceof IfStatement));
            case WhileStatement w -> !(w.getBody() instanceof Block);
            case DoStatement d -> !(d.getBody() instanceof Block);
            case ForStatement f -> !(f.getBody() instanceof Block);
            case EnhancedForStatement f -> !(f.getBody() instanceof Block);
            default -> false;
        };
    }

    private Optional<StatementBlock> parseClassicFor(ForStatement stmt, ParseContext ctx) {
        ClassicForBlock block = new ClassicForBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        VariableDeclarationFragment counter = ClassicForBlock.counter(stmt);
        if (counter != null) {
            if (counter.getInitializer() != null) {
                parseExpression(counter.getInitializer(), ctx).ifPresent(block::setStart);
            }
        } else {
            for (Object init : stmt.initializers()) {
                parseExpression((Expression) init, ctx).ifPresent(block::addInitializer);
            }
        }
        if (stmt.getExpression() != null) parseExpression(stmt.getExpression(), ctx).ifPresent(block::setCondition);
        for (Object update : stmt.updaters()) {
            parseExpression((Expression) update, ctx).ifPresent(block::addUpdater);
        }
        if (stmt.getBody() instanceof Block b) block.setBody(parseBodyBlock(b, ctx));
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseTry(TryStatement stmt, ParseContext ctx) {
        TryBlock block = new TryBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        for (Object resource : stmt.resources()) {
            parseExpression((Expression) resource, ctx).ifPresent(block::addResource);
        }
        block.setTryBody(parseBodyBlock(stmt.getBody(), ctx));
        for (Object clause : stmt.catchClauses()) {
            block.addCatchBody(parseBodyBlock(((CatchClause) clause).getBody(), ctx));
        }
        if (stmt.getFinally() != null) block.setFinallyBody(parseBodyBlock(stmt.getFinally(), ctx));
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseThrow(ThrowStatement stmt, ParseContext ctx) {
        ThrowBlock block = new ThrowBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        parseExpression(stmt.getExpression(), ctx).ifPresent(block::setException);
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseSynchronized(SynchronizedStatement stmt, ParseContext ctx) {
        SynchronizedBlock block = new SynchronizedBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        parseExpression(stmt.getExpression(), ctx).ifPresent(block::setLock);
        block.setBody(parseBodyBlock(stmt.getBody(), ctx));
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseAssert(AssertStatement stmt, ParseContext ctx) {
        AssertBlock block = new AssertBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        parseExpression(stmt.getExpression(), ctx).ifPresent(block::setCondition);
        if (stmt.getMessage() != null) parseExpression(stmt.getMessage(), ctx).ifPresent(block::setMessage);
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseLabeled(LabeledStatement stmt, ParseContext ctx) {
        LabeledBlock block = new LabeledBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        parseStatement(stmt.getBody(), ctx).ifPresent(block::setInner);
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseYield(YieldStatement stmt, ParseContext ctx) {
        YieldBlock block = new YieldBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        if (stmt.getExpression() != null) parseExpression(stmt.getExpression(), ctx).ifPresent(block::setValue);
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseConstructorCall(Statement stmt, List<?> arguments, ParseContext ctx) {
        ConstructorCallBlock block = new ConstructorCallBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        for (Object arg : arguments) parseExpression((Expression) arg, ctx).ifPresent(block::addArgument);
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseReturn(ReturnStatement stmt, ParseContext ctx) {
        ReturnBlock block = new ReturnBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        if (stmt.getExpression() != null) parseExpression(stmt.getExpression(), ctx).ifPresent(block::setExpression);
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseTypeDeclaration(TypeDeclarationStatement stmt, ParseContext ctx) {
        if (stmt.getDeclaration() instanceof EnumDeclaration enumDecl) {
            DeclareEnumBlock block = new DeclareEnumBlock(BlockId.of(stmt), stmt);
            ctx.nodeToBlockMap().put(stmt, block);
            ctx.nodeToBlockMap().put(enumDecl, block);
            return Optional.of(block);
        }
        // A local class, record or interface: a type's members are not statements, and no block draws them.
        return Optional.of(sourceStatement(stmt, ctx,
                "A class declared inside a method is shown as written."));
    }

    private Optional<StatementBlock> parseExprStmt(ExpressionStatement stmt, ParseContext ctx) {
        Expression expr = stmt.getExpression();

        if (isPrintStatement(expr)) {
            return parsePrint(stmt, ctx);
        }
        if (expr instanceof Assignment) {
            return parseAssignment(stmt, ctx);
        }
        if (expr instanceof PostfixExpression || expr instanceof PrefixExpression) {
            AssignmentBlock block = new AssignmentBlock(BlockId.of(stmt), stmt);
            ctx.nodeToBlockMap().put(stmt, block);
            if (expr instanceof PostfixExpression pe) {
                parseExpression(pe.getOperand(), ctx).ifPresent(block::setLeftHandSide);
            }
            if (expr instanceof PrefixExpression pe) {
                parseExpression(pe.getOperand(), ctx).ifPresent(block::setLeftHandSide);
            }
            return Optional.of(block);
        }
        if (expr instanceof MethodInvocation mi) {
            // Ahead of everything else a method invocation can be: a branch chain is a call whose arguments
            // are all lambdas, chained leftward, and every arm below would draw only its outermost link —
            // LibraryCallBlock its receiver as a scope string, MethodInvocationBlock its two lambdas as
            // arguments. Anything that is not exactly that shape falls through unchanged.
            if (BranchChainHandler.isBranchChain(mi)) {
                return parseBranchChain(stmt, mi, ctx);
            }

            // A call whose last argument is a { … } lambda, on any receiver at all. It sits outside the
            // isLibraryClass arm below on purpose: the block it builds reads its captions and its slot types
            // out of the source and the bot's own classpath, so it has no reason to require that the receiver
            // be a class some plugin catalogues. Before 2026-09-01 it was inside that arm, because the block
            // it built hardcoded one SDK facade and could not have drawn anything else.
            if (LambdaCallHandler.isLambdaCall(mi)) {
                return parseBodyCall(stmt, mi, ctx);
            }

            String scope = mi.getExpression() != null ? mi.getExpression().toString() : "";

            // Activity.disable/enable("X") and Bot.stop() are ordinary SDK facade calls — they fall through to
            // the standardized LibraryCallBlock path below (same chrome as every other SDK block), rather than
            // being special-cased into a bespoke fixed-label block.
            if (isLibraryClass(scope)) {
                LibraryCallBlock block = new LibraryCallBlock(BlockId.of(stmt), stmt, scope);
                ctx.nodeToBlockMap().put(stmt, block);
                for (Object arg : mi.arguments()) {
                    parseExpression((Expression) arg, ctx).ifPresent(block::addArgument);
                }
                return Optional.of(block);
            }

            MethodInvocationBlock block = new MethodInvocationBlock(BlockId.of(stmt), stmt);
            ctx.nodeToBlockMap().put(stmt, block);
            for (Object arg : mi.arguments()) {
                parseExpression((Expression) arg, ctx).ifPresent(block::addArgument);
            }
            return Optional.of(block);
        }
        // Any other expression run for its effect — `new Worker(queue);`, `super.stop();`.
        ExpressionStatementBlock block = new ExpressionStatementBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        parseExpression(expr, ctx).ifPresent(block::setExpression);
        return Optional.of(block);
    }

    /**
     * A branch chain ({@code found.when(m -> m.hasAny(ORE), () -> { … }).otherwise(() -> { … })}) as one
     * {@link BranchChainBlock} with a row per link — the condition as an ordinary boolean expression slot, the
     * body as a droppable {@link BodyBlock}, both recursed exactly as every other block's are.
     *
     * <p>Nothing here knows what the chain is over. {@code BranchChainHandler} reads the shape and the source
     * supplies the captions, which is what lets one block draw any plugin's chain — see that class for why the
     * guarded-switch machinery this replaced could not be written that way.
     */
    private Optional<StatementBlock> parseBranchChain(ExpressionStatement stmt, MethodInvocation mi,
                                                      ParseContext ctx) {
        BranchChainBlock block =
                new BranchChainBlock(BlockId.of(stmt), stmt, BranchChainHandler.subjectOf(mi));
        ctx.nodeToBlockMap().put(stmt, block);

        for (BranchChainHandler.Link link : BranchChainHandler.read(mi)) {
            BranchChainBlock.LinkView view = block.addLink(link.method(), link.isTerminal());
            Expression condition = link.conditionExpression();
            if (condition != null) parseExpression(condition, ctx).ifPresent(view::setCondition);
            view.setBody(parseBodyBlock(link.body(), ctx));
        }
        return Optional.of(block);
    }

    /**
     * A call with a trailing body lambda ({@code ImageFinder.whileFind(img, m -> { … })}) as a
     * {@link BodyCallBlock}: every argument before the lambda as a fillable slot, the lambda's body as a
     * droppable {@link BodyBlock} (recursed via {@link #parseBodyBlock}), so the block round-trips.
     *
     * <p>Every leading argument is parsed, not just the first. The block this replaced took exactly one,
     * because the nine methods it could draw all had exactly one — a limit that came from the enum rather than
     * from the shape.
     */
    private Optional<StatementBlock> parseBodyCall(ExpressionStatement stmt, MethodInvocation mi, ParseContext ctx) {
        LambdaExpression lambda = LambdaCallHandler.lambdaArg(mi);
        String receiver = mi.getExpression() != null ? mi.getExpression().toString() : "";
        BodyCallBlock block =
                new BodyCallBlock(BlockId.of(stmt), stmt, receiver, mi.getName().getIdentifier());
        ctx.nodeToBlockMap().put(stmt, block);

        List<?> args = mi.arguments();
        for (int i = 0; i < args.size() - 1; i++) {
            parseExpression((Expression) args.get(i), ctx).ifPresent(block::addArgument);
        }
        if (lambda.getBody() instanceof Block b) block.setBody(parseBodyBlock(b, ctx));
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseVariableDecl(VariableDeclarationStatement stmt, ParseContext ctx) {
        // The declare block draws one name and one value. `int a = 1, b;` drew as `a = 1` and hid `b`, which
        // an edit to the block then rewrote from what it showed.
        if (stmt.fragments().size() > 1) {
            return Optional.of(sourceStatement(stmt, ctx,
                    "It declares several variables at once; the variable block holds one."));
        }
        VariableDeclarationBlock block = new VariableDeclarationBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        VariableDeclarationFragment frag = (VariableDeclarationFragment) stmt.fragments().getFirst();
        if (frag.getInitializer() != null) parseExpression(frag.getInitializer(), ctx).ifPresent(block::setInitializer);
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseIf(IfStatement stmt, ParseContext ctx) {
        IfBlock block = new IfBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        parseExpression(stmt.getExpression(), ctx).ifPresent(block::setCondition);
        if (stmt.getThenStatement() instanceof Block b) block.setThenBody(parseBodyBlock(b, ctx));
        if (stmt.getElseStatement() != null) parseStatement(stmt.getElseStatement(), ctx).ifPresent(block::setElseStatement);
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseWhile(WhileStatement stmt, ParseContext ctx) {
        WhileBlock block = new WhileBlock(BlockId.of(stmt), stmt, ctx.manager());
        ctx.nodeToBlockMap().put(stmt, block);
        parseExpression(stmt.getExpression(), ctx).ifPresent(block::setCondition);
        if (stmt.getBody() instanceof Block b) block.setBody(parseBodyBlock(b, ctx));
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseFor(EnhancedForStatement stmt, ParseContext ctx) {
        ForBlock block = new ForBlock(BlockId.of(stmt), stmt, ctx.manager());
        ctx.nodeToBlockMap().put(stmt, block);
        if (stmt.getParameter() != null) parseExpression(stmt.getParameter().getName(), ctx).ifPresent(block::setVariable);
        if (stmt.getExpression() != null) parseExpression(stmt.getExpression(), ctx).ifPresent(block::setCollection);
        if (stmt.getBody() instanceof Block b) block.setBody(parseBodyBlock(b, ctx));
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseDoWhile(DoStatement stmt, ParseContext ctx) {
        DoWhileBlock block = new DoWhileBlock(BlockId.of(stmt), stmt, ctx.manager());
        ctx.nodeToBlockMap().put(stmt, block);
        parseExpression(stmt.getExpression(), ctx).ifPresent(block::setCondition);
        if (stmt.getBody() instanceof Block b) block.setBody(parseBodyBlock(b, ctx));
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseSwitch(SwitchStatement stmt, ParseContext ctx) {
        SwitchBlock block = new SwitchBlock(BlockId.of(stmt), stmt, ctx.manager());
        ctx.nodeToBlockMap().put(stmt, block);
        if (stmt.getExpression() != null) parseExpression(stmt.getExpression(), ctx).ifPresent(block::setExpression);
        List<?> statements = stmt.statements();
        BodyBlock currentBody = null;
        SwitchBlock.SwitchCaseBlock currentCase = null;
        // An arrow rule's body is one Block that follows its label; having become the case's BodyBlock below,
        // it must not also be parsed as a child statement of itself.
        Statement consumedRuleBody = null;
        for (int i = 0; i < statements.size(); i++) {
            Statement s = (Statement) statements.get(i);
            if (s == consumedRuleBody) continue;
            if (s instanceof SwitchCase sc) {
                currentCase = new SwitchBlock.SwitchCaseBlock(BlockId.of(sc), sc);
                applyReadOnly(currentCase, ctx);
                ctx.nodeToBlockMap().put(sc, currentCase);
                if (!sc.isDefault() && !sc.expressions().isEmpty()) parseExpression((Expression) sc.expressions().getFirst(), ctx).ifPresent(currentCase::setCaseExpression);
                // Which node backs the body decides where an inserted statement goes, and the two label forms
                // disagree. A colon case's statements are siblings of its label in the switch's own list, so
                // the label is the anchor. An arrow rule's are inside a Block of their own — anchoring on the
                // label there inserted the statement *in front of* that Block, as a bare block among arrow
                // rules, which doesn't parse. The deleted matches-switch arm already backed its branches with
                // the Block; this is the same rule for every arrow switch.
                Block ruleBody = labeledRuleBody(sc, statements, i);
                if (ruleBody != null) {
                    consumedRuleBody = ruleBody;
                    currentBody = parseBodyBlock(ruleBody, ctx);
                } else {
                    currentBody = new BodyBlock(BlockId.of(sc), sc, ctx.manager());
                }
                applyReadOnly(currentBody, ctx);
                currentCase.setBody(currentBody);
                block.addCase(currentCase);
            } else if (currentBody != null) {
                // A case's closing break is the case's own chrome, not a statement in it: leaving it out of the
                // BodyBlock is what makes it undeletable and undraggable (there is no block to grab), and it
                // also makes "append to this case" land before it — insertIntoList offsets from the case label,
                // so the end of the visible body is exactly the break's slot.
                if (s instanceof BreakStatement && endsCase(statements, i)) {
                    currentCase.setClosingBreak(true);
                    continue;
                }
                BodyBlock target = currentBody;
                parseStatement(s, ctx).ifPresent(target::addStatement);
            }
        }
        return Optional.of(block);
    }

    /**
     * The braced body of the arrow rule labelled at {@code i}, or {@code null} when {@code sc} isn't an arrow
     * rule or its body isn't a single {@link Block}.
     *
     * <p>{@code case X -> foo();} has no block to put anything into; {@code SwitchNormalizer} gives it one when
     * the file is opened, so by the time a user can drop into it there is a Block here. Returning null in the
     * meantime falls back to the colon-form anchor, which is wrong for an arrow rule but is what it has always
     * done — an insert there is refused by the edit guard rather than corrupting the switch.
     */
    private static Block labeledRuleBody(SwitchCase sc, List<?> statements, int i) {
        if (!sc.isSwitchLabeledRule() || i + 1 >= statements.size()) return null;
        return statements.get(i + 1) instanceof Block body ? body : null;
    }

    /** Whether the statement at {@code i} is the last one before the next {@code case} label (or the switch's end). */
    private static boolean endsCase(List<?> statements, int i) {
        return i == statements.size() - 1 || statements.get(i + 1) instanceof SwitchCase;
    }

    private Optional<StatementBlock> parsePrint(ExpressionStatement stmt, ParseContext ctx) {
        PrintBlock block = new PrintBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        MethodInvocation mi = (MethodInvocation) stmt.getExpression();
        if (mi.arguments().isEmpty()) {
            block.addArgument(new LiteralBlock<>(BlockId.of(stmt), mi, ""));
        } else {
            for (Object arg : mi.arguments()) parseExpression((Expression) arg, ctx).ifPresent(block::addArgument);
        }
        return Optional.of(block);
    }

    private Optional<StatementBlock> parseAssignment(ExpressionStatement stmt, ParseContext ctx) {
        AssignmentBlock block = new AssignmentBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        Assignment a = (Assignment) stmt.getExpression();
        parseExpression(a.getLeftHandSide(), ctx).ifPresent(block::setLeftHandSide);
        parseExpression(a.getRightHandSide(), ctx).ifPresent(block::setRightHandSide);
        return Optional.of(block);
    }

    // =========================================================================
    // EXPRESSIONS
    // =========================================================================

    public Optional<ExpressionBlock> parseExpression(Expression expr, ParseContext ctx) {
        if (ctx.reuse().take(expr, ctx) instanceof ExpressionBlock kept) return Optional.of(kept);

        if (expr instanceof ArrayCreation ac && ac.getInitializer() != null) {
            Optional<ExpressionBlock> inner = parseExpression(ac.getInitializer(), ctx);
            inner.ifPresent(b -> ctx.nodeToBlockMap().put(expr, b));
            return inner;
        }
        Optional<ExpressionBlock> result = dispatchExpression(expr, ctx);
        result.ifPresent(b -> applyReadOnly(b, ctx));
        return result;
    }

    private Optional<ExpressionBlock> dispatchExpression(Expression expr, ParseContext ctx) {
        Map<ASTNode, CodeBlock> map = ctx.nodeToBlockMap();

        if (expr instanceof ClassInstanceCreation cic) {
            InstantiationBlock block = new InstantiationBlock(BlockId.of(expr), cic);
            map.put(expr, block);
            for (Object arg : cic.arguments()) {
                parseExpression((Expression) arg, ctx).ifPresent(block::addArgument);
            }
            return Optional.of(block);
        }
        if (expr instanceof NullLiteral nl) {
            NullBlock b = new NullBlock(BlockId.of(expr), nl);
            map.put(expr, b);
            return Optional.of(b);
        }
        if (expr instanceof StringLiteral sl) {
            LiteralBlock<String> b = new LiteralBlock<>(BlockId.of(expr), expr, sl.getLiteralValue());
            map.put(expr, b);
            return Optional.of(b);
        }
        if (expr instanceof ArrayInitializer arrayInit) {
            ListBlock block = new ListBlock(BlockId.of(expr), arrayInit);
            map.put(expr, block);
            for (Object item : arrayInit.expressions()) {
                parseExpression((Expression) item, ctx).ifPresent(block::addElement);
            }
            return Optional.of(block);
        }
        if (expr instanceof PrefixExpression prefix) {
            if (prefix.getOperator() == PrefixExpression.Operator.NOT) {
                NotOperatorBlock b = new NotOperatorBlock(BlockId.of(expr), prefix);
                map.put(expr, b);
                parseExpression(prefix.getOperand(), ctx).ifPresent(b::setOperand);
                return Optional.of(b);
            }
        }
        if (isListStructure(expr)) {
            ListBlock b = new ListBlock(BlockId.of(expr), expr);
            map.put(expr, b);
            for (Expression item : getListItems(expr)) parseExpression(item, ctx).ifPresent(b::addElement);
            return Optional.of(b);
        }
        if (expr instanceof FieldAccess fa) {
            FieldAccessBlock b = new FieldAccessBlock(BlockId.of(expr), fa, ctx.markNewIdentifiersAsUnedited());
            map.put(expr, b);
            return Optional.of(b);
        }
        if (expr instanceof QualifiedName qn) {
            if (qn.resolveBinding() instanceof IVariableBinding vb) {
                if (vb.isEnumConstant()) {
                    EnumConstantBlock b = new EnumConstantBlock(BlockId.of(expr), qn);
                    map.put(expr, b);
                    return Optional.of(b);
                } else if (vb.isField()) {
                    FieldAccessBlock b = new FieldAccessBlock(BlockId.of(expr), qn, ctx.markNewIdentifiersAsUnedited());
                    map.put(expr, b);
                    return Optional.of(b);
                }
            } else if (qn.getQualifier() instanceof SimpleName) {
                // Unresolved bindings are routine, not exceptional: a sibling generated file may not be on the
                // classpath yet (Activities.java is rewritten and recompiled as activities change), and the
                // fallback below would render `Activities.Mining` as inert plain text — the same as a construct
                // we have no block for. `Qualifier.name` is unambiguously a field access syntactically, so
                // build the real block and let it round-trip.
                FieldAccessBlock b = new FieldAccessBlock(BlockId.of(expr), qn, ctx.markNewIdentifiersAsUnedited());
                map.put(expr, b);
                return Optional.of(b);
            }
        }
        if (expr instanceof MethodInvocation mi) {
            String scope = mi.getExpression() != null ? mi.getExpression().toString() : "";
            MethodInvocationBlock block = isLibraryClass(scope)
                    ? new LibraryCallBlock(BlockId.of(expr), expr, scope)
                    : new MethodInvocationBlock(BlockId.of(expr), expr);
            map.put(expr, block);
            for (Object arg : mi.arguments()) {
                parseExpression((Expression) arg, ctx).ifPresent(block::addArgument);
            }
            return Optional.of(block);
        }
        // A number is read by the grammar (NumberLiterals): `60000L` once reached Integer.parseInt, threw, and
        // blanked the whole canvas. One spelled in a way a number field cannot write back — hex, `1_000`, an
        // exponent, or an int literal only legal under a minus — falls through to the verbatim block below,
        // which keeps the author's spelling visible and intact.
        if (expr instanceof NumberLiteral nl && NumberLiterals.isPlainDecimal(nl.getToken())) {
            Optional<Number> value = NumberLiterals.value(nl.getToken());
            if (value.isPresent()) {
                ExpressionBlock b = new LiteralBlock<>(BlockId.of(expr), expr, value.get());
                map.put(expr, b);
                return Optional.of(b);
            }
        }
        if (expr instanceof BooleanLiteral bl) {
            BooleanLiteralBlock b = new BooleanLiteralBlock(BlockId.of(expr), bl);
            map.put(expr, b);
            return Optional.of(b);
        }
        if (expr instanceof SimpleName sn) {
            if (expr.getParent() instanceof Type) return Optional.empty();
            IdentifierBlock b = new IdentifierBlock(BlockId.of(expr), sn, ctx.markNewIdentifiersAsUnedited());
            map.put(expr, b);
            return Optional.of(b);
        }
        if (expr instanceof InfixExpression infix) {
            if (isComparisonOperator(infix.getOperator())) {
                ComparisonExpressionBlock b = new ComparisonExpressionBlock(BlockId.of(expr), infix);
                map.put(expr, b);
                parseExpression(infix.getLeftOperand(), ctx).ifPresent(b::setLeftOperand);
                parseExpression(infix.getRightOperand(), ctx).ifPresent(b::setRightOperand);
                return Optional.of(b);
            } else {
                BinaryExpressionBlock b = new BinaryExpressionBlock(BlockId.of(expr), infix);
                map.put(expr, b);
                parseExpression(infix.getLeftOperand(), ctx).ifPresent(b::setLeftOperand);
                parseExpression(infix.getRightOperand(), ctx).ifPresent(b::setRightOperand);
                return Optional.of(b);
            }
        }
        if (expr instanceof MethodReference mr) {
            MethodReferenceBlock b = new MethodReferenceBlock(BlockId.of(expr), mr);
            map.put(expr, b);
            return Optional.of(b);
        }
        // Fallback: never return empty. Callers use `.ifPresent(block::addArgument)`, so an empty Optional
        // silently DROPS the argument — the block then shows fewer args than the source has, and a later
        // rewrite from block state can delete them for real. Render it verbatim instead so it stays visible
        // and round-trips. Add a real branch above if you want the node type to be editable.
        UnknownExpressionBlock unknown = new UnknownExpressionBlock(BlockId.of(expr), expr);
        map.put(expr, unknown);
        return Optional.of(unknown);
    }

    // =========================================================================
    // PURE PREDICATES / HELPERS
    // =========================================================================

    private static boolean isLibraryClass(String name) {
        return com.botmaker.studio.plugin.PluginHost.isFacadeClass(name);
    }

    private static boolean isComparisonOperator(InfixExpression.Operator op) {
        return op == InfixExpression.Operator.EQUALS || op == InfixExpression.Operator.NOT_EQUALS ||
                op == InfixExpression.Operator.LESS || op == InfixExpression.Operator.GREATER ||
                op == InfixExpression.Operator.LESS_EQUALS || op == InfixExpression.Operator.GREATER_EQUALS ||
                op == InfixExpression.Operator.CONDITIONAL_AND || op == InfixExpression.Operator.CONDITIONAL_OR;
    }

    // isWait went on 2026-09-13 with the WaitBlock it fed; see the TryStatement note in dispatchStatement.

    public static boolean isPrintStatement(Expression expression) {
        if (!(expression instanceof MethodInvocation method)) return false;
        if (!method.getName().getIdentifier().equals("println")) return false;
        // `System.out` is a QualifiedName here, not a FieldAccess: with no bindings resolved, JDT cannot tell
        // a field selection from a package qualification, so it parses the whole dotted form as a name. The
        // FieldAccess arm is kept because a `this`- or cast-qualified form does produce one, and because
        // reading only the QualifiedName case would silently stop matching the day bindings are switched on.
        Expression receiver = method.getExpression();
        if (receiver instanceof QualifiedName qn) {
            return qn.getFullyQualifiedName().equals("System.out");
        }
        return receiver instanceof FieldAccess fa
                && fa.getName().getIdentifier().equals("out")
                && fa.getExpression() instanceof SimpleName sn
                && sn.getIdentifier().equals("System");
    }

    // isReadInputStatement went on 2026-09-01 with the Read blocks. It matched `BotMaker.readX()`, which is
    // to say the editor recognised one library's facade by name — the same coupling as writing it. A bot
    // still calling it renders as an ordinary variable declaration holding a library call, which is what it
    // is.

    public static boolean isListStructure(Expression expr) {
        if (expr instanceof ArrayInitializer) return true;
        if (expr instanceof ArrayCreation) return true;
        if (expr instanceof ClassInstanceCreation cic) {
            String typeName = cic.getType().toString();
            return (typeName.startsWith(JdkType.ARRAY_LIST.simpleName())
                    || typeName.startsWith(JdkType.ARRAY_LIST.qualifiedName())) && !cic.arguments().isEmpty();
        }
        if (expr instanceof MethodInvocation mi) {
            String scope = mi.getExpression() != null ? mi.getExpression().toString() : "";
            return (scope.equals(JdkType.ARRAYS.simpleName()) && mi.getName().getIdentifier().equals("asList")) ||
                    (scope.equals(JdkType.LIST.simpleName()) && mi.getName().getIdentifier().equals("of"));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static List<Expression> getListItems(Expression expr) {
        if (expr instanceof ArrayInitializer ai) return ai.expressions();
        if (expr instanceof ArrayCreation ac) {
            return ac.getInitializer() != null ? ac.getInitializer().expressions() : Collections.emptyList();
        }
        if (expr instanceof ClassInstanceCreation cic) {
            if (!cic.arguments().isEmpty()) {
                return getListItems((Expression) cic.arguments().getFirst());
            }
        }
        if (expr instanceof MethodInvocation mi) return mi.arguments();
        return List.of();
    }

}
