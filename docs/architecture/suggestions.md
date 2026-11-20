# Suggestion / Autocomplete Pipeline

`ProjectAnalyzer` (`botmaker-java`'s `types/`, since 2026-11-04) is the single entry point for all type-aware
suggestions. It combines:

- **Library index** (`TypeSummaryManager`, in `botmaker-core`'s `index/`) — `LibraryType` records of external jar
  types read directly from bytecode via **ClassGraph** (no decompilation), cached on disk as one JSON file per jar
  under the BotMaker cache dir
- **Project AST** — live `ITypeBinding` resolution from the user's own source, parsed with full bindings by
  `ProjectAnalyzer.createCompilationUnit`

The owning `CodeEditorService` is passed directly into every block's `getUINode()` call, giving blocks access to the edit port, event bus, drag-and-drop manager, project state, and `ProjectAnalyzer` without requiring service locators. (It earlier threaded a dedicated `CompletionContext` record, which was just a partial copy of the service and has been removed.)

The type-aware context menus (insert/replace an expression, pick a method/constructor/enum/variable, choose a
type) are built by `ui/render/menu/ExpressionMenu`, which reads `ProjectAnalyzer`. A menu pick is emitted
either as a sealed `palette/ExpressionType` (a plain palette entry) or as a sealed `ExpressionChoice`
(`Method` / `Constructor` / `EnumConstant` / `Variable`), both `botmaker-java`'s `edit/`;
`AbstractCodeBlock.applyExpressionSelection` dispatches it to the matching edit with an exhaustive `switch`.

**The `TypeSummary` DTO is gone.** It was a hand-rolled mirror of ClassGraph's model with a Java-serialized `.ser`
cache per jar. The index then handed out ClassGraph's own `ClassInfo` and cached ClassGraph's JSON, until
ClassGraph 6 removed that JSON (2026-10-18); it now scans each jar once into BotMaker's own `LibraryType` records,
cached as JSON beside a list of the jar's packages, so an open reads only the jars it offers from. Cache *policy*
(where, when, invalidation) is still ours.
