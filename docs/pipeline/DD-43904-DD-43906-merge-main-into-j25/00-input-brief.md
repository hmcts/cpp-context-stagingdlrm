# Input brief — DD-43906: merge main into java25 for stagingdlrm

> Stage 0 artefact (input brief). Feeds [`01-requirements.md`](./01-requirements.md).

## Source

| | |
|---|---|
| Sub-task | [DD-43906](https://hmcts.atlassian.net/browse/DD-43906) — *merge main into java25 for stagingdlrm* |
| Parent story | [DD-43904](https://hmcts.atlassian.net/browse/DD-43904) — *merge main into java2…* (title truncated in the Jira export) |
| Related epic | [DD-43191](https://hmcts.atlassian.net/browse/DD-43191) — Java 25 stack upgrade, DLRM contexts (not linked in Jira; context only) |
| Repo | `cpp-context-stagingdlrm` |
| Target branch | `team/25.104.x` (working branch `dev/merge-main-into-25`) |
| Sprint / priority | DLRM Sprint 67 / 3-Medium |

## What the ticket says

The title only. Description, acceptance criteria and linked items are **empty** (Jira export taken
2026-11-02). Everything below about scope comes from the title and the repo, and the gaps are listed
as open questions in [`01-requirements.md`](./01-requirements.md#open-questions).

## Why this is needed

`team/25.104.x` was cut from `main` for the DD-43191 Java 25 upgrade. Since the two split
(merge base `ed3837b`, 2026-07-08), `main` has kept receiving Java 17 fixes and features that the
Java 25 branch does not have. Unless they are merged across, the Java 25 release would quietly drop
them.

## Starting state (verified 2026-11-02)

| Branch | Head | Commits since split | Version line |
|---|---|---|---|
| `team/25.104.x` | `4e1b077` | 17 | `25.104.30-DLRMJ25-SNAPSHOT`, Java 25 / WildFly 40 / Jakarta EE |
| `main` | `c57ac88` | 70 | `17.104.25-SNAPSHOT`, Java 17 / `javax` |

### What main brings that team lacks (48 files)

| Change on main | Files |
|---|---|
| [DD-43208] Function App schema failures counted as received submissions | `TimerTriggerJava`, `StagingDlrmCommandHelper` + tests |
| Fix null-pointer when hearings/defendants absent | `StagingDlrmCommandHelper` |
| [DD-43226] Address mandatory in `personalInformation` | `personal-information.json`, IT + fixture |
| False "case already exists" fix (#40) and failed-remap throw (#42) | `SystemMapperService` + tests, `PcfdlrmStub`, `ProgressionStub`, IT fixtures, `junit-jupiter-params` test dependency |
| DD-42727 / DD-42988 / DD-42990 / DD-43000 reconciliation report | `tools/reconciliation/` (16 files, not part of the Maven build) |
| DD-43014 / DD-43153 pipeline docs | 14 files under `docs/pipeline/` |
| DD-43014 SDLC plugin set-up | `.gitignore` |

Code and test changes: 17 files, +746/−82. Docs and tools: 30 files, +3857. Test annotation counts go
from 19 to 22 integration tests and from 111 to 112 unit tests.

### Where the two branches clash

A trial merge stops on 28 files:

- **26 `pom.xml`** — project version (25.104.30 vs 17.104.25), and in the root pom four dependency
  versions (`coredomain`, `system.id-mapper`, `progression`, `pcfdlrm`).
- **`ProgressionStub.java`** — team moved the import to `jakarta`; main added a `SC_NOT_FOUND` import.
- **`CLAUDE.md`** — team's branch note and J25 wording against main's J17 wording and a reconciliation
  tooling section.

## Supporting analysis

- [Upgrade-mechanics ADR](../adrs/DD-43191-j25-upgrade-mechanics.md) — decision 2: *interface pins
  move only as far as the enforcer requires; no opportunistic bumps.* This governs how the root pom
  dependency clash is settled.
- [`docs/architecture/dlrm-flow-reference.md`](../../architecture/dlrm-flow-reference.md) — the flow
  the merged fixes touch (Function App ingestion, event-processor case lookup).
