package com.botmaker.studio.parser;

import com.botmaker.core.parser.StatementBodies;
import com.botmaker.core.parser.StatementKind;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.java.edit.BodyChildren;
import com.botmaker.java.edit.BlockId;

import com.botmaker.studio.blocks.ClassBlock;
import com.botmaker.studio.blocks.InterfaceBlock;
import com.botmaker.studio.blocks.RecordBlock;
import com.botmaker.studio.blocks.TypeBlock;
import com.botmaker.studio.blocks.expr.*;
import com.botmaker.studio.blocks.flow.*;
import com.botmaker.studio.blocks.func.ConstructorBlock;
import com.botmaker.studio.blocks.func.ExternalCallBlock;
import com.botmaker.studio.blocks.func.ProjectCallBlock;
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
import com.botmaker.java.edit.handlers.BranchChainHandler;
import com.botmaker.java.edit.handlers.LambdaCallHandler;
import com.botmaker.java.edit.helpers.FileTypeDetector;
import com.botmaker.java.edit.helpers.NumberLiterals;
import com.botmaker.core.project.LockResolver;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.java.types.JdkType;
import com.botmaker.java.grammar.TypeNodes;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import org.eclipse.jdt.core.dom.*;

import java.util.*;

import static com.botmaker.java.types.ProjectAnalyzer.createCompilationUnit;

/**
 * Converts Java source into a tree of {@link CodeBlock}s. Stateless: all per-parse state is
 * carried in an immutable {@link ParseContext} threaded through the recursion, so a single
 * instance is safe to reuse across files and edits.
 */
public class BlockConverter {

