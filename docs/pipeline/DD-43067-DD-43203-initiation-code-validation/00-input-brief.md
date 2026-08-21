# Input brief — LIBRA enabler: initiation-code update + validation

> Stage 0 artefact. Feeds [`01-requirements.md`](./01-requirements.md).
> **Self-contained** — everything an SDLC stage needs to run against this story is in this directory.

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43203](https://tools.hmcts.net/jira/browse/DD-43203) — initiation-code update + validation |
| Repo | `cpp-context-stagingdlrm` — **this repo only** |
| Enhances | [DD-43081](https://tools.hmcts.net/jira/browse/DD-43081) — schema enablement (delivered). Func-app half is a later enhancement of [DD-43086](https://tools.hmcts.net/jira/browse/DD-43086) |

## The epic this story belongs to

**DD-43067 — LIBRA enabler.** Ingest magistrates' court case files from legacy system LIBRA through
the shared DLRM pipeline (Azure Blob → Function App → stagingDLRM → PCFDLRM → Progression), reusing
the XHIBIT path. Source-system-specific behaviour is a pluggable, source-system-keyed strategy inside
the shared path — see [ADR-002](../adrs/002-source-system-keyed-dispatch.md).

## This story's request

DD-43081 left `caseDetails.initiationCode` pinned to `enum: ["O"]` because LIBRA 0.13 was believed to
send only `O`. That is no longer true: the LIBRA extract schema
[`dlrm-libra-0.13.1.json`](../../analysis/libra-ingestion/schema/libra/dlrm-libra-0.13.1.json) now
declares `initiationCode` values **`C, Q, J, R`**. With the canonical enum still `["O"]`, every LIBRA
submission carrying a real initiation code is an `InvalidFormatException` at deserialization — a
terminal 4xx before any aggregate or rule runs.

Two changes, matching the pattern DD-43081 already set up:

1. **Widen the canonical enum** on `case-details.json` from `["O"]` to the union of what the two
   source systems actually send — `C, Q, J, R, O` (LIBRA `C,Q,J,R` + XHIBIT `O`). The field **stays a
   typed enum**, so `MigratedCaseConvertor`'s `.getInitiationCode().name()` keeps compiling — no
   converter change. (`ADR-003` decision 5 speaks of a wider platform enum
   `uk.gov.justice.core.courts.InitiationCode`; only the codes evidenced by the two extracts are
   admitted here — a `Z`/`S`/`R`-beyond-LIBRA is added if and when a source system is shown to send it.)
2. **Add a per-source-system allowed-values rule** to `MigratedCaseValidationRuleEngine`, because the
   widened schema now admits all seven codes to *both* systems. This is the
   `InitiationCodeValidationRule` named in [ADR-002 rule 4](../adrs/002-source-system-keyed-dispatch.md):
   one class, two configured instances.
   - **XHIBIT** → `["O"]` only. Regression guard: XHIBIT has only ever been able to send `O`, so this
     re-pins it and net XHIBIT behaviour is zero change.
   - **LIBRA** → `["C","Q","J","R"]`, the extract's declared set.

## Decisions taken with the requester

| Question | Decision |
|---|---|
| Schema enum target | The two-system union `C,Q,J,R,O` only. `S` and `Z` from ADR-003's platform-enum note are **not** admitted — no evidence either source system sends them |
| XHIBIT allowed set | `["O"]` only — regression-preserving |
| LIBRA allowed set | `["C","Q","J","R"]` — from the updated 0.13.1 extract schema |
| Func-app gate schemas | **Out of scope.** Owned by DD-43086, done as a later enhancement. The LIBRA gate already accepts any 1-char code, so widening works end to end without touching the gate |

## Scope boundaries

| In scope | Out of scope |
|---|---|
| `stagingdlrm-domain-value-schema` — widen `case-details.json` enum | `stagingdlrm-azure-functions` — func-app gate schemas (DD-43086, later) |
| `stagingdlrm-domain-aggregate` — `InitiationCodeValidationRule` + engine wiring | `MigratedCaseConvertor` — no change (`.name()` still compiles) |
| LIBRA test fixtures — move `initiationCode` off `"O"` | `cpp-context-prosecution-casefile-dlrm` — its own pipeline; `initiationCode` is a plain string there |

## Known blockers / open items

- The value determines which PCFDLRM rule set LIBRA routes into (`CcProsecutionValidationRuleProvider`,
  keyed by a code-derived `CaseType`). The stagingDLRM change here does not depend on that, but the
  end-to-end routing does — carried forward from DD-43081 §5 Q2.
- `H` appears in two func-app / command-handler test fixtures and in neither the old nor the widened
  enum (workbook-corrections item 7). Left as-is — it was non-enum before and stays non-enum; not this
  story's to resolve.

## Supporting analysis

- [`libra-workbook-corrections.md`](../../analysis/libra-ingestion/libra-workbook-corrections.md)
  items 6–8 — the initiation-code reality questions and the platform-enum gap.
- [`libra-ingestion-analysis.md`](../../analysis/libra-ingestion/libra-ingestion-analysis.md) §3.4 —
  PCFDLRM `CaseType` routing from the initiation code.
- [ADR-002](../adrs/002-source-system-keyed-dispatch.md) and
  [ADR-003](../adrs/003-libra-payload-contract.md) — both already cover this decision; **no new ADR**.
