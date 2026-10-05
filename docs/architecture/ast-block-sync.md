# AST ↔ Block Synchronisation

The round-trip between Java source and visual blocks:

1. **Source → Blocks**: `BlockFactory` + `BlockParser` walk an Eclipse JDT `CompilationUnit` and create `CodeBlock` instances. `BlockFactory.parseBodyBlock()` is the recursion entry point. A `Map<ASTNode, CodeBlock>` is maintained as the canonical registry.
2. **Blocks → Source**: `CodeEditor` applies mutations (backed by Eclipse JDT `ASTRewrite`), delegating to the `parser/handlers/*` and its own pure transforms; `NodeCreator` creates new AST nodes. `CodeEditor` drives the overall write operation and publishes `CodeUpdatedEvent`.
3. **Sync flow**: User drops a block → `BlockDragAndDropManager` resolves the drop target → `CodeEditorService` calls `CodeEditor` → `CodeEditor` rewrites the AST → `CodeUpdatedEvent` published → `CodeEditorService` refreshes the UI by re-parsing the source file.

The `parser/handlers/` classes handle specialised AST mutations (method signatures, type replacements, enum manipulation, etc.).

