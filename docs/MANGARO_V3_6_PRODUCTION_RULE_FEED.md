# Production source-rule feed

Public HTTPS directory: https://mangaro-source-rules.vercel.app
Dedicated static Vercel project `jalem/mangaro-source-rules` (no backend, no Git connection
or changes to other sites). Only `production/public/**` and `vercel.json` are uploaded.
No APK rebuild is needed to publish a normal rule repair.

`gradle.properties` contains `sourceRulesUrl` and the production P-256 DER
SubjectPublicKeyInfo Base64 `sourceRulesPublicKey`. Both are public and version-controlled.
The private PEM is **only** `/home/jalem/.local/share/mangaro-source-rules/signing/private.pem`
(directory permissions 700, file 600). Back it up securely outside this repository. Losing
it prevents future updates for this public key; rotating the trusted key requires an APK.
Never put it in `production`, a payload, Git, an APK, or hosting environment variables.

## Initial deployment

All seven registered internal sources remain BUILT_IN. `index.json` records each existing
source ID and its publication state; `/SOURCE_ID.json` deliberately returns HTTP 404 until
its first reviewed repair. This is handled by the existing engine without disabling healthy
sources. There are no placeholder manifests pretending to be production parsers.

Schema 1 profiles replace all operations. A catalogue-only approximation is insufficient:
native metadata/status/date transformations, verified chapter retrieval and source-specific
image behavior must also be retained. Native filters disappear on generic activation;
current schema cannot retain Azora/MangaDar's supported filters. Other native readers/details
also use conditional extraction and metadata transformations not representable by direct
field paths. Accordingly no weaker revision-1 replacement is published just to fill the feed.
Future genuinely supported full repairs can be signed and published at the existing ID path.
This deployment does not extend the engine's declarative capabilities.

`/verification/v1|v2|v3|bad-signature/SOURCE_ID.json` contains explicitly isolated signed
acceptance fixtures for an unregistered diagnostic ID (`9223372036854775708`). These are data-only simulations against real
static HTTPS response/image files, **not Azora website parser rules**. Production maintenance
never requests these directories and acceptance uses a separate private RuleStore, leaving
all registered sources and user data untouched. Their signed source ID cannot match any real
source, preventing replay of public test profiles as production repairs. V1's `pages` extraction fails against the
changed `images` representation; V2 repairs it. V3 has a valid signature but invalid chapter
shape; the tampered envelope has an invalid signature.

## Publish a future repair

1. Edit a full schema-1 payload using the existing source ID. Preserve native manga/chapter
   identity and metadata semantics; verify all operations including complete chapters.
2. Increment `revision` beyond every previously accepted or rejected candidate.
3. From the Android project root, with this machine's existing Vercel login:

```bash
python3 tools/source-rules/publish.py /absolute/path/to/repair.payload.json
```

The command checks the external private key against the APK's public key, verifies existing
local/live signatures and revision, signs with the existing offline signer, deploys the static
public directory using pinned Vercel CLI 62.1.0, and verifies the exact public manifest.
It rejects verification fixtures and unknown IDs. Local publication files are restored on
CLI failure; if public post-deployment verification fails, inspect the actual deployment
before publishing again. Vercel login is needed for publication only; readers fetch anonymously.
A cloned checkout reconstructs its dedicated Vercel project link from public `hosting.json`.
The APK retains its own strict schema/signature checks and bounded live candidate validation.
Signing is an operator approval, not permission to bypass chapter safety.

For a public-data-only site redeployment without changing a manifest:

```bash
cd tools/source-rules/production
npx --yes vercel@62.1.0 deploy --prod --yes --scope jalem
```

Cache headers require revalidation. Active/last-known-good signed rules are cached in the app;
feed outage neither erases them nor disables a healthy native source.

## Emulator acceptance

Build opt-in debug tests with the same public production Gradle values:

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew -PsourceAudit=true :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-x86-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e productionFeed activate app.manhwaar.reader.dev.test/eu.kanade.tachiyomi.source.audit.SourceAuditInstrumentation
adb -s emulator-5554 shell am force-stop app.manhwaar.reader.dev
adb -s emulator-5554 shell am instrument -w -e productionFeed restart app.manhwaar.reader.dev.test/eu.kanade.tachiyomi.source.audit.SourceAuditInstrumentation
```

`activate` expects a fresh dedicated acceptance store (not production rule storage). `restart`
loads V2 in a separate process, exercises an actual simulated feed exception, proves pages
continue using ACTIVE, rejects live tampered and semantically invalid signed manifests,
and preserves ACTIVE/LKG. The acceptance profile is also rejected when staged against
Azora’s real ID, proving that signed diagnostics cannot become a built-in repair. Every representation/image/envelope request uses NetworkHelper's
client against the public HTTPS deployment; no response interceptor or test signing key. The diagnostic source is never registered or
shipped in the Release APK; all seven existing built-in source IDs remain unchanged.
The isolated acceptance store remains for inspection. Never automate the physical Vivo.

Acceptance run (2026-10-01): both separate emulator instrumentation processes completed with
`failures=0`; live HTTPS/signature, automatic V2 activation, COMPLETE=1 chapters, restart,
feed outage, bad signature and correctly signed invalid candidate all passed. Domain 75/app
193 tests passed. Operator preflight rejected insecure PEM permissions and accidental
publication of acceptance profiles. Final tests use the final production key and diagnostic ID. Hosting verified as Vercel Hobby; the production alias
is publicly accessible while deployment-specific preview URLs retain account protection.
