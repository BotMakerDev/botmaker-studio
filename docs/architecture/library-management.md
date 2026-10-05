# Library Management

The user can add/remove third-party dependencies from the GUI (**Project → Manage Libraries…**,
`ui/app/ManageLibrariesDialog`). The design keeps Maven simple:

- **The `pom.xml` is the single source of truth.** A "user library" is just any dependency in the pom that isn't
  one of `MavenService.DEFAULT_DEPENDENCIES`. There is no separate store file. `UserLibrary` is an immutable
  `record(groupId, artifactId, version)`; `MavenService.readUserLibraries` / `writeUserLibraries` read and rewrite
  the non-default dependencies in place (defaults, repositories and properties are preserved).
- **`LibraryService.updateUserLibraries`** runs the slow work off the FX thread: write pom → `resolveClasspath` →
  `ProjectState.setResolvedClasspath` → `TypeSummaryManager.refresh` (incrementally indexes the new jars) →
  publish `LibrariesChangedEvent`.
- **`MavenCentralSearch`** provides IntelliJ-style autocomplete in the dialog via the Maven Central Solr API,
  using the JDK's built-in `java.net.http.HttpClient` + Jackson (no new dependencies). All calls are async and
  best-effort — network failures resolve to empty results.

