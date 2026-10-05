# Capture, and the editors and pictures that moved to the SDK plugin

**Capture is two halves, and `ScreenCaptureService` is scaffolding between them (2026-08-30).** It was 1,324
lines in which resolving *which pixels* and deciding *what the user does with them* were one flow. They are
now `services/capture/`:

- **`TargetCapture`** — which pixels. The project's default capture target, window raise/focus/resize/bounds,
  the emulator's ADB frame, the desktop-crop fallback for a blank Wayland grab, the window-title list. It is
  the half that **leaves for the SDK plugin**: a window to look at is what a bot's own `CaptureSource` names,
  so the vocabulary is the SDK's rather than an editor's.
- **`ScreenOverlay`** — what the user does with them. The full-screen surface, the rubber band, the magnifier,
  the colour readout, the screen chooser, the blank-grab warning, the multi-pick session. It **names no
  capture target and nothing in `com.botmaker.shared`** — check that with a grep before adding to it, because
  it is what stays behind as the implementation of the contract's `StudioServices.capture()`.
- **`ScreenShot` is the seam** — pixels, bounds, `fullScreen`, `blank` — and `ShotSource` is the whole of what
  the overlay needs from the target half: `grab(owner)` and `title()`. Two methods, measured rather than
  guessed: a survey of the old class found exactly two places where the overlay flow touched a target.
  `Screens` holds the monitor arithmetic both halves genuinely need.

So **add nothing to `ScreenCaptureService`.** New overlay behaviour goes on `ScreenOverlay`; anything that
has to know what a capture target is goes on `TargetCapture`. The façade exists so the split cost no call
site a change, and it disappears when the target half moves.

**One question this split raised and did not answer:** the contract's `Capture.grabFrame` is documented as
*"one frame of whatever the host is configured to look at"*, and `HostServices` serves it by calling
`captureDefaultTargetAsync` — capture-**target** vocabulary reaching the contract. After the move the host
has no targets, so either that member goes or the host keeps a default-target notion it has no other use
for. **The maintainer settled it on 2026-08-30: the SDK owns capture targets and stores them itself**, so
`grabFrame` goes when the last host caller does. The step below is the first half of that.

**A capture target is the SDK's `CaptureTargetModel`, and Studio has no shape of its own for one
(2026-08-30).** `project/capture/{CaptureTarget,CaptureTargets,CaptureTargetNames}` are deleted: four sealed
records, the adapter that mapped them onto the spec grammar, and a label table. Roughly 180 references
across 20 files now name `com.botmaker.sdk.authoring.CaptureTargetModel` directly, which is where the
targets have actually been **stored** since earlier that day (`capture.json`, through `Authoring`).

Three things are worth knowing before touching any of it.

- **The store did not move; the vocabulary did.** `StudioProjectSettings` already wrote `capture.json` and
  read it back through a converter. Deleting the converter is the whole change, and it is what makes the
  editor and the running bot incapable of disagreeing about which window to look at — there is no second
  spelling left to drift.
- **`TargetCapture.WindowRef` is what did not move, and it is not an oversight.** A window plus an optional
  **live** native handle. A stored target's identity is its spec text and a handle is meaningless once
  persisted; but a private session's gamescope host window cannot be named by title at all, so the caller
  that launched it carries the id for one session and `resolveWindow` falls back to the title when it goes
  stale. `WindowRef.of(target)` is the ordinary path; only `ProgramShapeOverlay.sessionTarget` builds one
  with an id. (`OverlayRecorder` held a `WindowRef` rather than a target for the same reason the SDK
  plugin's `MacroRecorderDialog` does now: every click it records is window-relative, and a screen or an
  emulator is not something it can record against at all.)
