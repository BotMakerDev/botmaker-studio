# Changelog

What each released version of BotMaker Studio gives you, in a few bullets. `ROADMAP.md` stays the detailed
engineering log — why a thing was built, what was rejected, what it cost; this is the short answer to
*"should I update, and what changes for me?"*, and it is prepended to the GitHub Release notes for the tag.

**`release.sh` refuses to cut a version with no section here** (`check_changelog`, decide pass, before
anything is tagged). If the top section still says `## [Unreleased]`, rename it to the version being cut and
date it.

Sections are `## [x.y.z] — YYYY-MM-DD`, newest first.

## [Unreleased]

No source changes since v1.1.9; re-released for updated upstream pins.

### Changed

- **A plugin's editor is handed the resolved call and a type it can only ask about by class** (contract
  0.3.0). `plugin/HostTypes` builds the `TypeRef` from the binding (its erasure's binary name and every
  supertype) and the `Executable` from the call's `IMethodBinding`, loaded on the plugin loader by name and
  erased parameter types. `PickerContext` carries the call's binding rather than the scope text and method
  name. A call JDT could not resolve is no call, and a type known only by a bare simple name is unresolved:
  a call-site or type-keyed editor is absent there instead of matching a spelling.

- **A value is written as a JDT tree, never as text.** The grammar builds the expression node by node
  (`plugin/grammar/ValueWriter`) and answers a `JavaValue` — the node plus the classes it names by simple name
  — which replaces `ValueGrammar.Written`. Every sink copies that tree into its own file's tree: a Parameters
  row (`JavaParameterEdits`), a `@Managed` method's return (`JavaManagedEdits`), a canvas slot
  (`CodeEditor.replaceWithValue`), a varargs run (`setTrailingArguments`) and a recorded call
  (`CodeEditor.insertStatement`). `createStringPlaceholder` is gone from all of them, and a new parameter's
  field is a `FieldDeclaration` node laid out by the formatter. `HostValueContext` holds the value an editor set
  and its tree instead of a source string; a list, map or record cell composes its parts as trees
  (`ValueGrammar.compose`, `BotRecords.compose`), and a part nothing edited is written back as its own
  expression. A `@Managed` constant reference is matched through the file's imports (`SourceNames`), not by
  its spelling. Visible differences: a retyped or added field names `List`/`Map` by simple name with the
  import, and a field Studio adds imports `@Param` when the file did not.

- **A value's type is a `java.lang.reflect.Type`, and `ValueForm` is deleted.** A leaf is the plugin's own
  `Class`, a list or map is a `ParameterizedType` (`ValueTypes.Parameterized`), a bot's record is
  `ValueTypes.BotClass`, and anything else is `ValueTypes.Unknown`, shown and never written. A field's written
  type is resolved once, by `project/source/ValueTypeResolver`: from the binding when the classpath resolves,
  otherwise through the file's imports the way javac does. A `Duration` imported from another package is no
  longer read as `java.time.Duration`, and a nested record the bot declares is found by its canonical name.
  `ValueGrammar.qualify`, `type(String)` and `containerForJava` are gone; `JdkLiterals` is keyed by `Class`;
  a plugin factory's receiver and a `new` expression's class are matched the same way (`Factory.names`).

- **`@Param` and `@Managed` are identified by class, not by a name ending in `Param`.**
  `project/source/BotAnnotation` answers from the JDT binding when the unit was parsed against the project's
  classpath (`project/source/BotParser`), and otherwise resolves the written name through the unit's imports
  the way javac does (`SourceNames`). An annotation named `Param` from any other package — or a bare one
  nothing imports — is no longer read as a parameter. The pre-2.0 `botmaker-plugin-basics` annotations are
  still accepted by their exact names. With bindings, `@Param(category = SOME_CONSTANT)` reads the constant's
  value instead of dropping it. Replacing an annotation keeps the author's spelling of its name.

### Fixed

- **A blank project's first parameter compiles.** Adding a parameter wrote `@Param` into a project whose pom
  named no plugin, so nothing brought `botmaker-studio-api` and the build failed with `package
  com.botmaker.plugin.api.params does not exist`. Studio now declares the contract (the tag it was built
  against) when the project's classpath lacks it — never beside an SDK that already brings it — and a
  project already in that state is repaired when it is opened.

- **A plugin pinned as `${property}` reads and upgrades through the property.**
  `MavenService.readDependencyVersion` answers the property's value, and *Project ▸ Upgrade…* moves the
  property rather than overwriting the placeholder. The gamebot template pins its SDK as
  `${botmaker.sdk.version}`, and a project copied from it would otherwise resolve no SDK jar.

- **A zoomed-in canvas wraps instead of scrolling sideways.** Rows holding a comparison or a sum refused to be
  narrower than their one-line width; they wrap now, and at 200% a 700px window still fits the program.

- **Every word on a block clears WCAG AA in all four themes**, measured on the rendered canvas: the TRUE/FALSE
  chips (white on green was 2.1:1), a method's red × (1.5:1 on purple), a variable's name (black on every
  dark theme), "+ Add Function" on High Contrast's yellow, and a locked file, whose statements faded again at
  every level of nesting (a word four deep was drawn at 42% opacity).

- **The debugger's highlight and a compile error are rings round the block**, so they show on every colour —
  an error on a red block used to be invisible.

- **`new X(…)` reads on every block.** It kept a light teal card of its own, so its words were white on
  near-white (1.1:1) on every filled block, and its class name #333 on the dark themes' locked grey. An
  expression with no block yet (`i++` as a value) was muted grey on the block's colour (1.5:1 on the loop
  green). Both now take the block's own colours.

### Added

- **Every statement is on the canvas.** A `try` / `catch` / `finally` is a block with a body per clause,
  `+ catch` and `+ finally`, a catch type you type (`IOException | InterruptedException` works) and a name
  that renames only inside its clause. So are the counting loop (`for (int i = 0; i < 10; i++)`, drawn as
  *for int i from 0 while … each time …*), `throw`, `synchronized`, `assert`, a labelled loop (and the label
  on `break outer`), `this(…)` / `super(…)` in a constructor, and any other expression run for its effect
  (`new Worker();`). What still has no block of its own — a class declared inside a method, `int a = 1, b;` —
  is shown as the Java it is, with ✎ to edit it as text; text that is not Java is refused, not written.
  These statements used to be missing from the canvas while they stayed in the file.
- **Insert menu: Counting Loop, Try / Catch, Synchronized, Throw Error, Assert.** Each is seeded to compile
  where it is dropped (a fresh loop index, `catch (Exception e)`, the lock on the enclosing class).
- **An `if` or loop whose body is one statement without braces gets braces when the file is opened**, the
  way an arrow `case` already did, so its body has somewhere to drop a block. A file Studio does not rewrite
  (locked, read-only) shows such a statement as its Java instead.

- **Blocks look like blocks.** Every block is drawn in its category's colour with a darker lip under it; a
  loop, an `if` or a `switch` wraps its body like a C, so what is inside it is plainly inside; a method is a
  rounded "hat" its lines sit in; values are round and conditions square-ended, so a condition slot and what
  fills it can be told apart; an `if` / `else if` / `else` chain reads as one block. Typed-in values and
  dropdowns are as wide as what they show (a `0` is no longer a 150px box), keywords are bold and full size,
  and the `+` / `x` buttons are quiet until pointed at. **View ▸ Block Style** switches to *Outlined* — a
  plain card with the category as a bar down its edge — in every theme, and remembers the choice.
- **The block style is documented** in `docs/refactor/37-block-styling.md` (umbrella): the classes, the
  tokens per theme, each shape's anatomy, the states, and the recipe for a new block, which needs no CSS.

- **Zoom the block canvas.** View ▸ Zoom In / Zoom Out / Reset Zoom (Ctrl+=, Ctrl+-, Ctrl+0), Ctrl+wheel and
  a trackpad pinch, from 50% to 200%. Blocks re-wrap to the width they are drawn in rather than being
  stretched, the line under the pointer stays put, and a chip in the corner shows the zoom and resets it.
  Remembered across projects and restarts.

