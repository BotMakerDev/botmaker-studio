# Project Lifecycle

`BotMakerStudio` (JavaFX `Application`) is the entry point. It opens on the bot list, `ui/app/home/HomeShell`
(My bots, Get more bots), not on the project last opened (2026-10-25); only a project, a `.bmk` or a
`botmaker://` link named on the command line opens straight away. Opening a project goes through
`BotProject.open()`, the composition root. Since 2026-11-05 **the project is the core's**: `botmaker-core` is a
process of its own that owns the open bot (its lock, its edits, its compile and runs, its history, the MCP
endpoint), and Studio is its client. `BotProject.open()` wires Studio's side in order:

1. `ProjectConfig` (core) — immutable record with all paths and JVM info for the project
2. `EditorState` — Studio's state of the project: the core's `ProjectState` (current AST, classpath, …) plus
   the blocks drawn for the active file. **Confined to the FX thread.** Anything that runs off it takes a
   `state.snapshot()` *before* spawning its thread and reads that record instead. The block registry is built
   into a fresh map and published with `setNodeToBlockMap` in one assignment, never filled in place.
3. `EventBus` (core's `events/`) — per-project, not global; Studio's services talk through it
4. `services/CoreEdits` — opens the project in the core (started when none is running) and is Studio's edit
   port: each edit is a request to the core, and what the core tells comes back as the `CoreEvent`s Studio
   redraws from. The core makes the open-time writes (remotes, the contract's dependency, a plugin's missing
   managed holders) and resolves the pom once; Studio binds its copy of the plugins (`PluginHost.bind`) to the
   jars the core resolved. A failure after this step closes it, so the core does not keep the lock.
5. `TypeSummaryManager` (core's `index/`) — per-jar index of library types, cached on disk
6. `ProjectAnalyzer` (`botmaker-java`) — type/suggestion provider over the live JDT AST and the library index
7. `LibraryService` — the pom's libraries and plugins; its writes reload through the core, and it watches the
   pom for edits made outside Studio
8. `ProjectSettingsService`, then `BlockConverter` (source → blocks) and `CodeEditorService`, which turns the
   user's gestures into edits sent through `CoreEdits`
9. `CoreRuns` — compile, run, debug and stop, asked from the bus and done by the core; what a run tells is told
   on the bus. `StopKey`, `RunOverlay` and `RunEndReports` listen first, so a run the core already had (started
   through the MCP) is not missed
10. a plugin's view of the bot: Studio's `PluginsOnBus` (its runs and its undo), and the forwarding Studio
    installs on the core's `HostPluginValues` and `DevReloads` (its value writes and its dev reload), each
    reaching the core
11. `UIManager` — builds the JavaFX scene against `BotProject.context()` and connects UI events to services

`BotProject` owns all of Studio's services; `BotMakerStudio` holds only one `BotProject` at a time.