- **A spec nothing recognises reads as the whole desktop**, in every branch that used to switch on a sealed
  type. That state was unreachable before and is ordinary now (a hand-edited file, a newer Studio's form),
  and the legacy `settings.json` reader is deliberately a **tree walk** rather than a resurrected record set
  — four lines mapping an old node to a spec, so no second vocabulary survives to serve one file format.

**Three of Studio's value editors are the SDK plugin's now, and deleting *every* dispatch arm is what let them
be (2026-08-30/31).** A `java.awt.Color`, an `api.vision.Precision` and an `api.vision.ImageTemplate` are drawn
by `com.botmaker.sdk.internal.plugin.editors.{ColorEditors, PrecisionEditors, TemplateEditors}`; gone from
here are `ui/render/components/{ColorArgPicker, PrecisionArgPicker}`, `ui/app/capture/{ColorSampler, ZoomPan,
GameFrame}` and `ValueEditors`' `ColorRow`, `PrecisionRow` and `TemplateChip`.

- **A type the host answers is a type no plugin is ever offered**, and there are two places the host answers
  one: a `PickerRegistry` entry for a slot on the canvas, and a `case` in `ValueEditors` for a row in the
  Parameters window. Removing one leaves the plugin shut out of half the app, so each move removes both.
  `DurationEditor` is the precedent, and the standing comments at both sites say so.
- **There is a third, and `ImageTemplate` is what found it (2026-08-31): `ValueEditors.optionGraphic`**, the
  tile drawn beside a *declared choice* in the Parameters window. It is not an editor — the value is being
  listed, not edited — so no `SlotEditor` could reach it, and a plugin's type listed as raw text puts the
  decoding back on the person the choices exist for. The contract's `SlotEditor.preview` is the hook, and
  `previewFromPlugin` is this side of it: it reuses the editor's own `matches`, and its `default null` means
  every type that does not implement it lists exactly as it did before. **The rule to apply when the next
  editor moves is that this window shows a value in three places, not two.**
- **The gain is not tidiness, it is that the two halves stop disagreeing.** Studio sampled a colour off a
  frozen frame on a block and off the live screen in a row — same value, two answers, and only one could
  report the patch's ΔE spread. It drew a `Precision` as a dialog explaining each number on a block and as
  three bare fields in a row. One editor over a `ValueContext` is drawn in both.
- **A plugin's editor grabs its own pixels.** `EditorFrame` reads the project's default capture target out of
  the SDK's `capture.json` and grabs through shared; the host answers only `resourcesDir()`. Nothing was
  added to `StudioServices` for either move, which is the standing condition on all of this.
- **The `Precision` port had to leave JDT behind**, and that is the general cost of this direction: Studio
  reads a slot's current value off a syntax tree, and the contract hands a plugin source text. Expect the
  reader to be rewritten every time a picker moves.

**The project's pictures are the SDK's folder, and `services/ImageTemplateLibrary` is a façade over it
(2026-08-30).** The store is `com.botmaker.sdk.authoring.TemplateLibrary`, with `TagCatalog` and
`TemplateManifest` beside it; what is left here is a `ProjectConfig` → `resourcesRoot()` translation with no
state and no rules. **Put nothing in it that decides anything** — a rule here is a rule the plugin's own
pickers do not have, which is the bug the move exists to prevent.

- **It moved for the same reason `capture.json` did.** A named picture is `ImageTemplate`'s concept, so the
  plugin offering the type owns the folder. The façade exists only so ~90 call sites across 20 files —
  `sharing/TemplateArchive`, `ProjectRepair`, `SchemaMigrations`, `StatementFactory`, `BotType` and the
  dialogs — did not have to move with it.
- **`TemplateReferences` was said here to be unable to go, and on 2026-09-01 it went — half of it, which is
  the interesting part.** The sentence that stood here was right about *why* it could not move whole: it finds
  and repoints a picture's uses in the bot's own source, through the open buffers (`ProjectState`),
  `ReviewMarker` and a Project History snapshot, and rewriting a user's Java is host work by construction.
  What the sentence missed is that the class did two jobs. Knowing that `ore.png` is written `Templates.ORE`
  is `ImageTemplate`'s concept, so the editor was holding one plugin's vocabulary on its behalf and a second
  plugin renaming a concept of its own had no way to ask for the same service.
  The split is `com.botmaker.plugin.api.Sources`, implemented here as **`plugin/HostSources`**: the host takes
  a list of **Java token needles**, matches them token-wise (`Templates.ORE` matches `Templates . ORE` and not
  `Templates.OREX`), rewrites buffer and disk, snapshots, and marks. The spellings are the SDK's
  `TemplateUses`, and `ResourceManagerDialog` went with them. `TemplateReferences` and its test are deleted.
- **`HostSources.replace` does two things a plugin cannot see the need for**, and both are here rather than in
  the capability's caller for that reason: it calls `ReviewMarker.prepare` *before* the walk (a mark naming an
  annotation the project does not declare is a bot that stops compiling), and it publishes a
  `UIRefreshRequestedEvent` for the active file afterwards (a plugin never learns which file was open, so it
  cannot know it just rewrote the one on screen).
