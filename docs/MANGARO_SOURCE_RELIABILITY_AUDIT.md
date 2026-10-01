# Mangaro V3.6 source reliability and internal sources audit

**Date:** 2026-09-28. **Scope:** read-only code and upstream extension inspection plus small live GET/HEAD probes. No app code, database, parser, or Git history was changed. The observations below are a point-in-time snapshot, not a continuous uptime measurement. Source implementation references are to Keiyoushi `main` as retrieved on this date; pin an upstream commit before implementation.

**Git starting state:** branch `experiment/mangaro-phase05.6.4-a`, HEAD `896c3a422c32a88dd7f5e457da40b3d54c021b70`, clean working tree. The last 15 commits were inspected; they cover Home grouping, category pagination, new/completed discovery, and related tests. Inspection used source text, SQLDelight queries, upstream raw Kotlin/Gradle files, and small public HTTP probes. No Android build/device test was run for this analysis-only phase.

## Executive Summary

**Decision:** This checkout is a sound foundation for first-party sources *if* they enter through the existing `Source` / `CatalogueSource` / `HttpSource` contract and `SourceManager`. The source API, network stack, DB models, Reader loaders, download system, and library updater already form the needed pipeline. An internal source registry and a modest reliability layer are warranted. A second manga/chapter store, second Reader, or wholesale APK copying would create incompatible identity and security behavior.

The highest-risk existing behavior is `SyncChaptersWithSource`: chapter identity is exact `url` within a manga. A changed chapter path deletes the old row and inserts a new one; read/bookmark state is transferred only by recognized chapter number, while chapter row ID, page progress, history foreign key, and download path can be lost. An empty fetched list is rejected, but a partial nonempty list can delete every omitted chapter. This is especially relevant to Azora, whose current detail API returned **0 embedded chapters despite `totalChapterCount=68`** and requires a separate endpoint, and TeamX, whose chapter list is paginated. The upstream Iken extension already detects the Azora mismatch and switches APIs. Existing MangaDar Reader handling demonstrates that signed page URLs should be regenerated, but the generic Reader still writes page lists including image URLs to disk.

The six sources do not share one parser. Azora is Iken JSON API; MangaLek is Madara AJAX and was Cloudflare-blocked on its catalogue endpoint; Hijala is MangaThemesia with an image-stitching interceptor; MangaDar is a custom HTML/JSON parser with expiring signed images; MangaTime is tRPC JSON; TeamX is custom HTML with paginated chapters. Site homepages returning 200 did not prove catalogue, chapter, or image availability. The live probe matrix below names exact checks and gaps.

**Recommended order:** establish ID/URL compatibility fixtures and an internal registry with collision policy; pilot one source without replacing extension IDs blindly; harden chapter reconciliation and Reader recovery behind opt-in/controlled rollout; onboard remaining sources; then add persistent discovery caching and bounded background refresh. Preserve extension support throughout. Do not promise background freshness at an exact time: Android schedules periodic work opportunistically.

## Current Architecture Map

| Concern | This checkout's implementation | Consequence |
|---|---|---|
| Source API | `source-api/.../Source.kt`, `CatalogueSource.kt`, `online/HttpSource.kt`, `online/ParsedHttpSource.kt`; `SManga`, `SChapter`, `Page` | Internal sources can implement the same contracts. `HttpSource` supplies default URL/header/image behavior and generated IDs. |
| Runtime registry | `app/.../source/AndroidSourceManager.kt`; `domain/.../source/service/SourceManager.kt` | Registry currently rebuilds from `LocalSource` and installed extension sources only. It writes extension instances by ID into a map; a collision silently replaces one. `getOrStub` preserves metadata for unavailable sources. |
| Extension discovery | `ExtensionManager.kt`, `extension/util/ExtensionLoader.kt`, `ExtensionApi` | Shared Android packages and private `.ext` files are discovered; package metadata identifies `Source`/`SourceFactory`; library 1.4/1.6 compatibility, signatures, trust, NSFW gate, child-first class loader, and source instantiation are handled here. Merely placing Kotlin/APK files into the app does not register them or reproduce their resources/dependencies. |
| Recommended Arabic entries | `extension/DefaultArabicSources.kt` | These are recommendation metadata/package names, not built-in parsers. With zero installed extensions, Home's remote catalogue is empty. |
| HTTP | `core/common/.../network/NetworkHelper.kt`, `AndroidCookieJar`, `CloudflareInterceptor`, `UserAgentInterceptor`, `UncaughtExceptionInterceptor` | Shared OkHttp: 30 s connect/read, 2 min call, 5 MiB HTTP cache, cookies, UA, optional DoH, Cloudflare WebView handling. Extension-specific clients/rate limits may wrap it. A generic Home semaphore does not cap Reader, updater, and extension activity globally. |
| Cover images | `App.newImageLoader`, `data/coil/MangaCoverFetcher.kt`, `MangaCoverKeyer`, `CoverCache` | Coil memory/disk plus library cover cache; covers depend on source-specific headers when fetched. |
| Persistence | SQLDelight `data/sqldelight/.../mangas.sq`, `chapters.sq`, `history.sq`; `MangaRepositoryImpl`, `ChapterRepositoryImpl` | Manga has local `_id`, `source`, `url`; chapter has local `_id`, `manga_id`, `url`, number, state, memo. History joins chapter ID. No generic remote chapter ID column. JSON `memo` can hold source fields. |
| Discovery/Home | `HomeViewModel.kt`, `GetSourceDiscovery.kt`, `GetSourceCapabilities.kt`, `DiscoveryCategoryGridViewModel.kt`, `DiscoverySnapshotStore.kt` | Home filters enabled installed HTTP catalogue sources, does first two sources then remainder, with per-batch semaphore 2. Four categories can issue requests. Snapshot is process memory only and category grid may refetch page 1. Discovery interactor converts exceptions to empty success, hiding health failure. |
| Search | `BrowseSourceViewModel.kt`, `SearchViewModel.kt`, catalogue API | Source-specific search runs via Paging or global search, then `NetworkToLocalManga`. |
| Manga details/chapters | `MangaViewModel.kt`, `UpdateMangaFromRemote.kt`, `SyncChaptersWithSource.kt` | DB data shown first; source fetch based on uninitialized manga/empty chapters or manual refresh; chapter sync uses exact URL and deletes missing rows. |
| Library updates | `LibraryUpdateJob.kt`, `MetadataUpdateJob.kt`, `LibraryPreferences.kt` | Existing WorkManager periodic/manual library update, grouped by source, semaphore 5 across source groups, fetch interval and device/network restrictions, notifications/new downloads. This is distinct from Home discovery. |
| Reader | `ReaderViewModel.kt`, `ChapterLoader.kt`, `HttpPageLoader.kt`, `ChapterCache.kt`, `MangaDarPageResolver.kt` | Reader opens by local chapter ID, then source; download/local/HTTP loader; disk page-list/image cache. MangaDar bypasses page-list cache and decodes `data-mds`. Page retry only retries the existing image URL; it does not reconcile a moved chapter. |
| Downloads/migration/backup | `DownloadManager`, `DownloadProvider`, `DownloadCache`, `Downloader`, `MigrateMangaUseCase`, `MangaRestorer` | Paths and cache keys depend on source name, manga title, chapter name/scanlator/URL. Existing migration copies some chapter state by number but does not guarantee history, last page, downloads, or row IDs. Backup maps chapters by URL. |

