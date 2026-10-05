# BotMaker Roadmap

Completed work up to 2026-10-05: see CHANGELOG.md, docs/refactor/, and `git show 2f53621a:ROADMAP.md` (it
also holds the 2026-09-06 Studio ↔ SDK decoupling ledger, with a verdict per item).

## Open

- **Not yet run against a real model**: the Assistant tab (`assist/`, `docs/refactor/38-llm-edits.md`). Next
  after that: an MCP endpoint.
- **Parameters value cells** for nesting deeper than one container.
- **Constants**: the constant lookup scans the bot's sources on every read of a dotted name the grammar cannot
  read and on every write (a cache keyed on the buffers if a large bot makes it visible); Parameters rows
  (`HostValueContext`) do not resolve constants.
- **Recording**: no per-gesture choice when two plugins' `@Records` tie on rank (today: plugin order).
- **Quick Documentation on hover**; today it is F1 on the selected block only.
- **13 tests skip without the SDK plugin bound** (`TestSupport.assumeSdkPluginBound`); a way to run them —
  an SDK on the test classpath — is the platform rule's question.
- **Decoupling ledger leftovers**: `WhatsNew` reads `META-INF/botmaker/whats-new.md`, a convention only the
  SDK's pom implements, so a second plugin's report is empty (a contract question); the upgrade dialog is
  still the SDK's; `LibraryService.updateLibraries` is SDK-keyed, so `apply` refuses any other coordinate;
  the docs stack (`resolveSdkSourcesJar`) is coordinate-specific.
- **The Errors tab steals focus on every compile** with an error; the alternative is raising it only when the
  *set* of errors changes (needs a diff and a definition of change). Left as-is by the maintainer's call
  (2026-08-06).
- **Run / run-to-cursor from the overlay**, so a bot can be tested without switching back to Studio.
- **A recording that knows it is off-resolution**: recorded coordinates are raw window-relative pixels; scaling
  by `reference / windowBounds` needs a decision about which the user meant.
- **A configured Waydroid framebuffer resolution**: `WaydroidResolution.apply()` (shared) has no caller
  because nothing authors the expected size; it needs a BotMaker-owned project or emulator setting.
- **Retire `services/capture/ScreenOverlay`** (Studio's own overlay HUD) now the SDK draws its own.
- **`DayOfWeek`/`Month` on the canvas** rely on `EnumPicker` until basics declares them.
