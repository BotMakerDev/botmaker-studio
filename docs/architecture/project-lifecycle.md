# Project Lifecycle

`BotMakerStudio` (JavaFX `Application`) is the entry point. On launch it either re-opens the last project via `ProjectPreferences` or shows `ProjectSelectionScreen`. Opening a project goes through `BotProject.open()`, which is the composition root — it constructs all services and wires them together in order:

1. `ProjectConfig` — immutable record with all paths and JVM info for the project
2. `ProjectState` — mutable runtime state (current AST, classpath, highlighted block, etc.). **Confined to the
   FX thread.** Anything that runs off it — a run, a compile, a debug session — takes a `state.snapshot()`
   *before* spawning its thread and reads that record instead; the getters are not for background callers.
   The block registry is likewise built into a fresh map and published with `setNodeToBlockMap` in one
   assignment, never filled in place.
3. `EventBus` — per-project, not global; all inter-service communication goes through it
4. `MavenService` — generates and edits the project `pom.xml` (Maven Model API) and resolves the classpath in-process via Maven Resolver (Aether); no system `mvn` binary required. The `pom.xml` is the single source of truth for dependencies (see **Library Management**)
5. `TypeSummaryManager` — builds/loads a serialised cache of external library types per-jar
6. `ProjectAnalyzer` — unified type/suggestion provider backed by both the live JDT AST and the library index
7. `LibraryService` — add/remove user libraries: rewrites the pom, re-resolves the classpath, refreshes the type index, and publishes `LibrariesChangedEvent`
8. `CodeEditorService` — subscribes to `EventBus` and orchestrates all code editing operations
9. `ExecutionService` / `DebuggingService` — compile, run, and debug the user's project via JDI
10. `UIManager` — builds the JavaFX scene and connects UI events to services

`BotProject` owns all services; `BotMakerStudio` holds only one `BotProject` at a time.