`HttpSource.generateId(name, lang, versionId)` takes the first eight MD5 bytes of `name.lowercase()/lang/versionId` with the sign bit clear. It explicitly says increment `versionId` for incompatible URL changes. This is *source identity*, not a random internal namespace. The calculated IDs from the inspected upstream declarations are: Azora v2 `2482399499047903203`, MangaDar v1 `3975276517041363504`, MangaLek (display name `مانجا ليك`) v1 `918460697583900080`, Team X v1 `4110737012647435874`, MangaTime v1 `215553151312092548`, Hijala v2 `917436262447415426`. **Validation required:** verify these against the exact installed APK/index and on-device source IDs before adopting them; generated source metadata can override a default.

## Data Flow Diagrams

```text
A Home: HomeTab -> HomeViewModel -> enabled source IDs -> SourceManager
   -> GetSourceCapabilities -> GetSourceDiscovery -> CatalogueSource method
   -> extension/internal HTTP + parser -> SManga -> SourceDiscoveryItem
   -> NetworkToLocalManga (insert/reuse by source+URL) -> HomeDiscoveryItem -> UI
   Memory-only ViewModel/snapshot; HTTP and cover caches are separate.

B Search: Browse/Global Search -> selected CatalogueSource.getSearchManga
   -> source request/parser -> SManga -> NetworkToLocalManga -> result card.

C Details: card local manga ID -> MangaRepository -> MangaViewModel shows DB
   -> UpdateMangaFromRemote -> source.getMangaUpdate -> manga metadata/chapters
   -> MangaRepository + SyncChaptersWithSource -> screen flows.

D Chapter refresh: manga.source + manga.url + stored chapters -> source fetch
   -> SChapter list -> distinctBy(url) -> exact URL comparison -> insert/update/delete
   -> chapter SQL rows, last-update/fetch interval, optional downloads/notifications.

E Reader: chapter local ID -> chapter row -> manga.source -> SourceManager
   -> ChapterLoader (download/local/HttpPageLoader) -> cached pages or source.getPageList
   -> source.getImageUrl if absent -> source.getImage -> ChapterCache -> viewer.

F Library: WorkManager/manual -> LibraryUpdateJob restrictions/queue
   -> source-grouped manga -> UpdateMangaFromRemote -> chapter sync
   -> new-chapter notification/download; history remains keyed to chapter row.
```

Network occurs at source catalogue/search/details/chapter/page/image methods, Coil covers, and optional source metadata calls. Local manga IDs are allocated by SQL insert through `NetworkToLocalManga`; chapter IDs are allocated by insert during sync. Source IDs are source implementation fields. Persisted URLs are **extension-defined opaque strings**; many are relative, some contain slugs, and some include embedded `#id`. `HttpSource` defaults to `baseUrl + url`, but sources override when necessary. Staleness points: Home has no persistent discovery snapshot or TTL; manga metadata/chapter list waits for refresh; chapter URL may drift; cached page URLs can expire. Failure points include source missing, Cloudflare/transport, parser empty/exception, partial list, DB reconciliation, download path drift, and image CDN/headers.

## Source Extension Architecture

