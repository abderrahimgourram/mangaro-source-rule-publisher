# Mangaro V3.6 Phase 05.7.0 — source compatibility baseline

**Status:** source-code, pinned-upstream, and emulator runtime evidence recorded; Vivo physical-device evidence remains **pending**. The earlier Phase 05.7.0 check found no connected device; Phase 05.7.0-B later used the disposable `emulator-5554` sandbox. This document does not authorize a same-ID internal-source cutover. No internal source was registered and no production source code was changed. Extension installations and downloads described below occurred only on the emulator. The public fixtures under `app/src/test/resources/source-compatibility/` are explicitly *not* user database fixtures.

## Recovery checkpoint and provenance

The starting application branch is `experiment/mangaro-phase05.6.4-a`; HEAD is `896c3a422c32a88dd7f5e457da40b3d54c021b70`. Both `mangaro-v3.6-stable-phase05.6.4-a^{commit}` and `recovery/mangaro-v3.6-phase05.6.4-a` resolve to that exact commit. The two previous audit reports were read completely before inspection. They began as untracked documentation; this phase retains them and corrects one memo claim in the full audit.

**Frozen public reference:** [Keiyoushi source commit `60bc15ec9c13b3a4302bc54ca7b9fa892fdae726`](https://github.com/keiyoushi/extensions-source/commit/60bc15ec9c13b3a4302bc54ca7b9fa892fdae726), committed 2026-09-28 13:54:54 UTC. The [extension repository index at `9b72f771dd013b30052a20bb77c3ccb12a1a6631`](https://github.com/keiyoushi/extensions/blob/9b72f771dd013b30052a20bb77c3ccb12a1a6631/index.json) provides published APK `versionCode`, `versionName`, source names and IDs at 14:05:55 UTC. These are *upstream* facts; they do not identify the installed APK versions. Links in this document use hashes, not moving `main`.

## Pinned upstream version and ID matrix

| Source | Package / source class | Base class | Module versionCode + theme base | Published APK versionCode / versionName | lib / source versionId | Published ID |
|---|---|---|---|---|---|---:|
| Azora | `eu.kanade.tachiyomi.extension.ar.azora` / `Azora` | `Iken` | 46 + 27 | 106073 / `1.6.73` | 1.6 / 2 | 2482399499047903203 |
| MangaDar | `eu.kanade.tachiyomi.extension.ar.mangadar` / `MangaDar` | `KeiSource` | 2 + 0 | 106002 / `1.6.2` | 1.6 / 1 | 3975276517041363504 |
| MangaLek | `eu.kanade.tachiyomi.extension.ar.mangalek` / `Mangalek` | `Madara` | 13 + 55 | 106068 / `1.6.68` | 1.6 / 1 | 918460697583900080 |
| TeamX | `eu.kanade.tachiyomi.extension.ar.teamx` / `TeamX` | `KeiSource` | 33 + 0 | 106033 / `1.6.33` | 1.6 / 1 | 4110737012647435874 |
| MangaTime | `eu.kanade.tachiyomi.extension.ar.mangatime` / `MangaTime` | `KeiSource` | 1 + 0 | 106001 / `1.6.1` | 1.6 / 1 | 215553151312092548 |
| Hijala | `eu.kanade.tachiyomi.extension.ar.hijala` / `Hijala` | `MangaThemesia` | 3 + 0 | 106003 / `1.6.3` | 1.6 / 2 | 917436262447415426 |

The IDs above were independently calculated with this checkout's `HttpSource.generateId(name, "ar", versionId)` and cross-checked against the pinned public index. The names matter: MangaLek uses Arabic display name `مانجا ليك`, and TeamX uses `Team X`. The test runs the actual `HttpSource.id` calculation against the pinned expected IDs. **Neither index nor test substitutes for runtime SourceManager and device DB verification.** Candidate older IDs under versionId 1 are Azora `3544999809861403189` and Hijala `4536808529544021974`; these are calculated possibilities, not evidence of installed or historical user records.

### Installed extension matrix — device gate

`adb devices -l` returned an empty list; `lsusb` showed no Vivo/Android USB device. No device packages, paths, APK manifests, SourceManager objects, or private DB were read. All installed fields must therefore remain unknown rather than being filled from the public index.

| Source | Installed package? | Installed versionCode/name | Installed APK path | Runtime ID/name/lang/versionId | Public ID match? | DB ID match? |
|---|---|---|---|---|---|---|
| Azora | unknown | unknown | unknown | unknown | unverified | unverified |
| MangaDar | unknown | unknown | unknown | unknown | unverified | unverified |
| MangaLek | unknown | unknown | unknown | unknown | unverified | unverified |
| TeamX | unknown | unknown | unknown | unknown | unverified | unverified |
| MangaTime | unknown | unknown | unknown | unknown | unverified | unverified |
| Hijala | unknown | unknown | unknown | unknown | unverified | unverified |

**When the device is connected:** run `adb devices -l`; confirm model `V2357A`/`PD2357` with `getprop`; use read-only `pm list packages`, `dumpsys package <package>`, and `pm path <package>`. A private `.ext` may not appear as an installed Android package, so inspect Mangaro's private extension list through a nonmutating app-visible method or a supported debug/run-as path. Runtime `SourceManager` IDs require an existing diagnostics surface, read-only debugger, or temporary inspection build **without replacing the physically validated app or altering user data**. Do not infer runtime IDs solely from APK metadata. If `run-as` cannot access a release app's DB, use an existing user-approved backup or a read-only supported export; do not root, reinstall, or overwrite the app. Inspect DB only through `mode=ro`/`immutable=1` on a copied snapshot, not through update-capable app commands. Avoid dumping history to logs.

## Public URL and memo shape fixtures

The six entries in [`upstream_pinned_cases.json`](../app/src/test/resources/source-compatibility/upstream_pinned_cases.json) are public-source examples or explicit unknowns. Five include a representative manga/chapter path; MangaLek's chapter shape remains unknown because the previous live catalogue request met a challenge and no device extension output exists. The IDs/paths below are **not installed-record compatibility proof**.

| Source | `SManga.url` / manga memo | `SChapter.url` / chapter memo | Request construction and old-record risk |
|---|---|---|---|
| Azora | `solo-leveling-ragnarok#1422`; `{id:1422,slug:...}` | `/series/solo-leveling-ragnarok/chapter-68#89157`; `{seriesSlug,slug,id:89157}` | Details use `/api/post?postSlug=<slug>`; chapter list may use `/api/chapters?postId=<ID>`; page list `/api/chapter?chapterId=<memo.id>`. Old rows missing chapter memo cannot open pages. VersionId 1 records may have a different source ID. |
| MangaDar | `one-piece`; `{}` | `/manga/one-piece/1194/`; `{}` | Details use `/manga/<slug>`; page list uses stored relative path. Remote row ID exists on site but upstream parser drops it. URL slash/path parity matters. |
| MangaLek | unknown from installed parser; upstream Madara generally uses relative paths | unknown | The shared Madara parser and active mirror preference define precise paths; device fixture required. |
| TeamX | `/series/fast-break`; `{}` | `/series/fast-break/15`; `{}` | Base URL + stored relative path. A custom base preference and paginated chapter list are risks. |
| MangaTime | `/manga/blue-lock#697df7820c5d340ac154519f`; `{id,slug,type}` | `/manga/blue-lock/chapter/363`; `{}` | Details tRPC by slug, chapter list by series ID from fragment, pages tRPC by slug+integer chapter number. Decimal/special chapter routes are incompatible with `.toInt()`. |
| Hijala | `/solo-leveling/`; `{}` | `/solo-leveling-179/`; `{}` | MangaThemesia uses domainless paths. VersionId 1 records may belong to a different engine. Image stitching may require interceptor parity. |

The request URLs in the fixture express current upstream intent. They are not a new planned internal parser. The old-record compatibility question can only be answered for **actual** stored records after reading device data and comparing each source's exact installed APK semantics. A simple URL shape check is insufficient for Azora's memo, MangaTime's fragment, Hijala's image transform, or MangaDar signed pages.

## Database evidence and missing real fixtures

No physical DB was available. The following matrix records what must be sampled read-only, with private fields excluded from committed fixtures.

| Source | Existing manga rows | Existing chapter rows | History FK observation | Downloaded chapter observation |
|---|---|---|---|---|
| Azora | unknown | unknown | unknown | unknown |
| MangaDar | unknown | unknown | unknown | unknown |
| MangaLek | unknown | unknown | unknown | unknown |
| TeamX | unknown | unknown | unknown | unknown |
| MangaTime | unknown | unknown | unknown | unknown |
| Hijala | unknown | unknown | unknown | unknown |

Sample one representative row per source when present, preferably a favorite with chapters, and a second only if URL formats differ. Record source ID, exact stored manga/chapter URL and memo keys/required IDs, initialized flag, and whether corresponding history and download records resolve. Record local row IDs only as stable pseudonyms (e.g. `M1`,`C1`) unless raw IDs are needed for a temporary local check; publish only the fact that `history.chapter_id` points to `C1`, not timestamps/durations or reading titles. Do not commit raw private titles, history, personal scanlator choices, full DB files, cookies, or image tokens. If a source has no rows, record `not present` rather than fabricating a sample. A separate local scratch inspection may compare actual numeric IDs and `last_page_read`, but the committed fixture should minimize them. Existing `mangas.sq` and `chapters.sq` are the schema reference.

## Memo round-trip finding — corrected from prior audit

**Proven in current source code and focused test:** `SManga.memo` passes through `SManga.toDomainManga`, `MangaRepositoryImpl.insertNetworkManga`, SQLDelight `MemoColumnAdapter`, `MangaMapper`, and `Manga.toSManga`. `UpdateMangaFromRemote` includes remote manga memo in its update. `SChapter.memo` passes through `Chapter.copyFromSChapter`, `ChapterRepositoryImpl.addAll`, SQLDelight adapter/map, and `Chapter.toSChapter`. `ShouldUpdateDbChapter` compares memo, and `SyncChaptersWithSource` copies the new memo for an exact-URL match, then `toChapterUpdate`/repository update persists it. The prior audit statement that exact-URL chapter sync omitted memo was incorrect and has been corrected. The focused test checks conversion and adapter serialization; it does not operate on a physical DB. `Chapter.copyFrom(other Chapter)` used in backup restore does *not* copy memo, which is a separate backup-merge concern requiring dedicated verification. Historical DB rows may still lack fields because older extensions did not emit them.

Azora's current Iken source needs `chapter.memo.id` for `getPageList`; the chapter URL's `#id` is not used as fallback in that method. MangaTime's `seriesId` is in the manga URL fragment and memo; current chapter-list request reads the URL fragment, so a memo-only series ID is insufficient if the fragment is absent. MangaDar discards `row[0]` remote chapter ID while storing row URL; no chapter memo ID is available for recovery.

## Download compatibility and MangaDar parity

`DownloadProvider` uses `<downloads-root>/<DiskUtil.buildValidFilename(source.toString())>/<sanitized manga title>/<chapter name plus optional scanlator>_<first 6 MD5 hex of chapter URL>`. `HttpSource.toString()` is `"<name> (<LANG>)"`. The current provider recognizes a legacy unhashed chapter directory and the alternative non-ASCII filename setting, including `.cbz`. A same-ID internal source is **not sufficient** to preserve download resolution: name/lang (`toString()`), title, chapter name, scanlator, URL, filename sanitization preference, and storage root must match or have an explicit alias/rename policy. No actual downloaded chapter could be checked without device access. `DownloadCache`/`DownloadManager` look up existing files through `DownloadProvider`; do not rename files during this phase.

**MangaDar Reader:** `ChapterLoader` chooses `HttpPageLoader`; `HttpPageLoader.getPages` checks the exact MangaDar ID and calls `MangaDarPageResolver.getPages` rather than reading `ChapterCache`. The resolver fetches chapter HTML with `source.client`/headers, decodes each `.reader-page img[data-mds]` Base64 URL, rejects placeholders/invalid URLs, and returns fresh image URLs. Reader `source.getImage` then downloads the signed URL. `HttpPageLoader.recycle` still writes page data to ChapterCache, but its MangaDar read path bypasses it.

**MangaDar Downloader:** `Downloader.downloadChapter` calls `download.source.getPageList(download.chapter.toSChapter())` directly. It does **not** call `MangaDarPageResolver`, and it can retain `download.pages` across a retry. At the pinned upstream source, MangaDar's `getPageList` chooses `img.src` before `data-src`. In the sampled live chapter, all 24 `src` values were `data:image` placeholders and the valid expiring URL was in `data-mds`. `Downloader.getOrDownloadImage` then calls `source.getImage(page)` on a placeholder rather than decoding `data-mds`; its retry loop repeats the same page URL. This is a code-path and live-content parity failure; a device download test is still needed to establish the exact user-visible error for the installed APK. The path also does not regenerate expired `download.pages` on failure.

**Result: `MANGADAR_PILOT_NOT_YET_SAFE`.** This applies to *same-ID internal-source replacement now*. Fixing Reader alone would leave downloads broken; the pilot needs one shared fresh signed-page resolver used by both, verified against the installed APK and real downloads before any cutover. No fix is made in this phase.

## Pilot source selection

| Candidate | URL/chapter identity | Parser, image, anti-bot and pagination | Memo/migration risk | First-pilot decision |
|---|---|---|---|---|
| TeamX | Simple relative `/series/...` paths; no required memo shown | Custom HTML; direct images in sampled chapter; paginated chapters with unbounded upstream async requests; no challenge in samples | Source name and custom base URL must match; old DB unverified | **Conditional recommendation**, simplest demonstrated URL/page path; cap/complete pagination in future parser, after device fixtures. |
| MangaDar | Simple slug/relative chapter path; remote ID discarded | Custom HTML; signed expiring `data-mds`; Reader/Downloader split | Same-ID Reader behavior exists, but downloader parity fails | Reject first pilot until shared page resolver and downloads proven. |
| Azora | `slug#postId` and chapter `#id`/memo | Iken API, split chapter endpoint, locks | High memo and versionId 1 migration risk | Reject first pilot. |
| MangaTime | `/<type>/<slug>#seriesId`, chapter number in path | tRPC API/direct images; `.toInt()` page lookup | Series ID/slug coupling and number edge cases | Reject first pilot. |
| Hijala | Relative paths | MangaThemesia plus conditional paired-image interceptor | VersionId 2 and old engine migration risk | Reject first pilot. |
| MangaLek | Mirror-relative paths likely | Madara AJAX; challenge observed on diagnostic catalogue path | Exact installed paths unknown | Reject first pilot pending device and challenge evidence. |

**Recommended first pilot: TeamX, conditional on installed APK/runtime/DB/download fixtures.** It has the simplest demonstrated stored URL and image path among the six without MangaDar's confirmed Reader/Downloader divergence. This is an engineering selection for a future phase, not approval to register it. Its chapter pagination and current site structure still need complete bounded fixtures. If installed TeamX URLs or downloads differ, re-evaluate rather than forcing parity.

## Tests and fixture inventory

- One public JSON fixture with six source identity entries; five have manga/chapter examples and one (MangaLek) intentionally has null URL/memo until verified. **Real private DB fixture count: 0** while device is disconnected.
- `SourceCompatibilityBaselineTest` checks all six IDs through the actual `HttpSource.id`, manga memo conversion/SQL adapter for Azora and MangaTime, and Azora chapter memo conversion/SQL adapter plus `ShouldUpdateDbChapter` memo-only update detection. This is a unit-level compatibility guard, not an installed APK or DB integration test.
- Required future read-only device checks: installed APK/runtime ID matrix, one representative DB row per available source, history foreign-key relation without private timestamps, download path existence and resolver behavior, then exact old-record request construction against the pinned implementation. A fixture with actual sanitized device URL/memo shapes can be added only after inspection.

## Unresolved blockers and decision gate

1. **No ADB device attached:** Vivo V2357A/PD2357 package/version/APK path, SourceManager identity, private DB, history links and downloads cannot be verified. Do not mark six-way compatibility achieved.
2. Existing installed extension versions might differ from the pinned public index. Need manifests or APK hashes and actual source object IDs; package manager listing alone misses private extensions.
3. Old Azora/Hijala versionId 1 records may exist under different IDs and URLs. Neither their presence nor migration path is known.
4. MangaDar downloader has a demonstrated parity gap; same-ID internal cutover is blocked.
5. TeamX pilot choice remains conditional until representative installed-device DB and download paths are checked.
6. Device data access might require a supported read-only backup/export if the physically validated build is non-debuggable. Do not change the app installation merely to gain access.

**Phase 05.7.1 gate:** keep closed until installed APK/source-object/DB IDs are compared, every available source has a sanitized URL/memo fixture, and TeamX old-record/download compatibility is demonstrated or another pilot is selected. This phase stops here; it does not register an internal source.

## Emulator Runtime Evidence

**Phase 05.7.0-B, 2026-09-28.** Evidence labels in this section are **UPSTREAM VERIFIED**, **EMULATOR VERIFIED**, **PHYSICAL DEVICE PENDING**, and **UNKNOWN**. This pass stopped at the user's request before completing every UI and download scenario. The Vivo device was not connected. No internal source was registered and no production source was edited.

### Environment and APK identity

- **EMULATOR VERIFIED:** `emulator-5554`, model `sdk_gphone_x86`, device `generic_x86_arm`, Android 11/API 30, ABI `x86`. `adb devices -l` reported `device`.
- **EMULATOR VERIFIED:** `app.manhwaar.reader.dev` was already present as versionCode 1, versionName `1.0.0-87`, with an existing `tachiyomi.db`. Its original pulled APK SHA-256 was `4e91c4ab0d483ba8ade73fec4a93209876527f82811669bcabb48cb534412cc0`, different from the checkout's then-existing debug APK. The app was rebuilt with `:app:assembleDebug --offline` from branch `experiment/mangaro-phase05.6.4-a` at `896c3a422c32a88dd7f5e457da40b3d54c021b70` and `app-x86-debug.apk` was installed with `adb install -r`, preserving emulator data. Runtime observations after that installation use this build. No production file changed.
- **EMULATOR VERIFIED:** all six extension packages were already installed with the pinned *version numbers*, but their pulled APKs differed from the pinned published APKs; comparison of ZIP contents found `classes.dex` changed in all six. VersionCode alone therefore did not prove binary identity. All six were replaced **on the emulator only** with APKs downloaded from the pinned index at `9b72f771dd013b30052a20bb77c3ccb12a1a6631`. Pulled installed APK SHA-256 values then matched the downloaded published APK byte for byte. The preexisting emulator DB may contain records produced by the previous same-version, different-byte APKs; it is useful legacy-format evidence, not proof of the physical user's records.

| Source | Package suffix under `eu.kanade.tachiyomi.extension.ar.` | Pinned versionCode/name | Installed pinned APK SHA-256 | Installed APK path suffix after reinstall | Upstream ID | Emulator `SourceManager` ID | Physical Vivo |
|---|---|---|---|---|---:|---:|---|
| Azora | `azora` | 106073 / 1.6.73 | `e3d7439773f5148cf50ec492a69ab65d7f792177411a22701be0fc3819e1fe31` | `.../azora-GhTiYHjZV8ZnUCZ-lhf2UQ==/base.apk` | 2482399499047903203 | 2482399499047903203 | **PHYSICAL DEVICE PENDING** |
| MangaDar | `mangadar` | 106002 / 1.6.2 | `bdbdf39b6cc38e4518bffca25c541931512d869c984e72d68584c63a8378b0f6` | `.../mangadar-CLFQCSkuzvUKGRWFHvQ5bA==/base.apk` | 3975276517041363504 | 3975276517041363504 | **PHYSICAL DEVICE PENDING** |
| MangaLek | `mangalek` | 106068 / 1.6.68 | `d27df60c1322a0f0e315b2c10ab7f1e8f2c28c1a83a2e6a9225348b0815b2550` | `.../mangalek-YGdfNe0vFsnhvz7W510vSA==/base.apk` | 918460697583900080 | 918460697583900080 | **PHYSICAL DEVICE PENDING** |
| TeamX | `teamx` | 106033 / 1.6.33 | `bc55c78fac76c36708ce3a70f88ee2a63ef1cdbdfa42eb7ed4f9d5521629bc6f` | `.../teamx-2GO_wSPl_KYZNyjcKY1Lgw==/base.apk` | 4110737012647435874 | 4110737012647435874 | **PHYSICAL DEVICE PENDING** |
| MangaTime | `mangatime` | 106001 / 1.6.1 | `86360bf9fdba81f63c9b86b568b31ee8d49cf44111aa9fd459211b1f0df4a0bd` | `.../mangatime-e8oa-T1uAtDTmA-kxoYlIA==/base.apk` | 215553151312092548 | 215553151312092548 | **PHYSICAL DEVICE PENDING** |
| Hijala | `hijala` | 106003 / 1.6.3 | `ad83172c668870c5bdf5e88b3ba19f2bfa368e585b99d98d6f31357865ebf20f` | `.../hijala-PfDZB4dkS6m7ZJSbTijnFQ==/base.apk` | 917436262447415426 | 917436262447415426 | **PHYSICAL DEVICE PENDING** |

**EMULATOR VERIFIED:** a temporary test-only instrumentation harness queried the app's actual injected `SourceManager`, rather than relying on APK metadata or DB source values. It returned all six IDs above, names `Azora`, `MangaDar`, `مانجا ليك`, `Team X`, `MangaTime`, `Hijala`, language `ar`, and runtime class `keiyoushi.source.Generated`. The generated wrappers exposed `versionId=1` even for upstream Azora/Hijala declarations with versionId 2; the wrapper's property is **not** evidence that the delegate used versionId 1. The exact IDs match the pinned index. The temporary runner is not retained as a permanent test because it shadowed this checkout's configured AndroidJUnitRunner and made live network calls.

### Zero-extension control

**EMULATOR VERIFIED:** before pinning the APK binaries, all six Arabic packages were disabled with `pm disable-user` and the app was force stopped and relaunched. The Browse source screen displayed recommendation rows with “Install” actions; a live installed catalogue source was not demonstrated in that state. **Observed but inconclusive:** Home still displayed prior manga cards, including Continue Reading and “Popular now.” The emulator already had hundreds of DB manga rows and a reading history. Those visible cards do **not** establish a successful live remote refresh without extensions. Global search and `SourceManager.getAll()` were not instrumented while the packages were disabled. All six packages were subsequently re-enabled and replaced with the exact pinned APKs. No user or Vivo extension was touched.

### Bounded source and UI result matrix

`PASS` below means the specified operation succeeded on the emulator. `FAIL` is an observed failure. `INCONCLUSIVE` means the visible outcome or cause cannot be assigned confidently. “Details/chapters” and “page/image” distinguish direct pinned-source calls in the Android app process from actual Reader UI. New/Completed filtered catalogues were **NOT TESTED** for all six, because their genuine semantics were not established. Search used one bounded query (`solo`); no crawling was done.

| Source | Extension installed? | Runtime ID verified? | Discovery popular/latest/search | Details | Chapters | Reader UI | Download UI | Result / confidence |
|---|---|---|---|---|---|---|---|---|
| Azora | PASS | PASS | PASS / PASS / PASS | PASS | PASS (68) | NOT TESTED; page API/image PASS | NOT TESTED | Android source API PASS; old persisted chapter reload PASS; **EMULATOR VERIFIED** |
| MangaDar | PASS | PASS | PASS / PASS / PASS | PASS | PASS (1,171) | PASS, pages 1–2 displayed | FAIL, exact URL-scheme error | `MANGADAR_DOWNLOADER_BROKEN_CONFIRMED`; **EMULATOR VERIFIED** |
| MangaLek | PASS | PASS | PASS / PASS / PASS | PASS | PASS (257) on fresh source result; stored `775` returns 0 | NOT TESTED; page API/image PASS | NOT TESTED | UI “No chapters found” **INCONCLUSIVE** as to root cause; see below |
| TeamX | PASS | PASS | PASS / PASS / PASS | PASS | PASS (15 small list; 1,076 paginated list) | PASS, first page displayed | PASS, 30-page CBZ; offline Reader PASS | `TEAMX_PILOT_READY_FOR_05_7_1` for non-destructive pilot design; physical cutover pending |
| MangaTime | PASS | PASS | PASS / PASS / PASS | PASS | PASS (371) | NOT TESTED; page API/image PASS | NOT TESTED this pass | Old persisted chapter reload PASS; **EMULATOR VERIFIED** |
| Hijala | PASS | PASS | PASS / PASS / PASS | PASS | PASS (180) | NOT TESTED; page API/image PASS | NOT TESTED | Stitching branch **UNKNOWN** |

**EMULATOR TIMING ONLY:** single warm-process samples, including networking and parser work, were: popular Azora ~2.0 s, MangaDar ~7.2 s, MangaLek ~3.9 s, TeamX ~1.3–2.3 s, MangaTime ~2.4 s, Hijala ~2.0 s. Sample latest/search were generally <1 s except MangaLek latest ~1.7–1.9 s. Detail+chapter fetches were Azora ~0.2 s, MangaDar ~2.8 s for 1,171 chapters, MangaLek ~0.4 s, TeamX ~0.3 s for 15 and ~2.8 s for 1,076, MangaTime ~0.3 s, Hijala ~0.7 s. Page-list calls ranged ~0.1–0.6 s; first image GETs ranged ~0.04–2.1 s. These are not Vivo benchmarks, cold Home timings, or stable latency distributions. Download start was observed by the Android notification, but no precise start latency was recorded.

### Persisted URL, memo, and history fixtures

**EMULATOR VERIFIED:** the initial copied DB snapshot had 666 manga rows, 447 chapter rows and 3 history rows. All three history foreign keys resolved to chapter rows; the source distribution of those history rows was not sampled. Initial manga counts by the six IDs were Azora 241, MangaDar 30, MangaLek 133, TeamX 113, MangaTime 120 and Hijala 29. Only Azora (138) and MangaTime (309) initially had chapter rows. These are disposable emulator records, some made by the earlier same-version/different-byte APKs. The separate minimal fixture `app/src/test/resources/source-compatibility/emulator_runtime_cases.json` labels that provenance and excludes private history details, cookies, signed image URLs, and entire DB contents.

- **Azora, EMULATOR VERIFIED:** a persisted manga URL `...#702` reloaded with memo keys `id,slug`; its stored chapter URL ended `chapter-130#136428` with memo keys `seriesSlug,slug,id`. `ChapterRepository` → `toSChapter()` → the pinned runtime source returned 17 pages from that old row. Reloaded manga `toSManga()` → chapter-list request returned 130 chapters. This proves the required remote chapter ID survived that emulator DB path.
- **MangaTime, EMULATOR VERIFIED:** persisted `/manhwa/nano-machine#697df7690c5d340ac15445b3` reloaded with memo keys `id,slug,type`; persisted chapter `/manhwa/nano-machine/chapter/331` had `{}` memo. The reloaded chapter returned 14 pages; the manga fragment allowed a 309-chapter request. This tests an existing emulator row, not a Vivo row.
- **MangaDar, EMULATOR VERIFIED:** app UI inserted `one-piece` and `/manga/one-piece/1194/`; both memos were `{}`. Reloading the stored chapter through the domain repository and pinned source returned 12 page-list entries. The source's page entries were `data:image` placeholders; no stable remote chapter ID was present in memo. The UI displayed a “44 missing chapters” numeric-gap badge for One Piece, but this is not a proof that 44 remote chapters are absent or that Reader failed.
- **TeamX, EMULATOR VERIFIED:** app UI inserted `/series/fast-break` and `/series/fast-break/15`, with empty memo; its 15-chapter list and Reader page list succeeded. The source supplied `chapter_number=-1.0` for `الفصل 15`; the app displayed the chapter. The old emulator catalogue records also used `/series/...` paths, but no preexisting TeamX chapter row was available for historical chapter parity testing.
- **MangaLek, EMULATOR VERIFIED observation:** the pinned source popular result used numeric manga URL `775` and memo `path=/manga/otherworldly-evil-monarch/`; its 257 returned chapters used numeric chapter URLs such as `258` and chapter memo key `mangaPath`. The UI's stored `775` row instead had memo `{"path":"/"}`, initialized=true, and zero chapters. Reloading that stored row via the domain repository into the pinned source returned zero chapters. A separate older emulator row `/manga/calamity-hunter-i-have-an-exp-system/` with `{}` memo was accepted by the pinned source and returned 18 chapters and 8 pages for its first returned chapter. This demonstrates an old manga path can be accepted, but does not establish old stored chapter URL compatibility. The numeric-ID URL and `mangaPath` memo are a material correction to the earlier baseline's “unknown/general Madara-relative” description for this **published APK**. The exact operation that changed the `775` memo path to `/` was not isolated.
- **Hijala:** direct pinned-source result used manga `/solo-leveling/`, chapter `/solo-leveling-179/`, empty memo, 180 chapters, 24 pages and a first image HTTP 200. The initial emulator DB held 29 catalogue rows but zero Hijala chapters; its first sampled DB row `/test/` was not a usable compatibility fixture. Hijala DB→reload→Reader and `#chapter-pages-js-before` stitching remain **UNKNOWN**.

**“No chapters found” classification: INCONCLUSIVE.** It was observed on the correct MangaLek manga detail screen after opening the first popular card and a manual UI refresh. Fresh pinned-source popular and detail/chapter/page/image calls succeeded, while the stored `775` row had `memo.path="/"` and returned zero chapters on a direct reloaded-row call. This rules out a general claim that the source endpoint always returns empty, and it does not establish Cloudflare failure. It does **not** isolate whether the bad stored path arose during an earlier emulator session, catalogue conversion, detail update, UI navigation timing, or another persistence step. Do not classify it as a confirmed parser break or repair production code from this observation alone.

### Reader, downloads, and pilot decisions

**MangaDar, EMULATOR VERIFIED:** pinned extension `getPageList` returned 12 `data:image` placeholders for One Piece chapter 1194; direct `source.getImage` failed before an HTTP image request with `IllegalArgumentException: Expected URL scheme 'http' or 'https' but was 'data'`. The app Reader displayed page 1 and page 2 as real images using its `MangaDarPageResolver`. Tapping the chapter's download action created a download and posted an Android `downloader_error_channel` notification with the same exact URL-scheme error. The download directory contained only `الفصل 1194_d50692_tmp/001.tmp` through `012.tmp`, no complete chapter archive; `d50692` matches the first six MD5 hex digits of `/manga/one-piece/1194/`. One subsequent resume/tap did not yield a completed download; its precise retry error was not independently isolated. Result: **`MANGADAR_DOWNLOADER_BROKEN_CONFIRMED` and `MANGADAR_PILOT_NOT_YET_SAFE`**. The code-path disparity in the earlier baseline remains valid.

**TeamX, EMULATOR VERIFIED:** `/series/fast-break` returned 15 chapters; chapter `/series/fast-break/15` returned 30 pages and Reader displayed its first image. A separate `/series/god-of-martial-arts` returned 1,076 chapters. Its HTML had 40 `div.chapter-card` entries on page 1 and a maximum page link of 27; 26 additional pages times 40 plus 36 is consistent with the returned 1,076. The pinned upstream parser launches page 2–27 requests asynchronously; the exact live overlap/burst and behavior when one page fails were **NOT TESTED**. After clearing the failed MangaDar item from the emulator-only queue, TeamX download progressed to 24/30 in an Android notification and completed as `downloads/Team X (AR)/fast break/الفصل 15‏_01ce70.cbz`. `01ce70` matches MD5(`/series/fast-break/15`) prefix. With Wi-Fi and mobile data disabled and the app force stopped/relaunched, Reader opened the completed chapter and displayed the first page from the local archive. This supports **`TEAMX_PILOT_READY_FOR_05_7_1`** for feature-gated registry/pilot work, **not** same-ID replacement of a Vivo installation. Request burst/failure handling and physical old-record/download checks remain gates before a source cutover.

**MangaLek Cloudflare, EMULATOR VERIFIED:** the actual Android extension/network stack returned populated catalogue/search/details/chapters and HTTP 200 first image for a fresh popular result without observed WebView intervention. The earlier desktop diagnostic `GET /manga/` 403 does not generalize to the pinned Android request path. Cloudflare challenge behavior on the Vivo and later dates remains **PHYSICAL DEVICE PENDING/UNKNOWN**.

**Hijala stitching: NOT TESTED / UNKNOWN.** The sampled chapter produced ordinary image URLs and a successful image GET. No chapter containing `#chapter-pages-js-before` was identified, so neither synthetic loopback interception nor bitmap combination was verified. No actual loopback connection was observed or excluded for that branch.

**Other downloads and Reader UI: NOT TESTED.** The emulator already held MangaTime CBZ archives before this pass, but no fresh MangaTime download or offline Reader validation was performed. Azora, MangaLek, MangaTime, and Hijala page-list and first-image calls succeeded through actual Android source objects; their full Reader screens, later pages and downloads were not exercised. DownloadProvider path derivation remains as documented above. The TeamX archive is the only completed download newly verified end to end here.

### Failure experiments, limitations, and test gate

Stale chapter URL, missing memo, changed manga/chapter slug, forced HTTP 404, partial chapter-list response, one failed TeamX pagination page, and Hijala stitching experiments were **NOT TESTED** before the stop request. No DB rows were deliberately corrupted and no recovery behavior was implemented. Source-provided `chapter_number=-1.0` was observed for several Arabic chapter labels, including TeamX `الفصل 15`, MangaTime `Chapter 363...`, Azora `Chapter 68...`, Hijala `فصل 179`, and MangaLek numeric `258`; app-side recognition and persisted number require separate fixture comparison. No claim of duplicate-free chapter lists across all 1,076/1,171 entries is made from count alone.

`./gradlew :app:assembleDebug --offline` and `:app:assembleDebugAndroidTest --offline` succeeded for the temporary inspection harness. The earlier focused `SourceCompatibilityBaselineTest` had passed in Phase 05.7.0. The requested full `:app:test` and `:domain:test` suites were **NOT RUN in 05.7.0-B** because active testing was stopped at the user's direction. The temporary instrumentation runner was removed from the project after gathering results; its findings are retained here and in the sanitized fixture. No production source behavior was changed.

**Physical device gate remains:** inspect Vivo V2357A/PD2357 installed APK hashes/versions and actual `SourceManager`, real historical manga/chapter URL and memo values, history links, existing download paths, and Reader/network/Cloudflare behavior before claiming user-library compatibility or any same-ID provider replacement. The emulator had earlier APK binaries with identical version numbers but different code, so physical binary identity is especially important. Phase 05.7.1 was not started.