- **⏺ Record is back on the overlay HUD, and it inserts at the cursor.** Press Record, act in the game, press
  Stop: each click, double click, right or middle click, drag, scroll, typed text, key, key combination and
  pause becomes one statement at the cursor, each its own undo step, with its imports. Which call a gesture
  becomes is the plugins' to say; with the SDK, a click on one of your pictures is written
  `ImageClicker.click(Pictures.X)`, a click elsewhere `Mouse.click(…, x, y)`, and a pause that ends on a
  picture `ImageWaiter.waitFor(Pictures.X, …)`. Linux (X11) only, as before.
- **You choose which plugin records a gesture two plugins can write.** Right-click ⏺ Record: *Record with*
  lists each such gesture with *Automatic (highest rank)* and one choice per plugin, saved per project. The
  chosen plugin is tried first; a gesture it cannot write still falls to the next one.

### Changed

- **A plugin's factory is read as a constructor or method, not a name.** `plugin/grammar/Factory` is the
  host's view of the contract's `ComponentType.factory()` — constructor, static method, or an instance
  method on part 0 — and the grammar asks it how a call is spelled. The host's `List.of`, `Map.ofEntries`
  and `Map.entry` are real `Method`s too.
- **A value is read off the parsed Java, not its text, and hand-written chains are editable.** The grammar
  walks the JDT expression (`plugin/grammar/ExpressionReader`), so `Precision.TIGHT.minArea(400)` and
  `CaptureSource.window("G").region(new Rect(…))` draw as editable pills rather than as text, and a
  `public static final` constant of a class a plugin declares (`Precision.TIGHT`, `Color.RED`) reads as its
  value. An edited chain is written in the one-call form the plugin declares. A part nothing reads is shown
  exactly as you typed it. `SourceSplit`, the depth-zero comma splitter, is gone.
