# Automatic signed-rule publisher

The private Actions repository is `abderrahimgourram/mangaro-source-rule-publisher`.
It includes a committed Android snapshot so probes run the registered native source
implementations through NetworkHelper on emulator-5554. No Android production code changes.

## Operation

See [RELIABILITY.md](RELIABILITY.md) for schedules, coverage, persistent monitoring history,
verification tiers and local checks. Six-hour monitoring does not build Android or use
production credentials. Production publication is disabled in the workflow and CLI,
even if the old repository variable is enabled. Manual staging remains isolated.

## Trust and hosting

Production remains https://mangaro-source-rules.vercel.app. Staging is separately hosted
at https://mangaro-source-rules-staging.vercel.app and uses only the unregistered diagnostic
source ID 9223372036854775708. Native BUILT_IN sources legitimately have no production
manifest. A healthy native parser is never replaced merely to populate the feed.

The existing external P-256 PEM remains at
`/home/jalem/.local/share/mangaro-source-rules/signing/private.pem`, mode 0600.
The two Actions secrets `MANGARO_AUTOMATION_SECRET` and `MANGARO_STAGING_AUTOMATION_SECRET`
contain its signing bundle. `MANGARO_VERCEL_TOKEN` is the user's durable scoped CI credential.
No private key is exported to checkout, Android, deployment, logs, or artifacts. The APK's
existing public SPKI in gradle.properties remains the only rule trust anchor.

## Gates

Each source is independent. Two concurrent publisher checks maximum; Android requests
are capped at 48 per source and a 150-second source deadline. Publisher HTTP clients
have bounded requests, body limits, timeouts, pacing, public HTTPS-only destinations,
and redirect limits. Transient errors never cause publication.

A repair candidate requires three consecutive semantic failures, a previously verified mapping,
unique stable-identity evidence for each changed extraction, schema-1 compatibility,
two identical full validation passes, staging acceptance, and explicit authorization before production publication. Validation
checks differing catalogue pages, search identity, details identity, several works,
all bounded chapter pages, unique chapter identities, exact totals where declared,
retention of known chapter IDs and Reader page counts, and a real image signature. Partial native chapter
results cannot become a COMPLETE generic baseline. Azora NOVEL exclusion is mandatory.

JSON paths/selectors/endpoints/query mappings are inferred only from prior known
values and current response relationships. Ambiguity, inaccessible challenge pages,
unsupported executable transformations, incomplete chapters, or unstable representations
produce no publication and `REQUIRES_COMPILED_UPDATE` after the threshold.

The current generic Android schema replaces the whole source profile. It cannot preserve
all native filters, status/date transformations, or arbitrary cursor/conditional parsers.
Sources whose full native behavior cannot be proven equivalent therefore stay BUILT_IN;
this publisher cannot honestly repair every possible breakage of those sources without
an engine capability update. Existing verified declarative profiles can be repaired.

Per-source signed cache state stores known-good witnesses, counters and revision floors.
A 24-hour post-publication cooldown, candidate hash deduplication, and strictly increasing
reserved revisions prevent flapping. Feed outages leave production manifests untouched.
A staged deployment preserves every other source's signed manifest, verifies the candidate
with the production public key before alias promotion, checks for concurrent feed changes,
and fetches and verifies the public manifest afterwards. Failed validation never promotes.
Artifacts contain sanitized summaries only; raw native evidence is deleted after use.

## Acceptance

`python -m unittest discover -s tools/source-rules/automation -p test_automation.py -v`
exercises field/nesting/endpoint/domain/query/CSS/pagination changes, unsafe candidates,
unstable representations, tampering, chapter loss, isolation, and restart deduplication.
The staging dispatch additionally uses the real production signing key, real HTTPS feed,
automatic inference and publication, then the unchanged Android repair engine and a
separate process restart. It never modifies the production feed.
