# Chapter recovery and source health — 2026-10-01

Work began at 08845dca534df15b9bcde485d6bf00d2d36e0a37 on repair/v3.6-source-reliability. No source IDs, parsers, registry preference, DB schema or UI layouts changed. No new source or HTTP stack was added. Device automation used emulator-5554 exclusively.

## Chapter identity and Reader recovery

Matching is scoped to the selected source ID and local manga ID: unique exact URL, stable remote ID/memo, verified HTTP redirect identity, then a unique descriptive number/name/scanlator fingerprint. Conflicting remote IDs do not fall back to fingerprints. Legacy Azora fragments provide the pinned remote chapter ID when memo is absent. A different known number/scanlator invalidates a redirect match. Fingerprints require VERIFIED COMPLETE retrieval; partial/unverified lists cannot establish uniqueness. Ambiguous candidates are withheld from insertion, old rows remain, reconciliation becomes DEGRADED, and deletion is blocked. Unknown unmatched old rows remain even when the incoming list is COMPLETE. Distinct known remote IDs sharing a number remain distinct.

In-place migrations issue minimal ChapterUpdate(id, url, memo), preserving read/bookmark/last page, timestamps, history FK and ID. Ordinary metadata sync also avoids writing reading-state columns. URL-hashed download files are renamed before SQL updates, verified at the new URL, and rolled back on SQL failure. Shared bounded striped locks prevent renaming active downloads; queued downloads re-read the same chapter ID before downloading. Cancellation cannot interrupt the small local filesystem/SQL commit.

Reader handles 404/410/403 and explicit stale/missing/expired chapter failures with one list refresh and one validated page-list retry, bounded to 90 seconds. It never inserts or deletes a chapter. Canonical probes require an actual HTTP redirect to a list-produced URL and a valid page list; at most three unresolved URLs are probed, five seconds each. Permanent or uncertain failures stop cleanly. No whole-DB migration occurs.

Production emulator proof: Nano machine catalogue → postId 425 → 334 verified chapters. An intentionally stale stored URL with nonexistent fragment 999999999 produced a real API HTTP 404 while memo retained remote ID 24237. PARTIAL sync and the real Reader restored /series/nano-machine-s/chapter-2#24237 under local ID 598. Bookmark=true, read=false, last page=1 and history ID=1 remained attached; chapter count stayed 334 with zero duplicate insertions. Reader images rendered. A normal download completed for local ID 599; its archive remained discoverable after URL-hash migration and was restored to its original URL.

## Automatic reliability

HEALTHY / DEGRADED / UNAVAILABLE states are persisted separately from library data. Three consecutive semantic failures or five consecutive transient failures open the circuit. Backoff is 1, 2, 4, 8… minutes, capped at six hours; cooldown attempts do not issue requests or increment failures. Success resets health. Cancellation and waiting for occupied permits do not count as source failures. Unsupported/locked content is not an outage. Incomplete PARTIAL/FAILED results affect health; unverified legacy DEGRADED results retain data without declaring an outage. Empty Popular is a semantic failure only for known populated internal sources or an established positive catalogue.

Tracked operations permit three requests globally and one per source. Source updates run in supervised groups, catch per-source errors and propagate cancellation. Home publishes healthy source data before slow siblings finish. First-page discovery snapshots and populated degraded screens retain last-known-good data. Unavailable sources are hidden from new Home/category discovery; library manga/history/chapters/downloads stay. Recovery refreshes discovery automatically and does not switch sources.

WorkManager schedules one hourly health job plus a network-constrained startup probe after two minutes. Failed sources do not make the whole worker retry. Library chapter updates reuse LibraryUpdateJob and its device/network restrictions. The new feature initializes the existing interval to 24 hours once when previously disabled; subsequent user choices are honored. Scheduled jobs use exponential WorkManager backoff. Source-specific backoff is independent. App replacement clears stale parser failure cooldowns to DEGRADED; a successful check restores HEALTHY. Parser fixes continue shipping in normal app updates; no remote executable code is fetched.

## Runtime acceptance

| Internal source | Popular | Latest | Initial health |
| --- | ---: | ---: | --- |
| TeamX | 10 | 40 | HEALTHY |
| MangaTime | 24 | 24 | HEALTHY |
| MangaLek | 10 | 10 | HEALTHY |
| Azora | 23 | 24 | HEALTHY |
| Hijala | 5 | 5 | HEALTHY |
| MangaDar | 30 | 30 | HEALTHY |
| MangaSwat | 20 | 20 | HEALTHY |

Fault injection derived a client from the existing NetworkHelper and returned HTTP 200 with invalid Azora JSON through the actual Azora parser. Backoff eligibility was advanced in the test instead of sleeping for minutes. Three requests reached the parser; 15 additional attempts made zero network calls. Azora moved DEGRADED → UNAVAILABLE. Its real library updater stopped with a clean unavailable error before reconciliation. MangaTime's real library updater continued successfully with 371 chapters. Azora library membership, chapter IDs and history IDs remained unchanged. Real Home displayed healthy content as soon as the first source finished, hid Azora, and included it again after a successful original production source check. No duplicate chapter IDs appeared.

WorkManager runtime inspection confirmed one active source-health schedule and one library-update schedule. The startup probe was temporarily cancelled during fault injection to keep the simulation deterministic and was rescheduled afterward. These tests verify scheduling and worker-backed production operations; they do not wait 24 hours for the daily timer to fire.

Verification: 75 domain tests and 175 app tests passed, including real local HTTP redirect tests, state preservation, SQL/download rollback, ambiguity, partial-fetch protection, concurrency limits, cancellation, failure thresholds and recovery. Opt-in Android audit finished with failures=0. Release build: :app:assembleRelease; ARM64 artifact app/build/outputs/apk/release/app-arm64-v8a-release.apk.

Audit: adb -s emulator-5554 shell am instrument -w -e reliability true app.manhwaar.reader.dev.test/eu.kanade.tachiyomi.source.audit.SourceAuditInstrumentation
Evidence: /tmp/mangaro-v3.6-recovery/runtime-accepted.log; app-private screenshots reliability-stale-reader.png, reliability-home-broken.png and reliability-home-restored.png. Physical Vivo was not touched.
