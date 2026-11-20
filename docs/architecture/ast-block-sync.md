# AST ↔ Block Synchronisation

The round-trip between Java source and visual blocks. The source is the core's (`botmaker-core`, a process of its
own since 2026-11-05); Studio draws it and asks for changes.

1. **Source → Blocks**: `parser/BlockConverter` walks an Eclipse JDT `CompilationUnit` and creates `CodeBlock`
   instances. It is stateless: per-parse state is an immutable `ParseContext` threaded through the recursion. A
   `Map<ASTNode, CodeBlock>` is kept as the canonical registry (`EditorState.setNodeToBlockMap`).
2. **Blocks → Source**: the core's `CodeEditor` (`com.botmaker.core.parser`) applies the edit through JDT
   `ASTRewrite`, delegating to the handlers and its own pure transforms; `NodeCreator` creates new AST nodes.
3. **Sync flow**: user drops a block → `BlockDragAndDropManager` resolves the drop target → `CodeEditorService`
   sends the edit through `CoreEdits` → the core's `CodeEditor` rewrites and compiles → the core tells the change
   as a `CoreEvent` → Studio's state adopts the new source, `CodeUpdatedEvent` is published, and the open file
   is parsed into blocks again.

The handlers do specialised AST mutations, in two places: the core's `parser/handlers/` (method signatures,
types, lists, restoring a method) and `botmaker-java`'s `edit/handlers/` (enums, operators, branch chains,
instantiation, lambdas, `try`, …).