- **`openActivityTag` stayed** for the narrower version of the same reason — *which file is open* is editor
  state. `TemplateLibrary.declaredTag` is the half that travelled.
- **`ResourcesChangedEvent` is deleted (2026-08-31).** It had four publishers and never a subscriber, and its
  javadoc claimed open template pickers refreshed on it. They did not, and did not need to: every picker
  re-reads the library when it opens its gallery, which is why nobody noticed in the event's whole life. The
  four publish sites went with it, and `ResourceManagerDialog.published()` now only clears its status line.

**Capture Targets left too, and with it the editor's last claim on `capture.json` (2026-08-31).**
`ui/app/ManageCaptureTargetsDialog`, `ui/app/capture/{CaptureSourcePicker, TargetThumbnail}`,
`project/launch/QuickLaunch` and `project/capture/CaptureRegion` are **deleted**; they are
`com.botmaker.sdk.internal.plugin.capture.{CaptureTargets, SourcePicker, TargetThumbnail}` and
`internal.plugin.launch.QuickLaunch`, reached through a `ToolbarItem` in `ToolbarGroup.PROJECT` at order 50.

- **The maintainer's rule is stronger than "the SDK stores the targets": *whatever the SDK writes, the SDK
  reads*.** So the migration off this editor's own older `settings.json` shape went with them, into
  `Authoring.readCapture`, and so did the `capture.source` projection. `StudioProjectSettings.legacyCapture`,
  `legacyTarget`, `legacyReference`, `captureModel()` and `projectDefaultSource` are gone.
- **The one thing `write` still touches in that file is the reference resolution, and it is *merged*.** The
  plugin's manager writes the target list while the editor is open, so writing the model whole would put the
  list this settings was read with back over the top of it. `CaptureStoreTest` holds exactly that case, which
  is the only one here that would have failed silently.
- **`withTargets`, `withDefaultIndex` and `withKnownWindowTitles` are deleted** — their one caller was the
  dialog. `knownWindowTitles` survives as a component nothing reads; it was only ever accumulated by that
  dialog, and it goes when the components do.
- **`ProjectSettingsService.defaultTarget()` reads off disk on every call.** Nothing tells the editor when a
  plugin writes, so a cached copy would be stale from the moment the user pressed Apply. Every remaining
  reader here is on its way out with 3d.
- **Three host entry points went**: the toolbar item and `setOnManageCaptureTargets`,
  `StudioActions.openManageCaptureTargets`, and the **Change…** buttons in `ProjectSetupDialog` and
  `RunnerWindow` — the shell has no handle on another plugin's toolbar item, the same reason the Capture
  Templates steps lost theirs. Both rows still *report* the target and say where to change it.
- **What it cost: the toolbar button's label.** It used to read "🎯 " + the current default target's name.
  `toolbarItems()` is called with no `StudioServices`, so a plugin's item has no project to name.
