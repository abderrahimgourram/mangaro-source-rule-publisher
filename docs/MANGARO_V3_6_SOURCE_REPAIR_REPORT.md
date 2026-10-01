# MANGARO V3.6 source reliability repair

Date: 2026-10-01. Branch: `repair/v3.6-source-reliability`, based on clean `1dc83fb6a175e7af88513eea628ec325b8d8a6a1`.
Actual checkout: `/home/jalem/Downloads/ManhwaAR_V0.2_Mihon_Fork_Kit/ManhwaAR_V0.2_Kit/ManhwaAR_Android` (the supplied path omitted the intermediate kit directory).

## Acceptance and limits

All six registered internal sources were exercised through the initialized production NetworkHelper on **emulator-5554**. Catalogue and details screens, page-two UI scrolling, an online Reader chapter, and a normal Downloader chapter passed. Source registration/class identity was checked before requests. Reader checks require the requested chapter identity and a substantially visible, fully loaded image; screenshots were also inspected.

**MangaDar's Vivo zero-card failure is not established as fixed.** No ADB command targeted Vivo. The emulator receives populated HTML, including with fresh Android-WebView and desktop user agents: HTTP 200, final archive URL, `text/html; charset=UTF-8`, 398,615 decoded bytes, `Vary: Accept-Encoding`, 88 anchors, 67 templates, 8 scripts, 30 parsed cards. Both representations match. This does not identify the failing Vivo representation. Its safe response diagnostics/manual retest remain necessary for physical acceptance.

Counts below are live samples, not an exhaustive crawl of every catalogue or every chapter's images. Terminal pages were fetched separately; chapter counts compare raw Android response rows/IDs with internal results. Catalogue/detail titles, cover presence, initialization, and URLs were checked for the representative titles.

## Catalogue matrix

Every displayed page-one/two/three count consisted of fresh URLs relative to earlier sampled pages. Popular and Latest both passed; Search was also tested with a title query and the broader `ma` query.

| Source | Popular pages 1 / 2 / 3 | Latest pages 1 / 2 / 3 | Search `ma` | Supported filters | Verified Popular terminal |
|---|---|---|---|---|---|
| TeamX | 10 / 10 / 10 | 40 / 40 / 40 | 24 per page; all **267 / 267** results across 12 pages; last 3 | None offered | Page 128: 5, next=false |
| MangaTime | 24 / 24 / 24 | 24 / 24 / 24 | 24 / 24 / 24, distinct | None offered | Page 60: 3, next=false; advertised 21-page total is inaccurate |
| MangaLek | 10 / 10 / 10 | 10 / 10 / 10 | 20 / 20 / 20, distinct | None offered | Page 2032: 10, next=false; page 2048's homepage redirect is rejected |
| Azora | 24 / 24 / 24 | 24 / 24 / 24 | 24 / 24 / 24, distinct | Latest chapter, views, creation date, title; first two pages checked | Page 102: 11, next=false |
| Hijala | 5 / 5 / 5 | 5 / 5 / 5 | 10 / 10 / 10, distinct | None offered | Page 64: 3, next=false |
| MangaDar | 30 / 30 / 30 | 30 / 30 / 30 | 30 / 30 / 30, distinct | Native sorting and status; continuing filters have distinct page two; stopped=18, cancelled=12, both terminal | Page 65: 9, next=false |

Native sizes of 5/10/24/30 are per-page sizes, not app catalogue limits. MangaDar and MangaLek use WordPress `/page/N/` paths; Hijala catalogue uses `?page=N` while its search uses `/page/N/?s=...`; Azora uses `/api/query` and count metadata. TeamX has a separate native search pager. MangaTime continuation uses `hasMore`, not its capped advertised total.

## Details, chapters, Reader and download

| Source / representative title | Live rows / internal chapters | Completeness | Online Reader | Normal download: original / stored images |
|---|---|---|---|---|
| TeamX / Fast Break | 15 / 15 | COMPLETE | Chapter 2: 4 pages rendered | Chapter 1: 8 / 25 |
| MangaTime / Blue Lock | 371 / 371 | COMPLETE; hasMore=false, nextCursor=null | Chapter 9: 14 pages rendered | Chapter 1: 42 / 42 |
| MangaLek / Otherworldly Evil Monarch | 257 / 257 | DEGRADED | Chapter 2: 14 pages rendered | Chapter 1: 16 / 16 |
| Azora / Nano Machine | 334 / 334, also matches declared API total | COMPLETE | Chapter 2: 5 pages rendered, identity remains selected during preload | Chapter 1: 3 / 28 |
| Hijala / Lookism | 630 / 630 | DEGRADED | Chapter 02: 10 pages rendered; newest 626 also parsed 34 pages and decoded its first image | Chapter 01: 8 / 31 |
| MangaDar / Kingdom | 935 / 935 unique embedded IDs | COMPLETE | Chapter 2: 37 pages rendered | Chapter 1: 52 / 52 |

TeamX's large **God of Martial Arts** case additionally returned **1055 / 1055 unique chapters across all 27 numbered pages** through NetworkHelper, including dynamically discovered middle pagination links. Independent raw-response traversal made 28 requests because the root and `?page=1` both represent the first page.

Normal tall-image splitting explains the stored-image differences. Download verification checks every original page index and every split-part sequence, including missing-middle detection. No alternate downloader was used. MangaLek/Hijala have no independent advertised chapter total established by this audit, so their verified rendered rows are deliberately insufficient to authorize deletion. Locked/paid content and future site changes remain outside the tested public samples.

