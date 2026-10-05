# Rewriting pipeline (parser package)

The write path is `CodeEditor` (public, per-edit API) → `parser/handlers/*` + `CodeEditor`'s own `private static`
transforms (bespoke AST shapes) + `NodeCreator` (which owns the `parser/factories/*`) → `AstRewriteHelper.applyRewrite`.
Every rewrite is a pure transform: it takes `(CompilationUnit cu, String originalCode, …)` and returns the new source
string. `CodeEditor` is the only stateful layer — its `edit(markUnedited, op)` helper wraps each call with
`canModify()` and `triggerUpdate()` (publishes `CodeUpdatedEvent`), so individual methods are one-liners.

Shared low-level rewrite primitives live in `AstRewriteHelper` (`applyRewrite`, `removeNode`, `renameSimpleName`,
`getListRewriteForBody`) — reuse these rather than re-implementing `ASTRewrite` boilerplate. The stateless handlers
`OperatorReplacementHandler` and `EnumManipulationHandler` expose only static methods.

**Remaining cleanup opportunities** (favor the functional-OOP guideline above):

- **`BlockFactory` scratch fields (reentrancy smell):** it keeps per-`convert()` state in mutable fields (`ast`,
  `currentSourceCode`, `allComments`, `blockParser`, `isReadOnlyMode`, `markNewIdentifiersAsUnedited`).
  `markNewIdentifiersAsUnedited` is toggled by `CodeEditor` via a setter before each edit and reset in a `finally`
  (temporal coupling). Prefer threading a per-call context object instead of holding these as fields.
- **`StatementFactory` library imports:** the vision-type creators no longer emit imports for `Point`/`Rect`/etc.
  (the old `resolveLibraryFQN` was a `""` stub). If those types ever live outside the default package, add real
  FQN resolution (e.g. via `ProjectAnalyzer`/`TypeSummary`) at the creation sites.
