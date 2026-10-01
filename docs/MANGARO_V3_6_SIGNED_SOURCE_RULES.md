# Signed automatic source repair

The engine replaces internal source operations with verified declarative data. It does not
infer a new parser or download executable code. Publishers still inspect site changes and
publish signed rules; installed apps fetch, validate and activate those rules without another APK.
The seven existing IDs, language/version/name, external-preferred collision policy, Kotlin
fallback parsers and chapter reconciliation safeguards remain in place.

## Production configuration

No Mangaro-owned trusted source-rule endpoint/key exists in this project. The disabled APK
updater points to upstream Mihon and is unsuitable. This build therefore ships with an empty
feed URL/public key and runs the existing parsers. **Automatic remote repair requires the
operator's static HTTPS feed and public key in the initial deployment build.** There is no
fake production endpoint, bundled private key, backend, or executable parser distribution.

Configure Gradle properties (in local configuration, not a committed secret file):

- `sourceRulesUrl`: your real static HTTPS directory URL; no query/fragment, credentials or IP host.
- `sourceRulesPublicKey`: Base64 of the DER SubjectPublicKeyInfo P-256 public key.

Feed files are `{sourceRulesUrl}/{existingSourceId}.json`. Each is an independent signed
manifest/envelope: `{"payload":"<exact JSON string>","signature":"<Base64 DER ECDSA signature>"}`.
Signature algorithm is SHA256withECDSA, over exact UTF-8 payload bytes. Whitespace is signed.
Use an offline P-256 private key; export only its public key for the app:

```
openssl ecparam -name prime256v1 -genkey -noout -out private.pem
openssl pkey -in private.pem -pubout -outform DER | openssl base64 -A
python3 tools/source-rules/sign.py payload.json --private-key private.pem --output SOURCE_ID.json
```

The private key stays outside the repository/app/feed. Publish data files on any operator-owned
static HTTPS host. A valid update must have the same source ID and a strictly higher accepted
revision. Publish a new revision after fixing a structurally rejected candidate. Transient
network validation failures and cancellation do not burn the candidate revision.

## Schema 1

`SourceRules`: schema=1, existing sourceId, independent positive revision, HTTPS baseUrl,
headers, operations, optional validationQuery/validationMangaId. The signed validation search
selects a genuinely produced small series by stable ID without changing Popular/Latest requests.
Required operations: popular/search/details/chapters/pages; latest is
required for a source that supports it. Unknown JSON keys/schema versions are rejected.

Each operation defines:

- endpoint beginning `/`, or `{path}` / `{path}/suffix`, GET/POST, FORM/JSON POST body encoding, named parameters;
- JSON or HTML response; `rows` is a dotted JSON path or a CSS selector;
- named fields with path, HTML attribute, optional flag, ROOT/ROW scope (`root` boolean),
  and fixed NONE/BASE64 transform;
- identity template, and optional required field/value allowlists (e.g. image-content types);
- explicit pagination: page parameter, page size, declared total path and/or boolean next path
  or HTML next selector, max pages (at most 25).

Placeholders: `{page}`, `{query}`, `{url}`, `{path}`, `{slug}`, `{id}`, `{mangaSlug}`, `{segment0}`..`{segment5}`; identity templates
can also use extracted fields. `{path}` preserves the encoded path separators of an existing URL and uses the new base
host (even for stored absolute old-domain URLs). Slugs are the last decoded path segment.
Other endpoint substitutions are encoded; parameters are encoded
by OkHttp. Pagination uses server metadata, never filtered catalogue count.

Catalogue fields: required id/url/title; optional cover/description/author/artist/genre.
Details: required id/title, same ID as requested manga; namespaced existing ID memos and bare
ID manga URLs are supported. Legacy route-only manga additionally require a returned canonical
URL matching the requested route before learning the remote ID; titles are never identity.
Existing URL/memo and non-empty
metadata survive. Optional `chapterTotal` can extract an independent declared details count
(including from ROOT). Chapters: required id/url/name; number, scanlator, epoch-millisecond
date and `memo.*` values optional. Stable ID is stored in memo. Pages: required image;
optional integer order, with unique ordering; NONE/BASE64 image extraction supported.

Required identity/image fields cannot be declared optional. JSON paths are member/index
traversals only. There is no evaluator, JS/DEX/Kotlin/native loading, regex expression language,
remote class loading or arbitrary transform execution. Methods/headers/limits are allowlisted.

## Activation, storage and chapter safety

