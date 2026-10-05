# Sharing — the gallery is read whole and written one file at a time

**The HTTP layer under all of this is `botmaker-shared`'s since 2026-09-05.** `GitHubClient`, `GitHubAuth`,
`GitHubConfig` and `SemVer` are `com.botmaker.shared.github` — moved verbatim, imports only on this side.
What stayed here is every **reader** built on them (`BotPublisher`, `GitHubGallery`, `PluginRegistry`,
`BotInstaller`, `UpdateService`, `CliUpdateService`), because those know what a bot, a gallery entry and a
plugin index are, and that is the editor's vocabulary rather than the platform's. The line to hold when
something new is added: *a repository name, a token or a request belongs to shared; what the JSON means
belongs here.*

- **The reason it moved is a second operator, not tidiness.** `botmaker-dashboard` reads the same registry,
  the same gallery and the same pull requests, and a hand-rolled OAuth **device flow** with a `0600` token
  file is precisely the code that must not exist in two repositories.
- **`GoogleAuth` and `GoogleConfig` did not move** — nothing else wants them. They still share
  `credentials.json` with `GitHubAuth`, whose `store` merges the whole map rather than overwriting it; that
  rule now spans two repositories, so signing into Google must still not wipe the GitHub token.


`sharing/GitHubGallery` **reads** `catalog.json` (since 2026-09-16; `index.json` before, and still as its
fallback) from the gallery's raw-CDN URL; `sharing/BotPublisher`
**writes** `bots/<owner>-<repo>.json` and nothing else. Since 2026-08-28 `index.json` is *generated* by the
gallery's own CI from those entry files, and a pull request that edits it is refused — so the read path and
the write path no longer touch the same file, and that asymmetry is the design rather than an accident:

- **The read URL is a compatibility promise.** Every Studio already installed has it compiled in, so the
  generated array stays byte-compatible with `GalleryEntry` and the path never moves. Since 2026-09-16 it
  holds Vetted bots only — see *Tiers* below.
- **The write path was the problem.** Appending to a shared array made every concurrent submission a merge
  conflict and each publish a read-modify-write against a base SHA somebody else may have moved; unpublishing
  rewrote the whole file for a one-line removal. `GitHubConfig.entryPath` is where a bot's identity becomes a
  path, and re-publishing is idempotent because that path is the identity.
- **Publishing from a Studio older than that release broke, deliberately** — the gate refused the pull
  request with a message naming the update. The alternative (a CI job converting an index-only PR) means
  maintaining both shapes indefinitely.
- `GitHubClient.delete(url, body, token)` exists for this: GitHub's Contents API needs a body to delete a
  file and `HttpRequest.DELETE()` sends none. The bodyless `delete(url, token)` stays for the endpoints that
  reject one.

