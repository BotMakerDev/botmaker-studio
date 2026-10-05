# UI Structure

The `ui/` package is split by concern:

- **`ui/app/`** — the application shell. `UIManager` is the *coordinator*: it assembles the main window out
  of the collaborators below and releases what that window acquired (`dispose()`) — which matters because a
  new one is built on every project open **and every reload**, so anything it holds and doesn't release is
  leaked per reload. It builds and hands callbacks to `EditorCanvas` (the block canvas, its scroll position
  and the Reader banner), `DiagnosticsPanel` (the Errors tab), `IdentityCluster` (accounts + the theme
  dropdown; owns one of the window's two `BlockTheme` listeners), `StudioActions` (every menu/toolbar action
  in one wiring table, plus the GitHub/sharing services that back them), `ProjectRecoveryAction`
  (**Project ▸ Recover Project Files**) and `WorkspaceLayoutStore` (the persisted dividers + open bottom tab);
  none of them holds a reference back. `BottomTab` is the closed set of bottom tabs. Alongside those: the
  panel/screen managers `FileExplorerManager` (project file tree), `MenuBarManager` / `ToolbarManager` (menus
  and toolbar; the **Project → Manage Libraries…** entry lives here), `RunConsole` (the Run tab: the
  bot's output, Stop, Clear — the bot runs on pipes, so it is lines, not a terminal; the Event Log that stood
  beside it was deleted on 2026-09-25), `terminal/TerminalPane` + `TerminalView` (the Terminal tab:
  xterm.js vendored under `resources/terminal/` in a `WebView`, over `services/terminal/PtySession` on
  pty4j; no JavaFX in the session, and the window's `dispose()` ends every shell), `AssistantPane` (the
  Assistant tab: an AI CLI from `assist/AiTool` in a `TerminalView`, over the MCP endpoint, denied direct
  edits — umbrella `docs/refactor/38-llm-edits.md` §5), `versions/VersionsPane` (the Versions tab: the
  timeline `versions/Timeline` folds, *Save version*, restore, naming by git note; a refresh first writes the
  editor's sources through `project/vcs/Checkpoints.flush`; a file's change is `versions/DiffCards` — the
  pure `project/vcs/BlockDiff` over `VersionReader`'s two sides, each drawn by `versions/BlockPreview`
  against a staged `ProjectState`, never the live one; *Restore this function* is `CodeEditor.replaceMethod`
  (`parser/handlers/RestoreHandler`), an ordinary edit; the strip on top is `project/vcs/SyncModel` over the
  remotes `mine`/`original` (`project/vcs/Remote`), *Save to my copy* is `sharing/MyCopy`, an install is
  `ProjectVcs.cloneAt` and a zip install is `ProjectVcs.attach`ed once; *Get vX.Y* is `ProjectVcs.mergeTag` +
  `versions/ConflictSheet` (per file, `resolve`/`finishMerge`/`abortMerge`; `checkpoint` refuses mid-merge)
  and *Suggest to author…* is `sharing/Suggestion` — umbrella `docs/refactor/39-versions.md`; it
  replaced `VcsPanel`/`VcsDialog` on 2026-09-25), `ProjectSelectionScreen`, `GitHubAccountBar` /
  `GoogleAccountBar`, and ~15 dialogs
  (`ProjectSetupDialog`, `LaunchTargetDialog`, `ManageCaptureTargetsDialog`, `ManageLibrariesDialog`,
  `ResourceManagerDialog`, `GalleryDialog`, …). The open-time source migrations are **not**
  here — they are `project/ProjectOpenMigrations`, run from the shell's constructor before
  `FileExplorerManager` exists, since a migration can delete a file the tree would otherwise go on listing.
  There is **no `PaletteManager`** — this entry named one for a long time and no such file has ever existed;
  the insertable catalogs are `palette/` below, and the overlay's own palette bar is
  `ui/app/overlay/OverlayPalette`.
- **`ui/app/pilot/` and `services/pilot/` are gone (2026-08-30)** — the Remote Pilot is the SDK plugin's
  feature, in `botmaker-sdk`'s `internal/plugin/pilot/`, reached through a `ToolbarItem` like any other
  plugin's contribution. Nothing here calls it and nothing here closes it: the host says the project is
  closing and the plugin releases its own port and display. See *the Remote Pilot left Studio* below.