- **The HUD's activity picker lists the methods your flow runs.** It lists `Collect::body` for each method
  reference in a plugin value (the SDK's flow), opens the file declaring the class and scopes the tree to the
  method. It searched for `Activities.define("…")`, which SDK 2.0 deleted, and so found nothing. A reference
  to a class your project does not declare says so on the status line. The canvas's *Activity name* submenu
  on `Activity.enable("…")` is gone with the `Activity` class it keyed on; the SDK's own editor for
  `Activities.enable`/`disable`/`active`/`setEnabled` offers the names.
- **A plugin value named by one of your constants reads as that value, and a picked value is written as
  the constant.** A picture slot holding `Pictures.ORE` shows the ore picture, a run of pictures shows each
  one, and choosing a picture your `Pictures` class already holds writes `Pictures.X` rather than its path.
  The Parameters window does the same for a `@Param` field holding `Pictures.ORE`.
- **A capture source is a value everywhere.** `CaptureSource.window("Game")`, a monitor, an emulator, a
  region and `Source.current()` read and write through the SDK's declarations, and a recorded click writes
  `Mouse.click(Source.current(), x, y)` with the import rather than the fully qualified name. A narrowed
  source is written `CaptureSource.region(source, new Rect(…))`.
- **The duration editor has no *Random range* toggle** — it rewrote the call around a slot as text, which
  no plugin can do any more. Write `Wait.between` from the palette.
- **The palette comes from `@Palette` on a plugin's classes.** Studio finds every annotated class in each
  plugin's jar when the plugin builds no catalog of its own, so the SDK's palette now also offers
  `Activities` and `Flows`, which its old hand-written list had missed.

- **A colour, a duration or any other plugin value opened in the Parameters window and closed without an
  edit comes back byte-identical.** Studio used to read a value as text, run it through a codec it could
  not check and write it back — a `java.awt.Color` came out as `new java.awt.Color(255, 255, 255)`. Studio
  now reads every value as the value it is, through each plugin's own declaration of its type, and writes
  it back only when an editor changes it.
- **Every value editor is a plugin's.** The check box, spinners, date and time pickers, direction pad, mouse
  diagram and key list Studio drew itself now come from the plugin that declares the type (plugin-basics for
  the JDK's, the SDK for its own), so the Parameters window, the Runner and a block draw the same editor.
  One thing is lost: a declared `@Param(min, max)` no longer narrows a number's editor; it is still read,
  shown and kept.
- **A value written into your own file uses simple names and adds the import**, where it used to write
  `com.botmaker.sdk.api.geometry.Point` in full.
- **Retyping a parameter to a type with no starting value** (one of your own records) declares it with no
  initialiser rather than refusing.
- `@Param` bounds are written as numbers (`min = 1`); text bounds from older bots (`min = "1"`) still read.
- **Manage Plugins no longer reads an entry's `valueTypeIds`.** Nothing did anything with it, and the
  registry stopped asking for it. Entries that still carry the field read as before.

### Removed

- **Studio no longer writes a plugin's file into your project.** `PluginSourceFiles` ran on every plugin
  bind — opening a project, and again after any pom edit — copying in whatever Java each loaded plugin
  shipped. It is deleted with the contract method behind it.
  Reading those files is unchanged: the flow editor and 🖼 Manage Pictures still find `@Managed` methods in
  your own source and still rewrite one returned expression at a time, and the explorer still groups
  `plugins/<name>/` under *Generated by BotMaker*. What is gone is the writing.
  **A project made from a template already has the file.** A blank project that installs the SDK now gets
  no `plugins/sdk/Sdk.java` — the flow window offering to create one is owed and not in this release.

- **The Parameters window has one kind of section, and it is a class of your bot.** Studio no longer asks a
  plugin for parameter sections, rows or edits: `PluginHost.parameterGroups`/`parameterGroup`/`parameterRows`/
  `parameterEdited`, `HostParameters`' group half and `project/params/ParameterSurface` are all gone, and
  what is left is `JavaParameters`, which reads and writes `@Param` fields off your own source.
  Nothing a plugin ever declared is lost, because no plugin ever declared one — the surface read a
  pre-2026-09-17 project's JSON and answered empty for every project made since. **A plugin that wants a row
  of its own puts a `@Param` field in the file it ships**, and the window finds it like any other: a field in
  `plugins/sdk/Sdk.java` is listed under `Sdk.java`, editable, with the same value cell.
  Visible differences, all of them the sections: a heading reads `Parameters.java` rather than a plugin's
  title, a plugin with no parameters no longer draws an empty section, and the rail's categories are the
  distinct `@Param(category = …)` strings your bot actually uses.

### Changed

- **Rebuilt against the plugin contract's new package layout.** Studio's plugin host imports
  `com.botmaker.plugin.api.slot`, `.parameters`, `.toolbar` and `.source` now. Imports only — nothing in the
  canvas, the Parameters window or the plugin loader behaves differently. A plugin built against
  `botmaker-studio-api` v0.1.5 or earlier will not load in this Studio; rebuild it against v0.1.6.

- **The walkthrough describes the bot you actually get.** Getting Started and `WORKFLOW.md` still said a
  variable's value lived in `activities.json` and was read at startup by a generated `Activities.java`, that
  Studio maintained one source file per activity plus a registry, and that a run was driven by a generated
  `FlowDriver`. None of that has been true since a plugin's values became Java the plugin ships: a variable
  is a `@Param` field in your own `Parameters.java`, an activity is a `public static Outcome
  body(ActivityContext ctx)` the flow names as `Collect::body`, and the flow is a value in your own
  `plugins/sdk/Sdk.java`. The steps, the order and the runtime diagram's shape are unchanged — only the
  sentences that described the old machinery.

## [1.1.9] — 2026-09-23

### Added

- **⏺ Record is back on the overlay HUD, and it inserts at the cursor.** Press Record, act in the game, press
  Stop: each click, double click, right or middle click, drag, scroll, typed text, key, key combination and
  pause becomes one statement at the cursor, each its own undo step, with its imports. Which call a gesture
  becomes is the plugins' to say; with the SDK, a click on one of your pictures is written
  `ImageClicker.click(Pictures.X)`, a click elsewhere `Mouse.click(…, x, y)`, and a pause that ends on a
  picture `ImageWaiter.waitFor(Pictures.X, …)`. Linux (X11) only, as before.
- **You choose which plugin records a gesture two plugins can write.** Right-click ⏺ Record: *Record with*
  lists each such gesture with *Automatic (highest rank)* and one choice per plugin, saved per project. The
  chosen plugin is tried first; a gesture it cannot write still falls to the next one.

### Changed

- **A plugin's factory is read as a constructor or method, not a name.** `plugin/grammar/Factory` is the
  host's view of the contract's `ComponentType.factory()` — constructor, static method, or an instance
  method on part 0 — and the grammar asks it how a call is spelled. The host's `List.of`, `Map.ofEntries`
  and `Map.entry` are real `Method`s too.
- **A value is read off the parsed Java, not its text, and hand-written chains are editable.** The grammar
  walks the JDT expression (`plugin/grammar/ExpressionReader`), so `Precision.TIGHT.minArea(400)` and
  `CaptureSource.window("G").region(new Rect(…))` draw as editable pills rather than as text, and a
  `public static final` constant of a class a plugin declares (`Precision.TIGHT`, `Color.RED`) reads as its
  value. An edited chain is written in the one-call form the plugin declares. A part nothing reads is shown
  exactly as you typed it. `SourceSplit`, the depth-zero comma splitter, is gone.
- **The HUD's activity picker lists the methods your flow runs.** It lists `Collect::body` for each method
  reference in a plugin value (the SDK's flow), opens the file declaring the class and scopes the tree to the
  method. It searched for `Activities.define("…")`, which SDK 2.0 deleted, and so found nothing. A reference
  to a class your project does not declare says so on the status line. The canvas's *Activity name* submenu
  on `Activity.enable("…")` is gone with the `Activity` class it keyed on; the SDK's own editor for
  `Activities.enable`/`disable`/`active`/`setEnabled` offers the names.
- **A plugin value named by one of your constants reads as that value, and a picked value is written as
  the constant.** A picture slot holding `Pictures.ORE` shows the ore picture, a run of pictures shows each
  one, and choosing a picture your `Pictures` class already holds writes `Pictures.X` rather than its path.
  The Parameters window does the same for a `@Param` field holding `Pictures.ORE`.
- **A capture source is a value everywhere.** `CaptureSource.window("Game")`, a monitor, an emulator, a
  region and `Source.current()` read and write through the SDK's declarations, and a recorded click writes
  `Mouse.click(Source.current(), x, y)` with the import rather than the fully qualified name. A narrowed
  source is written `CaptureSource.region(source, new Rect(…))`.
- **The duration editor has no *Random range* toggle** — it rewrote the call around a slot as text, which
  no plugin can do any more. Write `Wait.between` from the palette.
- **The palette comes from `@Palette` on a plugin's classes.** Studio finds every annotated class in each
  plugin's jar when the plugin builds no catalog of its own, so the SDK's palette now also offers
  `Activities` and `Flows`, which its old hand-written list had missed.

- **A colour, a duration or any other plugin value opened in the Parameters window and closed without an
  edit comes back byte-identical.** Studio used to read a value as text, run it through a codec it could
  not check and write it back — a `java.awt.Color` came out as `new java.awt.Color(255, 255, 255)`. Studio
  now reads every value as the value it is, through each plugin's own declaration of its type, and writes
  it back only when an editor changes it.
- **Every value editor is a plugin's.** The check box, spinners, date and time pickers, direction pad, mouse
  diagram and key list Studio drew itself now come from the plugin that declares the type (plugin-basics for
  the JDK's, the SDK for its own), so the Parameters window, the Runner and a block draw the same editor.
  One thing is lost: a declared `@Param(min, max)` no longer narrows a number's editor; it is still read,
  shown and kept.
- **A value written into your own file uses simple names and adds the import**, where it used to write
  `com.botmaker.sdk.api.geometry.Point` in full.
- **Retyping a parameter to a type with no starting value** (one of your own records) declares it with no
  initialiser rather than refusing.
- `@Param` bounds are written as numbers (`min = 1`); text bounds from older bots (`min = "1"`) still read.
- **Manage Plugins no longer reads an entry's `valueTypeIds`.** Nothing did anything with it, and the
  registry stopped asking for it. Entries that still carry the field read as before.

### Removed

- **Studio no longer writes a plugin's file into your project.** `PluginSourceFiles` ran on every plugin
  bind — opening a project, and again after any pom edit — copying in whatever Java each loaded plugin
  shipped. It is deleted with the contract method behind it.
  Reading those files is unchanged: the flow editor and 🖼 Manage Pictures still find `@Managed` methods in
  your own source and still rewrite one returned expression at a time, and the explorer still groups
  `plugins/<name>/` under *Generated by BotMaker*. What is gone is the writing.
  **A project made from a template already has the file.** A blank project that installs the SDK now gets
  no `plugins/sdk/Sdk.java` — the flow window offering to create one is owed and not in this release.

- **The Parameters window has one kind of section, and it is a class of your bot.** Studio no longer asks a
  plugin for parameter sections, rows or edits: `PluginHost.parameterGroups`/`parameterGroup`/`parameterRows`/
  `parameterEdited`, `HostParameters`' group half and `project/params/ParameterSurface` are all gone, and
  what is left is `JavaParameters`, which reads and writes `@Param` fields off your own source.
  Nothing a plugin ever declared is lost, because no plugin ever declared one — the surface read a
  pre-2026-09-17 project's JSON and answered empty for every project made since. **A plugin that wants a row
  of its own puts a `@Param` field in the file it ships**, and the window finds it like any other: a field in
  `plugins/sdk/Sdk.java` is listed under `Sdk.java`, editable, with the same value cell.
  Visible differences, all of them the sections: a heading reads `Parameters.java` rather than a plugin's
  title, a plugin with no parameters no longer draws an empty section, and the rail's categories are the
  distinct `@Param(category = …)` strings your bot actually uses.

### Changed

- **Rebuilt against the plugin contract's new package layout.** Studio's plugin host imports
  `com.botmaker.plugin.api.slot`, `.parameters`, `.toolbar` and `.source` now. Imports only — nothing in the
  canvas, the Parameters window or the plugin loader behaves differently. A plugin built against
  `botmaker-studio-api` v0.1.5 or earlier will not load in this Studio; rebuild it against v0.1.6.

- **The walkthrough describes the bot you actually get.** Getting Started and `WORKFLOW.md` still said a
  variable's value lived in `activities.json` and was read at startup by a generated `Activities.java`, that
  Studio maintained one source file per activity plus a registry, and that a run was driven by a generated
  `FlowDriver`. None of that has been true since a plugin's values became Java the plugin ships: a variable
  is a `@Param` field in your own `Parameters.java`, an activity is a `public static Outcome
  body(ActivityContext ctx)` the flow names as `Collect::body`, and the flow is a value in your own
  `plugins/sdk/Sdk.java`. The steps, the order and the runtime diagram's shape are unchanged — only the
  sentences that described the old machinery.

## [1.1.8] — 2026-09-21

### Changed

- **Rebuilt against the plugin contract's new package layout.** Studio's plugin host imports
  `com.botmaker.plugin.api.slot`, `.parameters`, `.toolbar` and `.source` now. Imports only — nothing in the
  canvas, the Parameters window or the plugin loader behaves differently. A plugin built against
  `botmaker-studio-api` v0.1.5 or earlier will not load in this Studio; rebuild it against v0.1.6.

- **The walkthrough describes the bot you actually get.** Getting Started and `WORKFLOW.md` still said a
  variable's value lived in `activities.json` and was read at startup by a generated `Activities.java`, that
  Studio maintained one source file per activity plus a registry, and that a run was driven by a generated
  `FlowDriver`. None of that has been true since a plugin's values became Java the plugin ships: a variable
  is a `@Param` field in your own `Parameters.java`, an activity is a `public static Outcome
  body(ActivityContext ctx)` the flow names as `Collect::body`, and the flow is a value in your own
  `plugins/sdk/Sdk.java`. The steps, the order and the runtime diagram's shape are unchanged — only the
  sentences that described the old machinery.

## [1.1.7] — 2026-09-21

### Added

- **A value can be a record with fixed parts, not just a list or a map.** A plugin's `Flow` reads as the
  shape it is — activities, edges, start, limits — instead of as an unknown type shown read-only, which is
  what any type without angle brackets did before.

- **A plugin can give your bot a file, and it lands once.** A plugin that ships one gets it written to
  `src/main/java/<your package>/plugins/<plugin>/`, with the package filled in, the first time Studio sees
  the plugin on your project's classpath — installing it, reloading plugins, or just opening the project.
  It compiles and runs as it lands, before you have drawn anything. **It is never written over**: edit it,
  add helpers and comments to it, delete it if you like. Nothing regenerates it, and a re-install brings
  back only a file that is actually missing. One Project History entry per file written.

- **A plugin's own values are read and written in your bot's Java.** A `@Managed("id")` method in a file the
  plugin gave your project is found by its id, its type read off the declared return type, and its value
  edited through the same control a block's slot and a Parameters row use. Studio rewrites the one
  expression that method returns and nothing else: your comments, your helper methods and your formatting
  survive a save, and the `git diff` after changing a plugin's value is one hunk. A body you have edited by
  hand into something other than a single `return` is shown read-only with the reason, and never
  overwritten. Nothing uses this yet — the SDK's flow moves onto it next — and no existing project changes.

### Changed

- **Nothing in your project is "Generated by BotMaker" any more.** That group in the file tree, and the
  read-only 🔒 badge that went with it, existed for one day for a design that was withdrawn the same day.
  Studio writes no file it then owns: a plugin's file is yours from the moment it lands, and it is listed
  under *My code* with the rest of your bot. What is still refused on the canvas is one `@Managed` method's
  body — one expression, not a file.

- **What a plugin locks on the canvas is an annotation you can see.** A plugin used to name a *type*, and
  Studio locked every `static final` field of it, plus any class holding nothing but those. That guessed:
  one picture constant beside ordinary code locked the whole file. A `@Managed("id")` method or class is
  refused, and nothing else is.

- **A plugin's editor is handed the Java a value is written as.** A row of the Parameters window used to
  hand a plugin a stored string and a slot on a block used to hand it an expression, so a plugin drawing
  both had two spellings to keep in step and could only ever be offered one leaf at a time. There is one
  spelling now and a whole type tree with it, which is what lets a plugin claim a value the host has no
  control for. Visible where the SDK's own editors used to differ: a duration in the Parameters window
  commits on OK rather than as you type, and a colour the editor cannot write back leaves the swatch alone
  instead of showing white.

- **A parameter's type picker wraps instead of picking a shape.** *Shape ▸* offered four fixed choices; the
  menu is now **Wrap in ▸** — every container the installed plugins registered, `List` and `Map` included —
  plus **Unwrap**, and the button shows the Java you will get. Two containers deep is as far as it goes,
  which is a limit on the picker and not on your bot: a deeper type written by hand is still listed, shown
  and left alone.
- **A `Map` parameter is edited as a map**, in two columns with an Add row, and a key another row already
  has is marked where you typed it rather than silently dropped. A list is still rows of its own editor, a
  value with a set of choices declared is still ticks or radio buttons — and whether those appear now
  follows the choices you wrote down rather than a shape chosen beforehand, so a parameter never changes
  control because of something you cannot see.
- **A value your installed plugins cannot read is shown exactly as you wrote it**, with the reason, and
  nothing this window does will rewrite it.
- **A parameter's type badge in the Runner names the Java you wrote**, `java.util.List<Duration>` where it
  said "List of Duration". A type is a tree now and there is no prose short enough to say one truthfully; the
  Java is what is in your file, and it is what the type picker's button has said since this release.

### Added

- **A parameter can be one of your bot's own records.** `@Param public static Point origin = new Point(1, 2);`
  is listed, typed and edited component by component, and what is written back is
  `new com.example.bot.Point(…)`. Records only: an ordinary class with several constructors is shown as
  written, because which one to call would be a guess. If a component is of a type no installed plugin
  knows, the window says which component — and it never puts a placeholder into a class of yours.

- **One upgrade door, not three.** *Project ▸ Upgrade SDK…* and *Project ▸ Modernise…* are gone. Both were
  *Project ▸ Upgrade…* with one row chosen for you, and three entries made one report read as three
  operations. The SDK is a row in the upgrade table like any other plugin.
- **The upgrade window says what it is doing.** Picking a version runs that row's check by itself; every row
  carries its own state — *checking…*, *nothing breaks*, *N repairable*, *blocked* — instead of sharing one
  spinner; the status line keeps the outcome of the check instead of clearing it; and when Apply is greyed
  out, a line below it says why and what to do next.
- **The window stays open when an upgrade succeeds**, with what the pass did: the versions that moved, how
  many calls were repaired in how many files, and a button to the Review tab when your own code was
  rewritten. A removal says the same. Previously the window closed on success and the message went with it.

### Fixed

- **A plugin you install is there at once.** Adding the SDK — or any plugin — to an open project used to
  leave the statement menus empty until the project was reopened, and *Reload Plugins* did not help. The
  types a newly bound plugin brings are offered as soon as it binds.
- **One plugin that will not compose no longer costs you every other one.** Two plugins registering the same
  value type id (an SDK older than 1.1.7 beside `plugin-basics`, which is where the nine JDK types live now)
  used to silently drop *all* plugin editors — no pictures, no durations, no colours — with nothing on
  screen to say so. The plugin that clashes is left out on its own, and Manage Plugins names it, the ids and
  who already claims them.
- **Manage Plugins refuses to install a plugin another plugin already brings**, and says to change that
  plugin's version instead. Declaring it twice let Maven pin a version it was never built against.

- **One number stopped blanking a whole file.** `Duration.ofMillis(60000L)` — or any `long`, hex, binary,
  octal or `1_000` literal — was read as an `int`, threw, and left the canvas empty with no message. Numbers
  are read by Java's own rules now. A spelling a number field cannot write back (hex, `1_000`, an exponent)
  is shown exactly as you wrote it instead of being turned into a decimal.
- **A member that cannot be drawn costs that member, not the file.** The rest of the file draws, and the
  status line names what was left out.
- **A field's value gets its real editor.** `static final ImageTemplate COLLECT = new ImageTemplate(…)` shows
  the picture editor, and a `Duration` field its duration editor, where both used to be a generic blue
  *Create …* block. Class fields now ask the same editors a local variable and a call argument do.

### Changed

- **The download page looks like the rest of the project, and its commands copy.** The stylesheet and the
  copy button come from `botmakerdev.github.io/assets/`, the organization's front page, rather than from a
  `<style>` block repeated in four repositories, and the page links to that front page — where one command
  installs every BotMaker tool at once. The installers, the repository and the signing key are untouched.
- **Parameters and plugin-managed constants are shown read-only.** Everything in `Parameters.java`, every
  `@Param` field wherever it lives, and every constant a plugin manages (the SDK's pictures) draws with its
  blocks and its picker — as a preview — and refuses edits, naming the window that owns it. A picker that
  opened a chooser and then had its write refused is gone.

- **An edit on the canvas changes only what it edits.** Every edit used to re-lay-out the whole file, so
  adding one statement could rewrite `private Collect() {}` three methods away. Now only the lines the edit
  wrote are laid out, and everything else stays exactly as you wrote it.
- **What Studio writes is indented with four spaces**, like the rest of the file, not with a tab.
- **A public parameter is written `visibility = Param.PUBLIC`**, the annotation's own constant, not the
  string `"public"`, so a field the Parameters window added reads like the ones beside it.

### Changed

- **The title bar and the Parameters window name the project's directory** (`~/IdeaProjects/gamebot`), not
  only its name. Two copies of one template are common, and an edit that seems lost is usually in the other
  copy.

## [1.1.6] — 2026-09-19

### Fixed

- **One number stopped blanking a whole file.** `Duration.ofMillis(60000L)` — or any `long`, hex, binary,
  octal or `1_000` literal — was read as an `int`, threw, and left the canvas empty with no message. Numbers
  are read by Java's own rules now. A spelling a number field cannot write back (hex, `1_000`, an exponent)
  is shown exactly as you wrote it instead of being turned into a decimal.
- **A member that cannot be drawn costs that member, not the file.** The rest of the file draws, and the
  status line names what was left out.
- **A field's value gets its real editor.** `static final ImageTemplate COLLECT = new ImageTemplate(…)` shows
  the picture editor, and a `Duration` field its duration editor, where both used to be a generic blue
  *Create …* block. Class fields now ask the same editors a local variable and a call argument do.

### Changed

- **The download page looks like the rest of the project, and its commands copy.** The stylesheet and the
  copy button come from `botmakerdev.github.io/assets/`, the organization's front page, rather than from a
  `<style>` block repeated in four repositories, and the page links to that front page — where one command
  installs every BotMaker tool at once. The installers, the repository and the signing key are untouched.
- **Parameters and plugin-managed constants are shown read-only.** Everything in `Parameters.java`, every
  `@Param` field wherever it lives, and every constant a plugin manages (the SDK's pictures) draws with its
  blocks and its picker — as a preview — and refuses edits, naming the window that owns it. A picker that
  opened a chooser and then had its write refused is gone.

- **An edit on the canvas changes only what it edits.** Every edit used to re-lay-out the whole file, so
  adding one statement could rewrite `private Collect() {}` three methods away. Now only the lines the edit
  wrote are laid out, and everything else stays exactly as you wrote it.
- **What Studio writes is indented with four spaces**, like the rest of the file, not with a tab.
- **A public parameter is written `visibility = Param.PUBLIC`**, the annotation's own constant, not the
  string `"public"`, so a field the Parameters window added reads like the ones beside it.

### Changed

- **The title bar and the Parameters window name the project's directory** (`~/IdeaProjects/gamebot`), not
  only its name. Two copies of one template are common, and an edit that seems lost is usually in the other
  copy.

## [1.1.5] — 2026-09-19

### Changed

- **Studio talks to the `BotMakerDev` organization.** The updater, the CLI update check, Help ▸ Report
  Issue and the Help menu's repository links follow the repositories that moved on 2026-09-18. The Linux
  install script and package repository are at `botmakerdev.github.io/botmaker-studio`.

### Fixed

- **Publishing to the gallery as its maintainer commits directly again.** It compared your login with the
  gallery's owner, which is an organization since the move, so the maintainer would have been sent down the
  fork path for a repository they cannot fork.

## [1.1.4] — 2026-09-18

### Added

- **Open a project from any folder.** *Open Folder…* on the project screen opens a Maven project wherever
  you keep it — your own repository, for instance — and it is listed under **Elsewhere** from then on. A
  template that declares its package in `botmaker-template.properties` opens in that package, whatever its
  folder is called. `--project=` also takes a path now; a bare name still means `~/BotMakerProjects/<name>`.

### Changed

- **Browse Bots, New Project's templates and Manage Plugins keep working when the gallery and the plugin
  registry move** to the `BotMakerDev` organization. Studio reads the new location first and the current one
  second, so it needs no update on the day they move.
- **Studio moves only the projects it keeps.** *Archive* works as before for a project in
  `~/BotMakerProjects`. For a project opened from elsewhere the button reads *Remove from Recents*, and it
  forgets the project without touching its folder.

## [1.1.3] — 2026-09-18

No source changes since v1.1.2; re-released for updated upstream pins.

### Added

- **Studio reads the parameters a bot declares in its own Java.** A `@Param` field is found wherever the
  author wrote it, with its type, its value, its category, its description, its bounds and its choices, and
  it can be added, renamed, retyped, re-annotated and removed from the editor — every edit landing in the
  source file as a one-line diff, leaving the author's own formatting and comments where they are. A field
  the editor cannot safely rewrite (not `public static`, `final`, a type no plugin registers, or a value
  written by hand as an expression) is still listed and still shows what it holds, with a line saying why
  its cell is read-only.
- **The Parameters window and the Runner are over those fields now.** One section per class of yours that
  declares any — headed with the file name, saying the bot reads them as `Parameters.<name>` — then one per
  plugin. Adding a parameter writes a field, and a project's first one creates `Parameters.java` and says
  so; renaming one repoints every reference to it in your bot; retyping one rewrites the declared type and
  resets the value, because a value written for one type is not a value of another. Removing one takes the
  declaration and **leaves the places your bot reads it**, with a line saying how many there are: what a
  use should become is your judgement, and the compiler is what points at them.
- **A plugin's section is value-only.** Its rows' names, types, categories and notes are shown as the
  plugin's own facts rather than as fields you can type into — because they are, and a text box you may not
  type in reads as something broken.
- The value cell is the same editor the canvas uses, a plugin's included, so a colour is picked off the
  screen and a duration is entered as hours and minutes wherever you meet one.
- **The categories on the left are yours.** They are whatever your `@Param` fields say, so the rail lists
  what you actually filed something under — the SDK's six fixed ones (Timing, Targets, Vision, Input,
  Limits, Debug) are gone, and an empty project no longer offers six drawers with nothing in them.

### Fixed

- **No more warnings at startup, at project open, or when a bot runs.** The JVM's "restricted method" notice
  named `javafx.graphics` as well as the unnamed module, and ClassGraph's cleaner call needed
  `--sun-misc-unsafe-memory-access=allow`; both are now in the run plugin, the installer and the jar's
  manifest. Opening a project printed six resolver warnings per module about JavaFX's published poms —
  silenced to `error`, where a resolution that really fails still reports. A bot's own JVM grants native
  access too, so OpenCV no longer warns in the output pane the moment a bot starts.

### Added

- **Vetted and Community bots.** Browse Bots shows each bot's tier as a badge and filters by it. A Vetted bot
  is one a maintainer looked at, and installing it downloads the release that was looked at. A Community bot
  was listed automatically, and the install warning says nobody reviewed its code.
- **Open on GitHub** on every Browse Bots row, and a *Requires* line naming the plugins a bot declares.
- **New Project lists Vetted templates.** *Show community templates* adds the rest, and appears only when there
  are any.
- **A new Publish window.** One page in sections — Kind (bot or template), Listing, Details, Release — beside a
  live preview of the Browse Bots card, a checklist of the publish's five steps, and your listing as the gallery
  has it: tier, pull request, whether it is waiting or needs a maintainer, and Unpublish.
- **A failed publish resumes.** *Retry* continues from the step that failed; the repository, upload and release
  already done are not repeated.
- **Listing no longer waits for a maintainer.** The entry goes to the gallery as a pull request its checks merge
  by themselves, and it names the plugins your bot requires. Re-publishing updates your listing; it used to say
  "already listed" and change nothing.

### Changed

- **An installed Vetted bot is offered its vetted release**, never an older one than it has. Everything else
  is offered its newest release, as before.
- Studio reads the gallery's `catalog.json`, and falls back to `index.json` when it cannot.

## [1.1.2] — 2026-09-17

### Added

- **Studio reads the parameters a bot declares in its own Java.** A `@Param` field is found wherever the
  author wrote it, with its type, its value, its category, its description, its bounds and its choices, and
  it can be added, renamed, retyped, re-annotated and removed from the editor — every edit landing in the
  source file as a one-line diff, leaving the author's own formatting and comments where they are. A field
  the editor cannot safely rewrite (not `public static`, `final`, a type no plugin registers, or a value
  written by hand as an expression) is still listed and still shows what it holds, with a line saying why
  its cell is read-only.
- **The Parameters window and the Runner are over those fields now.** One section per class of yours that
  declares any — headed with the file name, saying the bot reads them as `Parameters.<name>` — then one per
  plugin. Adding a parameter writes a field, and a project's first one creates `Parameters.java` and says
  so; renaming one repoints every reference to it in your bot; retyping one rewrites the declared type and
  resets the value, because a value written for one type is not a value of another. Removing one takes the
  declaration and **leaves the places your bot reads it**, with a line saying how many there are: what a
  use should become is your judgement, and the compiler is what points at them.
- **A plugin's section is value-only.** Its rows' names, types, categories and notes are shown as the
  plugin's own facts rather than as fields you can type into — because they are, and a text box you may not
  type in reads as something broken.
- The value cell is the same editor the canvas uses, a plugin's included, so a colour is picked off the
  screen and a duration is entered as hours and minutes wherever you meet one.
- **The categories on the left are yours.** They are whatever your `@Param` fields say, so the rail lists
  what you actually filed something under — the SDK's six fixed ones (Timing, Targets, Vision, Input,
  Limits, Debug) are gone, and an empty project no longer offers six drawers with nothing in them.

### Fixed

- **No more warnings at startup, at project open, or when a bot runs.** The JVM's "restricted method" notice
  named `javafx.graphics` as well as the unnamed module, and ClassGraph's cleaner call needed
  `--sun-misc-unsafe-memory-access=allow`; both are now in the run plugin, the installer and the jar's
  manifest. Opening a project printed six resolver warnings per module about JavaFX's published poms —
  silenced to `error`, where a resolution that really fails still reports. A bot's own JVM grants native
  access too, so OpenCV no longer warns in the output pane the moment a bot starts.

### Added

- **Vetted and Community bots.** Browse Bots shows each bot's tier as a badge and filters by it. A Vetted bot
  is one a maintainer looked at, and installing it downloads the release that was looked at. A Community bot
  was listed automatically, and the install warning says nobody reviewed its code.
- **Open on GitHub** on every Browse Bots row, and a *Requires* line naming the plugins a bot declares.
- **New Project lists Vetted templates.** *Show community templates* adds the rest, and appears only when there
  are any.
- **A new Publish window.** One page in sections — Kind (bot or template), Listing, Details, Release — beside a
  live preview of the Browse Bots card, a checklist of the publish's five steps, and your listing as the gallery
  has it: tier, pull request, whether it is waiting or needs a maintainer, and Unpublish.
- **A failed publish resumes.** *Retry* continues from the step that failed; the repository, upload and release
  already done are not repeated.
- **Listing no longer waits for a maintainer.** The entry goes to the gallery as a pull request its checks merge
  by themselves, and it names the plugins your bot requires. Re-publishing updates your listing; it used to say
  "already listed" and change nothing.

### Changed

- **An installed Vetted bot is offered its vetted release**, never an older one than it has. Everything else
  is offered its newest release, as before.
- Studio reads the gallery's `catalog.json`, and falls back to `index.json` when it cannot.

## [1.1.1] — 2026-09-16

No `## [Unreleased]` section exists — v1.1.0 is HEAD, tagged, no commits since. Nothing to write; an Unreleased section would be empty.

## [1.1.0] — 2026-09-16

### Added

- **A block may declare how it is rendered, and two surfaces read the declaration.**
  `CodeBlock.componentSpec(context)` answers a `ComponentSpec` — the labels, slots, pickers and bodies a block
  is made of, each as a supplier of its widget — and the overlay editor's `CompactSpecRow` draws it at HUD
  density, one line inside a 340px panel, while the canvas draws the same components through its own layout
  builder. Visibility and the lock are `ComponentResolver`'s on both surfaces, so a component hidden on one
  cannot appear on the other; a body is dropped in the HUD, because the tree already shows branches as nested
  rows. The default is an empty spec, and a block that declares none renders exactly as it did before — which
  is what lets this land one block at a time. **Observably a no-op so far: no block declares a spec yet.**

- **A call declares its sentence, argument by argument.** `MethodInvocationBlock` — the SDK badge, the scope,
  the method, the ⚙ overload picker, one component per argument with its ✕, the picture-run row, the varargs
  ＋, the `→ Type` badge and the ⓘ — is now a declaration rather than 130 lines of assembly, so the overlay
  editor can draw a call's arguments in the row instead of only as text. Each argument has a stable id
  (`arg0`, `arg1`, …) that survives a re-parse, which is what lets the HUD keep focus on the argument being
  edited while the block behind it is rebuilt. Everything the declaration needs — the scope selector, the
  method list, the resolved overload — is resolved once per render and only when something actually draws, so
  describing a call costs no classpath lookup. The call config popover keeps its job: a 340px row has no space
  for a parameter list or for a plugin's gallery, and that is a question about size rather than capability.
- **The control-flow blocks declare their shape too, bodies included.** `while`, `do/while`, `for each` and
  the assignment block answer `componentSpec`, which is what makes a body a *declared* part of a block rather
  than something each surface guesses at: the canvas breaks the block into rows at each body and draws it
  between them, the overlay HUD drops it and draws one line, because the HUD's tree already shows branches as
  nested rows. `do/while` is the case that proves the rule — its body is declared between two rows, so the
  closing `while (…)` lands under the body exactly as it always has.
- **Print and Return declare their sentence, and both surfaces read the declaration.** The first two blocks to
  answer `componentSpec` — a print is the word, one slot per argument and the ⊕; a return is the keyword plus
  whichever of its three tails applies (a value with its change button, a missing value with its ⊕, or the
  `(void)` note). Nothing about what is drawn changes: the canvas builds the same nodes it always did, through
  the same factories, because the sentence layout's keyword, label and slot construction is now called by both
  it and the schema instead of living in one of them. What is new is that the overlay HUD can draw those parts
  at its own density rather than falling back to a line of source text.
- `CodeEditorService.audience()` — one derivation of who the session is drawing for, with the canvas and the
  HUD as its readers. Reader mode is a live toggle, so it is read at render time and never captured.

- **Right-click a value to choose which plugin's editor draws it.** Two plugins may honestly both have an
  editor for a rectangle, and until now the winner was whichever one `ServiceLoader` reached first —
  silently, and in an order nothing guaranteed. The host collects every claimant and offers *Edit with ▸* on
  the slot; the verdict is saved per project in `settings.json` as `preferredEditors`, keyed on the fully
  qualified Java type so a choice made on a block also applies to that type's row in the Parameters window
  and to the pictures beside its declared choices. A verdict naming an uninstalled plugin is inert. Nothing
  crosses the contract — there is no priority a plugin can declare for itself, because the first one to write
  a big number would win forever. **The menu is on the canvas only**: persisting a verdict needs the
  project's settings service, and the Parameters window's editors are handed a `ProjectConfig` alone, so that
  window honours the verdict without being able to set one.
- `CodeEditorService.rerenderActiveFile()` — draws the open file's blocks again from the text already in
  memory, for the changes that alter *how* a block is drawn rather than what it says. Two callers: a re-bound
  plugin set, and an editor verdict.
- **The overlay editor draws a plugin row.** Every `ToolbarGroup.OVERLAY` item, built by the same
  `ToolbarItems` the main bar uses — so a plugin's button on the HUD is styled, tooltipped, icon-boxed and
  guarded identically. At 340px a second renderer would not merely look different, it would size differently
  and push the rest of the row off the panel.
- `PluginHost.itemsIn(ToolbarGroup)` — one filter with two readers, the main bar (every group but `OVERLAY`)
  and the HUD (only that one). A group nobody reads is an item silently absent, which is what
  `ToolbarMergeTest` exists to catch.
- `HostOverlayContext` — `HostActionContext` plus the three live facts an overlay item is handed: the title of
  the window the HUD is drawn over, where that window sits right now, and the editor's insertion cursor. All
  three are suppliers, because the window moves and the cursor changes while the HUD is up.
- **Right-click either toolbar to hide a group.** Six checkboxes, one per `ToolbarGroup`, saved per project in
  `settings.json` as `hiddenToolbarGroups`. It hides Studio's own items in that group as well as any
  plugin's — a group whose plugin buttons vanish while the host's remain is a group that lies about what it
  holds — and it is not an overflow policy: `OverflowBar` already answers *no room right now*, this answers
  *never*, and survives a resize. The set names what is **hidden**, so a group a later release adds shows up
  rather than being absent from every project written before it existed, and an enum name this Studio does
  not know is ignored rather than costing the user the rest of their list. The overlay HUD's row carries the
  same menu, because the HUD has no menu bar to get a group back from.
- **Recorded actions can land at the overlay's cursor again.** `ActionContext.insertAtCursor` is served here
  through `CodeEditor.pasteCode`, so an inserted statement brings its imports and goes through the same
  rewrite, undo entry and diagnostics refresh as any other edit. The loss recorded when the macro recorder
  became a plugin was accepted because a capability shaped to one caller is a back door; what answers that is
  a row several plugins can contribute to, not a better argument.

### Changed

- `PluginHost` remembers which plugin each slot editor came from (`ownedSlotEditors()`). `slotEditors()` is
  unchanged and still answers the flat list — derived from the owned one, so the two cannot disagree.

- **The overlay editor asks which window to draw over.** When no private session is live it offers every open
  window first, in the window manager's own order, then any remembered title not already there. **Nothing is
  persisted:** which window the *editor* draws on lasts as long as the HUD, while what the *bot* watches stays
  the owning plugin's `capture.json` — two facts with one shape, held by the two owners that actually have an
  opinion about them, rather than one fact spelled twice.

### Removed

- **The Wait block is the plugin's, so Studio no longer has one.** `blocks/flow/WaitBlock`, the
  `BlockConverter` arm that recognised `try { Thread.sleep(n) } catch (InterruptedException e)`, the palette's
  `Kind.WAIT` and the `StatementFactory` branch behind it are deleted. The hand-written Wait *entry* went on
  2026-09-01 with every other palette entry naming a plugin's type; what was left was the editor still
  recognising a raw-sleep spelling and captioning it `Wait … ms` — a second, host-flavoured way to say a word
  the SDK owns, under labels Studio invented. A wait written the plugin's way (`Wait.time(…)`) draws as an
  ordinary facade call with the plugin's own editors, as it already did. **The cost, plainly:** a bot whose
  source holds a hand-written `Thread.sleep` no longer shows that statement on the canvas — the same as every
  other `try`, which this editor has never drawn. The source text is untouched, and it still runs. It also
  removes the one place in the editor that ever wrote a `printStackTrace` into a user's bot.

- **Studio holds no capture target.** `services/capture/CaptureTarget`,
  `ScreenCaptureService.defaultTarget()`/`captureDefaultTargetAsync`/`forProjectFiles` and `TargetCapture`'s
  default-target supplier, its `ProjectSettingsService` constructor, its monitor/desktop/emulator branches
  and its ADB frame are all gone. The record was field-for-field the owning plugin's own capture-target
  model, and every one of those readers had answered "no target" since Studio stopped reading `capture.json`
  on 2026-08-31. The overlay editor resolves its own window live, and the *bot's* target is written by the
  plugin from the overlay's own row, so there is nothing left for a second spelling to hold. What Studio's
  capture service still does is what a caller with a window in hand asks for — raise, resize, capture,
  bounds, the title list — plus the screen, which a pick over several monitors now always asks about.

### Fixed

- **Opening the overlay editor with nothing to draw over is no longer a dead end.** It read the project's
  configured capture target, which Studio stopped reading on 2026-08-31 — so
  `ScreenCaptureService.defaultTarget()` had answered `null` for every caller since, and the HUD could only
  open over a live private session. It asks now. `ProgramShapeOverlay.open`'s `chooseTarget` parameter is gone
  with it: the Launch Target dialog it existed to call left for the SDK plugin on 2026-09-01, so its one
  caller had been passing `null` for eleven days. What replaces the affordance is better than what it lost —
  the dialog arranged a *launch*, while the question is which of the windows already open the user means, and
  a window they opened by hand was never reachable through it at all.

- **A plugin that will not load is now said out loud, in Manage Plugins.** When a plugin on a project's
  classpath cannot be loaded — most often because its own dependency is missing — Studio carries on with the
  plugins that did load, which is right: a project must open. But the only trace was one line in a log
  nobody reads, so *this project has no plugins* and *this project has a broken plugin* looked identical: an
  empty palette. The dialog now names each plugin that failed and why, and says plainly that the project
  itself is unaffected.

- **One broken plugin no longer hides the others.** A project with two plugins, the first of which failed to
  load, got **neither**. Each is loaded independently now.

### Changed

- **Installing a plugin declares what *that plugin* says it needs — not what Studio knew about one of
  them.** Some plugins need a library in the editor that they do not carry into your bot: the SDK's Remote
  Pilot needs a web server and a QR encoder, and a bot that never opens the pilot must not download either.
  Studio used to carry that list in its own code, for the SDK alone, so any other plugin needing the same
  thing simply did not work. The list is part of a plugin's registry entry now, Studio reads it there, and
  removing the plugin takes it back out. Nothing changes for the SDK: the same two libraries are declared,
  in the same place, at the same scope.

  Two consequences worth knowing. A plugin you built locally that the registry has never seen is installed
  on its own, because there is no entry to read a list from — publish it, or add the library through Manage
  Libraries. And **File ▸ Recover Project Files** rebuilds a lost `pom.xml` from what is on disk, which
  cannot include that list; a repaired project keeps its plugins and one visit to Manage Plugins puts back
  what they need in the editor.

- **A new project's `pom.xml` no longer declares the plugin toolkit or JavaFX.** Three dependencies left the
  generated list; two of them were downloads nothing ever used, and the third could break the editor. The
  SDK's own pom declares `botmaker-plugin-toolkit` normally, so it arrives with the SDK — and Maven prefers
  the *nearest* declaration, which meant a version written into your pom by whichever Studio created the
  project won over the one the SDK you pinned was actually built with. An SDK newer than that pin then hit
  `NoSuchMethodError` in the editor. JavaFX was never resolved from a project at all: Studio hands every
  plugin its own. What a project still declares for the SDK plugin is Javalin and ZXing, the two the SDK
  genuinely marks optional and nothing else supplies.

  **Existing projects are untouched and keep working.** The three entries stay recognised as built in, so
  Manage Libraries will not offer to delete them and editing your libraries will not drop them. Removing
  them from an older project is safe but not necessary.

### Fixed

- **A new project pins SDK 1.1.6, which is what 1.0.37 said it did.** That release's notes announced the
  pin and the constant behind it never moved — it still read `1.1.5`, the one SDK whose editor-side half
  cannot start on a machine without JavaFX. Projects created by 1.0.37 are unaffected in Studio itself,
  which supplies JavaFX either way; what they could not do is pass the plugin registry's own check.
  Existing projects are untouched — this is only what a *freshly created* pom declares, and
  **Project ▸ Manage Libraries** changes it whenever you like.

### Changed

- **The GitHub layer is `botmaker-shared`'s.** `GitHubClient`, `GitHubAuth`, `GitHubConfig` and `SemVer` are
  `com.botmaker.shared.github`; Studio changed import lines and nothing else. Sign-in, publishing, the
  gallery, the plugin registry and both updaters behave exactly as before. The move is for the coming
  operator GUI, which reads the same repositories and must not carry a second copy of the OAuth device flow.

## [1.0.37] — 2026-09-05

### Fixed

- **A plugin you install works straight away — no restart.** Its toolbar buttons appear as soon as the
  install finishes, and the blocks already on screen pick up the editors it brings. Removing one takes them
  away again the same way.
- **Installing the SDK from *Project ▸ Manage Plugins* now gives you a working palette.** It added the SDK
  and nothing else, and the SDK's editor-side pieces — the widget toolkit, JavaFX, the Pilot's server —
  are marked so that they do not travel with it. So the plugin could not load: no menu entries, no slot
  editors, no plugin toolbar buttons, and nothing on screen saying why. Installing declares those pieces
  now, and removing the plugin takes them back out.
- **The Install button tells the truth.** A plugin your project already has reads **Remove**, and pressing
  Install twice can no longer leave two copies of it in your `pom.xml`.
- ***Manage Libraries* no longer lists a BotMaker SDK your project does not have.** A blank project showed
  an SDK row with an empty version and offered to change it.

### Added

- **The `botmaker` command comes with Studio on Linux, and Studio keeps it current.** The `.deb` and `.rpm`
  now require it, so installing Studio installs the command-line tool from the same signed repository; and
  **Help ▸ Update Command-Line Tool…** checks its latest release and upgrades it through your package
  manager, behind one authorisation prompt that shows you the command first. Studio's own version does not
  have to move for a newer command to be offered. Note that a package file downloaded and installed **by
  hand**, without that repository configured, will now report an unresolved `botmaker` dependency — register
  the repository (`packaging/linux/install.sh`) or install `botmaker` first. Windows and macOS are unchanged
  and show no such menu entry.

### Changed

- ***New Project ▸ Start from* leads with the published templates.** *Base* was doing the same job as
  Studio's built-in *Blank*, so the two are no longer listed side by side: the templates replace *Blank*
  when the gallery can be reached, and *Blank* is what you get when it cannot — so creating a project still
  works with no network. Template names are shown capitalized.

### Removed

- **🗂 Resources and *Project ▸ Resource Manager…* are gone.** Both opened nothing — the window moved to
  🖼 **Manage Pictures** on the toolbar in 1.0.32, and these two were left behind pointing at nowhere. Use
  🖼 Manage Pictures; it does everything the old window did.

### Changed

- **A newly created project pins SDK 1.1.6**, which is the first release whose plugin half loads when the SDK
  is added to a project as a dependency — the arrangement every project has used since Studio stopped
  bundling a plugin. Nothing in Studio itself changed; see `botmaker-sdk`'s changelog for what the empty
  palette was.

## [1.0.36] — 2026-09-05

### Fixed

- **A new project pins an SDK that exists — third attempt.** `1.0.35` pointed at SDK `1.1.4`, whose own
  JitPack build then failed for a third distinct reason: an upstream module pinned a Maven plugin whose
  declared prerequisite is newer than the Maven JitPack runs, so it published nothing. This release points
  the constant at a tag that resolves. Nothing in Studio itself changed.

## [1.0.35] — 2026-09-04

### Fixed

- **A new project pins an SDK that exists.** `1.0.34` moved those constants to versions whose JitPack
  builds then failed for a second reason (the upstream compiler pin named a plugin version JitPack's own
  Maven is too old to run), so a bot created by it still could not resolve its SDK on a first build. This
  release points them at tags that are published. Nothing in Studio itself changed.

## [1.0.34] — 2026-09-04

### Fixed

- **A new project pins an SDK and a toolkit that can actually be downloaded.** Nothing in Studio itself
  changed. What changed is upstream: every BotMaker library published since 2026-09-02 was unbuildable on
  JitPack (their poms did not pin `maven-compiler-plugin`, so JitPack's default 3.1 built them with
  `source 5`), so a bot created by Studio 1.0.33 pinned an SDK that failed to resolve on the first build.
  This release moves the two version constants a generated pom carries to tags that exist.

- **Installing the SDK through *Project ▸ Manage Plugins* gives you a working palette.** Also an upstream
  fix — the SDK declared its widget toolkit `optional`, so a project that added the SDK got the plugin
  without the classes it extends and Studio loaded no plugin at all. See the SDK's own changelog.

## [1.0.33] — 2026-09-02

- **Studio runs on Java 25 (LTS) and JavaFX 25.0.4.** If you installed Studio from a release, nothing is
  asked of you: the installers bundle their own runtime, and jpackage now bundles a 25 one. If you build from
  source you need a JDK 25 or newer. The bots you create are unaffected by this — what they compile against
  is whichever SDK version their own pom pins.

## [1.0.32] — 2026-09-02

- **New projects declare what the editor needs to show you the SDK's own screens.** A generated `pom.xml`
  now lists the widget toolkit, JavaFX, the pilot's server and its QR encoder at `provided` scope. They are
  there so the editor can draw the SDK's pickers and open the Remote Pilot; `provided` means your *bot*
  still does not link any of them, so a headless bot is unchanged and downloads nothing extra at run time.
  Without them the 🎮 Pilot button failed with a missing class, and the SDK's slot editors were silently
  absent.
- **Studio ships no plugins of its own, and asks your project which ones it has.** The editor no longer
  carries a copy of the SDK. Every menu, value type and toolbar button you see comes from the plugins your
  open project actually resolves — so what you are offered is what your bot can really call, and a project
  pinning an older SDK is offered that SDK. With **no** project open there is nothing to ask, so type names
  are not recognised and plugin buttons are absent until you open one.
- **The Resource Manager is the SDK's 🖼 Manage Pictures now.** The window that renames, retags, replaces,
  deletes, imports and exports your bot's pictures moved out of the editor and onto the toolbar, beside ✂
  Capture Templates. It does everything it did, including rewriting the blocks that name a picture you rename
  or delete — that half never left. What left is the half that knew what a picture is called.
- **A plugin can rename something it owns and carry your code with it.** Whatever a plugin names in your bot's
  source, it can now find and repoint through the editor, in your open buffers as well as on disk, with the
  project snapshotted to Project History first and the changed functions marked for review when the meaning
  moved. It reaches files that do not currently compile, which is exactly the file a half-finished rename
  leaves behind.

## [1.0.31] — 2026-08-24

- **The files BotMaker generates for you come from the SDK your project pins, not from Studio.** Studio fills
  in what is true about your project — your activities, your flow, your stored parameters — and the SDK owns
  everything else. Two things follow for you: a generated file is written in the idiom of *your* SDK rather
  than of the Studio that happened to create the project, and a Studio older than your SDK still produces
  files that compile, because anything it does not recognise stays at the SDK's own default.
- **`FlowDriver` and `Activities` hold your project's data and nothing else.** The walk loop, the step budget
  and ~150 lines of parameter-parsing code are the SDK's now. Your two knobs, `MAX_STEPS` and
  `STEP_DELAY_MS`, are still in `FlowDriver` where you left them.
- **A duration reads the same in the editor and in the bot.** `1h30m` was parsed by two separate
  implementations — one in Studio, one written into the generated code — that nothing could compare. There is
  one now, and it is the SDK's.
- **Studio requires SDK 1.1.0 or newer to write a generated file.** An older bot still opens, and every file
  in it stays editable, buildable and runnable — but saving the Activity Flow, and *Recover Project Files*,
  ask you to run *Project ▸ Upgrade SDK…* first, by name and with nothing changed on disk. The upgrade
  re-renders `FlowDriver` and `Activities` in the new shape and does not touch your stubs, `GoHome` or
  `Popups`. New projects pin 1.1.0.
- ***Project ▸ Upgrade SDK…* opens with what the release gives you, not with what it costs.** The SDK ships
  its own `CHANGELOG.md` inside its jar, so the dialog shows every release you are moving through — newest
  first, in the author's words — above the cost sections. The exhaustive API diff is still there, below it.
- ***Project ▸ Upgrade SDK…* leads with what the release costs *your* bot, with file and line** — and now
  also with what the SDK's author said about each move, word for word, out of the `@ReplacedBy` / `@Replaces`
  pointers the SDK carries in its jar. A redirect that keeps its shape but changes what it does is marked for
  review instead of shipping silently, and additions are grouped by the version that introduced them.
- **A member that became two now asks you which one you meant, per call site.** `scroll(3)` and `scroll(-3)`
  in one bot want different answers, so the dialog lists every call with its own combo — already answered, so
  you can accept the whole thing untouched.
- **The palette is curated.** Studio offers the members the SDK's `@Palette` names, in the statement menu, the
  expression menu, both search views, the ⚙ overload picker and the method-name dropdown. A bot already using
  a member that is no longer proposed still renders, still resolves and still compiles — filtering applies to
  what is *offered*, never to what is *resolved*. A bot pinned to an SDK that predates `@Palette` sees exactly
  the menus it saw before.
- **Existing projects survive the SDK's package reorganisation**: `api.*` imports are repointed on open rather
  than opening as a wall of red.
- **Upgrading no longer stops half-way because of a file Studio wrote.** The generated files — `Activities`,
  `ActivityRegistry`, `FlowDriver`, `Templates` — are re-rendered against the new SDK after the version moves,
  using the same pointers that repair your own code, so a release that renames something they use goes
  through instead of refusing. Where the move genuinely cannot be expressed, the report says so **at the top,
  before you start**, rather than failing part-way through.
- **A new project is never created against an SDK it cannot compile against.** Studio checks the version you
  picked before it writes a single file, and says which member is missing so you can choose another version —
  instead of leaving a broken project behind.
- **Saving the Activity Flow is all-or-nothing.** Every generated file is produced and verified in memory
  before any of them is written, so a flow edit can no longer leave three files updated and a fourth stale.
  If it cannot be done at all, nothing on disk changes and the editor tells you which SDK member is the
  reason. (Reachable only on an SDK newer than your Studio; the fix is to update Studio.)
- A type the bot only *writes* (a field, a parameter, a cast, a type argument) is now seen by the upgrade
  report, and a defaulted value says what type it is.

## [1.0.30] — 2026-08-22

- Re-tagged against a new SDK. No source change.

## [1.0.29] — 2026-08-22

- Documentation only.

## [1.0.28] — 2026-08-22

- **The Linux packages install cleanly and are less than half the size**: the bundled runtime is jlinked
  rather than copied whole (rpm 241 → 126 MB, deb similarly), other platforms' natives are gone, and rpm/deb
  declare the GUI stack they actually need.

## [1.0.27] — 2026-08-21

- **One-command install** on Linux (`packaging/linux/install.sh`) instead of registering the repository by
  hand.

## [1.0.26] — 2026-08-21

- Re-tagged against a new SDK. No source change.

## [1.0.25] — 2026-08-21

- **Studio installs and updates from a signed dnf/apt repository**, so updates arrive through
  `dnf upgrade` / `apt upgrade` rather than a visit to the Releases page. An RPM upgrade no longer deletes the
  application menu entry.
- **Refactoring across files**: a signature you cannot change behind the project's back, a result type the
  body agrees with, every picker on one screen, and undo that spans files.
- The release is built from source at pinned upstream refs rather than from JitPack.

## [1.0.24] — 2026-08-04

- **A selectable UI theme** — Default, Dark, Black, High Contrast — from the View menu.

## [1.0.23] — 2026-08-02

- **The Activity Flow arranges itself**, and a new activity gets a dialog instead of a blank file.
- **The overlay draws over a live private session** and names the activity it records into; object capture
  zooms.
- **An eyedropper**, and one `Precision` editor that hides the knobs a given call cannot use.
- The bot's tuning became project settings rather than a generated `BotSettings.java`.

## Earlier

v1.0.22 and below predate this file. `ROADMAP.md` has the dated log.
