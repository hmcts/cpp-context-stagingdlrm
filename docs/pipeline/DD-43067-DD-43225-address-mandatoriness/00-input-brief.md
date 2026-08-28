# Input brief — Address mandatoriness: defendant optional, individual mandatory

> Stage 0 artefact. Feeds [`01-requirements.md`](./01-requirements.md).
> **Self-contained** — everything an SDLC stage needs to run against this story is in this directory.

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43225](https://tools.hmcts.net/jira/browse/DD-43225) — Address mandatoriness realignment |
| Repo | `cpp-context-stagingdlrm` — **this repo only** |
| Builds on | [DD-43180](../DD-43067-DD-43180-funcapp-validation-fixes/00-input-brief.md) — func-app gate reconciled to the LIBRA 0.13.1 contract (delivered) |

## The epic this story belongs to

**DD-43067 — LIBRA enabler.** Ingest magistrates' court case files from legacy system LIBRA through the
shared DLRM pipeline (Azure Blob → Function App → stagingDLRM → PCFDLRM → Progression). Source-system
behaviour is pluggable and source-system-keyed — see
[ADR-002](../adrs/002-source-system-keyed-dispatch.md).

## This story's request

Two address requiredness changes, in opposite directions, settled with the requester:

### 1 — Defendant-level `address` becomes **optional** for LIBRA

`defendant.address` (the top-level defendant address) was mandatory for LIBRA. LIBRA does not always
supply it, so a valid submission was being rejected. Make it optional — at the LIBRA gate, in the domain
rule engine, and in the 0.13.1 contract. XHIBIT never required it and is untouched.

### 2 — Individual `personalInformation.address` becomes **mandatory** for LIBRA **and** XHIBIT

The individual's address (`defendant.individual.personalInformation.address`) is the address that must be
present. Enforce it for both source systems. The shared canonical `personal-information.json` carries the
requirement, so it holds for XHIBIT and LIBRA at the command layer; the LIBRA gate and the 0.13.1
contract mirror it.

## Decisions taken with the requester

| Question | Decision |
|---|---|
| Which address is optional | The **defendant-level** `defendant.address`, LIBRA only |
| Which address is mandatory | The **individual's** `personalInformation.address`, LIBRA **and** XHIBIT |
| Where individual-address is enforced | The shared canonical `personal-information.json` (`required: ["surname","address"]`) — one schema covering both source systems; no new rule-engine rule needed |
| Shared `address` definition side effect | Accept the intersection: relaxing LIBRA's defendant `address` does not change officer/parent-guardian addresses at runtime; the generated-schema note is recorded, not code |
| Generated `dlrm-libra-0.13.json` | **Not regenerated** — the source workbook is absent from the repo (only a stale older revision exists in git). Divergence recorded; regenerate when the current workbook is available |

## Scope boundaries

| In scope | Out of scope |
|---|---|
| LIBRA gate `libra.case-submission.json` — defendant `address` optional, `personalInformation.address` required | XHIBIT gate schemas (shallow — do not model `personalInformation`) |
| Domain rule engine — drop the LIBRA defendant-address rule | `MigratedCaseConvertor`, PCFDLRM, Progression |
| `0.13.1` contract — mirror both changes | Regenerating `dlrm-libra-0.13.json` / provenance / the workbook |
| Canonical `personal-information.json` (individual address) + affected tests/fixtures | The shared-`address` intersection as a code change (accepted as-is) |

## Supporting analysis

- [ADR-002](../adrs/002-source-system-keyed-dispatch.md) — source-system-keyed validation.
- [ADR-004](../adrs/004-funcapp-gate-mirrors-business-rules.md) — the func-app gate mirrors the domain
  business rules; both changes keep gate ↔ domain parity.
- [`libra-workbook-corrections.md`](../../analysis/libra-ingestion/libra-workbook-corrections.md) —
  the 0.13.1 table records both changes and the unregenerated-`0.13` divergence.
