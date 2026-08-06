# Input brief — LIBRA enabler: Function App LIBRA ingest

> Stage 0 artefact. Feeds [`01-requirements.md`](./01-requirements.md).
> **Self-contained** — everything an SDLC stage needs to run against this story is in this
> directory.

| | |
|---|---|
| Epic | [DD-43067](https://tools.hmcts.net/jira/browse/DD-43067) — LIBRA enabler |
| Story | [DD-43082](https://tools.hmcts.net/jira/browse/DD-43082) — Function App LIBRA ingest |

## The epic this story belongs to

**DD-43067 — LIBRA enabler.** Ingest magistrates' court case files from legacy system LIBRA
through the existing DLRM pipeline (Azure Blob → Function App → stagingDLRM → PCFDLRM →
Progression), reusing the XHIBIT path rather than forking it.

**Design decision already taken for the epic** (analysis §2): XHIBIT and LIBRA share **one**
stagingDLRM endpoint and **one** canonical schema family. Source-system-specific behaviour is
pluggable strategies inside the shared path, not duplicated endpoints or command/event types. The
rejected separate-endpoint alternative is in
[`libra-ingestion-analysis.md`](../../analysis/libra-ingestion/libra-ingestion-analysis.md) §7.

**Repo in scope:** `cpp-context-stagingdlrm`, module `stagingdlrm-azure-functions` only.

## This story's request

Make the Azure Function App accept and route LIBRA blobs, so that a LIBRA submission can reach
stagingDLRM at all. The Function App is a standalone Azure Functions app outside the WildFly/JMS
stack, with three entry points: `EventGridTriggerJava` (validates the blob path, enqueues the
submission folder), `TimerTriggerJava` (lists blobs, validates `case.json`/`manifest.json`,
assembles the payload, POSTs to stagingDLRM) and `EventGridMonitor` (writes outcome JSON back to
Blob Storage).

## Current state — verified in the code, not quoted from the analysis

| Fact | Evidence |
|---|---|
| `dlrm_folder_name` is a **single-value exact match** | `EventGridTriggerJava:84` — `folderName.trim().equalsIgnoreCase(tokens.get(0))` |
| `dlrm_batch_name` already supports a **comma-separated list and a `*` wildcard** | `EventGridTriggerJava:97`, `validateBatchNames:119-121` |
| The Function App holds **8 hand-maintained schema files** | `src/main/resources/`: `case-details.json`, `definitions.json`, `migrated-case.json`, `migrationSourceSystem.json`, `migrationSourceSystemName.json`, `pcf-prosecutor.json`, `stagingdlrm.case-submission.json`, `stagingdlrm.manifest.json` |
| It has **no Maven dependency** on `stagingdlrm-domain-value-schema`, and no unpack/copy plugin | `pom.xml` — zero matches for either |
| Its local `case-details.json` has **drifted from canonical** | missing `caseMarkers`, `cpsOrganisation`, `dateOfSending`, `dateOfCommittal`, `sendingCourt`; `additionalProperties: true` vs canonical `false`; no `anyOf`; `initiationCode` has no `enum`; `originatingOrganisation` has no oucode length constraints. (`required` happens to match.) |

The drift matters because it is **asymmetric in the dangerous direction**: the local schema is
*more lenient* than canonical, so a payload can pass Function App validation and then be rejected
by stagingDLRM — and that rejection is a **terminal 4xx with zero retries** (analysis §4).

Now quantified, and it is more total than "some drift". Flattening both schema sets
(`tools/schema-gen/flatten-canonical-schema.py`, both outputs committed under
`docs/analysis/libra-ingestion/schema/canonical/`) shows the gate is a **presence check only**: 8
`caseDetails` properties, all 8 `required`, and **not one constraint** — no patterns, no lengths, no
enums — with `additionalProperties: true` on both `caseDetails` and `migratedCase`, and no descent
into `defendants`, `hearings` or `offences` at all.

The live consequence for XHIBIT today, not just for LIBRA: `initiationCode: "C"` **passes the gate
and fails canonical's `enum: ["O"]`**. The cheap check accepts it, the expensive one rejects it as a
terminal 4xx after the payload has been enqueued and dispatched. `prosecutor.prosecutingAuthority`
is a second instance — `required` in canonical, optional at the gate.

Two consequences for this story: the only thing blocking LIBRA at the gate is the four `required`
fields it does not supply (impact §1), and the depth of the new LIBRA schema is a real decision
rather than a copy-paste — see FR3a.

## Decisions taken with the requester

| Question | Decision |
|---|---|
| Per-source-system schemas in the Function App? | **Yes.** Keep the table-driven `Map<sourceSystem, validators>` from analysis §3.2. XHIBIT and LIBRA each get their own pre-validation schema, so a bad payload is rejected at the edge rather than downstream. |
| Where do those schemas come from? | **The Function App owns its own normalised schema per source system, and does *not* depend on stagingDLRM's canonical schema.** This deliberately overrides analysis §3.2's build-time-unpack proposal. |

### Consequence of the second decision, stated once

Independent ownership means the drift above is **not** eliminated by this story — the Function
App's schemas can diverge from canonical again, in the same direction, with the same terminal-4xx
outcome. Nothing about per-source-system selection causes that; it is the independence that does.

Since the modules are staying decoupled by choice, the story proposes a **drift *detection*** guard
instead of drift elimination: a check that fails the build when a Function App schema is more
lenient than canonical for a field both declare (FR6). It keeps the schemas independently owned
while catching the one failure mode that has already bitten in production. It is a proposal — drop
it if the team would rather carry the risk.

Trade-off also accepted with per-source-system selection: a Function App rejection produces an
outcome file **without the case URN**, because the case has not been parsed at that point, whereas
a stagingDLRM rejection keeps it. Earlier rejection, less diagnostic context.

## Relationship to the other stories in this epic

Neither hard-blocks the other, but:

- [DD-43081](https://tools.hmcts.net/jira/browse/DD-43081) (schema enablement) has an
  end-to-end acceptance criterion — a real LIBRA submission accepted and processed — which
  **cannot be met until this story's FR1 and FR4 land**. Everything else in DD-43081 is provable
  at unit and component level without it.
- [DD-43078](https://tools.hmcts.net/jira/browse/DD-43078) (test hardening) already covers the
  Function App's path validation and local-schema validation for XHIBIT. This story **extends
  those suites**; it does not create parallel ones.
- This story needs **no real LIBRA sample** for most of its scope — the LIBRA normalised schema is
  authored from the workbook, and path/routing behaviour is testable with a synthetic payload. It
  can start immediately.

## Known blockers and open items

- **The EventGrid subscription path filter is unverified** (analysis §5 Q4). If the Blob Storage
  EventGrid subscription filters to `XHIBIT/*`, no LIBRA blob will ever trigger the Function App
  regardless of what this story changes. It is Terraform/ARM outside this repo, so it needs the
  infra owner.
- **`dlrm_folder_name` must not gain wildcard support.** Unlike batch name, folder name *is* the
  source-system gate; wildcarding it would silently accept any legacy system's blobs, which is
  what this validation exists to prevent.

## Supporting analysis

- [`libra-ingestion-analysis.md`](../../analysis/libra-ingestion/libra-ingestion-analysis.md) — §3.1 Azure
  Storage, §3.2 the Function App change plan and the drift finding, §4 the terminal-rejection
  behaviour, §5 open questions.
- [`libra-schema-impact.md`](../../analysis/libra-ingestion/libra-schema-impact.md) — the
  field-level impact the LIBRA normalised schema must reflect (§1 relaxation scope — including the
  four func-app `required` entries LIBRA cannot satisfy; §2 the matrix and its CSV).