- **Still owed**: `ui/render/components/CaptureSourcePicker` and `ExpressionMenu` now call the plugin's picker
  directly. That is a transitional Studio→SDK import which phase 6 removes by making the picker a
  `SlotEditor` the plugin contributes.

**Capture Templates left, and it is the second whole feature to leave through the toolbar surface
(2026-08-31).** `ui/app/capture/OverlayTemplateCapture` is **deleted**; the tool is
`com.botmaker.sdk.internal.plugin.capture.CaptureTemplates`, contributed as a `ToolbarItem` into
`ToolbarGroup.TOOLS` at order 20 — the slot Studio's own ✂ Templates item vacated, so the bar reads the same.

- **Four host entry points went, and each for a different reason.** `ToolbarManager`'s ✂ Templates item and
  `setOnCaptureTemplates` (the plugin's item takes the slot); `StudioActions.openOverlayTemplateCapture`;
  `ProjectSetupDialog`'s **Capture…** button and `GettingStartedDialog`'s **Open Capture Templates ▸** —
  *the shell has no handle on another plugin's toolbar item*, so both steps now read without a button and
  say where to go instead, exactly as the Remote Pilot's step already did. `ProjectSetupDialog.row` grew a
  null-label arm for it.
- **`ResourceManagerDialog`'s "Capture new..." went with them**, the accepted consequence recorded when this
  was planned: a dialog has no handle on another plugin's toolbar item, so the toolbar is the one way in. The
  clause that followed — *the dialog itself cannot move, its guards are host work* — was true of the guards
  and not of the dialog, and **on 2026-09-01 the dialog moved too**, to
  `sdk/internal/plugin/templates/ResourceManagerDialog` and a 🖼 Manage Pictures item in `ToolbarGroup.TOOLS`
  at order 30. What unblocked it is the `Sources` capability above: the guards stayed host work and stopped
  being *this window's* work. Its per-picture **Capture a new picture…** stays with it — a region crop the
  window runs itself, over a picture that already exists.
- **`OverlayToolbars.show` is deleted and `installDrag` delegates.** The mini-toolbar factory is
  `OverlayStage.bar` now; its only caller was the tool that left, and the `ProgramShapeOverlay` HUD builds
  its own stage and only ever wanted the drag.
- **What it cost: the suggested tag.** Studio passed the open activity's tag so a picture captured while an
  activity was open was filed under it. *Which file the editor has open* is host state, there is no contract
  member for it, and adding one is what the stop condition refuses. The tag menu is still on the naming
  dialog — a default was lost, not a capability.

**The naming step and the tag menu are the SDK's, and `TagPicklist` is the façade (2026-08-31).**
`com.botmaker.sdk.internal.plugin.capture.{TemplateNaming, TagPicker}`. Studio's
`ui/app/capture/BatchTemplateNamingDialog` is **deleted** and `ImageTemplatePicker.promptNewTemplate` is a
one-line delegation; `TagPicklist` is a two-line subclass of `TagPicker` taking a `ProjectConfig`.

- **A tag is a tag of a picture**, and a picture is `ImageTemplate`'s concept — the catalog comes out of the
  plugin's own manifest under `resourcesDir()`, so the whole control travelled. The façade stays because
  three callers that are **not** moving (`TagManagerDialog`, `ParametersDialog`, `ResourceManagerDialog`)
  should not each build a `StudioServices` to open a menu.
- **Two dialogs became one class, and it fixed a real gap.** The single-capture prompt had no tag field at
  all — a picture captured on its own could only be filed later, from the resource manager — while the batch
  dialog had one. They enforced the same three refusals in two places and had already drifted in wording.
- **Nothing was added to `StudioServices`**, the standing condition: theming is `services.theme()`, the
  thumbnails are `services.capture().toFxImage`, and the rest is a folder.
