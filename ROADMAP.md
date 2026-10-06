# BotMaker Roadmap

Completed work up to 2026-10-05: see CHANGELOG.md, docs/refactor/, and `git show 2f53621a:ROADMAP.md` (it
also holds the 2026-09-06 Studio ↔ SDK decoupling ledger, with a verdict per item).

## Open

- **Not yet run against a real model**: the Assistant tab and its MCP endpoint (`assist/`,
  `docs/refactor/38-llm-edits.md`). The Studio halves of the MCP tools (`StudioBridge`) have no automatic test;
  a plugin added in the Plugins & Libraries window is served from the endpoint's next start; a `revert` ends a
  Claude session running in the Assistant tab.
- **Parameters value cells** for nesting deeper than one container.
- **Constants**: the constant lookup scans the bot's sources on every read of a dotted name the grammar cannot
  read and on every write (a cache keyed on the buffers if a large bot makes it visible); Parameters rows
  (`HostValueContext`) do not resolve constants.
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
- **Run to the cursor from the overlay editor**: the panel runs the bot, one activity (*Run this activity*) or
  one statement (▶ Try), but not the bot up to the caret.
- **A configured Waydroid framebuffer resolution**: `WaydroidResolution.apply()` (shared) has no caller
  because nothing authors the expected size; it needs a BotMaker-owned project or emulator setting.
- **Retire `services/capture/ScreenOverlay`** (Studio's own overlay HUD) now the SDK draws its own.
- **`DayOfWeek`/`Month` on the canvas** rely on `EnumPicker` until basics declares them.
