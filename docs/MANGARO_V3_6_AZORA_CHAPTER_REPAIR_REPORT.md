# Azora chapter repair — 2026-10-01

Reproduced through the registered internal Azora source and NetworkHelper on emulator-5554:
Rabbit Holes, catalogue identity `rabbit-holes#2822`, image type MANHWA, details `isNovel=false`.
Request: `https://api.azorafly.com/api/chapters?postId=2822`.
Details root totalChapterCount, details _count.chapters and chapter endpoint totalChapterCount all declare 0.
Raw chapter rows, unique remote IDs and unique URLs are all 0; duplicate IDs/URLs are absent.
Before repair, parseChaptersResponse rejected every empty array with “Azora empty or duplicate chapter list”.
This is a confirmed empty series, not lost chapters or a wrong postId. No nonempty duplicate response was reproduced.

| Catalogue-produced manga | postId | Live total | Raw rows | Unique IDs / URLs | Internal after | State |
| --- | --- | --- | --- | --- | --- | --- |
| Rabbit Holes | 2822 | 0 | 0 | 0 / 0 | 0 (previously error) | COMPLETE |
| Nano machine | 425 | 334 | 334 | 334 / 334 | 334 (unchanged) | COMPLETE |
| Rebirth Of The Urban Immortal Cultivator | 92 | 621 | 621 | 621 / 621 | 621 (unchanged) | COMPLETE |

The chapter endpoint is unpaginated: NetworkHelper probes page=1 and page=2 with perPage=10 each return the same full 334 Nano IDs, in order. No cursor/next-page metadata exists. A single complete request is correct; adding a page loop would repeat the full list. Parsed unique count must match the declared total. Unexpected advertised continuation is rejected rather than labeled COMPLETE.

The repair permits zero only when independent details and chapter totals agree, rejects contradictory totals, unexpected empty lists, missing arrays, API errors, truncation, duplicate remote IDs (including different slugs) and foreign post rows. It preserves distinct remote IDs sharing a chapter number. No rows are silently deduplicated. Existing URL/memo identities remain intact; API creation date and scanlator metadata are retained when present. Missing verified totals remain DEGRADED. Errors produce no reconciliation result and preserve existing DB chapters. Global empty-list synchronization and deletion safeguards remain unchanged.

Device audit uses real search-produced objects without replacement: catalogue URL → details postId → chapters → completeness → DB/UI. Rabbit Holes opens its details with zero server chapters; it cannot display nonexistent chapters. Nano and Rebirth show 334 and 621 chapters. This confirms the empty-series bug, not every unspecified physical duplicate report.

Verification: :domain:test (64), :app:testDebugUnitTest (168), including five focused Azora regression tests and real Rabbit Holes response fixtures; opt-in registered-source Android audit with raw captures and screenshots; :app:assembleRelease. Device automation used emulator-5554 only.

Audit command: `adb -s emulator-5554 shell am instrument -w -e source Azora -e azoraChapters after -e full true app.manhwaar.reader.dev.test/eu.kanade.tachiyomi.source.audit.SourceAuditInstrumentation`.
Public captures and logs: `/tmp/azora-chapter-repair/`; app-private captures `Azora-chapter-audit-{2822,425,92}.json`, corresponding details JSON and screenshots.
Release ARM64 output: `app/build/outputs/apk/release/app-arm64-v8a-release.apk`.
