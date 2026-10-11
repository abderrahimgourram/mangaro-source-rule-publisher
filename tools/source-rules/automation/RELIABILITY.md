# Source reliability monitoring

## Schedule and budgets

Routine: `17 */6 * * *` (manga). Three-hour retry dispatcher: `47 */3 * * *`. Daily targeted deep: `29 3 * * *`.
Weekly comprehensive: `7 4 * * 0` (UTC; GitHub cron timing is approximate).
Planning performs no source requests. A zero-check planning report explicitly says
`PLANNING_ONLY` / `NOT_EXECUTED`, includes eligible/registered counts, and does not claim
source coverage. The checker rejects missing or duplicate results for selected sources.
Retry checks only due sources, after 1h, 2h and 4h, at most three retries per incident. Eligibility stays 1h/2h/4h; execution occurs on the next available three-hour dispatcher, not at the exact eligibility time.
Daily deep checks at most two eligible sources, oldest attempt first, no more than daily
per source. No due sources skips dependencies, source requests and expensive jobs.
Weekly selects every registered source; in-flight leases are explicitly excluded.

## History and credentials

No new health-state secret. Planning uses the existing `GITHUB_TOKEN` (`contents: read`,
`actions: read`) to restore immutable `source-health-state-v1` artifacts from this exact
workflow's completed, trusted default-branch schedule/dispatch runs. PRs cannot restore
or persist history. Artifact retention is 30 days, refreshed on every checking run.
Checksum detects corruption; authenticated GitHub workflow provenance supplies trust,
not the checksum. Scope mismatch, corrupt/expired history, failed API access or deleted
previously persisted history blocks restoration rather than resetting counters.
Local and Actions locks, generation checks, and checkpointed 1h leases prevent races.
Cancelled/unknown checks preserve counters and last verified successes. Partial sample
success can recover observational failures but cannot establish complete/native health.
Monitoring artifacts are observational history, never production rule authority.
Existing signed `.publisher-state` is read-only and remains independent from new monitoring history.

## Verification and reporting

HTTP 200 is not HEALTHY. A 403/challenge is a blocked observation, not parser proof.
Repeated errors do not independently establish a compiled defect. Failed details/identity
block dependent chapters/reader/text. Complete index status requires legitimate totals,
termination and unique identities; counts from sampled rows are observations only.
Reports contain classifications, operation outcomes, verification tier, transitions,
follow-up eligibility, coverage and measured seconds. Private bodies/cookies/tokens are
excluded. Transition notices are deduplicated; correlated network failures suppress
individual defect notices. Every required code-change check runs; in-job path filtering
skips documentation-only expense without skipping the check itself. Changed workflows
run pinned actionlint; source changes run relevant fast tests before native compilation.

## Production and activation

`MANGARO_AUTOPUBLISH_ENABLED=false`. Routine and deep monitoring cannot sign or publish.
No production secrets are available to manga health jobs. Automatic safe-rule operations are isolated in `source-operations.yml`; monitoring cannot publish.
Declarative candidates need a verified baseline, identity/ownership/pagination regression
checks, independent validation and staging; new domains/headers require human review.
Unsupported transformations require a compiled update. Native observation witnesses
cannot become repair rules or establish full HEALTHY semantics.
The user authorized review-branch rollout and monitoring activation. Production activation remains conditional on all gates.
No secrets/variables/environment policies or live deployments were changed locally.
After approval, the first trusted run bootstraps empty observational history; manga
legacy counters are imported only after existing P-256 signature verification. If history
was deleted/expired, restore a trusted checkpoint explicitly; do not bootstrap it silently.
Existing GitHub environment protections and allowed branches still apply.

## Local verification

`python -m unittest discover -s tools/source-rules/automation -p 'test_*.py' -v`
`actionlint -shellcheck=''`
`git diff --check`

Live local network observations are provisional and are not GitHub schedule verification.
Registry: `tools/source-rules/production/public/index.json`, currently 8 sources.
Bootstrap checks enumerate each origin but report missing operation evidence honestly.
Weekly native runs populate safe source-specific operation witnesses. Routine jobs
contain no Gradle, emulator, signing or publication. Native jobs build the existing
opt-in debug audit, target emulator-5554, and decode a bounded real image; raw native
traces are ephemeral and never uploaded. Unverified WebView transport stays fail-closed.
Native read-only witnesses allow bounded operation rechecks where schema 1 cannot
represent the complete parser; they remain provisional and cannot authorize repair.


## Automatic safe-rule operations

`source-operations.yml` is separate from monitoring and shares the publication concurrency
lock. Its default-branch gate uses no signing credential. Production signing is isolated
in the existing credential contract; the existing repository variable must equal `true`
AND an authenticated, approved per-source journal must be eligible. Disabled automatic
operations skip their hosted jobs; no extra pip/install job on a no-op retry.
A manual signing-contract check validates the pinned key without publishing a rule.

The signed source-operation journal belongs to the dedicated `source-operations-state`
Git ref, not `main` or ephemeral runners. Non-force Git CAS checkpoints reserve revisions
before external writes. Signed current and last-good revisions, full witnesses, source
gates, independent staging receipts and audit hashes are mandatory. Missing history never
bootstraps production authority. No public observation artifact authorizes publication.
Safe class/id selector mappings and existing manga inference never emit executable code.
Novel source-scoped rollback preserves the current mappings of all unrelated sources in
its shared bundle. Rollback needs three revision-bound, confirmed semantic failures spaced
at least one hour apart, plus two successful full historical-rule validations and real
independent staging acceptance. Challenges, unknown exceptions and transport errors do
not qualify. Pending operations reconcile only exact authenticated before/after revisions.

Activation blockers currently include absent authenticated published last-good rules and
absent real-source candidate staging receipts. Diagnostic staging fixtures do not satisfy
real-source acceptance. Novel sample attestation/unknown index totals remain insufficient.
Do not enable the production variable merely because signing or monitoring CI succeeds.

Monitoring uses its own history-write concurrency group. Publication and rollback share
a separate publication group; manual signing checks and disabled operation runs have
unique groups, so GitHub's one-pending-run replacement cannot cancel monitoring or
protected publication accidentally. Repair execution reads authenticated observational
history for *eligibility only*: three semantic failures or a pending confirmed regression.
Healthy/unaffected sources do not trigger deep/signing jobs. Observation history never
satisfies the independent production acceptance gates.