## Bugs and repairs

- **TeamX:** search pagination stopped after 24 despite 267 live matches. Recognize the current `tx-pager` next button. Chapter traversal now follows each fetched page's pagination, rejects failed/repeated/empty middle pages, and limits concurrency to two.
- **MangaTime:** raw query interpolation broke quoted searches; missing/error envelopes could become empty success. Encode query strings and validate IDs, arrays, page URLs and terminal chapter signals. Preserve remote chapter IDs in memo.
- **MangaLek:** homepage catalogue and later archive pages mixed different result sets and prematurely ended Popular; chapter fetch failures could reuse existing chapters as fresh success. Use the archive consistently, reject failed empty fallback and partial page lists. A high-page request demonstrably redirects to the homepage with HTTP 200: reject that wrong content instead of adding unrelated cards.
- **Azora:** `/api/posts` ignored catalogue query/sort/limit semantics and supplied 10-item false-terminal pages. Use the live `/api/query` parameters and metadata. Remove the chapter-count sort: it returns the same top 200 items on page two. Reject incomplete chapter totals and page lists; exclude novels from the image Reader.
- **Hijala:** catalogue path pagination repeated page one. Use native query pagination while retaining WordPress search pagination. Reject empty/challenge/incomplete reader results and retain lazy image URLs.
- **MangaDar:** query pagination repeated page one; HTML chapter parsing returned zero despite 935 embedded Alpine rows. Use native path pagination and strictly parse all embedded rows, preserving ID/URL/fraction/date. Validate signed reader images. Add safe representation diagnostics and one fresh retry only for an unusable cached response; no speculative selector expansion or claim of a proven Vivo fix.
- **Reader:** an Android run showed RTL preloading switching Azora from chapter 2 to 3 without input. Preserve the selected page object when adjacent chapters alter adapter positions. The device audit verifies chapter identity after preload and waits for actual image loading, rather than a merely initialized image view.

## Chapter completeness and network behavior

`SMangaUpdate` carries `COMPLETE`, `PARTIAL`, `DEGRADED`, or `FAILED`. Its original two-argument JVM constructor remains available and defaults to DEGRADED for legacy/unverified sources. Only COMPLETE (or local-source) results may remove DB chapters; other results preserve them. FAILED errors before DB updates. The old 50% collapse check is additional protection, not the authorization mechanism. Reconciliation matches URLs, preserves memo fields/remote IDs, and removes number-only state transfer between replacement URLs. Existing explicit duplicate-read preferences remain respected.

Empty metadata/unknown status cannot overwrite valid catalogue/DB values. Details/chapters fetch flags reach the source and reconciliation respects them. Parent cancellation propagates. Updates have a 180-second bound; catalogue loads have a 120-second bound, reject repeated pages, and bound skipped unsupported-content pages. Existing NetworkHelper timeout/cookies/DNS/Cloudflare handling is reused. No new global HTTP stack was introduced. HTML challenges, wrong representations, empty required bodies, invalid JSON envelopes and incomplete page lists produce errors rather than empty success.

## Verification and reproduction

Required commands: `./gradlew :domain:test`, `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleRelease` (JAVA_HOME `/opt/android-studio/jbr`). Domain has 64 tests per tested build variant; app has 152 tests, with no failures/errors/skips in the final unit run. The final combined verification log is `/tmp/mangaro-v3.6-audit/final-gradle.log`.

Opt-in audit build: `./gradlew -PsourceAudit=true :app:assembleDebug :app:assembleDebugAndroidTest`. Install `app-x86-debug.apk` and `app-debug-androidTest.apk` using **`adb -s emulator-5554`**. Run:

```sh
adb -s emulator-5554 shell am instrument -w -e full true -e matrix true -e paginationUi true app.manhwaar.reader.dev.test/eu.kanade.tachiyomi.source.audit.SourceAuditInstrumentation
```

Optional `-e source MangaDar` selects one class; `-e catalogueOnly true` omits chapters/Reader/download. This audit adds sample manga/history/downloads only on the emulator. Screenshots and raw public responses stay in app-private test files. Exported evidence/logs are `/tmp/mangaro-v3.6-audit/`; no manga HTML/images are committed.

ARM64 Release APK: `app/build/outputs/apk/release/app-arm64-v8a-release.apk`.

## Commits

- `91d065f` Protect chapter reconciliation with explicit fetch completeness
- `c3edf01` Repair MangaDar pagination and embedded chapters with response diagnostics
- `f59e733` Use Azora query pagination and verify full chapter counts
- `d1da4ce` Browse MangaLek catalogue consistently and reject failed chapter fallback
- `7867890` Validate MangaTime envelopes and chapter completion signals
- `2619851` Verify every TeamX chapter page before declaring completion
- `5ce5614` Fix Hijala query pagination and reject incomplete reader responses
- `4a15f95` Remove Azora sort that repeats the same 200 titles
- `60cebde` Bound catalogue continuation and propagate cancellation
- `3ea1b99` Keep the selected Reader page when adjacent chapters preload
- `06e6d65` Follow TeamX native search pagination beyond 24 results
- `f4422bf` Reject MangaLek homepage redirects as catalogue responses
- `6a05e07` Add opt-in Android production source and UI reliability audit

The final report commit is the delivered HEAD. No merge, stable tag, physical-device install, source-name hiding or background auto-update work was performed.