    /**
     * Immutable per-{@code convert()} parsing context. Threaded through every recursive
     * step of {@link BlockConverter} so the converter holds no per-parse mutable state.
     *
     * @param cu                            the binding-resolved compilation unit being parsed
     * @param sourceCode                    the original source (used to recover comment text)
     * @param comments                      non-Javadoc comments collected from {@code cu}
     * @param nodeToBlockMap                canonical AST-node → block registry being populated
     * @param manager                       drag-and-drop manager handed to interactive blocks
     * @param readOnly                      whether blocks parsed under this context are locked
     * @param resolver                      the file's lock rules; null when there is no project (tests)
     * @param markNewIdentifiersAsUnedited  whether freshly created identifiers/field accesses
     *                                      should be visually marked as auto-generated
     * @param reuse                         which blocks of the previous parse may be kept; {@link BlockReuse#NONE}
     *                                      switches reuse off, which is what every caller that has not opted in
     *                                      passes and what makes a parse under it byte for byte the parse this
     *                                      project has always done
     *
     * <p>There was an {@code audience} component here until 2026-09-13, threaded in from
     * {@code ProjectState.getAudience()} and <b>never read by anything</b>: it had already narrowed from deciding
     * whole members to deciding components, and the component axis is gone too. See
     * {@code docs/refactor/29-block-layer.md} §2.1.
     */
    public record ParseContext(
            CompilationUnit cu,
            String sourceCode,
            List<Comment> comments,
            Map<ASTNode, CodeBlock> nodeToBlockMap,
            BlockDragAndDropManager manager,
            boolean readOnly,
            LockResolver resolver,
            boolean markNewIdentifiersAsUnedited,
            BlockReuse reuse
    ) {
        /**
         * This context with {@code readOnly} set to {@code ro}, for parsing a method body whose lock differs from
         * the file around it. Every block created under the returned context inherits the verdict.
         *
         * <p><b>This is two-way, and deliberately so</b> — it can unlock a subtree the file around it locked, not
         * only lock one. That mattered when a method's verdict could differ from its file's; today the two agree
         * everywhere, since the only read-only cases left are whole-file (bundled library source, and a bot open
         * for reading). The only thing allowed to widen a lock is a {@link LockResolver} verdict — do not call
         * this with a hand-derived boolean.
         */
        public ParseContext withReadOnly(boolean ro) {
            return ro == readOnly ? this
                    : new ParseContext(cu, sourceCode, comments, nodeToBlockMap, manager, ro, resolver,
                            markNewIdentifiersAsUnedited, reuse);
        }
    }

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
        CompilationUnit ast;
        try {
            ast = parsed != null ? parsed : parse(javaCode);
        } catch (RuntimeException e) {
            return unreadable(e);
        }
        return convert(ast, javaCode, null, nodeToBlockMap, manager, isReadOnly,
                LockResolver.forActiveFile(config, state), markNewIdentifiersAsUnedited, reuse);
    }

    /**
     * A file that is not the bot's, drawn with every block locked: a library class opened by Go to Definition
     * (2026-09-27). No {@link LockResolver} is asked — the resolver speaks for the open project file, and this
     * one is not in the project at all — so nothing in it answers to the bot's lock rules, and nothing in it
     * can be edited.
     *
     * <p>{@code type} is the one drawn: the file's first when null, and otherwise a nested one — the canvas has
     * no row for a class inside a class, so {@code Map.Entry}'s members are only reachable by drawing it alone.
     */
    public ConvertResult convertReadOnly(CompilationUnit parsed, String javaCode, AbstractTypeDeclaration type,
                                         Map<ASTNode, CodeBlock> nodeToBlockMap, BlockDragAndDropManager manager) {
        return convert(parsed, javaCode, type, nodeToBlockMap, manager, true, null, false, BlockReuse.NONE);
    }

    private ConvertResult convert(CompilationUnit ast, String javaCode, AbstractTypeDeclaration type,
                                  Map<ASTNode, CodeBlock> nodeToBlockMap,
                                  BlockDragAndDropManager manager, boolean isReadOnly, LockResolver resolver,
                                  boolean markNewIdentifiersAsUnedited, BlockReuse reuse) {
        try {
            List<Comment> comments = BodyChildren.nonJavadoc(ast);

            ParseContext ctx = new ParseContext(
                    ast, javaCode, comments, nodeToBlockMap, manager, isReadOnly, resolver,
                    markNewIdentifiersAsUnedited, reuse);

            if (ast.types().isEmpty()) return new ConvertResult(null, ast);

            AbstractTypeDeclaration rootNode = type != null ? type : (AbstractTypeDeclaration) ast.types().getFirst();
            List<String> problems = new ArrayList<>();
            AbstractCodeBlock root = parseRoot(rootNode, ctx, problems);
            return new ConvertResult(root, ast, List.copyOf(problems));

        } catch (Exception e) {
            return unreadable(e);
        }
    }

    private static ConvertResult unreadable(Exception e) {
        System.err.println("Critical error in BlockConverter.convert: " + e.getMessage());
        e.printStackTrace();
        return new ConvertResult(null, null, List.of("This file could not be drawn ("
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")."));
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
        // --- CASE A: Standard Class File (a class, an interface or a record) ---
        if (rootNode instanceof TypeDeclaration || rootNode instanceof RecordDeclaration) {
            return typeBlock(rootNode, rootCtx, problems);
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

    /**
     * A class, an interface or a record, and its members: the file's own type, or one nested in it (2026-10-12: an
     * interface drew as a class, and a nested type or a record file drew nothing).
     */
    private TypeBlock typeBlock(AbstractTypeDeclaration typeDecl, ParseContext outer, List<String> problems) {
        // A class another window owns whole (only a plugin's managed constants in it) draws every member
        // read-only, the same way a file open for reading does — LockResolver says which.
        ParseContext ctx = signatureEditable(typeDecl, outer) ? outer : outer.withReadOnly(true);
        TypeBlock classBlock = switch (typeDecl) {
            case TypeDeclaration type when type.isInterface() -> new InterfaceBlock(BlockId.of(type), type);
            case TypeDeclaration type -> new ClassBlock(BlockId.of(type), type, ctx.manager());
            case RecordDeclaration record -> new RecordBlock(BlockId.of(record), record);
            default -> throw new IllegalArgumentException(typeDecl.getName() + " is not a class, interface or record");
        };
        applyReadOnly(classBlock, ctx);
        ctx.nodeToBlockMap().put(typeDecl, classBlock);

        for (Object obj : typeDecl.bodyDeclarations()) {
            // One member that cannot be drawn costs that member, not the file. Until 2026-09-19 the throw
            // reached convert()'s outer catch, which answered "no root" — so one `60000L` drew the whole
            // file as an empty canvas and said nothing. The member is left out and named instead.
            try {
                parseMember(obj, classBlock, ctx, problems);
            } catch (RuntimeException e) {
                problems.add(memberName(obj) + " could not be drawn ("
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")");
            }
        }
        return classBlock;
    }

    /** What a member is called in a status line — {@code Parameters.restBetween}, {@code run()}. */
    private static String memberName(Object member) {
        return switch (member) {
            case MethodDeclaration method -> method.getName().getIdentifier() + "()";
            case FieldDeclaration field when !field.fragments().isEmpty() ->
                    ((VariableDeclarationFragment) field.fragments().getFirst()).getName().getIdentifier();
            case AbstractTypeDeclaration type -> type.getName().getIdentifier();
            case Initializer ignored -> "an initializer block";
            default -> "a member";
        };
    }

    /** One member of a class, added to {@code classBlock}. */
    private void parseMember(Object obj, TypeBlock classBlock, ParseContext ctx, List<String> problems) {
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
        } else if (obj instanceof TypeDeclaration || obj instanceof RecordDeclaration) {
            // A nested class, interface or record, drawn as a file's own type is, inside its outer one.
            classBlock.addBodyDeclaration(typeBlock((AbstractTypeDeclaration) obj, ctx, problems));
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

        // The lines read_tree lists too (StatementBodies), statements and comments in source order.
        for (ASTNode line : StatementBodies.of(astBlock, ctx.comments()).lines()) {
            if (line instanceof Comment comment) bodyBlock.addStatement(parseCommentBlock(comment, ctx));
            else parseStatement((Statement) line, ctx).ifPresent(bodyBlock::addStatement);
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

    /**
     * The block for {@code stmt}, by its {@link StatementKind} — the kind read_tree names, so the canvas and the
     * assistant cannot disagree on what a statement is (2026-11-05).
     */
    private Optional<StatementBlock> dispatchModelled(Statement stmt, ParseContext ctx) {
        // A try arm stood here until 2026-09-13 that matched one shape — `try { Thread.sleep(n) } catch …` — and
        // drew it as a bespoke WaitBlock. Waiting is a plugin's verb (`Wait.time(Duration)`), so that went; TRY
        // draws every try as the try it is, the raw sleep included. A guarded arrow switch had its own arm until
        // 2026-09-01; branching on what was found is a chain of calls (BranchChainHandler), so a switch of any
        // form is an ordinary switch.
        return switch (StatementKind.of(stmt)) {
            case SOURCE -> Optional.of(sourceStatement(stmt, ctx, StatementKind.drawnAsSource(stmt).orElseThrow()));
            case BODY -> Optional.of(parseBodyBlock((Block) stmt, ctx));
            case DECLARE_ENUM -> parseTypeDeclaration((TypeDeclarationStatement) stmt, ctx);
            case VARIABLE_DECLARATION -> parseVariableDecl((VariableDeclarationStatement) stmt, ctx);
            case IF -> parseIf((IfStatement) stmt, ctx);
            case WHILE -> parseWhile((WhileStatement) stmt, ctx);
            case FOR -> parseFor((EnhancedForStatement) stmt, ctx);
            case CLASSIC_FOR -> parseClassicFor((ForStatement) stmt, ctx);
            case DO_WHILE -> parseDoWhile((DoStatement) stmt, ctx);
            case TRY -> parseTry((TryStatement) stmt, ctx);
            case THROW -> parseThrow((ThrowStatement) stmt, ctx);
            case SYNCHRONIZED -> parseSynchronized((SynchronizedStatement) stmt, ctx);
            case ASSERT -> parseAssert((AssertStatement) stmt, ctx);
            case LABELED -> parseLabeled((LabeledStatement) stmt, ctx);
            case YIELD -> parseYield((YieldStatement) stmt, ctx);
            case CONSTRUCTOR_CALL -> parseConstructorCall(stmt, stmt instanceof ConstructorInvocation c
                    ? c.arguments() : ((SuperConstructorInvocation) stmt).arguments(), ctx);
            case SWITCH -> parseSwitch((SwitchStatement) stmt, ctx);
            case BREAK -> Optional.of(new BreakBlock(BlockId.of(stmt), (BreakStatement) stmt));
            case CONTINUE -> Optional.of(new ContinueBlock(BlockId.of(stmt), (ContinueStatement) stmt));
            case RETURN -> parseReturn((ReturnStatement) stmt, ctx);
            case PRINT -> parsePrint((ExpressionStatement) stmt, ctx);
            case ASSIGNMENT -> parseAssignment((ExpressionStatement) stmt, ctx);
            case BRANCH_CHAIN -> parseBranchChain((ExpressionStatement) stmt,
                    (MethodInvocation) ((ExpressionStatement) stmt).getExpression(), ctx);
            case BODY_CALL -> parseBodyCall((ExpressionStatement) stmt,
                    (MethodInvocation) ((ExpressionStatement) stmt).getExpression(), ctx);
            case EXTERNAL_CALL, PROJECT_CALL -> parseCallStatement((ExpressionStatement) stmt, ctx);
            case EXPRESSION_STATEMENT -> parseExpressionStatement((ExpressionStatement) stmt, ctx);
            case COMMENT -> Optional.empty();
        };
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

    /** A local enum; a local class is SOURCE ({@link StatementKind#drawnAsSource}). */
    private Optional<StatementBlock> parseTypeDeclaration(TypeDeclarationStatement stmt, ParseContext ctx) {
        DeclareEnumBlock block = new DeclareEnumBlock(BlockId.of(stmt), stmt);
        ctx.nodeToBlockMap().put(stmt, block);
        ctx.nodeToBlockMap().put(stmt.getDeclaration(), block);
        return Optional.of(block);
    }

    /**
     * A call run for its effect. A branch chain and a call ending in a { … } lambda are their own kinds, ahead of
     * this ({@link StatementKind}): a body call sits outside the facade test on purpose, since its block reads its
     * captions and slot types from the source and the bot's classpath. Activity.disable/enable("X") and Bot.stop()
     * are ordinary facade calls — one block for every call.
     */
    private Optional<StatementBlock> parseCallStatement(ExpressionStatement stmt, ParseContext ctx) {
        MethodInvocation mi = (MethodInvocation) stmt.getExpression();
        MethodInvocationBlock block = callBlock(BlockId.of(stmt), stmt, mi);
        ctx.nodeToBlockMap().put(stmt, block);
        for (Object arg : mi.arguments()) {
            parseExpression((Expression) arg, ctx).ifPresent(block::addArgument);
        }
        return Optional.of(block);
    }

    /** Any other expression run for its effect — `new Worker(queue);`, `super.stop();`. */
    private Optional<StatementBlock> parseExpressionStatement(ExpressionStatement stmt, ParseContext ctx) {
        Expression expr = stmt.getExpression();
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

    /** One variable; several in one declaration are SOURCE ({@link StatementKind#drawnAsSource}). */
    private Optional<StatementBlock> parseVariableDecl(VariableDeclarationStatement stmt, ParseContext ctx) {
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
        // Which node backs a case's body decides where an inserted statement goes, and the two label forms
        // disagree (StatementBodies.cases). A colon case's statements are siblings of its label in the switch's
        // own list, so the label is the anchor. An arrow rule's are inside a Block of their own — anchoring on the
        // label there inserted the statement *in front of* that Block, as a bare block among arrow rules, which
        // doesn't parse. `case X -> foo();` has no block until SwitchNormalizer gives it one when the file opens;
        // until then it falls back to the label, and an insert there is refused by the edit guard.
        for (StatementBodies.Case each : StatementBodies.cases(stmt, ctx.comments())) {
            SwitchCase sc = each.label();
            SwitchBlock.SwitchCaseBlock currentCase = new SwitchBlock.SwitchCaseBlock(BlockId.of(sc), sc);
            applyReadOnly(currentCase, ctx);
            ctx.nodeToBlockMap().put(sc, currentCase);
            if (!sc.isDefault() && !sc.expressions().isEmpty()) parseExpression((Expression) sc.expressions().getFirst(), ctx).ifPresent(currentCase::setCaseExpression);
            BodyBlock currentBody;
            if (each.body().anchor() instanceof Block ruleBody) {
                currentBody = parseBodyBlock(ruleBody, ctx);
            } else {
                currentBody = new BodyBlock(BlockId.of(sc), sc, ctx.manager());
                for (ASTNode line : each.body().lines()) {
                    parseStatement((Statement) line, ctx).ifPresent(currentBody::addStatement);
                }
            }
            // A case's closing break is the case's own chrome, not a statement in it: leaving it out of the
            // BodyBlock is what makes it undeletable and undraggable (there is no block to grab), and it also
            // makes "append to this case" land before it — insertIntoList offsets from the case label, so the end
            // of the visible body is exactly the break's slot.
            if (each.closingBreak()) currentCase.setClosingBreak(true);
            applyReadOnly(currentBody, ctx);
            currentCase.setBody(currentBody);
            block.addCase(currentCase);
        }
        return Optional.of(block);
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
        switch (stmt.getExpression()) {
            case Assignment a -> {
                parseExpression(a.getLeftHandSide(), ctx).ifPresent(block::setLeftHandSide);
                parseExpression(a.getRightHandSide(), ctx).ifPresent(block::setRightHandSide);
            }
            // `i++;` and `--i;`: an assignment of its operand.
            case PostfixExpression pe -> parseExpression(pe.getOperand(), ctx).ifPresent(block::setLeftHandSide);
            case PrefixExpression pe -> parseExpression(pe.getOperand(), ctx).ifPresent(block::setLeftHandSide);
            default -> { }
        }
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
        // Parentheses are grouping, and nesting already draws grouping: `(a + b) * c` is a product with a sum
        // block in one slot. So the block is the inside's. Replacing it replaces the inside, and the parentheses
        // stay in the file around whatever goes there.
        if (expr instanceof ParenthesizedExpression parens) {
            Optional<ExpressionBlock> inner = parseExpression(parens.getExpression(), ctx);
            inner.ifPresent(b -> ctx.nodeToBlockMap().put(expr, b));
            return inner;
        }
        Optional<ExpressionBlock> result = dispatchExpression(expr, ctx);
        result.ifPresent(b -> applyReadOnly(b, ctx));
        return result;
    }

    private Optional<ExpressionBlock> dispatchExpression(Expression expr, ParseContext ctx) {
        Map<ASTNode, CodeBlock> map = ctx.nodeToBlockMap();

        if (expr instanceof ClassInstanceCreation cic && cic.getAnonymousClassDeclaration() != null) {
            return Optional.of(sourceExpression(expr, ctx,
                    "It creates an object with a class body written in place, and a class is edited as Java."));
        }
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
            UnaryBlock b = new UnaryBlock(BlockId.of(expr), prefix);
            map.put(expr, b);
            parseExpression(prefix.getOperand(), ctx).ifPresent(b::setOperand);
            return Optional.of(b);
        }
        if (expr instanceof PostfixExpression postfix) {
            UnaryBlock b = new UnaryBlock(BlockId.of(expr), postfix);
            map.put(expr, b);
            parseExpression(postfix.getOperand(), ctx).ifPresent(b::setOperand);
            return Optional.of(b);
        }
        // `new int[n]` has no elements to list; drawn as a list it lost its size, so it goes to its own block.
        if (isListStructure(expr) && !(expr instanceof ArrayCreation ac && ac.getInitializer() == null)) {
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
            MethodInvocationBlock block = callBlock(BlockId.of(expr), expr, mi);
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
                for (Object extra : infix.extendedOperands()) {
                    parseExpression((Expression) extra, ctx).ifPresent(b::addExtendedOperand);
                }
                return Optional.of(b);
            } else {
                BinaryExpressionBlock b = new BinaryExpressionBlock(BlockId.of(expr), infix);
                map.put(expr, b);
                parseExpression(infix.getLeftOperand(), ctx).ifPresent(b::setLeftOperand);
                parseExpression(infix.getRightOperand(), ctx).ifPresent(b::setRightOperand);
                for (Object extra : infix.extendedOperands()) {
                    parseExpression((Expression) extra, ctx).ifPresent(b::addExtendedOperand);
                }
                return Optional.of(b);
            }
        }
        if (expr instanceof MethodReference mr) {
            MethodReferenceBlock b = new MethodReferenceBlock(BlockId.of(expr), mr);
            map.put(expr, b);
            return Optional.of(b);
        }
        Optional<ExpressionBlock> form = dispatchForm(expr, ctx);
        if (form.isPresent()) return form;
        // Fallback: never return empty. Callers use `.ifPresent(block::addArgument)`, so an empty Optional
        // silently DROPS the argument — the block then shows fewer args than the source has, and a later
        // rewrite from block state can delete them for real. Render it verbatim instead so it stays visible
        // and round-trips. Add a real branch above if you want the node type to be editable.
        UnknownExpressionBlock unknown = new UnknownExpressionBlock(BlockId.of(expr), expr);
        map.put(expr, unknown);
        return Optional.of(unknown);
    }

    /**
     * The value forms that are not calls, names or operators with a block of their own above: a choice between
     * two values, a cast, a type check, a lambda, an array element or a new array, an assignment or a
     * declaration used as a value, a class literal, {@code this}, {@code super.x}, a literal kept as spelled,
     * and a switch that is a value. Empty only for a node none of them is, which then falls to the unknown
     * block.
     */
    private Optional<ExpressionBlock> dispatchForm(Expression expr, ParseContext ctx) {
        Map<ASTNode, CodeBlock> map = ctx.nodeToBlockMap();
        ExpressionBlock block = switch (expr) {
            case ConditionalExpression c -> {
                ConditionalBlock b = new ConditionalBlock(BlockId.of(expr), c);
                map.put(expr, b);
                parseExpression(c.getExpression(), ctx).ifPresent(b::setCondition);
                parseExpression(c.getThenExpression(), ctx).ifPresent(b::setWhenTrue);
                parseExpression(c.getElseExpression(), ctx).ifPresent(b::setWhenFalse);
                yield b;
            }
            case CastExpression c -> {
                CastBlock b = new CastBlock(BlockId.of(expr), c);
                map.put(expr, b);
                parseExpression(c.getExpression(), ctx).ifPresent(b::setOperand);
                yield b;
            }
            case InstanceofExpression i -> {
                InstanceofBlock b = new InstanceofBlock(BlockId.of(expr), i);
                map.put(expr, b);
                parseExpression(i.getLeftOperand(), ctx).ifPresent(b::setOperand);
                yield b;
            }
            case PatternInstanceofExpression i -> {
                InstanceofBlock b = new InstanceofBlock(BlockId.of(expr), i);
                map.put(expr, b);
                parseExpression(i.getLeftOperand(), ctx).ifPresent(b::setOperand);
                yield b;
            }
            case LambdaExpression l -> {
                LambdaBlock b = new LambdaBlock(BlockId.of(expr), l);
                map.put(expr, b);
                if (l.getBody() instanceof Block body) b.setBlockBody(parseBodyBlock(body, ctx));
                else parseExpression((Expression) l.getBody(), ctx).ifPresent(b::setExpressionBody);
                yield b;
            }
            case ArrayAccess a -> {
                ArrayAccessBlock b = new ArrayAccessBlock(BlockId.of(expr), a);
                map.put(expr, b);
                parseExpression(a.getArray(), ctx).ifPresent(b::setArray);
                parseExpression(a.getIndex(), ctx).ifPresent(b::setIndex);
                yield b;
            }
            case ArrayCreation a -> {
                ArrayCreationBlock b = new ArrayCreationBlock(BlockId.of(expr), a);
                map.put(expr, b);
                for (Object dimension : a.dimensions()) {
                    parseExpression((Expression) dimension, ctx).ifPresent(b::addDimension);
                }
                yield b;
            }
            case Assignment a -> {
                AssignmentValueBlock b = new AssignmentValueBlock(BlockId.of(expr), a);
                map.put(expr, b);
                parseExpression(a.getLeftHandSide(), ctx).ifPresent(b::setTarget);
                parseExpression(a.getRightHandSide(), ctx).ifPresent(b::setValue);
                yield b;
            }
            case VariableDeclarationExpression v -> {
                DeclarationExpressionBlock b = new DeclarationExpressionBlock(BlockId.of(expr), v);
                map.put(expr, b);
                for (Object f : v.fragments()) {
                    VariableDeclarationFragment fragment = (VariableDeclarationFragment) f;
                    if (fragment.getInitializer() != null) {
                        parseExpression(fragment.getInitializer(), ctx)
                                .ifPresent(init -> b.setInitializer(fragment, init));
                    }
                }
                yield b;
            }
            case SuperMethodInvocation s -> {
                SuperAccessBlock b = new SuperAccessBlock(BlockId.of(expr), s);
                map.put(expr, b);
                for (Object arg : s.arguments()) parseExpression((Expression) arg, ctx).ifPresent(b::addArgument);
                yield b;
            }
            case SuperFieldAccess s -> put(map, new SuperAccessBlock(BlockId.of(expr), s));
            case ThisExpression t -> put(map, new ThisBlock(BlockId.of(expr), t));
            case TypeLiteral t -> put(map, new TypeLiteralBlock(BlockId.of(expr), t));
            case NumberLiteral n -> put(map, new TokenLiteralBlock(BlockId.of(expr), n));
            case CharacterLiteral c -> put(map, new TokenLiteralBlock(BlockId.of(expr), c));
            case TextBlock t -> put(map, new TokenLiteralBlock(BlockId.of(expr), t));
            // A dotted name nothing above resolved: `a.b.c` read as a chain of field accesses, which is what a
            // qualified name in a value position is.
            case QualifiedName q -> put(map, new FieldAccessBlock(BlockId.of(expr), q, ctx.markNewIdentifiersAsUnedited()));
            case SwitchExpression s -> parseSwitchExpression(s, ctx);
            case Pattern p -> sourceExpression(expr, ctx, "It is a pattern, which is edited as Java.");
            case CaseDefaultExpression c -> sourceExpression(expr, ctx, "It is the default label of a pattern switch.");
            case Annotation a -> sourceExpression(expr, ctx, "It is an annotation, which is edited as Java.");
            default -> null;
        };
        return Optional.ofNullable(block);
    }

    private static ExpressionBlock put(Map<ASTNode, CodeBlock> map, ExpressionBlock block) {
        map.put(block.getAstNode(), block);
        return block;
    }

    /**
     * A switch that is a value, one {@link SwitchExpressionBlock.Case} per {@code case … ->}: the value it
     * yields, the block it runs, or the statement it throws with. A switch expression written with colon labels
     * is statements with {@code yield}s among them, and is drawn as source.
     */
    private ExpressionBlock parseSwitchExpression(SwitchExpression expr, ParseContext ctx) {
        List<?> statements = expr.statements();
        boolean arrows = statements.stream()
                .filter(SwitchCase.class::isInstance)
                .allMatch(s -> ((SwitchCase) s).isSwitchLabeledRule());
        if (!arrows) {
            return sourceExpression(expr, ctx,
                    "It is a switch value written with `case X:` labels; only the `case X ->` form is drawn as blocks.");
        }
        SwitchExpressionBlock block = new SwitchExpressionBlock(BlockId.of(expr), expr);
        ctx.nodeToBlockMap().put(expr, block);
        parseExpression(expr.getExpression(), ctx).ifPresent(block::setSubject);
        SwitchExpressionBlock.Case current = null;
        for (Object o : statements) {
            Statement s = (Statement) o;
            if (s instanceof SwitchCase sc) {
                current = new SwitchExpressionBlock.Case(sc.isDefault());
                for (Object label : sc.expressions()) {
                    parseExpression((Expression) label, ctx).ifPresent(current::addLabel);
                }
                block.addCase(current);
            } else if (current != null) {
                switch (s) {
                    case YieldStatement y when y.isImplicit() && y.getExpression() != null ->
                            parseExpression(y.getExpression(), ctx).ifPresent(current::setValue);
                    case Block b -> current.setBody(parseBodyBlock(b, ctx));
                    default -> parseStatement(s, ctx).ifPresent(current::setStatement);
                }
            }
        }
        return block;
    }

    /** {@code expr} drawn as the Java it is, cut from the file, with the one-sentence {@code reason}. */
    private SourceExpressionBlock sourceExpression(Expression expr, ParseContext ctx, String reason) {
        String source = null;
        if (ctx.sourceCode() != null) {
            int start = expr.getStartPosition();
            int end = start + expr.getLength();
            if (start >= 0 && end <= ctx.sourceCode().length()) source = ctx.sourceCode().substring(start, end);
        }
        SourceExpressionBlock block = new SourceExpressionBlock(BlockId.of(expr), expr, source, reason);
        ctx.nodeToBlockMap().put(expr, block);
        return block;
    }

    // =========================================================================
    // PURE PREDICATES / HELPERS
    // =========================================================================

    /**
     * The block for a call, by whose code it runs ({@link StatementKind#ofCall}): a call written on a plugin's
     * facade class, or one whose binding lands outside the bot's source, is an {@link ExternalCallBlock}; the
     * bot's own (or an unresolved call) a {@link ProjectCallBlock}. Deciding from the source every time is what
     * makes switching a call's class rebuild the right block.
     */
    private static MethodInvocationBlock callBlock(String id, ASTNode node, MethodInvocation mi) {
        if (StatementKind.ofCall(mi) == StatementKind.PROJECT_CALL) return new ProjectCallBlock(id, node);
        String scope = mi.getExpression() != null ? mi.getExpression().toString() : "";
        return new ExternalCallBlock(id, node, PluginHost.isFacadeClass(scope) ? scope : null);
    }

    private static boolean isComparisonOperator(InfixExpression.Operator op) {
        return op == InfixExpression.Operator.EQUALS || op == InfixExpression.Operator.NOT_EQUALS ||
                op == InfixExpression.Operator.LESS || op == InfixExpression.Operator.GREATER ||
                op == InfixExpression.Operator.LESS_EQUALS || op == InfixExpression.Operator.GREATER_EQUALS ||
                op == InfixExpression.Operator.CONDITIONAL_AND || op == InfixExpression.Operator.CONDITIONAL_OR;
    }

    // isWait went on 2026-09-13 with the WaitBlock it fed; see the TryStatement note in dispatchStatement.

    // isReadInputStatement went on 2026-09-01 with the Read blocks. It matched `BotMaker.readX()`, which is
    // to say the editor recognised one library's facade by name — the same coupling as writing it. A bot
    // still calling it renders as an ordinary variable declaration holding a library call, which is what it
    // is.

    public static boolean isListStructure(Expression expr) {
        if (expr instanceof ArrayInitializer) return true;
        if (expr instanceof ArrayCreation) return true;
        if (expr instanceof ClassInstanceCreation cic) {
            return TypeNodes.names(cic.getType(), JdkType.ARRAY_LIST.qualifiedName()) && !cic.arguments().isEmpty();
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
