# Bounded Android production-path audit — 2026-10-01

Every request ran through NetworkHelper and the real internal source parser on
emulator-5554. Catalogue-produced objects continued into details/chapters, and an
actual chapter continued into page discovery plus one bounded image MIME/magic probe.
Raw responses stayed in private device/local storage and are excluded from artifacts.

| Source | Health | Popular p1/p2 | Latest | Search | Details | Chapters (three works) | Reader | Automatic outcome |
|---|---|---:|---:|---|---|---|---|---|
| TeamX | HEALTHY | 10/10, distinct | 40 | pass | 3 pass | 15/4/13 COMPLETE | pass | BUILT_IN retained |
| MangaTime | HEALTHY | 24/24, distinct | 24 | pass | 3 pass | 371/238/305 COMPLETE | pass | BUILT_IN retained |
| MangaLek | DEGRADED | 10/10, distinct | 10 | identity variant | 3 pass | 257/126/601 DEGRADED | pass | no unsafe rule published |
| Azora | HEALTHY | 23/24, distinct | 20 | pass | 3 pass | 334/165/310 COMPLETE | pass | BUILT_IN retained |
| Hijala | DEGRADED | 5/5, distinct | 5 | pass | 3 pass | 630/202/245 DEGRADED | pass | completeness unverified; BUILT_IN retained |
| MangaDar | HEALTHY | 30/30, distinct | 30 | pass | 3 pass | 935/122/186 COMPLETE | pass | BUILT_IN retained |
| MangaSwat | DEGRADED | 20/20, distinct | 20 | pass | 2 pass, 1 clean error | 185/190 COMPLETE; third rejected | pass | no unsafe rule published |

Large regression checks: TeamX **God Of Martial Arts: 1055 COMPLETE**; Azora
**Rebirth Of The Urban Immortal Cultivator: 621 COMPLETE**; MangaSwat
**Nano Machine: 333 COMPLETE**. Azora novel exclusion passed for the physical
example; **Rabbit Holes: declared/parsed zero, COMPLETE**. Decimal chapter identity
and exact known chapter retention are also tested in the deterministic publisher suite.

MangaLek's catalogue identity `/manga/otherworldly-evil-monarch/#775` becomes
`/manga/otherworldly-evil-monarch` in search. The audit reports an identity variant,
not an empty search. It does not guess an equivalent remote ID or publish a lossy parser.
MangaSwat's **Omniscient Reader's Viewpoint**, series 193603, declares 315 chapters;
its first raw page has 200 unique chapter IDs and **three empty slugs**. The native
parser correctly rejects required missing identities. Other sources continue normally.

No production manifests were created: seven 404s remain valid BUILT_IN state. Native
filters/status/date/conditional representations cannot all be preserved by schema 1's
whole-profile replacement. Confirmed failures without a safe equivalent profile are
reported REQUIRES_COMPILED_UPDATE after three separated monitoring observations.

## Hosted production monitor acceptance

Private Actions staging run [36902550037](https://github.com/abderrahimgourram/mangaro-source-rule-publisher/actions/runs/36902550037) passed: three semantic observations generated one signed repair, revision 12; HTTPS publication verification, unchanged Android engine activation, COMPLETE chapters/Reader and forced-process-restart persistence all passed.

Production run [36903849705](https://github.com/abderrahimgourram/mangaro-source-rule-publisher/actions/runs/36903849705) passed with publication enabled. All seven checks completed independently. TeamX, MangaTime and Azora were HEALTHY; MangaLek and MangaSwat had one pending semantic-failure observation; Hijala retained its unverified native chapter-completeness state (631/202/245 on this later run). MangaDar failed catalogue/search semantic validation in hosted CI despite passing the earlier local Android audit. Details, chapters and Reader were therefore not reachable for MangaDar in CI. This is an observed representation/runtime difference, not a proven selector repair; diagnostics remain sanitized and no guessed rule was published.

All seven production manifests remain absent (valid BUILT_IN 404 state). No native behavior was replaced. The repository publication gate is enabled and the six-hour UTC schedule is active. Unsupported native whole-profile mappings remain an explicit schema-1 limitation; this acceptance proves automatic repair of supported verified declarative mappings, not universal repair of every native parser.
