# Mangaro V3.6 final source repair

Started from clean `0edc797e60304956c9943c37a8a43c93bca91f29` on `repair/v3.6-source-reliability`. Device automation used only `emulator-5554`.

## JsonNull repair

Exact reproduced cause: Azora's `parsePostDetailsResponse`, formerly line 236, called `postObj["createdby"]?.jsonObject` when the live Rebirth response contains `"createdby": null`. JSON null is a present `JsonElement`, so Kotlin's null-safe call still invokes the throwing cast. Nullable `_count`, genres, alternate payloads, and MangaTime's tRPC objects had the same unsafe access. MangaTime also used `.content` on null scalar metadata, producing literal `"null"` instead of preserving existing values.

Fixed `Azora.kt`, `MangaTime.kt`, and added `internal/util/JsonElementUtil.kt`. Optional objects/arrays use type-checked access; null scalar metadata is absent. Required objects/entries/IDs/arrays still throw `IOException`; a null tRPC payload cannot fall through to fabricated wrapper metadata. Other existing sources are unchanged.

**Android result:** Rebirth Of The Urban Immortal Cultivator, catalogue-produced `rebirth-of-the-urban-immortal-cultivator#92`, loads without the crash: description 328 characters, nine available genres, cover, and **621 live / 621 internal chapters, COMPLETE**. The actual details UI displays all 621. Author/artist are genuinely null in the live API. Azora Nano Machine regression also passes Reader (five pages rendered) and normal download (three original pages, 28 split images).

## MangaSwat

Pinned identity from [Keiyoushi source commit 60bc15ec](https://github.com/keiyoushi/extensions-source/tree/60bc15ec9c13b3a4302bc54ca7b9fa892fdae726/src/ar/mangaswat) and [published index 9b72f771](https://github.com/keiyoushi/extensions/blob/9b72f771dd013b30052a20bb77c3ccb12a1a6631/index.json): ID **7657007209499352344**, name **MangaSwat**, language **ar**, source versionId **2**, extension **1.6.61**, base **https://meshmanga.com**. Display compatibility: `MangaSwat (AR)`.

Preserved manga numeric-ID URL (`1624038`), empty manga memo, chapter `/chapters/<id>/<slug>/`, and chapter memo `{id: numeric, slug: string}`. Website links remain `/series/<id>` and `/chapter/<id>`. Details retain catalogue URL/memo instead of losing the numeric ID as the upstream details DTO would when used directly.

| Check | Android production result |
|---|---|
| Popular | Pages 1/2/3: **20/20/20**, all distinct; page **83: 14**, next=false; advertised catalogue **1,654** |
| Latest | Pages 1/2/3: **20/20/20**, all distinct; **200** recent updates, terminal page **10: 20**, next=false |
| Search | `Revenge`: **9**; broad `ma`, pages 1/2/3: **20/20/20**, all distinct |
| Filters | None: pinned extension exposes none; no invented genre/status mappings |
| UI | Popular catalogue/covers, real page-two scrolling, Latest, Search (first visible result), details and chapter count render |
| Chapters | Sword Hound **185 live / 185 internal**; Nano Machine **333 live / 333 internal** across **200 + 133** entries, both COMPLETE |
| Reader | Parser-produced Sword Hound chapter two: **21** pages, real image rendered |
| Download | Normal Downloader, chapter one: **24** original pages verified contiguous, **48** stored split images |
| Offline Reader | Wi-Fi/data disabled; Android activeNetwork=null; downloaded chapter renders via DownloadPageLoader, **48** images; connectivity restored |
| Fallback | MangaSwat external package absent on emulator; SourceManager resolves the registered internal class at the pinned ID. Default stays EXTERNAL_PREFERRED; compatibility test verifies external selection and internal fallback |

Only MangaSwat was added. It derives its client from NetworkHelper, reusing cookies, DNS, interceptors and timeouts; existing rate limiter preserves upstream one request/second. No separate global stack or additional retries. Null/error/challenge JSON, invalid continuation hosts/routes, wrong-series cursors, repeated IDs/pages, changing counts, and incomplete image counts produce clean errors. Chapter traversal is sequential, bounded to 100 pages/180 seconds, and parent cancellation propagates. A source-owned timeout becomes IOException.

**Completeness:** COMPLETE requires every chapter page's advertised count, a terminal null cursor, unique remote IDs, and exact aggregate count. Missing count proof returns DEGRADED. Truncation/repetition/error throws before reconciliation. Existing COMPLETE/PARTIAL/DEGRADED/FAILED architecture remains; only COMPLETE authorizes destructive chapter sync.

## Validation and artifacts

Focused tests cover the captured real Rebirth null response, nullable metadata, required-null failures, pinned ID/URLs/memos/collision fallback, all continuation pages, missing proof, truncated totals, repeated IDs/cursors, wrong-series continuations, challenge/error responses, and partial Reader image arrays.

`./gradlew :domain:test`: **64 passing**. `./gradlew :app:testDebugUnitTest`: **160 passing**. `./gradlew :app:assembleRelease`: successful; ARM64 output `app/build/outputs/apk/release/app-arm64-v8a-release.apk`.

Android runner: `-PsourceAudit=true`, real registered sources and NetworkHelper; full MangaSwat and Azora audits finish with **zero failures**. The search audit initially waited for Sword Hound (the ninth result) without scrolling; it now verifies the first visible result before reopening Popular. The corrected complete run has zero failures. Raw Android responses/screenshots and logs are retained at `/tmp/mangaro-v3.6-final/` and in the debug app's private files.

Limitations: physical Vivo retest remains manual; no physical ADB or APK installation occurred. Paid/locked chapters without supplied images return clean errors. Popular catalogue page count reflects the current live inventory; no claim that every manga/chapter was individually opened. No tag, merge, background refresh, URL migration, or UI redesign.

Commits: `396f3b4` nullable JSON repair; `ecb9895` internal MangaSwat; the commit containing this report adds compatibility/runtime tests and evidence.
