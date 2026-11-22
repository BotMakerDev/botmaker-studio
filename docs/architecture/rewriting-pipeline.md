# Rewriting pipeline

The write path is the core's since 2026-11-05: Studio sends an `Edit` through `services/CoreEdits`, the core's
`edit/CoreEditor` receives it, and `CodeEditor` (`com.botmaker.core.parser`, public, per-edit API) → the class owning
that kind of edit (`MemberEdits`, `ExpressionEdits`, `CallEdits`, `SlotEdits`, `StatementEdits`, `SwitchEdits`, package
private, since 2026-11-22) → the handlers (the core's `parser/handlers/*`: methods, types, lists, restore;
`botmaker-java`'s `edit/handlers/*`: enums, operators, branch chains, …) + the class's own `private static` transforms
(bespoke AST shapes) + `NodeCreator` (over the core's `parser/factories/StatementFactory` and `botmaker-java`'s
`edit/factories/*`) → `AstRewriteHelper.applyRewrite`. Every rewrite is a pure transform: it takes
`(CompilationUnit cu, String originalCode, …)` and returns the new source string. `EditGate` is the only stateful
layer — its `edit(target, kind, markUnedited, …)` helper wraps each call with `canModify()` and `triggerUpdate()`, so
individual methods are one-liners. `CodeEditor` holds its public methods only: a refused edit is journalled under the
first `CodeEditor` frame on the stack, which a private helper there would answer for.

Shared low-level rewrite primitives live in `botmaker-java`'s `edit/helpers/AstRewriteHelper` (`applyRewrite`,
`removeNode`, `renameSimpleName`, `getListRewriteForBody`) — reuse these rather than re-implementing `ASTRewrite`
boilerplate. The stateless handlers `OperatorReplacementHandler` and `EnumManipulationHandler` expose only static
methods.

**Remaining cleanup opportunity:**

- **`StatementFactory` library imports:** the vision-type creators no longer emit imports for `Point`/`Rect`/etc.
  (the old `resolveLibraryFQN` was a `""` stub). If those types ever live outside the default package, add real
  FQN resolution (e.g. via `ProjectAnalyzer`) at the creation sites.

(The `BlockFactory` scratch-field smell listed here before is gone: `BlockConverter`, which replaced it, threads an
immutable `ParseContext`.)