Upstream extension modules live in [Keiyoushi extensions-source](https://github.com/keiyoushi/extensions-source). Current declarations use library 1.6 and generated `@Source` entry points from Gradle metadata. Many inherit `KeiSource` and multisource libraries. Copying an APK or class into the app would break generated metadata, injected source identity, theme libraries/resources, class-loading/trust semantics, and possibly Android package visibility. Even copying source Kotlin requires integrating all dependencies and preferences. Duplicate IDs in `AndroidSourceManager` currently overwrite based on iteration; internal-plus-external coexistence needs an explicit priority, diagnostics, and one canonical instance per ID. An extension with the same ID but newer parser may be preferable until parity is verified. Internal source changes become app releases unless a safe declarative subset is remotely configured.

| Source | Extension package / class (source class before generated entry point) | Calculated current ID |
|---|---|---:|
| Azora | `eu.kanade.tachiyomi.extension.ar.azora` / `Azora` | 2482399499047903203 |
| MangaDar | `eu.kanade.tachiyomi.extension.ar.mangadar` / `MangaDar` | 3975276517041363504 |
| MangaLek | `eu.kanade.tachiyomi.extension.ar.mangalek` / `Mangalek` | 918460697583900080 |
| TeamX | `eu.kanade.tachiyomi.extension.ar.teamx` / `TeamX` | 4110737012647435874 |
| MangaTime | `eu.kanade.tachiyomi.extension.ar.mangatime` / `MangaTime` | 215553151312092548 |
| Hijala | `eu.kanade.tachiyomi.extension.ar.hijala` / `Hijala` | 917436262447415426 |

### Shared versus source-specific

Shared: catalogue/manga/chapter/page interfaces; OkHttp, cookies, UA, Coil, SQLDelight, Reader, source manager, and migration/backup. Source-specific: request paths and method, API schema, URL encoding, chapter IDs/memo, filter semantics, page parsing, image transforms, rate limits, domains, and anti-bot behavior. Even within WordPress-family sources, MangaLek uses Madara AJAX while Hijala uses MangaThemesia HTML/script and image stitching.

## Source-by-Source Dossiers

### Azora

- **Implementation:** `src/ar/azora/.../Azora.kt` is `Azora : Iken()`; module uses theme `iken`, lib 1.6, base `https://azorafly.com`, language ar, versionId 2. API host is derived as `https://api.azorafly.com`. ID calculated above. No alternate-domain logic in this module.
- **Catalogue/search/filter:** popular/latest both call `GET /api/query?page=&perPage=18&searchTerm=&...` with `orderBy` filter; search uses same endpoint. Iken fetches `/api/genres`; status/type/sort/genre filters are API parameters. `perPage=18`; if an API page contains only filtered-out novels, Iken recursively advances, potentially adding latency/request load. “New” depends on the shared filter's labels, which the current Home capability heuristic may or may not recognize; do not equate “latest” with “new series” without a fixture.
- **Details/chapters:** `GET /api/post?postSlug=...`; stored `SManga.url` is `slug#postId`. Iken detects `totalChapterCount > embedded chapters` and switches to `/api/chapters?postId=...`. Chapters carry an API `id` and slug in `SChapter.memo`; page list uses `GET /api/chapter?chapterId=<memo.id>`, not chapter HTML. `getChapterUrl` reconstructs `/series/<seriesSlug>/<chapterSlug>`. Locked/coin/shortlink states are explicit. If memo is missing, Reader throws “Refresh Chapter List.” Old manga URL lacking `#postId`, changed slug, or versionId drift are migration hazards.
- **Images/headers:** returned image URLs are storage URLs ordered by API `order`; default shared UA/cookies. No required Referer or signed expiry proven in code. Live `storage.azorafly.com` image HEAD was 200 `image/webp`; no anti-hotlinking established.
- **Live:** homepage 200; `/api/query` popular/search 200 JSON; selected details 200 with `totalChapterCount=68` and zero embedded chapters; `/api/chapters` 200 with 68-class data; selected `/api/chapter?chapterId=89157` 200 with image list. Fragile points: API schema, memo ID, split chapter endpoints, locked flags, slug#ID format.

### MangaDar

- **Implementation:** custom `MangaDar : KeiSource`, lib 1.6, base `https://mangadar.com`, ar, versionId default 1, calculated ID `3975276517041363504`; source-specific rate limit `2`. No alternate-domain code found.
- **Catalogue/search/filter:** popular `GET /manga/page/{page}/` (server redirected page 1 to `/manga/`); latest `GET /manga/?sort=new`; query search `GET /wp-admin/admin-ajax.php?action=mangaverse_search&q=...` JSON, with no next page. Blank-query filters use `/manga/` plus `sort/type/status/genre`; completed uses status filter. The “New” Home heuristic must be tested against actual filter label/value; `sort=new` in the extension is **latest**, and a synthetic `sort=newest` probe does not establish New-series support. List selector is broad `a[href*=/manga/]` with exactly two path segments and a title; a redesign can silently yield zero.
- **Details/chapters:** `GET /manga/<slug>/`. Manga DB URL is just `<slug>`; chapter DB URL is a relative path from compact `rows` in `div[x-data]`, such as `/manga/one-piece/1194/`. Rows include remote ID but current parser discards it. Numeric chapter field is converted with `toFloatOrNull() ?: 0f`, so nonnumeric special chapters collapse to 0. Pagination of chapter rows was not observed. Empty/malformed `rows` returns empty, which sync rejects but cannot distinguish transient parser breakage from a genuinely empty series.
- **Pages/images:** upstream `getPageList` reads `.reader-page img` `src`/`data-src`. The app's `MangaDarPageResolver` instead reads Base64 `data-mds` and bypasses disk page-list reuse. Live chapter HTML had 24 `data-mds` and 24 `data:image` placeholders. Decoded URL had `mdrs`, chapter ID, index, `exp`, nonce, signature. A one-byte ranged GET followed a 302 to `storage.mangadar.com` and returned 206 `image/webp`. Signed URL expiry is directly indicated by `exp`; exact clock semantics and lifespan need testing. No special Referer observed in parser, but do not infer none is ever needed. Resolver keys on one source ID and uses source headers. Existing app workaround needs parity in downloads and every other page-list path, not just Reader.
- **Failure points:** stale signed page-list cache, placeholder accepted as image, slug/path changes, remote ID discarded, row JSON/DOM changes, `0f` collisions.

### MangaLek

- **Implementation:** `Mangalek : Madara()` with Arabic chapter date formats; theme `madara`, base mirrors ordered `https://mangalik.net`, `https://lekmanga.online`, `https://like-manga.net`, `https://lekmanga.site`, `https://manga-leko.site`; display name `مانجا ليك`, ar, default versionId 1. Module does not implement extra page logic. Shared Madara theme handles WordPress AJAX `/wp-admin/admin-ajax.php` using `madara-core/content/content-archive`, catalogue/search/filter, details, chapter extraction, and image parsing. Mirror preference can change the active base without changing relative stored paths.
- **Catalogue/search/filter:** Madara popular/latest/search use AJAX POST, not the simple HTML GET used in the probe. Shared filters include order, genres, status and other template fields; exact current response must be tested with the extension client after challenge handling. Date parser tries Arabic month/date then `yyyy-MM-dd`.
- **Live:** homepage `https://mangalik.net/` returned 200, but `/manga/` and a generic search URL returned 403 Cloudflare “Just a moment.” These were diagnostic requests, **not the actual Madara AJAX POST**; no parser-success conclusion is possible. Prior [Keiyoushi issue #3251](https://github.com/keiyoushi/extensions-source/issues/3251) reports ban/captcha and empty chapter symptoms; [#17838](https://github.com/keiyoushi/extensions-source/issues/17838) reports broken filters in a 2026 version. Those are reports, not proof of current APK behavior.
- **Failure points:** anti-bot challenge, mirror/domain variation, AJAX template changes, large all-chapter loads, parser selector changes. Per-source conservative backoff matters more than extra parallelism.

### TeamX

- **Implementation:** custom `TeamX : KeiSource`, lib 1.6, base customizable, current default `https://olympustaff.com`, display name `Team X`, ar, versionId default 1; rateLimit(10 requests/s) in the extension. No shared Madara parser. A custom base preference can change host; paths remain relative.
- **Catalogue/search/filter:** popular `/series/?page=N`; latest homepage `/?page=N`; query `/search?keyword=...`; blank-query filters use `/series` with type/status/genre URL params obtained from page options. HTML selectors include `div.listupd div.bsx`, `div.last-chapter div.box`, `div.tx-grid a.tx-card`, and `a[rel=next]`. No clearly supported New-series order filter found; Home “New” should be classified unsupported until proven.
- **Details/chapters:** `/series/<slug>`; details use fixed selectors. `div.chapter-card` carries `data-number`, `data-date` and a chapter link. Locked cards are skipped. Pagination scans all numeric pagination links and fires `async` GETs for pages 2..N **without a local cap**; this can flood a large series despite the extension rate limit and can fail the entire list if one page fails. Stored URLs are relative `/series/<slug>/<chapter>`. Source order assumes all pages return in order.
- **Pages/images:** `div.image_list canvas[data-src], div.image_list img[src]`; live selected chapter used `<img src=...>`. A one-byte ranged image GET with chapter Referer returned 206 `image/png`. Default UA/cookies; no signed URLs or *required* Referer proven. Live homepage/catalogue/search/details/chapter all returned 200. Fragile points: selectors, pagination, locked chapters, slug changes, high request count.

### MangaTime

- **Implementation:** custom `MangaTime : KeiSource`, lib 1.6, base `https://mangatime.org`, ar, versionId default 1; tRPC API `/api/trpc/...`, not WordPress. No mirror logic or custom rate limit in module.
- **Catalogue/search/filter:** `search.searchSeries` tRPC GET with batch envelope `{"0":{"json":...}}`, page size 24, `sortBy=popularity` or `recent`; text query uses same endpoint. `getFilterList` is not overridden, so Home New/Completed are unsupported. An initial synthetic probe encoded `query:null` and got HTTP 400 (`query` expects string); omitting it returned 200 and 24 results. This does **not** prove the extension is broken: its serializer likely omits nulls; the successful shape is the meaningful fixture.
- **Details/chapters:** `content.getSeriesBySlug` and `content.getChapters(seriesId,limit=-1)`. Manga DB URL is `/<type>/<slug>#<seriesId>`. Chapter DB URL is `/<type>/<slug>/chapter/<number>`; DTO does not persist remote chapter ID although endpoint response has one. Pages parse URL path segment 4 with `.toInt()`, so decimal/noninteger chapter numbers would throw. `content.getChapterPages(seriesSlug,chapterNumber)` returns pages and lock status; a separate asynchronous `content.trackView` POST follows. A slug change affects both manga/chapters even though series ID exists. Query for all chapters may be large.
- **Images:** direct returned URLs are prefixed to base if relative, spaces encoded. Live selected `getChapters` 200 with 371 entries; selected page query 200, `isUnlocked=true`, 20 pages; first URL was a same-domain PNG. A ranged GET to that URL returned 200 `image/png` (server ignored Range). No signed expiry/Referer proved.

### Hijala

- **Implementation:** `Hijala : MangaThemesia()`, theme `mangathemesia`, lib 1.6, base `https://hijala.com`, ar, versionId 2; module notes earlier move from ZeistManga back to MangaThemesia. This version change is strong evidence of previous URL/engine incompatibility, not a safe ID to apply retroactively.
- **Catalogue/search/filter:** MangaThemesia uses HTML listings and status/type/order/genre filters. Popular/latest map to order filters; search uses site query. Shared selectors include `.utao .uta .imgu`, `.listupd .bs .bsx`, and `#chapterlist li`/`.bxcl li`; page list normally `#readerarea img` with script fallback. Exact “New” semantic availability needs a fixture rather than guessing from a generic order label.
- **Details/chapters/pages:** stored manga/chapter URLs are domainless paths. Live home and `/manga/?order=popular|update` were 200, details `/solo-leveling/` 200 with real chapter links such as `/solo-leveling-179/`; chapter HTML 200 with `#readerarea` and image markup. A selected image ranged GET redirected from HTTP to HTTPS and returned 206 `image/jpeg`. A hidden `#/chapter-{{number}}` template appears before real entries: a naive link scraper would select an invalid placeholder. The extension's actual selectors target the chapter list, not all links.
- **Image transform:** when `#chapter-pages-js-before` exists, extension pairs source images into synthetic `http://127.0.0.1/?leftImage=...&rightImage=...` URLs; its **source-specific OkHttp interceptor** fetches both pieces, combines bitmaps, and returns JPEG. This is a virtual request, not a local server. Directly copying the page parser without the interceptor breaks images. A live selected page did not show that marker, so stitching on that chapter was **not demonstrated**. It may be conditional. Check memory use and image-size limits in device testing.

## Live Probe Results

All probes used a conventional browser UA from a terminal, not the Android extension's cookie jar, Cloudflare WebView, or image pipeline. Times are single wall-clock samples and not performance benchmarks. HTTP redirects were followed by GET unless noted. Site content can change immediately.

| Source | Home | Catalogue/search | Detail/chapter/pages/image | Interpretation / gap |
|---|---|---|---|---|
| Azora | 200 HTML, 0.23 s | `/api/query` 200 JSON ~0.16–0.21 s | `/api/post` 200, `/api/chapters` 200, `/api/chapter` 200; storage image HEAD 200 webp | API shape confirmed for one work. Exact filter variants and Reader device path untested. |
| MangaDar | 200 HTML, 0.23 s | popular page 1 redirected to `/manga/`, 200; latest/filters 200; AJAX search 200 JSON | `/manga/one-piece/` 200, `rows` present; chapter 200 with 24 signed data attributes; image signed GET -> 302 storage -> 206 webp | 200 for synthetic `sort=newest` is not evidence that it sorts new works. Signed URL recovery is necessary. |
| MangaLek | 200 HTML, 0.24 s | diagnostic `/manga/` and search GET 403 challenge | Not probed further due challenge | Real Madara AJAX POST, chapters, pages, images remain unknown. |
| TeamX | 200 HTML, 0.29 s | `/series/`, home/latest, `/search?keyword=solo` all 200 HTML | `/series/fast-break` 200 with chapter cards; `/series/fast-break/15` 200 with `div.image_list img`; ranged image GET 206 PNG | Filter combinations and pagination completeness untested. |
| MangaTime | 200 HTML, 0.35 s | valid tRPC popular 200 JSON 24 items, 1.11 s; synthetic null-query form 400 | tRPC chapters 200 with 371, pages 200 with 20 URLs/unlocked; image GET 200 PNG | Detail-by-slug not separately probed; successful tRPC response established real schema. |
| Hijala | 200 HTML, 0.77 s | popular/latest/search 200 HTML | `/solo-leveling/` 200, `/solo-leveling-179/` 200, reader markup present; ranged image GET HTTP→HTTPS→206 JPEG | Stitching branch not probed; placeholder link in hidden template must be ignored. |

A page/body status alone is insufficient: inspect content type, expected selectors/JSON, nonzero results, challenge markers, redirect target, and a bounded representative chapter. The probes did not authenticate, crawl, bypass challenges, or verify all manga/chapter types. HTTP response headers of note were Cloudflare `server`/`cf-ray`, JSON versus HTML content types, MangaDar 302 location to storage, and webp/PNG content types. No relevant `Set-Cookie` requirement was demonstrated in these samples.

## Missing Chapter / Chapter Not Found Investigation

The failure string can represent different layers: local chapter ID missing after sync, source absent, stale `SChapter.url`, source remote ID/memo missing, server 404, page list empty, or image failure. These must have separate error types and observability. Current Reader `ReaderViewModel` resolves a local chapter ID from a loaded list and errors if absent; `ChapterLoader` selects download/local/HTTP, and `HttpPageLoader` calls source page APIs. It does not refresh the chapter list on 404 or remap a changed URL. Generic page image retry enqueues the same page; stale signed URL remains stale.

`SyncChaptersWithSource` first rejects a fully empty nonlocal list with `NoChaptersException`. Then it `distinctBy(url)`, assigns `sourceOrder`, runs `prepareNewChapter` and `ChapterRecognition`, and matches DB chapters by exact `url`. It calculates removed rows from any URL missing in the returned list and **deletes them**. It inserts new rows for new URLs. It updates name/number/scanlator/order/date/**memo** for exact URL matches: `ShouldUpdateDbChapter` compares memo, and the sync copy includes it. For deleted numbers, it can copy `read`, `bookmark`, `dateFetch` to a new row with the same recognized number. It can also mark duplicate-number chapters read under a preference. It does **not** preserve original row ID, `last_page_read`, history FK, downloads, or a safe alias when URL changes. `history.sq` joins by chapter ID, so deletion is significant. Source order is not identity; reorder changes metadata. Scanlator/name/date are metadata, not current match keys. A partial successful list is dangerous; no completeness proof exists before deleting omitted chapters.

For a domain change, relative URL storage usually survives if path remains the same and source ID remains stable; absolute stored URLs may not. A manga slug change breaks a path-based manga URL and often every chapter URL. Chapter slug changes are treated as remove/add. A remote ID may exist in Azora/MangaDar/MangaTime response yet be discarded or only in `memo`; no cross-source universal remote chapter ID exists. Do not infer equivalence from title or chapter number alone: alternate releases, language, scanlator, volume, special, corrected upload, and duplicate chapter numbering occur.

## Chapter Identity Analysis

The candidates below operate within one verified manga and source. They do not make chapter numbers globally unique. A source ID, local manga ID, original URL, and source-specific remote ID or memo should be recorded separately when available; each has a different lifetime and migration meaning.

### Candidate reconciliation strategies

| Strategy | Reliability / false-match risk | Cost / compatibility / history and downloads |
|---|---|---|
| A Exact URL | Current deterministic match, safe when URL stable; brittle on slug/domain/trailing slash/query changes | Fast; no schema; remove/add loses row-linked state; compatible with all extensions. |
| B Normalized URL | Safe for known equivalences (scheme/host when source owns host, trailing slash, benign tracking params); dangerous for semantically significant query, fragments, case, percent encoding | Cheap; add source-specific canonicalization policy. Preserve original URL for requests; normalization alone must not rewrite DB or broad-match unrelated hosts. Downloads still keyed to old URL unless row URL/path migration is designed. |
| C Chapter fingerprint | Number+title+scanlator/date can find candidates when URL changes; high false-match risk, especially `0`, specials, decimal parts and scanlator duplicates | Compute cheaply but require high confidence; do not treat as stable global ID. Safe only within a confidently matched manga and source. |
| D Multi-key reconciliation | First exact; then known redirect/canonical path; then source remote ID; then unique high-confidence fingerprint | Best long-term tradeoff. In-place row URL update preserves chapter ID/history/progress, but download rename/alias needs coordinated handling and rollback. Ambiguous matches must remain unresolved. |
| E Refresh-before-fail | On confirmed stale chapter URL or source-specific missing-ID, refresh full chapter list then retry once | Better user recovery; costs network and may trigger destructive sync. **Do not run current sync as part of retry** until completeness checks and conservative matching exist; use read-only remote comparison first. Avoid retry loops and respect 403/429. |
| F Redirect/slug recovery | Follow permitted same-source redirects; compare new list and unique remote ID/path/fingerprint | Good for explicit redirects and a unique equivalent; unsafe for site-wide redirects to homepage/login. Validate content type and final path. Avoid automatically opening a different numbered release. |
| G Historical alias map | Old source+manga+chapter URL → canonical chapter ID/new URL can preserve deep links | Useful after verified in-place remap; extra storage and bounded retention needed. Never store expiring page/image URLs as aliases. Requires backup/sync/download compatibility policy. |

**Recommended invariant:** a chapter row's local ID is the durable identity for history and progress. Replace its URL in place only after uniquely verified source-scoped equivalence and a successful representative page-list validation; retain old URL as an alias for rollback/links. Keep source-provided stable IDs where available in memo or a later schema evolution. Treat uncertainty as a visible unresolved chapter, not a silent reassignment. Do not delete rows on a partial list; compare counts, pagination completion, and previous totals, and quarantine suspicious shrinkage pending a second check/manual action.

## Chapter Number Edge Cases

`ChapterRecognition` accepts a supplied number if `> -1` or exactly `-2`; otherwise it lowercases, strips the manga title, changes commas/hyphens to dots, and finds ASCII `[0-9]+(.[0-9]+)?` with limited alphabetic suffixes. It uses the first number after unwanted volume/season tags are removed when multiple numbers exist. The domain chapter model uses `Double`; legacy `SChapter.chapter_number` uses `Float`, so decimal precision can be lost. `-1` means unrecognized, not a valid negative chapter. Arabic-Indic digits (`١٠`) are not matched. Arabic words `الفصل` alone are irrelevant; `الفصل 10.5` parses 10.5. `Chapter 10 Part 1` tends to choose 10. `Extra`, `Special`, `Oneshot`, `Prologue`, `Epilogue`, `Side Story` without digits stay -1 unless extension assigns a number. `1.5` and `10.1` parse; `0` is valid; `-1` becomes positive 1 after hyphen substitution, a likely failure. `10a` maps to 10.1; `10.1` can collide. MangaDar maps nonnumeric server number to **0f**, masking the unrecognized case. MangaTime page URL parser `.toInt()` rejects decimal chapter numbers. TeamX `data-number` is embedded in a title and parsed by generic recognition; Arabic-Indic digits likely fail.

Safer compatibility plan: keep existing numeric field for Mihon sorting/tracking, add a separate structured/normalized chapter label in a later phase (not during this audit), and use a source-specific remote ID or URL as primary identity. Normalize Unicode decimal digits for *comparison*, preserve original label/URL, classify special/part/volume explicitly, and never match solely on number for `0`, negative, special, or ambiguous duplicates. Fixture-test all listed cases plus Arabic digits, ranges, suffixes, locale separators, two-number titles, and duplicate scanlators against actual source responses.

## Reader Failure Analysis

After the chapter row resolves, Reader can fail at page-list HTTP/parser, per-page URL resolution, or image fetch. `HttpPageLoader.getPages` uses `ChapterCache` disk JSON keyed by `mangaId + chapter.url` except MangaDar, which calls `MangaDarPageResolver` fresh. On recycle it saves `Page(index,url,imageUrl)` for generic HTTP sources. Image bytes are cached by the exact image URL in a 100 MiB disk LRU. A 403/404 after signed URL expiry remains tied to that stale URL. Page retries call `source.getImage(page)` again with the same `page.imageUrl`; if the URL is empty, `getImageUrl` is called once. Reader preloads four pages, which helps perceived latency but adds requests. Local/downloaded chapter paths have separate loaders.

**Safe future retry sequence:** classify failure; for transient timeout/5xx, bounded backoff on same request; for 403/404 or source-declared expiring URLs, invalidate only that chapter's page list, fetch a fresh list once, preserve page index where lengths agree, and retry the page with fresh image URL; for chapter HTML/API 404, attempt a read-only chapter-list lookup and unique identity match before any DB mutation. Respect `Retry-After` for 429. Cloudflare challenge should lead to user-visible WebView/cookie remedy, not automated high-volume retries. Never persist signed/nonce URL lists beyond their validity; use in-memory TTL or source opt-out. Do not generalize MangaDar's `data-mds` parsing to all sources; generalize only the *policy* that page URLs may expire. Hijala needs its interceptor in any internal implementation; otherwise `127.0.0.1` pseudo-URLs fail.

## Network / HTTP Failure Analysis

The shared OkHttp client handles cookies, UA, Cloudflare challenge via WebView, optional DoH, and small RFC-style HTTP cache. It does not confer universal source rate limits or prove an endpoint is healthy. `awaitSuccess` throws on non-2xx, whereas plain `asJsoup`/extension helpers have their own behavior. A 200 HTML Cloudflare page may parse as empty rather than fail. `GetSourceDiscovery` catches arbitrary exceptions and returns an empty result; Home cannot distinguish no results, parser breakage, or block. Latency from the local probes was subsecond for most endpoints, but 2-minute call timeout and all-source batching can dominate first load. Any endpoint health logic needs expected content and result shape, not HTTP status alone.

## Source Maintenance & Parser Update Problem

| Option | Security/update speed/offline | Maintenance/cost/privacy/reliability |
|---|---|---|
| A Copy six sources into main APK | No runtime code download; slow app-release fixes. Offline cached content possible | Copying implementation alone misses theme libs/generated metadata. Fork maintenance and app size increase; user still connects directly. |
| B Internal source modules in build | Same release speed but clear ownership, tests and package boundaries | Recommended baseline. Source manager must register them and handle ID collisions. Keeps direct Reader/no server dependency. |
| C Signed declarative remote configs | Fast for base URL, selector, filter label, simple endpoint shape; safer than arbitrary code when schema, allowlist, signatures, rollback are enforced | Cannot represent Azora/MangaTime API logic or Hijala image stitching safely; config parser becomes a platform and requires tight validation. Could be a later narrow enhancement. |
| D Backend parsing | Fast centralized fixes, common cache/health, less device parser drift | Server cost/abuse/privacy/IP, single failure point, terms/content rights, Reader latency; can be blocked by site. Not justified as universal first step. |
| E Hybrid local Reader + optional discovery/backend | Backend can accelerate Home while Reader remains direct; can degrade to local | Operational complexity, matching identity between backend and local, privacy and source load. Explore only after local baseline telemetry. |
| F Auto-managed trusted extensions | Fast upstream parser updates and familiar API | Still package install/trust/signature, distribution and Google Play policy concerns; zero-extension goal not met in a first-party sense. Useful transitional support, not foundational replacement. |

**Recommendation:** B now; perhaps C for a small allowlisted configuration surface after stable fixtures. Keep extension compatibility. A backend is a product/operations decision after measured failure and cost data. Do not ship remotely downloaded executable parser code as an easy update channel. [Google Play Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/16273414) restricts downloading executable code outside Play; distribution/legal review is required if Play is targeted. Site content licensing/terms and source load also require review before bundling or proxying.

## Continuous Background Update Analysis

`LibraryUpdateJob.setupTask` already schedules unique periodic WorkManager work at user-selected **hours**, with 10-minute flex, connected/unmetered/Wi-Fi/charging/battery-not-low constraints. It separates manual/auto work, groups manga by source and runs up to five source groups, each sequential internally. Its manga fetch interval/restrictions reduce work. It updates chapter lists and notifications, **not** Home discovery. `MetadataUpdateJob` exists separately. Android periodic work is inexact and may be delayed by Doze/battery/constraints; the [official WorkManager docs](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest) set a 15-minute minimum and explain delays. App-specific freshness should be opportunistic, not a hard clock promise.

Proposed layers (conceptual initial values, tune with measurements): Home opens and displays persistent last-good category snapshot immediately; if foreground snapshot age >15–30 minutes, refresh the first eligible sources with maximum two shared source operations. Session in-memory dedupe ~5–15 minutes. Persistent discovery snapshot TTL for refresh ~1–3 hours with stale-while-revalidate display up to 1–3 days and source/URL/version key; never turn a failed refresh into an empty persisted snapshot. Library updates retain user interval/fetch-window behavior and should not share a duplicate crawl with discovery. Background discovery, if eventually added, uses unique constrained periodic work perhaps 6–12 hours and small per-source budget. Health checks should piggyback on real requests; independent checks no more than several hours and only if stale. Per-source cooldown after 429/Cloudflare. A **global** scheduler should coordinate Home, grid, background, library, and prefetch; per-screen semaphores alone do not preserve the observed stable ~2 operations globally.

## Performance / Latency Analysis

- Startup: extension APK discovery/class instantiation and initial source Flow availability precede Home remote catalogue. No installed extension means no Home network source. Persistent discovery cache could make first frame immediate.
- Home: first two sources' four category requests complete before an early result; remaining sources run afterward. Batch semaphore 2 prevents unlimited concurrency within one batch, but a slow first source and capabilities calls delay first result. `GetSourceDiscovery` failure-to-empty obscures diagnostics. Category grid seeds from process memory then fetches initial page again; cold process has no seed. `NetworkToLocalManga` inserts/reuses card rows, adding DB latency.
- Details: DB content is shown first; uninitialized/empty chapters trigger source refresh. Some sources fetch details and chapters in one request (MangaDar/TeamX), others make multiple API calls (Azora/MangaTime), TeamX may fetch many chapter pages at once. Cache completeness and `fetchDetails`/`fetchChapters` behavior differ by extension.
- Reader first page: local row lookup, page-list cache/API, image URL, image bytes. MangaDar fresh 1 MB chapter HTML on the sampled chapter is an unavoidable current request for signed URLs; generic caching can save chapter HTML parsing only while URLs remain valid. Four-page prefetch aids scrolling but can amplify anti-bot/rate limits.
- Avoidable: duplicate Home/grid page 1, repeated failed source probes, unbounded TeamX pagination, unnecessary source details when fields are already fresh, missing persistent Home snapshot, and long waits for unavailable sources. Dangerous: prefetching all chapter pages/images, unlimited parallel source calls, preloading signed page lists hours ahead, full library plus discovery at the same time.

## Internal Source Architecture Options

First-party sources should implement `CatalogueSource`; HTTP sources should normally extend `HttpSource` or a compatible `KeiSource` abstraction only after verifying the class/dependency contract. `AndroidSourceManager` should expose internal and extension sources transparently through existing `SourceManager` methods. Internal registration should be explicit and deterministic. For each source, establish the precise package version, generated ID, URL/memo semantics and test vectors. Choose one canonical provider per ID; never permit silent overwrite. A compatible internal replacement may use the existing ID **only if** manga URL, chapter URL, memo, headers, and page behavior are compatible with already stored rows. If not, use a new source ID and controlled migration. Source ID collision is a deliberate compatibility choice, not a namespace shortcut. Source preferences/filtering may also need to regard internal sources as enabled even with zero installed extensions; current Home's `GetEnabledSources` path is extension-shaped and must be audited in the first implementation phase.

The app should own first-party source registry/contracts, reliability policy, cache/health telemetry, migration tools, and user-facing recovery. It should not duplicate Mihon's DB/repositories, Reader, HTTP/cookie/Cloudflare framework, download manager, or extension loader. Keep third-party extensions installed, trusted, visible, and usable; no forced removal. Use source-scoped request policy, not app-wide parser assumptions.

## Extension Compatibility

Existing installed extensions should continue through the current loader, signature trust, package metadata, and source APIs. The internal registry should resolve same-ID providers explicitly, report conflicts, and allow a per-source rollback to the external provider while maintaining the same stored source ID. A new internal ID is appropriate only for incompatible URLs or behavior and requires an intentional migration. Default Arabic recommendation metadata is not a parser substitute. Keep external-source search, downloads, WebView access, backups, and stubs working even when all six internal sources are present.

## Existing Library Migration Risks

**Best case:** internal source exposes exact old ID and understands every stored `SManga.url`/`SChapter.url`/`memo`. Manga `_id`, chapter `_id`, favorites, history, progress, and most downloads remain reachable without a DB migration. This requires a parity test using backups/fixtures from every relevant extension version. It must survive external extension coexistence through deterministic precedence and a rollback toggle. In particular, Azora/Hijala `versionId=2` may not match older IDs; MangaTime needs `#seriesId`, Azora needs `#postId` and chapter memo ID; Hijala/MangaLek relative path formats depend on theme version.

**If ID or URL format differs:** the existing `MigrateMangaUseCase` is insufficient for lossless transition. It creates/uses a target manga, maps chapter read/bookmark/date by number, moves categories/tracks/cover/favorite, but does not preserve original chapter row IDs/history/last-page-read and may leave/remove downloads. [Mihon's source migration guide](https://mihon.app/docs/guides/source-migration) explicitly warns downloads may remain behind. A future dedicated migration should preflight source parity, back up, map manga by stable remote identity, map chapters conservatively, preserve local IDs where safe, move/alias download paths, retain history/progress, be transactional/idempotent, and be reversible. Test with real pre/post backups and device file storage. Do not “simply change `mangas.source`” without verifying source URL/memo and download directory resolution.

## Caching Strategy

| Object | Proposed store/TTL and stale behavior | Invalidation/safety |
|---|---|---|
| Home catalogue | memory 5–15 min; persistent last-good 1–3 h refresh, show stale 1–3 days with age | key source ID/category/filter/page/parser version; no negative cache on transport/parser failure; bound rows/storage. |
| Manga details | DB is existing persistent cache; conditional foreground refresh per source/age | manual refresh, content change, domain/ID migration; preserve user custom title/cover. |
| Chapter lists | DB already persists; use source-aware fetch interval; retain last-good on suspicious partial list | completeness checks, pagination success, count delta, manual refresh. Never delete on failed/partial list. |
| Source health | memory plus small persistent rolling counters/hysteresis, hours-scale | do not store private cookies/tokens or full HTML; reset after successful representative parse. |
| Redirect/domain alias | small source-scoped allowlist with expiry and validation | reject cross-origin/login/homepage redirects unless explicitly verified. |
| Page lists | in memory or short disk TTL only for stable URLs; source opt-out (MangaDar already bypasses disk read) | on 403/404 invalidate/refetch once; never persist short-lived signed image URLs. |
| Image bytes/covers | retain existing ChapterCache/Coil policies | bytes are safe to cache by URL until eviction; URL-key churn and privacy/storage limits matter. |

## Source Health Strategy

Lightweight source-scoped state machine: `UNKNOWN` until evidence; `HEALTHY` after expected nonempty parse; `SLOW` after rolling latency threshold; `TEMPORARY_FAILURE` for timeouts/5xx with success history; `BLOCKED` for 403/challenge or repeated 429; `PARSER_BROKEN` for 200 wrong content/selector or schema exceptions across multiple representative requests; `DOMAIN_CHANGED` only for verified redirect/known migration. A single empty catalogue may be legitimate, so use hysteresis and compare to prior nonempty samples. Piggyback on normal requests, record endpoint class, status, content type, redirect chain, parse count, latency, source version, and failure type. Never log signed image URLs, query searches, cookies or HTML. Expose actionable fallback and stale content; no automated challenge bypass flood. Cross-device telemetry requires explicit privacy design.

## Failure Recovery Matrix

| Failure | Likely cause | Bounded automatic action | User fallback |
|---|---|---|---|
| 403 chapter/image | hotlink/UA/cookie/Cloudflare/lock | verify challenge vs lock; one fresh page list if signed URL; no blind retries | WebView/sign-in or source blocked message |
| 404 chapter | stale path, removed entry, wrong base | read-only fresh chapter list, unique remap candidate, one retry | changed/removed chapter, manual refresh/migrate |
| 404 manga | slug/domain move or removal | verified redirect/canonical lookup; no title-only silent switch | source migration/search |
| 404 image | expired/moved CDN object | regenerate page list once, retry same index | page unavailable; keep progress |
| 429 | rate limit | respect `Retry-After`, source cooldown and jitter | try later; preserve cache |
| 5xx | transient site error | bounded exponential backoff, stale cache | temporary source error |
| timeout/DNS | network/site/DoH | one bounded retry when appropriate, offline cache | network diagnostic |
| TLS/SSL | certificate/domain problem | no insecure downgrade; verify domain | explain secure connection failure |
| 200 challenge/login HTML | anti-bot/auth | classify content, cookie/WebView path if supported | source blocked/login needed |
| 200 malformed/schema/empty parser | site redesign, partial HTML | avoid destructive sync, health `PARSER_BROKEN` after confirmation | report source problem; old data shown |
| empty chapter list | truly empty, lock, API pagination mismatch | retain DB, confirm once/manual; compare reported count | no chapters or parser error |
| missing local chapter ID | deleted/reordered DB row | alias/reconciliation lookup only if unique | return to chapter list |
| source unavailable | uninstalled/collision/disabled | `StubSource`, preserve DB and downloads | install/enable or choose internal source |
| domain migration | redirect/mirror change | source-scoped host validation; relative URL reconstruction | verified source update |
| expired signed URL | CDN token lifetime | discard page list and regenerate once | image temporarily unavailable |
| `data:image` placeholder | lazy signed data attr | source-specific decode/validation | parser update needed |

## Security Considerations

Internal source code executes in the app trust boundary; audit URL construction, redirects and pseudo-URLs, avoid SSRF-like arbitrary host fetch via remote configs, bound HTML/JSON/bitmap size, reject `javascript:`/`file:` page URLs, and limit decoded data. Hijala's `127.0.0.1` pseudo-host is intercepted by its client; never let an unrecognized loopback URL escape to the network or read arbitrary URL parameters. `MangaDarPageResolver` bounds encoded `data-mds` length and requires HTTP URL but currently accepts any HTTP host; a future source-specific host policy may be useful. Cookies, user searches, signed URLs and chapter history must not enter diagnostic logs or backend caches. Extension signature/trust remains distinct from first-party code. Google Play executable-code policy and content rights must be reviewed for any distribution choice. Do not use backend proxies to evade site restrictions.

## Findings Classified by Confidence

**PROVEN (local code/live response):** source manager contains only local plus installed extensions; source IDs derive from name/lang/version unless overridden; Home uses enabled installed catalogue sources and per-batch semaphore 2; discovery exceptions become empty; sync exact-matches chapter URL and deletes omitted rows; history uses chapter ID; Reader caches generic page lists and MangaDar bypasses cache/decodes signed images; existing WorkManager library updater uses hours and semaphore 5; upstream source classes/bases above; six homepages 200 in sampled requests; MangaLek catalogue GET 403 challenge; Azora API chapter split; MangaDar signed URL/302; TeamX and Hijala sample chapter markup; MangaTime tRPC response.

**STRONG INFERENCE:** zero-extension Home has no remote discovery; changed chapter URL can lose history/progress because old row is deleted; arbitrary copying of extensions would fail registration/parity; stale signed MangaDar URLs cause image errors if cached elsewhere; TeamX pagination can create bursts; source-dependent New/Completed detection can misclassify.

**HYPOTHESIS:** some devices see Cloudflare blocks different from terminal; certain Hijala chapters require stitching; some image hosts require Referer; prior extension versions have incompatible URLs or IDs beyond known `versionId=2`; persistent discovery cache materially improves first content on slow networks. Verify before treating as fact.

**UNKNOWN:** exact installed APK versions/IDs in user devices; actual production failure rates; signed URL expiry duration; page image behavior across all six sites; all source filter semantics; source terms/content rights and Play distribution status; whether device data produced by all historical APKs retains required memo fields; compatibility with old Azora/Hijala extension IDs; stability of URLs across historical versions; real physical-device Cloudflare and download behavior. Phase 05.7.0 code inspection confirmed the current SManga/SChapter-to-domain/SQL memo path and exact-URL chapter memo updates; installed-device records still require inspection.

## Recommended Target Architecture

```text
Home / Browse / Details / Library / Reader
                 | existing domain APIs
       SourceManager + canonical ID registry
         /                         \
 internal CatalogueSource/HttpSource   installed trusted extensions
         \                         /
        source-scoped reliability coordinator
        (bounded requests, health, last-good cache,
         conservative chapter identity/recovery)
                 |
     existing OkHttp/cookies/Cloudflare/Coil
                 |
  existing SQLDelight manga/chapter/history + downloads
```

The reliability coordinator should be a small policy layer around existing source calls, not a replacement parser or database. Its components may be staged independently: registry/collision diagnostics; typed result/error classification; source-scoped request budget and health; discovery cache; chapter completeness and conservative remap; Reader single-shot page URL refresh. Maintain one canonical source object per ID and preserve extension interoperability. Source-specific adapters own endpoint/parser/headers/image transforms. The existing stable Home concurrency of approximately two source operations is a ceiling to retain initially; global coordination should prevent other jobs from unexpectedly multiplying it. Source maintenance should be through pinned, tested internal modules and fast app releases, with narrow signed config only after proof of need.

## Implementation Roadmap

Each phase is future work; **none is implemented by this audit**. Capture a pre-phase DB backup and fixture set. Roll back at each feature flag/release boundary without rewriting user data.

| Phase | Goal / affected architecture | Principal risk and rollback | Required tests / physical device validation |
|---|---|---|---|
| 05.7.0 Compatibility baseline | Pin six upstream commits/APK versions, capture source IDs, URLs, memo and representative HTML/JSON/image fixtures; add diagnostic classification design | Wrong baseline; rollback is docs/fixture revision | Compare installed APK IDs and stored DB samples; on device open existing library chapters/downloads from all six. |
| 05.7.1 Internal registry | Register first-party `CatalogueSource`/`HttpSource` through `SourceManager`, explicit collision policy and enabled-source handling, no parser cutover | Duplicate IDs or zero-source Home regression; feature-flag/registry rollback | Unit registry/collision tests, source Flow and stub tests; device with zero, one, and all installed extensions; verify library/history/downloads unchanged. |
| 05.7.2 One pilot source | Implement a single source with complete URL/memo parity, likely MangaDar **only if** signed-page behavior and downloads are verified; otherwise choose simpler TeamX subset | Parser mismatch, cached URL/download mismatch; revert provider priority to extension | Fixture tests catalogue/search/details/chapters/page/image and old DB URLs; physical device new and old library entries, Reader, download, Cloudflare, offline cache. |
| 05.7.3 Chapter safety | Add completeness validation and source-scoped multi-key reconciliation with in-place ID preservation only on unique matches; no schema change initially if feasible | False matches corrupt progress; feature flag off, keep old rows/alias log for rollback | Property/fixture tests for reorder, partial lists, moved URLs, duplicate numbers, special chapters; device backup/restore/history/progress/download tests. |
| 05.7.4 Reader recovery | Typed page/chapter errors, single refresh of expired page URL and conservative chapter remap; source-specific opt-out | Retry loops or load spikes; disable recovery | Mock server 403/404/429/5xx/signed expiry/challenge tests; device sample chapters including MangaDar and Hijala, page index preserved. |
| 05.7.5 Remaining internal sources | Port source-by-source with pinned upstream parity; choose Azora/MangaTime API, Hijala interceptor, MangaLek challenge behavior, TeamX pagination caps individually | API churn, mirror/ID mismatch; per-source provider priority rollback | Each source's request/parse/URL/memo tests, old extension-version DB fixtures; physical device end-to-end including image and downloads. |
| 05.8 Discovery cache/health | Last-good persistent Home snapshots, typed results, source health and bounded global scheduling | Stale/incorrect cards or extra load; clear isolated cache/disable scheduler | Cold-start/offline/TTL/failed-refresh/zero-extension tests; device startup/scroll/network switching and source request counts. |
| 05.9 Background discovery | Unique constrained WorkManager refresh separate from library updates, per-source budgets | Battery/site load; cancel unique work | WorkManager scheduling/constraints/backoff tests; device Doze/metered/Wi-Fi/battery and multi-day observation; confirm no exact-time promises. |
| 06.x Optional remote config/backend | Only after measured parser break frequency and operations/privacy review | Security and backend single point of failure; turn off remote layer | Signed config validation, rollback, allowlist, offline fallback and abuse tests; Play/legal review if relevant. |

## Open Questions

1. Which extension APK builds are actually installed by Mangaro users, and do their generated IDs/URL formats match current Keiyoushi `main`? Obtain backed-up DB samples with consent.
2. Does MangaDar image download path use the same `data-mds` resolver as Reader? Current app special handling is in `HttpPageLoader`; downloader appears to call source page APIs separately.
3. Do source memo fields survive every SManga/SChapter conversion and chapter update? Audit with a round-trip fixture before depending on Azora's chapter ID.
4. Which sources genuinely expose **newly added manga** and **completed** filters? Check actual filter labels and server result ordering; do not infer from HTTP 200.
5. How should legacy Azora/Hijala versionId 1 libraries be treated? Their current IDs differ; migration cannot be assumed lossless.
6. Are TeamX pagination and MangaTime `limit=-1` acceptable on large series and slow devices? Measure counts and request caps.
7. Which Hijala chapters require paired-image stitching, and what are maximum bitmap dimensions/memory costs?
8. What are the site-specific rate limits, robots/terms, anti-bot rules, and legal distribution constraints? Do not turn source health probing into extra traffic.
9. What are the actual MangaDar signature lifetime and `exp` semantics, and do other sources use expiring URLs?
10. What success criteria define “reliable” on real devices: chapter-open success, image-open success, P50/P95 Home first content, update freshness, request count, and battery use? Establish baseline before tuning.

## External References

Primary code and official docs used:

- [Keiyoushi extensions-source repository](https://github.com/keiyoushi/extensions-source), [extension authoring/ID and URL guidance](https://github.com/keiyoushi/extensions-source/blob/main/CONTRIBUTING.md).
- Source modules: [Azora](https://github.com/keiyoushi/extensions-source/tree/main/src/ar/azora), [MangaDar](https://github.com/keiyoushi/extensions-source/tree/main/src/ar/mangadar), [MangaLek](https://github.com/keiyoushi/extensions-source/tree/main/src/ar/mangalek), [TeamX](https://github.com/keiyoushi/extensions-source/tree/main/src/ar/teamx), [MangaTime](https://github.com/keiyoushi/extensions-source/tree/main/src/ar/mangatime), [Hijala](https://github.com/keiyoushi/extensions-source/tree/main/src/ar/hijala); shared [Iken](https://github.com/keiyoushi/extensions-source/blob/main/lib-multisrc/iken/src/eu/kanade/tachiyomi/multisrc/iken/Iken.kt), [Madara](https://github.com/keiyoushi/extensions-source/blob/main/lib-multisrc/madara/src/eu/kanade/tachiyomi/multisrc/madara/Madara.kt), [MangaThemesia](https://github.com/keiyoushi/extensions-source/blob/main/lib-multisrc/mangathemesia/src/eu/kanade/tachiyomi/multisrc/mangathemesia/MangaThemesia.kt).
- [Mihon source migration guide](https://mihon.app/docs/guides/source-migration), [Mihon current repository](https://github.com/mihonapp/mihon), [Mihon issue: deleted chapter loses read status](https://github.com/mihonapp/mihon/issues/3923), [Mihon issue: missing pages/downloads](https://github.com/mihonapp/mihon/issues/587).
- [Keiyoushi MangaLek challenge report](https://github.com/keiyoushi/extensions-source/issues/3251), [MangaLek filter report](https://github.com/keiyoushi/extensions-source/issues/17838), [current extension issue list showing domain/image/parser break classes](https://github.com/keiyoushi/extensions-source/issues).
- [Android WorkManager periodic request reference](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest), [work constraints/periodic guide](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [Google Play Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/16273414).

Local paths cited above are relative to the project root. Live probe URLs are documented in the dossiers and matrix; server content was not saved into the project.
