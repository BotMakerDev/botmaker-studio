# Tiers, and a publish that resumes (2026-09-16)

**The gallery lists as Vetted or Community, and nobody merges a Community listing by hand.** The gallery's CI
runs `botmaker-cli`'s `GalleryGate` on a listing pull request and its `ListingPolicy` merges it, unless the
author is over 3 new listings in 24 hours (`waiting`) or the change needs a maintainer (`needs-maintainer`).
Vetted is a maintainer's `vetted/<owner>-<repo>.json` pinning one release, written from the dashboard. Every
rule is the CLI's; Studio reads the outcome.

- **The read side is `catalog.json`, with `index.json` as its fallback.** `GitHubGallery.parseCatalog` reads
  `{"schemaVersion", "bots": [...]}`; anything unreadable falls back to `parseLegacyIndex`, which marks every
  entry Vetted because since this date that file holds nothing else — it is Vetted-only *so that* Studios
  already installed, which cannot show a tier, show only bots somebody looked at. `GalleryTier.fromId` is
  total and falls to Community: an unknown tier must never read as more trusted.
- **A Vetted bot installs and updates to its vetted release.** `GalleryEntry.installTag` and `updateTarget`
  are the two rules, pure and tested (`GalleryCatalogReadTest`); an update never moves an installed copy to a
  release older than the one it has. The accepted cost, stated in the gallery's README: an older Studio
  installs a Vetted bot's newest release, because it never learnt `vettedVersion`.
- **A publish is `BotPublisher.Run` over a `PublishPlan`** — REPO, PUSH, RELEASE, ARCHIVE, LISTING — and a
  retry resumes at the failed step. Each step is safe to run twice: the repository is found, a release whose
  tag exists counts as cut, a listing identical to the gallery's entry is not resubmitted. ARCHIVE downloads
  the zipball **without** a token, because that is what the gallery's gate does and what an installer has.
- **The entry is `PublishRequest.entry`: schema 2, the CLI's field order, and `requires`** — the pom's
  dependencies the plugin registry knows, by id, properties interpolated. Keyed by the registry exactly as
  `botmaker-cli`'s `Requirements` is, so Studio and `botmaker bot publish` write the same file.
- **A listing is a pull request from `listing/<repo>` on the author's fork, reset to the gallery's tip.** Not
  from the fork's `main`: that diverges from the gallery the first time a listing is squash-merged, and a
  second pull request from it would carry the first listing again. An open pull request from the branch is
  updated in place. The gallery's owner still commits straight to `main`.
- **`ListingStatus` is the workflows' words, not Studio's**: the `validate` check run, the two labels and the
  comment starting `<!-- botmaker-listing -->`, whose sentence is shown verbatim.
- **`ui/app/gallery/GalleryCard` is the one card**, drawn by Browse Bots and by `PublishSheet`'s preview, so
  the preview cannot promise a row the gallery does not show.