Per-source atomic private-files snapshot contains BUILT_IN native descriptor, CANDIDATE,
LAST_KNOWN_GOOD, ACTIVE, accepted high-water revision, rejected revision and last-check time.
A staged candidate never overwrites ACTIVE/LKG. On successful validation + replay, ACTIVE
becomes candidate and the previous verified active remains LKG. Native Kotlin is the fallback
when no previous remote version exists. Rollback never lowers the accepted revision floor.
The last accepted envelope is also retained so the exact already-trusted high-water payload
can be revalidated after a temporary rollback; lower remote revisions remain rejected.
Cache is signature-verified again after process restart; damaged snapshots fall back safely.
An independent atomic accepted-revision floor survives snapshot damage to reject downgrades. Loading is off the startup thread;
normal source getters use the in-memory verified snapshot.

Semantic errors trigger DEGRADED -> fetch for that source -> verify -> stage -> bounded live
validation -> replay original operation once -> activate -> HEALTHY. No second repair loop.
If a live active version fails structurally and repair fails, rollback restores LKG/native and
returns a clean error. Invalid candidates leave the previous active/LKG untouched. Feed outage
alone never marks a source unhealthy or discards a working active version.

Validation makes at most eleven logical source requests (fewer without latest or catalogue page 2):
popular 1/2, latest 1, optional signed small-series search, search of produced title, same
produced manga details, up to three
chapter pages, one produced chapter's page list and one image. Original-operation replay is
one additional operation, subject to the same per-request and chapter-page bounds.
Candidate validation is bounded to 90 seconds, response bodies to 2 MiB (feed envelope 384 KiB),
individual source requests to 15 seconds, feed/image requests to 10 seconds. Image validation
checks MIME plus image magic, not merely HTTP 200. Source-client internal redirects are subject
to the existing NetworkHelper client. HTTPS/public-host checks apply to URLs.

Chapter results require a declared total from chapters or details, unique remote IDs/URLs,
no repeated/missing pages, stable totals, exhausted pagination and exact total agreement.
Production chapter traversal caps at 25 pages; validation caps at three pages/150 chapters.
Candidates needing a bigger verification sample are refused rather than labelled COMPLETE.
In-memory witnesses plus existing SQL chapters provide an identity floor. A new parser may not
lose those known identities even if the server declares a matching smaller total. No DB writes
occur during candidate validation. Reconciliation still independently controls deletion.
Unknown identities or missing counts fail safely; candidate support is intentionally limited
to this finite schema. Cursor-only APIs, encrypted readers and arbitrary JS extraction require
a compiled capability change, not remote code. Filters unavailable in generic rules are omitted;
native filters remain until activation. Request headers/image headers are part of signed data.

## Scheduling and isolation

One unique 30-minute periodic WorkManager job, network/battery constraints, plus deduplicated
foreground/background maintenance. Per-source last-check skips recent checks (30 minutes;
reactive checks minimum one minute). No startup network wait. Two candidate jobs globally,
one update per source, sequential bounded validation requests. Parent cancellation propagates.
Existing three-network-operation/one-operation-per-source health layer and supervised Home/
library updates continue. Rule worker failure is isolated per source, not a batch retry.
Disabled/external-preferred sources are not silently replaced or probed as internal.
Existing three-semantic/five-transient failure thresholds and 1-minute-to-6-hour source backoff
remain. Unavailable sources disappear only from new discovery; library/history/downloads stay.
Successful repair automatically resets health with no manual reset.

## Verification

Unit simulations cover JSON pages->images, chapters->post.chapters, title->postTitle,
cover->featuredImage, search endpoint, CSS selector, base domain and pagination parameter
changes; full automatic fetch/signature/validation/activation/replay and process-restart cache.
Also signature tampering, wrong source, schema/downgrade rejection, repeated pages, mismatched
identity/total, null required field, forged image MIME, rollback, per-source isolation, SQL
identity floor, feed outage and recent-check suppression. Prior chapter/state protections remain
in the full test suite.

Opt-in emulator audit: `-PsourceAudit=true`, instrumentation argument `ruleRepair=true`.
It exercises real registered native fallbacks for all seven sources, then a signed synthetic
Azora representation through a derived NetworkHelper client, the registry, production update/
SQL path, Browse UI and real Reader. Test fixture data/key are in androidTest only; the one
fixture manga is removed afterwards, and the registered production source is restored.
No real provider-rule feed is claimed or activated in this unconfigured release.

Final verification: 75 domain / 193 app unit tests, zero failures/skips; 18 focused repair tests.
Offline publisher OpenSSL signature round-trip passed with an ephemeral test key.
Emulator-5554 audit passed with zero failures: live native Popular counts TeamX 10,
MangaTime 24, MangaLek 10, Azora 23, Hijala 5, MangaDar 30, MangaSwat 20; unique 30-minute
work registration; exactly one simulated 404 and one signed feed fetch; automatic validation/
activation/retry; different page 2; cached activation across engine restart with offline feed;
Browse catalogue visible; same produced URL/memo through production update and SQL COMPLETE
chapter; real Reader image rendered. Temporary fixture/source registration was restored/removed.