- **`ui/app/capture/`** — what is *left* of the screen-capture feature: `OverlayTemplateCapture` (the
  on-screen capture toolbar), `CaptureSourcePicker`, `TargetThumbnail`, `BatchTemplateNamingDialog`.
  **The overlay is a feature of the SDK, not of Studio** — it exists to turn a `CaptureTargetModel` into an
  `ImageTemplate`, both of which are that plugin's — so `ColorSampler`, `ZoomPan` and `GameFrame` left on
  2026-08-30 and `CaptureSurface`, `ObjectCaptureSurface` and `MagicWand` followed the same day, into
  `com.botmaker.sdk.internal.plugin.capture`. `OverlayTemplateCapture` names them there and hands them a
  `StudioServices` (built from the project with `HostServices.forProject`) until it follows, which is the
  step that makes *Capture Templates* a `ToolbarItem`.
- **`ui/app/flow/` is gone (2026-09-11)** — the activity-flow graph editor is
  `com.botmaker.sdk.internal.plugin.flow`, opened from a 🔀 Activity Flow toolbar item. A flow is the one
  thing here that does not reduce to a `ParameterRow`, which is why it moved while the Parameters window and
  the Runner stayed. **`activities.json` has no reader in Studio at all**: `project/activity/`,
  `services/ActivityService`, `ActivitiesChangedEvent` and `ProjectState.activities` went with it. What a
  menu or a picker needs instead is `plugin/HostParameters` (the parameters every loaded plugin declares)
  and `project/managed/MethodReferences` (the methods the bot's `@Managed` values reference).
- **`project/params/`** — **a user parameter is a `@Param` field in the bot's own Java** (2026-09-17), and
  this package is how Studio reads and writes one. Five classes, split by what each needs: **
  `JavaParameterSource`** parses one source into `ParameterRow`s (JDT, **no bindings** — a type is whatever
  it is *written* as, so a bot whose pom is mid-edit still shows its parameters), **`JavaParameterEdits`**
  rewrites one source (`ASTRewrite`, so the author's formatting and comments survive; an edit it cannot
  make answers the source unchanged and never throws), and **`JavaParameters`** is the half that knows
  about the project — `BotSources`, buffers before files, both written. The first two are pure, which is
  why they are tested over source text rather than over a project on disk.
  **The grammar is Studio's since 2026-09-22** (`plugin/grammar/`): `ValueForm`, `ValueContainer`,
  `HostContainers` and `SourceSplit` moved here from the contract, and **`ValueGrammar`** replaced
  `ValueCatalog`'s grammar half — built once per bind by `PluginHost.grammar()` from the bound plugins'
  `types()` and `componentTypes()`. A leaf is a JDK literal (`JdkLiterals`), an enum constant, a
  `ComponentType` taken apart through `components` and put back through `build`, a declared type written
  as its own source, or unknown. **No plugin sees Java source**: an editor gets the value
  (`ValueContext.value`) and hands one back (`set(Object)`), and `ValueGrammar` spells it. Where this
  section still says *codec*, *`ValueCatalog`* or *wire*, read *`ValueGrammar`* and *value*: the codec,
  the catalog and every text bridge were deleted, not moved.
  **A field's type is a `ValueForm`, read recursively** (`JavaParameterSource.formOf`, 2026-09-20): a
  known leaf, or a registered `ValueContainer` over forms, all the way down, so `Map<String,
  List<Duration>>` is something this reads rather than something it calls unknown. It was a `ValueChoice`
  — a type plus one list, deleted from the contract 2026-09-20 — and a field javac accepts perfectly well
  came out unknown and read-only. The single type a form's values are typed as is `ValueForm.leaf()`, which
  is what a declared set of choices and a declared `Range` are asked of and the only thing a `ValueChoice`
  said that anything still needs. What is
  *not* a container is still an unknown leaf, shown as written: an array, a wildcard, a type variable, a
  `Set` nobody contributed, and a container whose written arity disagrees with the registered one.
  **Reading a value back is `ValueGrammar.valueOf`**: the host wrote the spelling, so the host reads it,
  and a plugin's `build` turns the typed parts into its value. A field whose initialiser the grammar declines is listed, shown and
  **read-only, with the reason** — the window never hides a parameter the bot reads, and never rewrites
  Java the author wrote by hand. **A row's value is the initialiser itself**, as the author wrote it: one
  source string rather than a list of stored items, because a composite has no other canonical form
  (`32-generic-values.md` decision 6). **The value cells read and write that same spelling** (2026-09-20):
  `ParamValueWidgets` switches on the form — a leaf editor, a list of them, a two-column map, and the
  author's own source read-only for everything else — and a composite is taken apart one level at a time
  through `ValueGrammar.partsOfInitializer`, never encoded. `plugin/ValueWire` keeps only that structural
  half (`parts`, `compose`, `containers`); its leaf join to text (`literal`/`wire`) went on 2026-09-22,
  because a leaf's control is handed the value now. `ui/render/components/ValueTypePicker` holds a form too — `Wrap in ▸` from `ValueWire.containers()`
  plus `Unwrap`, capped at two containers because a picker is capped where the model is not.
  **`BotRecords` is the fifth class, and the bot's own half of the vocabulary** (2026-09-20): it reads the
  project's `record` declarations and answers what `JavaParameterSource` structurally cannot — whether a
  written name is a class *this bot* declares — so a field typed `Point` is a `ValueForm.Declared` rather
  than an unknown leaf. It also owns the grammar for one, because the contract's declines it: `new
  com.example.bot.Point(1, 2)` out, positional parts back, read with a real expression parse. (A plugin's
  `Point` is a `ComponentType` and never reaches here; `BotRecords` is for the bot's own.) **Records
  only** (a canonical constructor is unambiguous where a class's five are a guess), **no generics**
  (substituting a type variable needs the binding this package does without), and **never a placeholder**
  into a class of the user's.
  **`ParameterSurface` stood beside them from 2026-09-17 to 2026-09-22 and is deleted, folded into
  `JavaParameters`.** It merged *two* sources of rows — `@Param` fields under a `java:<ClassName>` section
  id, and rows a plugin declared under its own group id — and almost all of its 354 lines were that merge.
  **Nothing ever declared a plugin row**: the SDK's group was the only one in existence and declared none,
  and `botmaker-plugin-basics`' `ParameterStore.declare` had no caller anywhere, so `parameterRows` returned
  a pre-2026-09-17 project's JSON and empty for every project made since — the *second reader of a format
  nothing writes* the umbrella `CLAUDE.md` forbids by name. The contract surface went with it.
  **So a section is a class and the handle is `(className, fieldName)`**, which javac already keeps unique
  where `(group, name)` needed a `java:` prefix to keep two plugins' `timeout` apart; and `Entry(group, row,
  java)` is simply `JavaParameter`, since the field *is* the entry when there is one source. A plugin that
  wants a row of its own **puts a `@Param` field in the file it ships** — `BotSources.scan` already walks
  `plugins/sdk/Sdk.java`, so it is found, drawn and edited with no new code.
  Every method takes a `ValueGrammar`, defaulting to `PluginHost.grammar()`: the grammar is what turns a
  value into the Java a field is initialised with, so a window and a test that disagreed about it would write
  two different files from the same click. `ParametersDialog` and `RunnerWindow` read this one list; the
  Runner also drops what it cannot rewrite, because a read-only cell is a message for the author and there
  is no author in the Runner.
- **A plugin's values live in a file the plugin ships** (2026-09-20, `33-plugin-java.md`). The contract's
  half is `PluginSource` — a class name and its whole text — plus `PluginValues` off
  `StudioServices.pluginValues()`, two methods: `ids()` and `open(String id)`. The plugin writes the class
  once, with working defaults and one `@Managed("id")` method per value; the host copies it into
  `src/main/java/<bot package>/plugins/<last id segment>/` when the plugin is added, and after that
  **rewrites nothing but the expression a `@Managed` method returns**.
  **The host is not the author of a compilation unit, and that is the whole design.** Four earlier attempts
  made it one — a record handed over reflectively, the grammar host-side, an annotation processor, and a
  sequence of `ModelCall` statements written into a generated class, which shipped as studio-api `2bc1e2f`
  earlier the same day and was withdrawn. Each had to own the package, the class name, the imports and the
  ordering. Handing the file over answers all of those at once and shrinks the reader's input domain from
  *a class* to *one expression*, which is what `ValueGrammar.valueOf` already reads.
  **The write is one `ASTRewrite` over a `ReturnStatement`'s expression**, which is the surgery
  `JavaParameterEdits.setValue` already performs on a field's initializer: one node, in place, the rest of
  the file byte-identical. The `ValueForm` comes from the method's **declared return type** through
  `JavaParameterSource.formOf`, so the plugin declares no forms at all.
  **`open` hands back a `ValueContext`**, the same interface a slot on the canvas and a Parameters row are
  edited through, so there is one way to edit a value and not two.
  **A body that is not exactly `return <expression>;` is read-only with a named reason** — the rule
  `whyNotEditable` already applies to a computed `@Param` initializer.
- **`ui/app/overlay/`** — the **Overlay Editor** (2026-10-06): a panel docked beside the screen the bot
  watches, where the bot is built while looking at what it sees. `OverlayEditor` is the *coordinator* — what
  the panel is beside, the event subscriptions, the caret, every edit, and the FX-thread state that sequences
  an edit against the re-parse it causes. Its collaborators take callbacks and hold no reference back:
  `DockedPanel` (the window: docks via the pure `DockPlacement`, follows the window, resizes, stays above
  fullscreen through `OverlayToolbars`), `PanelHeader` (what it is beside, ⇄ Change, and the run bar's slot —
  `ui/app/run/RunBarDock`), `TargetStrip` (a chip per plugin target, and the `Collect ▸ body() ▾` breadcrumb),
  `OverlayTreeView` over `BlockTree` (**pure, no JavaFX** — the tree model and the flattened rows; one ⋮ per
  row), `ToolTabs` (each plugin's `OverlayTool` pane, *Actions* for `ToolbarGroup.OVERLAY`, *Blocks* for
  `OverlayPalette`), `ToolContext` and `WatchedShots` (a tool's frame, picks and insert),
  `ArgumentConfigPopover`, `OverlayStyles`. What the panel is beside comes from a live session, else the
  plugins' `OverlayPart.watched` (`services/overlay/WatchedScreen`), else `OverlayWindowPicker`, which lists
  open windows only. Where blocks go is `services/overlay/OverlayTargets`, read off bindings; a tool's insert
  is `services/overlay/OverlayCalls`. **⏺ Record is gone** (with `plugin/record/` and the
  `preferredRecorders` setting): its job — turning what is on screen into a call — is the tools'. The row
  look is `overlay-*` classes in `blocks.css`. Design: `docs/refactor/42-overlay-editor.md` (umbrella).
- **`ui/dnd/`** — drag-and-drop and block input events: `BlockDragAndDropManager`, `DropInfo`, `MoveBlockInfo`,
  `BlockEvent`, `DropZoneFactory`.
- **`palette/`** (top-level, dependency-light) — the insertable catalogs: `BlockType`/`BlockCatalog`/`BlockCategory`
  and `Initializer` for statements, `ExpressionType`/`ExpressionCatalog`/`ExpressionCategory` for expressions.
- **`ui/render/`** — block rendering: `layout/` (the fluent `BlockLayout` DSL — only `header()`/`sentence()`,
  with `HeaderLayoutBuilder.andBody()`, are live), `components/` (pure JavaFX widget factories, e.g.
  `BlockUIComponents`), `menu/` (`ExpressionMenu` fills an expression slot, `StatementMenu` inserts a block,
  `MenuBuilders` is their shared plumbing and `MenuIcons` the single glyph lookup), and `theme/` (theming constants;
  `Spacing.gutter()` is the single source of the block gutter width).

Cross-cutting block decoration lives in `core/render/` (the `BlockDecorator` pipeline, see **Block System**), and
block state styling lives in `src/main/resources/css/blocks.css`. **How a block looks is umbrella
`docs/refactor/37-block-styling.md` (2026-09-24)**: `getUINode` puts `block`, `shape-*` (`core/render/BlockShape`,
worked out from the syntax tree), `block-category` and `category-*` on every root, the layout builders stamp
`bc-*` per `BlockComponent.Kind`, and the canvas carries `blocks-filled`/`blocks-outlined` (`BlockStyle`,
View ▸ Block Style). A new block overrides `category()` and, rarely, `shape()`, and writes no CSS; run
`BlockStyleContrastTest` and `BM_BLOCK_GALLERY=<dir> mvn test -Dtest=BlockGalleryTest` after touching BLOCK STYLE. That file also carries the **window
chrome** — the toolbar's hairline, the status line, the Errors filter bar and the diagnostic rows — as classes
over the `-bm-*` design tokens each theme redefines. Style the shell there, never with `setStyle`: an inline
style beats the stylesheet in *every* theme, which is exactly how the toolbar border came to override its own
token-driven rule and the Errors bar came to stay light grey in Dark.

