# Input brief — J17→J25 behavioural-parity tests for stagingDLRM

> Stage 0 artefact. Feeds [`01-requirements.md`](./01-requirements.md).
> **Self-contained** — everything an SDLC stage needs to run against this story is in this directory.

| | |
|---|---|
| Epic | [DD-43191](https://tools.hmcts.net/jira/browse/DD-43191) — Java 25 upgrade, DLRM contexts |
| Story | [DD-43192](https://tools.hmcts.net/jira/browse/DD-43192) — stagingDLRM, **parity stage** |
| Repo | `cpp-context-stagingdlrm` — **this repo only** |
| Target branch | **`team/25.104.x`**, while it is still J17 — cut from `main` before this story starts, and not yet upgraded |
| Sibling story | [DD-43192 upgrade stage](../DD-43191-DD-43192-j25-upgrade/00-input-brief.md) — same branch, runs **after** this story merges; consumes these tests as its regression gate |
| Platform tickets | PEG-3296 (J25 upgrade), **PEG-3377** (parity testing) |

## The epic this story belongs to

**DD-43191 — Java 25 upgrade, DLRM contexts.** Move `cpp-context-stagingdlrm` and
`cpp-context-prosecution-casefile-dlrm` from Java 17 to Java 25 (WildFly 26.1→40, Jakarta EE 8→10/11,
~30 transitive library bumps), de-risked by behavioural-parity tests written and executed on J17 first.

Four pipelines: parity and upgrade, for each of the two repos. Cross-cutting decisions live in two ADRs
and are **not restated** here:

- [**ADR-005**](../adrs/005-j25-parity-test-method-and-bc-scope.md) — parity method and the BC
  applicability matrix for both repos. **Read this before stage 4.**
- [**ADR-006**](../adrs/006-j25-branch-milestone-and-funcapp-jdk.md) — branch/milestone strategy,
  Function App JDK, pipeline track. Relevant here only for the branch ordering (its decision 1).

## This story's request

Add parity tests that pin the current J17 behaviour of the seams the J25 upgrade will move, so the
upgrade stage has a real regression gate rather than a suite that passes because nothing recompiled.

Source of candidates:
[`j25-behavioural-change-investigation-report.md`](../../analysis/j25-upgrade/j25-behavioural-change-investigation-report.md)
— 24 catalogued behavioural changes (17 Confirmed, 3 Refuted, 2 Mixed, 2 Inconclusive).
Reference implementation: `cpp-context-users-groups` PR
[#217](https://github.com/hmcts/cpp-context-users-groups/pull/217) and its `doc/j25-parity-checklist.md`.

**Bucket A for this repo — 9 items** (full matrix and evidence in ADR-005):

| BC | Seam | Weight |
|---|---|---|
| **BC-13** | JSON-schema validation strictness (`org.json` 20231013→20251224, everit) at the **command-API / schema-catalogue** tier — `MigratedCaseSubmissionSchemaContractTest`, `SchemaMatchers`, framework runtime validator | **primary** |
| **DLRM-01** | Jackson `ObjectMapper.readTree` parse behaviour (2.12.7→2.21.4) at the **Function App gate** — `JsonSchemaValidator`, `EventGridEvent`, `EventGridSchema`. **Not in the 24-BC catalogue** | **primary** |
| **BC-11** | JSON-P provider collision (glassfish→Parsson) — 6 `javax.json` coordinates, incl. func-app on `org.glassfish:javax.json:1.0.2` | high |
| BC-03 | Drools 7→10 allow/deny — `command-migrate-case-submission-api.drl`, **2 rules, only 1 covered** | high |
| BC-20 | Drools harness rule-count gate | low (cheap) |
| BC-12 | RESTEasy engine swap — func-app's 4 compile-scope resteasy artifacts | medium |
| BC-21 | Codegen (reflections 0.9.10→0.10.2) — all 4 generator plugins + RAML | medium |
| BC-07 | Liquibase 4→5 removed properties — `liquibase.properties` | low (deploy blocker) |
| BC-08 | Jackson `'Z'` → `ZoneOffset.UTC` — `ZonedDateTime` in one test helper only | thin |

**N/A — do not write tests for these.** The whole persistence cluster (BC-01, 02, 04, 05, 06, 24) is
absent: `stagingdlrm-viewstore-persistence` contains **zero Java files** — no `@Entity`, no repository,
only `persistence.xml` and `beans.xml`. Also N/A: BC-09/BC-10 (no Activiti), BC-18 (no
`ActiveMQConnectionFactory`), BC-19 (SJP), BC-22 (no Tika), BC-23 (no Quartz). BC-14/16/17/15 are
framework-tier (Bucket B) — record a check, don't write a test.

This is why **the reference PR must not be copied file-for-file**: 7 of its 14 files are
viewstore-repository tests for entities this repo does not have.

## Decisions taken with the requester

| Question | Decision |
|---|---|
| Pipeline shape | Four pipelines — parity + upgrade, per repo. This is the parity one |
| Target branch | `team/25.104.x` pre-upgrade. Tests must be authored **and executed green on J17**, which the branch still is until the upgrade PR lands — ADR-005 Method 1 |
| Story keys | One Jira key per repo; the two stages share it, distinguished by directory slug |
| Copy the reference PR? | No — its shape is persistence-led and this repo has no persistence layer |
| Checklist location | `docs/j25-parity-checklist.md` (reference used `doc/`; adjusted to this repo's convention) |

## Scope boundaries

| In scope | Out of scope |
|---|---|
| Parity tests for the 9 Bucket A items, J17-style `javax` imports | Any version bump, jakarta rename, or pom change — that is the upgrade story, and doing it here would destroy this story's J17 evidence |
| `docs/j25-parity-checklist.md` — scope, status, J17 run evidence | Cutting `team/25.104.x` — done before this story starts |
| Fixing the BC-03 branch gap (untested error-submission rule) | `cpp-context-prosecution-casefile-dlrm` — DD-43194, its own pipeline |
| Recorded Bucket B checks (no test files) | Framework/platform repos — PEG-3296 owns those |

## Known blockers / open items

- **The `centos8-j17` agent must still be available.** `azure-pipelines.yaml:29` demands it, and this
  story deliberately does not change that — the branch must stay on the J17 agent for this story's runs
  to be J17 evidence. The upgrade story moves it to `ubuntu-j25`.
- **Two validator stacks, two regression tables.** A code read on 2026-08-28 corrected an earlier
  assumption: the func-app gate uses `com.networknt:json-schema-validator` **1.0.83, hard-pinned** in
  `stagingdlrm-azure-functions/pom.xml:138`, so it is *not* exposed to BC-13's `org.json`/everit
  mechanism — its exposure is the BOM-managed Jackson bump behind `ObjectMapper.readTree` (DLRM-01).
  BC-13's real carrier is the `domain-value-schema` catalogue tier. Each needs its own pinned
  numeric-literal table (`0`, `007`, `01`, `.5`, `10.0`, `1e3`, `12345678901234567890`); see ADR-005's
  DLRM-01 addendum.
- **The report may be wrong in places, and a J17 run outranks it.** In the reference context three
  BC-04 tests were written to the report's claim and refuted by an actual J17 run. Expect at least one
  such correction here and record it.
- **ITs need Docker** (`CPP_DOCKER_DIR` → `cpp-developers-docker`). Any IT-tier parity test is
  authored-not-executed until that is available; mark it 🟡, not 🟢.
- Owner is unassigned for stagingdlrm on the PEG-3296 tracker. Confirm with Platform Engineering that
  DLRM is unowned rather than queued.
