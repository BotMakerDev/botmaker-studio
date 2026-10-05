# Suggestion / Autocomplete Pipeline

`ProjectAnalyzer` is the single entry point for all type-aware suggestions. It combines:

- **Library index** (`TypeSummaryManager` / `TypeSummary`) — lightweight summaries of external jar types read directly from bytecode via **ClassGraph** (no decompilation), serialised and cached per-jar under the BotMaker cache dir
- **Project AST** (`CompilationUnitAnalyzer`) — live `ITypeBinding` resolution from the user's own source

The owning `CodeEditorService` is passed directly into every block's `getUINode()` call, giving blocks access to the `CodeEditor`, event bus, drag-and-drop manager, project state, and `ProjectAnalyzer` without requiring service locators. (It earlier threaded a dedicated `CompletionContext` record, which was just a partial copy of the service and has been removed.)

The type-aware context menus (insert/replace an expression, pick a method/constructor/enum/variable, choose a
type) are built by `ui/render/menu/ExpressionMenu`, which reads `ProjectAnalyzer`. A menu pick is emitted
either as a sealed `palette/ExpressionType` (a plain palette entry, from `ExpressionCatalog`) or as a sealed `parser/ExpressionChoice`
(`Method` / `Constructor` / `EnumConstant` / `Variable`); `AbstractCodeBlock.applyExpressionSelection` dispatches it
to the matching `CodeEditor.replaceWith…` call with an exhaustive `switch`.

**Planned: drop `TypeSummary`, consume ClassGraph directly.** The `TypeSummary`/`MethodSummary`/`FieldSummary`
records are a hand-rolled DTO mirroring what ClassGraph already models, plus a hand-rolled per-jar Java-serialized
(`.ser`) cache (`saveJar`/`loadFromFile` via `ObjectOutputStream`). The intended direction is to remove this
duplication and let ClassGraph own both the model and the persistence:
- Cache the scan with ClassGraph's built-in `ScanResult.toJSON()` / `ScanResult.fromJSON(String)` instead of the
  custom `.ser` files — deletes `saveJar`, `saveAll`, `loadFromFile`, `getCacheFileForJar`'s `.ser` logic, and the
  `Serializable` requirement.
- Have `ProjectAnalyzer` consume `ClassInfo`/`MethodInfo`/`FieldInfo` directly; the sealed
  `ProjectAnalyzer.ResolvedMethod`/`ResolvedField` `FromIndex(...)` variants would wrap ClassGraph types instead of
  `TypeSummary.MethodSummary`/`FieldSummary`.
- Delete the `TypeSummary` record and the `toSummary`/`toMethodSummary` mappers in `TypeSummaryManager`.

Accepted tradeoff: this couples `ProjectAnalyzer` to ClassGraph (today `TypeSummary` is an anti-corruption boundary
that kept the CFR→ClassGraph swap isolated to `TypeSummaryManager`). The win is much less of our own code — no DTO,
no mapper, no bespoke serialization. Note ClassGraph still leaves cache *policy* (where/when/invalidate) to us; only
the serialize/deserialize step is outsourced.

