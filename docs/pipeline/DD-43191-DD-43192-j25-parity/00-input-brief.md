# Input brief — J17→J25 behavioural-parity tests for stagingDLRM

> Stage 0 artefact. Feeds [`01-requirements.md`](./01-requirements.md).
> **Self-contained** — everything an SDLC stage needs to run against this story is in this directory.

| | |
|---|---|
| Epic | [DD-43191](https://tools.hmcts.net/jira/browse/DD-43191) — Java 25 upgrade, DLRM contexts |
| Story | [DD-43192](https://tools.hmcts.net/jira/browse/DD-43192) — stagingDLRM, **parity stage** |
| Repo | `cpp-context-stagingdlrm` — **this repo only** |
| Target branch | **`team/25.104.x`**, cut from `main` before this story starts, while it is still J17 |
| Sibling story | [DD-43192 upgrade stage](../DD-43191-DD-43192-j25-upgrade/00-input-brief.md) — same branch, runs **after** this story merges; consumes these tests as its regression gate |
| Platform tickets | PEG-3296 (J25 upgrade tracker), **PEG-3377** (parity testing) |

## The epic this story belongs to

**DD-43191 — Java 25 upgrade, DLRM contexts.** Move `cpp-context-stagingdlrm` and
`cpp-context-prosecution-casefile-dlrm` from Java 17 to Java 25 (WildFly 26.1→40, Jakarta EE 8→10/11,
~30 transitive library bumps), de-risked by behavioural-parity tests written and executed on J17 first.

Per the fleet tracker (`docs/analysis/j25-upgrade/Java 25 Upgrade Status.pdf`, last updated 06 Aug 2026),
stagingdlrm is still **"Not yet upgraded"** — one of a small remaining set alongside pcfdlrm,
staging-bulkscan, staging-darts, staging-enforcement, and the three staging-prosecutors variants. Owner
is unassigned ("?") on the tracker. Roughly 30 of the ~45 tracked contexts already show *QA Docker Image
Available*; the fleet's own recorded failures (generator plugins missing parsson, `javaee-api` surviving
inside plugin dependency blocks, jacoco choking on JDK 25 bytecode, nine contexts merging with no Docker
image produced) are cheaper for this story to inherit than to rediscover.

Cross-cutting decisions live in two ADRs and are **not restated** here:

- [**`DD-43191-j25-parity-method`**](../adrs/DD-43191-j25-parity-method.md) — parity method and the BC
  applicability matrix for both repos. **Read this before stage 4.**
- [**`DD-43191-j25-upgrade-mechanics`**](../adrs/DD-43191-j25-upgrade-mechanics.md) — branch/milestone strategy,
  Function App JDK, pipeline track. Relevant here only for the branch ordering (its decision 1).

## This story's request

Add parity tests that pin the current J17 behaviour of the seams the J25 upgrade will move, so the
upgrade stage has a real regression gate rather than a suite that passes because nothing recompiled.

Three sources of candidates, read together, not in isolation:

1. [`j25-behavioural-change-investigation-report.md`](./j25-behavioural-change-investigation-report.md)
   — 24 catalogued behavioural changes (17 Confirmed, 3 Refuted, 2 Mixed, 2 Inconclusive), the original
   per-context investigation.
2. [`Parity+Testing+Java17+-_+Java25.pdf`](./Parity+Testing+Java17+-_+Java25.pdf) — the fleet-wide,
   **empirically corrected** generic guide, with an evidence log from ~14 completed contexts. **Where
   the two disagree, this one wins** — it is dated later and reflects real J17/J25 runs, not the
   original hypothesis. In particular its BC-11 entry ("CORRECTED 2026-08-26") replaces the original
   report's JSON-P `ServiceLoader`-collision hypothesis with a verified
   `JsonObjectBuilder.add(key, null)` NPE-parity finding — see the parity-method ADR's decision 8.
3. Reference implementation: `cpp-context-users-groups` PR
   [#217](https://github.com/hmcts/cpp-context-users-groups/pull/217) and its `doc/j25-parity-checklist.md`.

**This repo's own architecture is the fourth source, and the one that turns a generic BC into a concrete
test target.** `docs/architecture/dlrm-flow-reference.md` §6 lists the exact JSON schema files the
Function App validates (`stagingdlrm.case-submission.json`, `stagingdlrm.manifest.json`, and their
nested `case-details.json`/`migrationSourceSystem.json`/`pcf-prosecutor.json`/`definitions.json`), which
field of which schema is the right numeric-literal target, and the exact payload-assembly and
outcome-write logic (§2.4, §2.6) that BC-13/DLRM-01's parse-vs-validation distinction has to respect.
`docs/architecture/material-file-flow.md` confirms materials are pointer-only through this repo (never
read for bytes), which is why no BC in this story concerns material file content — only the metadata
(`fileType`, `documentType`) schema-checked before forwarding.

**Bucket A for this repo — 9 items** (full matrix and evidence in the parity-method ADR):

| BC | Seam | Weight |
|---|---|---|
| **BC-13** | JSON-schema validation strictness (`org.json` 20231013→20251224, everit) at the **schema-catalogue** tier — `stagingdlrm-domain-value-schema`'s own copies of `case-details.json`, `migrated-hearing.json`, etc. | **primary** |
| **DLRM-01** | Jackson `ObjectMapper.readTree` parse behaviour (2.12.7→2.21.4) at the **Function App gate** — `JsonSchemaValidator`, validating against the flat schema copies in `stagingdlrm-azure-functions/src/main/resources/`. **Not in the 24-BC catalogue** | **primary** |
| **BC-11** | **Corrected** (parity-method ADR decision 8): `JsonObjects.createObjectBuilder().add(key, null)` NPE parity — not a JSON-P provider-collision count. Real call site: `StagingDlrmCommandHelper.generateErrorMigratedCaseSubmissionPayload`'s `errorMessage` field (dlrm-flow-reference.md §2.4/§5) | high |
| BC-03 | Drools 7→10 allow/deny — `command-migrate-case-submission-api.drl`, **2 rules, only 1 covered** (the untested rule gates `stagingdlrm.receive-error-migrated-case-submission`, the error path documented in dlrm-flow-reference.md §3.5) | high |
| BC-20 | Drools harness rule-count gate | low (cheap) |
| BC-12 | RESTEasy engine swap — func-app's 4 compile-scope resteasy artifacts (the func-app is a standalone JAR per dlrm-flow-reference.md §2, not a WAR — no container to supply them) | medium |
| BC-21 | Codegen (reflections 0.9.10→0.10.2) — all 4 generator plugins + RAML | medium |
| BC-07 | Liquibase 4→5 removed properties — `liquibase.properties` | low (deploy blocker) |
| BC-08 | Jackson `'Z'` → `ZoneOffset.UTC` — `ZonedDateTime` in one test helper only | thin |

**N/A — do not write tests for these.** The whole persistence cluster (BC-01, 02, 04, 05, 06, 24) is
absent: `stagingdlrm-viewstore-persistence` contains **zero Java files** — no `@Entity`, no repository,
only `persistence.xml` and `beans.xml`. Also N/A: BC-09/BC-10 (no Activiti — dlrm-flow-reference.md's
module map has no workflow-engine dependency), BC-18 (no `ActiveMQConnectionFactory`), BC-19 (SJP),
BC-22 (no Tika), BC-23 (no Quartz). BC-14/16/17/15 are framework-tier (Bucket B) — record a check, don't
write a test.

This is why **the reference PR must not be copied file-for-file**: 7 of its 14 files are
viewstore-repository tests for entities this repo does not have.

## Decisions taken with the requester

| Question | Decision |
|---|---|
| Pipeline shape | Four pipelines — parity + upgrade, per repo. This is the parity one |
| Target branch | `team/25.104.x` pre-upgrade. Tests must be authored **and executed green on J17**, which the branch still is until the upgrade PR lands — the parity-method ADR's Method 1 |
| Story keys | One Jira key per repo; the two stages share it, distinguished by directory slug |
| Copy the reference PR? | No — its shape is persistence-led and this repo has no persistence layer |
| Which BC-11 to build against | The PDF's corrected finding (decision 8), not the investigation report's original hypothesis — the report was written before the 2026-08-26 correction and is not self-updating |
| Checklist location | `docs/j25-parity-checklist.md` (reference used `doc/`; adjusted to this repo's convention) |

## Scope boundaries

| In scope | Out of scope |
|---|---|
| Parity tests for the 9 Bucket A items, J17-style `javax` imports | Any version bump, jakarta rename, or pom change — that is the upgrade story, and doing it here would destroy this story's J17 evidence |
| `docs/j25-parity-checklist.md` — scope, status, J17 run evidence | Cutting `team/25.104.x` — done before this story starts |
| Fixing the BC-03 branch gap (untested error-submission rule) | `cpp-context-prosecution-casefile-dlrm` — DD-43194, its own pipeline |
| Recorded Bucket B checks (no test files) | Anything past the `receive-migrated-case-file` REST call to pcfdlrm — material-file-flow.md's cross-context trace (Material, Alfresco, Progression) is context only, not this repo's code |

## Known blockers / open items

- **The `centos8-j17` agent must still be available.** `azure-pipelines.yaml:29` demands it, and this
  story deliberately does not change that — the branch must stay on the J17 agent for this story's runs
  to be J17 evidence. The upgrade story moves it to `ubuntu-j25` (upgrade-mechanics ADR decision 7).
- **Two validator stacks, two regression tables.** The func-app gate uses
  `com.networknt:json-schema-validator` **1.0.83, hard-pinned** in `stagingdlrm-azure-functions/pom.xml`,
  so it is *not* exposed to BC-13's `org.json`/everit mechanism — its exposure is the BOM-managed
  Jackson bump behind `ObjectMapper.readTree` (DLRM-01). BC-13's real carrier is the
  `domain-value-schema` catalogue tier. Each needs its own pinned numeric-literal table
  (`0`, `007`, `01`, `.5`, `10.0`, `1e3`, `12345678901234567890`); see the parity-method ADR's
  DLRM-01 addendum.
- **The report may be wrong in places, and a J17 run outranks it — BC-11 already was.** Expect at least
  one further correction here and record it (parity-method ADR decision 4).
- **ITs need Docker** (`CPP_DOCKER_DIR` → `cpp-developers-docker`). Any IT-tier parity test is
  authored-not-executed until that is available; mark it 🟡, not 🟢.
- Owner is unassigned for stagingdlrm on the PEG-3296 tracker (confirmed against the 06 Aug 2026 tracker
  snapshot). Confirm with Platform Engineering that DLRM is unowned rather than queued.
