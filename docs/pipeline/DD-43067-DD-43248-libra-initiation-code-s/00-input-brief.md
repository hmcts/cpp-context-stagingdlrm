# Input brief — LIBRA: admit initiation code `S`

> Stage 0 artefact. Feeds [`01-requirements.md`](./01-requirements.md).
> **Self-contained** — everything an SDLC stage needs to run against this story is in this directory.

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43248](https://tools.hmcts.net/jira/browse/DD-43248) — admit LIBRA initiation code `S` |
| Repo | `cpp-context-stagingdlrm` — **this repo only** |
| Enhances | [DD-43203](https://tools.hmcts.net/jira/browse/DD-43203) — initiation-code update + validation (delivered) |

## The epic this story belongs to

**DD-43067 — LIBRA enabler.** Ingest magistrates' court case files from legacy system LIBRA through
the shared DLRM pipeline (Azure Blob → Function App → stagingDLRM → PCFDLRM → Progression), reusing
the XHIBIT path. Source-system-specific behaviour is a source-system-keyed strategy inside the shared
path — see [ADR-002](../adrs/002-source-system-keyed-dispatch.md).

## This story's request

Add **`S`** to LIBRA's permitted `caseDetails.initiationCode` set.

DD-43203 widened the canonical enum and added the per-source-system `InitiationCodeValidationRule`,
but admitted only the codes then evidenced — LIBRA `C, Q, J, R`, XHIBIT `O` — and explicitly deferred
`S`/`Z` "until a source system is shown to send it." LIBRA is now confirmed to send `S`, which
[ADR-003 decision 5](../adrs/003-libra-payload-contract.md) always anticipated (it names LIBRA's
expected set as `C, J, Q, S` and `S` as one of the platform's seven `Q, R, S, C, J, Z, O`). This
story is that follow-on: no new pattern, one code added at every gate DD-43203 established.

`S` is admitted for **LIBRA only**. XHIBIT stays pinned to `O`.

## Decisions taken with the requester

| Question | Decision |
|---|---|
| Which system gets `S` | LIBRA only. XHIBIT unchanged (`["O"]`) |
| Remove `R`? | No — `R` stays in LIBRA's set; this story only **adds** `S`. Result: LIBRA = `C, Q, J, R, S` |
| Canonical enum | Gains `S` → `C, Q, J, R, O, S`, so a LIBRA `S` deserializes instead of failing as an `InvalidFormatException` before any rule runs |
| Func-app LIBRA gate | **In scope** — gains `S`. A gate stricter than canonical would reject a valid `S` at ingest, a terminal 4xx after enqueue (ADR-003 decision 6) |

## Scope boundaries

| In scope | Out of scope |
|---|---|
| Canonical `case-details.json` enum — add `S` | XHIBIT set — unchanged |
| `MigratedCaseValidationRuleEngine` LIBRA set — add `S` | `MigratedCaseConvertor` — no change (`.name()` still compiles) |
| Func-app LIBRA gate `libra.case-submission.json` — add `S` | Func-app XHIBIT gate (`case-details.json`) — unchanged |
| LIBRA extract schema `dlrm-libra-0.13.1.json` — add `S` | PCFDLRM `CaseType` routing from the code — its own pipeline |
| Tests + one LIBRA `S` fixture | New ADR — ADR-002/003 already cover this |

## Known blockers / open items

- The `CaseType` LIBRA routes into downstream (`CcProsecutionValidationRuleProvider`) is keyed off the
  code; making `S` valid here does not settle that routing — carried forward from DD-43203.

## Supporting analysis

- [ADR-003](../adrs/003-libra-payload-contract.md) decision 5 — platform seven and LIBRA's expected
  `C, J, Q, S`; decision 6 — the gate must never be more lenient than canonical.
- [ADR-002](../adrs/002-source-system-keyed-dispatch.md) rule 4 — one `InitiationCodeValidationRule`
  class, one configured instance per source system.
- [`DD-43203`](../DD-43067-DD-43203-initiation-code-validation/00-input-brief.md) — the predecessor
  that built the four gates `S` now flows through.
