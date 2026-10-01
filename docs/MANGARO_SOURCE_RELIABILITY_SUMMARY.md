# Mangaro source reliability: decision summary

**Audit date:** 2026-09-28. **Status:** analysis complete; no production implementation. Full evidence, source dossiers, probe matrix, and staged tests: [MANGARO_SOURCE_RELIABILITY_AUDIT.md](MANGARO_SOURCE_RELIABILITY_AUDIT.md).

## Decisions

1. **Use the existing Mihon contracts.** First-party Arabic sources should enter through `CatalogueSource`/`HttpSource` and `SourceManager`, sharing OkHttp, SQLDelight, Reader, downloads, and extension compatibility. Add explicit ID-collision handling and canonical provider precedence. Current `AndroidSourceManager` only registers LocalSource plus installed extensions, so zero-extension remote Home needs new registry wiring.
2. **Preserve IDs only with verified URL and memo parity.** The current six upstream source implementations have different engines. Azora and Hijala use `versionId=2`; their legacy users may have other IDs. A source-ID change or naive migration can break library, history, progress, and downloads. Existing `MigrateMangaUseCase` is not lossless for these fields.
3. **Protect chapter identity before automatic recovery.** Sync matches exact chapter URL, deletes missing rows, and inserts replacement rows. It can transfer read/bookmark status by number but not local chapter ID, page progress, history, or reliably downloads. A partial nonempty source response can delete many valid rows. Add completeness checks and unique, conservative in-place remapping before refresh-on-Reader-error.
4. **Treat page URLs as potentially temporary.** MangaDar's live chapter contained `data:image` placeholders and expiring signed `data-mds` URLs; its app-specific Reader resolver fetches them fresh. Generic Reader disk page-list cache and page retry can retain stale image URLs. Future recovery should regenerate a page list once on an appropriate 403/404, with source-specific policy.
5. **Keep source maintenance modular.** Azora is Iken API, MangaDar custom HTML/JSON, MangaLek Madara AJAX, TeamX custom HTML, MangaTime tRPC, Hijala MangaThemesia plus conditional image stitching. Build internal modules with pinned fixtures and normal app updates. A remote backend or executable parser delivery is not the first step.

## Biggest risks seen in live checks

- MangaLek homepage returned 200 while a catalogue path returned 403 Cloudflare challenge. Homepage uptime is not source health; the actual Madara AJAX POST remains untested.
- Azora detail API reported 68 chapters yet embedded none; a separate chapters endpoint returned data. A parser that trusts only the detail body will falsely report missing chapters.
- MangaDar's sampled chapter had 24 signed image attributes and 24 placeholders; a ranged image GET redirected to storage and returned 206 webp.
- TeamX paginates chapter cards and upstream extension launches remaining page requests concurrently. Large series can generate bursts.
- MangaTime's sampled tRPC chapter query returned 371 chapters; page query returned 20 images. Chapter page lookup parses number as integer, so fractional numbering needs an explicit policy.
- Hijala has a hidden `#/chapter-{{number}}` template before real chapter links, and some chapters may require a source interceptor to stitch split images.

## Recommended phase order

| Phase | Goal | Rollback gate |
|---|---|---|
| 05.7.0 | Pin upstream versions and collect real APK/DB URL, ID, memo and device fixtures | Revise fixtures only |
| 05.7.1 | Internal source registry and collision policy, zero-extension source visibility | Feature flag disables internal registry |
| 05.7.2 | One parity-tested pilot source; validate old library and downloads | Prefer external extension again |
| 05.7.3 | Chapter-list completeness and conservative identity reconciliation | Disable remap; retain old rows/aliases |
| 05.7.4 | Reader page URL refresh and single-shot chapter recovery | Disable recovery |
| 05.7.5 | Remaining five internal sources one at a time | Per-source provider switch |
| 05.8 | Last-good persistent discovery cache and source health | Disable cache/health coordinator |
| 05.9 | Bounded WorkManager discovery refresh separate from existing library updater | Cancel unique work |

Every phase needs fixture tests and physical-device validation. Preserve the current approximate maximum of two concurrent Home source operations; coordinate with other jobs before increasing traffic. Existing library updates already use WorkManager; Home discovery does not.

## What not to do

- Do not copy extension APKs/classes directly into the main APK or silently overwrite same-ID sources.
- Do not duplicate the DB, Reader, download system, or extension loader.
- Do not assign new IDs casually or migrate library entries solely by title/chapter number.
- Do not delete chapters on a partial returned list or auto-remap ambiguous specials/duplicate numbers.
- Do not persist short-lived signed image URLs or repeatedly retry Cloudflare/429 responses.
- Do not assume all six sources are WordPress/Madara or that a 200 homepage means the parser works.
- Do not add a backend or background crawler before measuring failure rates, request volume, privacy and maintenance costs.

**First implementation recommendation:** 05.7.0, the compatibility baseline and on-device fixtures, followed by 05.7.1 internal registration with an explicit provider collision rule. This report stops before implementation.
