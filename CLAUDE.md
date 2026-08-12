# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Standard build (skips integration tests)
mvn clean install

# Run unit tests only
mvn test

# Run integration tests (requires Docker + CPP_DOCKER_DIR env var pointing to cpp-developers-docker repo)
./runIntegrationTests.sh

# Run integration tests via Maven profile (against a running environment)
mvn verify -P stagingdlrm-integration-test

# Run performance tests
mvn verify -P stagingdlrm-performance-test

# Run a single test class
mvn test -pl stagingdlrm-command/stagingdlrm-command-handler -Dtest=StagingdlrmCommandHandlerTest

# Run a single IT class
mvn verify -P stagingdlrm-integration-test -pl stagingdlrm-integration-test -Dit.test=ReceiveCaseFileSubmissionIT
```

## Local Development Setup

Requires WildFly 10, PostgreSQL, and ActiveMQ running locally.

**WildFly:** Start with the custom server config:
```bash
bin/standalone.sh -c standalone-stagingdlrm.xml -Dspring.config.name=sandbox
```

**ActiveMQ:** Download the ActiveMQ RAR file and place it in WildFly's `standalone/deployments/`. Update the `<archive>` entry in `standalone-stagingdlrm.xml` to match the filename.

**PostgreSQL driver:** Place the JDBC jar in `modules/org/postgresql/main/` and create a `module.xml` alongside it (see README.md for the exact XML content).

Deploy built WARs by copying them to WildFly's `deployments/` directory (or run `./deployAll.sh` with `VAGRANT_DIR` set).

## Architecture

This service implements the **Staging DLRM** context — it ingests migrated criminal prosecution case files (from legacy systems `LIBRA`/`XHIBIT` via Azure Blob Storage), validates and forwards them to `cpp-context-prosecution-casefile-dlrm` (**pcfdlrm**), and reports outcomes back to Blob Storage. It follows the **CQRS + Event Sourcing** pattern used across the CPP framework, fronted by a standalone Azure Functions app that is *outside* the WildFly/JMS stack.

For a full source-cited trace of every stage (payload assembly, retry/dead-letter rules, outcome file writers) see `docs/architecture/dlrm-flow-reference.md`. `docs/architecture/material-file-flow.md` continues the trace of material (document) files past pcfdlrm into Material/Alfresco/Progression.

`docs/architecture/` holds only settled reference material for the service as it is today. Pre-pipeline investigation — feasibility studies, schema deltas, sizing drafts — lives in `docs/analysis/<topic>/`; this is the "supporting analysis" that story `00-input-brief.md` files link to. Each topic folder is **self-contained**: the write-ups, the source data they derive from, and the generated artefacts all sit together. `docs/analysis/libra-ingestion/` is the worked example — `libra-ingestion-analysis.md` and `libra-schema-impact.md`, the source `.xlsx` workbook, the generated `schema/` tree, and `libra-schema-impact.csv`.

`tools/schema-gen/` generates those LIBRA artefacts (Python 3 stdlib only, no `openpyxl`). Three scripts: `flatten-canonical-schema.py` inlines a multi-file schema set into one diffable document — run once for the canonical `stagingdlrm-domain-value-schema` and once for the func-app's own copies under `stagingdlrm-azure-functions/src/main/resources`; `generate-dlrm-schema.py` emits the LIBRA schema from the workbook, read **against** the two live contracts (`--reference`, the flattened canonical schema, and `--xhibit`, `schema/xhibit/dlrm-xhibit-0.12.json`) — see below; `build-schema-impact.py` produces `libra-schema-impact.csv`, one row per payload field with its status in each schema (func-app / canonical / pcfdlrm / Progression) and the change each needs.

**Only LIBRA is generated from the workbook.** XHIBIT is already in production, so its contract is read from the two live schema sets rather than re-derived; the func-app's resources are the XHIBIT gate and are not modified by this tooling.

**The LIBRA schema is shareable; its provenance sidecar is not.** `generate-dlrm-schema.py` writes two files: `schema/libra/dlrm-libra-0.13.json`, which is safe to send to the LIBRA extract team and the func-app owner — no workbook references, no sheet names, no row numbers, no internal notes, and the same shape and conventions as `schema/xhibit/dlrm-xhibit-0.12.json` down to definition and property order — and `dlrm-libra-0.13.provenance.json` beside it, which holds everything the workbook says (sheet field name, Format cell, per-case-type mandatoriness, business rules, comments, row numbers) keyed by JSONPath, plus a `deviations` list of every point where the contract overrode the sheet. **Keep it that way: internal detail belongs in the sidecar, never in the schema.**

**The workbook is not the naming or typing authority.** For any field the live contract already models, the contract's property **name** and **definition** are emitted verbatim, not the workbook's label or Format-cell derivation. `required` is the one thing taken from the sheet — the intersection of its case-type columns, and, for a definition the contract reuses in several places, the intersection of the sections sharing it (so a parent guardian's mandatory address does not become mandatory for every defendant). Lookup is per **container**, so `officerInCase.surname` stays LIBRA-only while `personalInformation.surname` resolves, and the parent-guardian block resolves against the contract's own `oneOf` branches. Every sheet-vs-contract disagreement is classified in the run's report as a conflict, an unstated Format cell, or the contract merely being looser. `build-schema-impact.py` reads the sidecar (`--provenance`), so the impact matrix still compares what LIBRA **supplies** against what canonical **declares** — without it that comparison would be canonical against itself.

Every script takes its inputs as parameters (`--workbook`, `--source`, `--root`, `--canonical`, `--funcapp`, `--pcfdlrm`, …) with the committed locations as defaults, and **writes to the current directory unless given `--out-dir`/`--out`** — so an ad-hoc run never touches the repo. To refresh what is committed, run `./tools/schema-gen/regenerate.sh`, which runs all four steps in dependency order against `docs/analysis/libra-ingestion/`. Regeneration is deterministic: a clean run reproduces the committed artefacts byte-for-byte. The curated pcfdlrm/core claims in `build-schema-impact.py` are re-verified against the sibling repo checkouts on every run and a stale claim fails the run, so the CSV cannot quietly rot.

### End-to-End Flow

```
Azure Blob Storage (file landed under {sourceSystem}/{batch}/{case}/{submissionId}/{fileName})
  │  Microsoft.Storage.BlobCreated EventGrid event
  ▼
[stagingdlrm-azure-functions] EventGridTriggerJava  — validates path, enqueues submission folder
  ▼
Azure Queue Storage (dlrm_queue)
  │  timer tick
  ▼
[stagingdlrm-azure-functions] TimerTriggerJava  — lists blobs, validates case.json/manifest.json
  │  schemas, assembles payload (material bytes are never downloaded — only blob paths)
  │  POST /stagingdlrm-command-api/.../receive-migrated-case-submission
  ▼
stagingdlrm-command-api        (REST endpoint, access control)
  └─> stagingdlrm-command-handler  (JMS: stagingdlrm.handler.command; runs MigratedCaseSubmissionAggregate)
        └─> Domain event on `stagingdlrm.event` topic
              ├─> stagingdlrm-event-listener   (persists to view store)
              │         └─> stagingdlrm-query-api  (reads from view store)
              └─> stagingdlrm-event-processor  (StagingDlrmEventProcessor)
                        │  resolves case URN → CPP Case File UUID via system-id-mapper
                        │  POST /pcfdlrm-service/.../receive-migrated-case-file
                        ▼
                  pcfdlrm  (processes file, fetches material bytes directly from Blob Storage)
                        │  publishes public.pcfdlrm.migrated-case-file-processed on `public.event`
                        ▼
                  PcfDlrmEventProcessor (stagingdlrm) → record-submission-processing-output command
                        └─> stagingdlrm-command-handler → MigratedCaseSubmissionProcessed event
                              └─> stagingdlrm-event-processor → publishes outcome to Azure EventGrid
                                        ▼
                                  [stagingdlrm-azure-functions] EventGridMonitor
                                        └─> writes outcome JSON back to Blob Storage
```

The original uploader has no synchronous response — it must poll Blob Storage for the outcome file. There is no streaming of material file bytes through this service at any point; only blob-path pointers travel through the whole chain.

### Module Responsibilities

| Module | Path | Artifact | Purpose |
|--------|------|----------|---------|
| Command API | `stagingdlrm-command/stagingdlrm-command-api` | WAR | REST entry point; access control; routes to JMS command queue |
| Command Handler | `stagingdlrm-command/stagingdlrm-command-handler` | WAR | Executes commands against `MigratedCaseSubmissionAggregate`; appends domain events |
| Event Listener | `stagingdlrm-event/stagingdlrm-event-listener` | WAR | Subscribes to `stagingdlrm.event` topic; persists to view store |
| Event Processor | `stagingdlrm-event/stagingdlrm-event-processor` | WAR | Converts domain events to downstream commands (pcfdlrm REST, `system-id-mapper`, Azure EventGrid); also handles the `public.event` callback from pcfdlrm |
| Query API | `stagingdlrm-query/stagingdlrm-query-api` | WAR | Read-only query interface over the view store |
| Service | `stagingdlrm-service` | WAR | Deployable assembly of all component WARs |
| Domain Aggregate | `stagingdlrm-domain/stagingdlrm-domain-aggregate` | JAR | `MigratedCaseSubmissionAggregate` — core domain logic |
| Domain Event | `stagingdlrm-domain/stagingdlrm-domain-event` | JAR | Domain event POJOs (generated from JSON schemas) |
| Domain Value Schema | `stagingdlrm-domain/stagingdlrm-domain-value-schema` | JAR | Shared JSON Schema value types (case details, individual, address, pcf-* types) used across command/domain schemas |
| Domain Transformations | `stagingdlrm-domain/stagingdlrm-domain-transformations/stagingdlrm-domain-transformation-anonymise` | JAR | Event Store event-log anonymisation transformation rules |
| Viewstore Persistence | `stagingdlrm-viewstore/stagingdlrm-viewstore-persistence` | JAR | DeltaSpike/JPA persistence for the query-side view store |
| Viewstore Liquibase | `stagingdlrm-viewstore/stagingdlrm-viewstore-liquibase` | JAR | Liquibase DB migrations |
| Datatypes Common | `stagingdlrm-datatypes-common` | JAR | Shared data-type JSON schema catalog |
| Event Sources | `stagingdlrm-event-sources` | JAR | `event-sources.yaml` — declares the `stagingdlrm` and `public.event.source` JMS event sources |
| Azure Functions | `stagingdlrm-azure-functions` | JAR | Standalone Azure Functions app (`EventGridTriggerJava`, `TimerTriggerJava`, `EventGridMonitor`) — ingests from Blob Storage/Queue and writes outcomes; runs outside WildFly/JMS |
| Healthchecks | `stagingdlrm-healthchecks` | JAR | `StagingdlrmIgnoredHealthcheckNamesProvider` |
| Integration Test | `stagingdlrm-integration-test` | JAR | Failsafe integration tests (WireMock-backed) |
| Performance Test | `stagingdlrm-performance-test` | JAR | JMeter performance tests |
| Test Harness | `stagingdlrm-testharness` | JAR | Manual/exploratory test harness driving the ingestion flow against fixture case/manifest/material files |

### Domain Events

- `stagingdlrm.events.migrated-case-submission-received` — appended by `receiveMigratedCaseSubmission()`; consumed by `StagingDlrmEventProcessor` to forward to pcfdlrm
- `stagingdlrm.events.error-migrated-case-submission-received` — appended by `receiveErrorMigratedCaseSubmission()`; consumed by `StagingDlrmEventProcessor` to publish a failure outcome to EventGrid
- `stagingdlrm.events.migrated-case-submission-processed` — appended by `recordMigratedCaseSubmissionOutput()` after pcfdlrm confirms processing; consumed by `StagingDlrmEventProcessor` to publish a success outcome to EventGrid
- `stagingdlrm.events.duplicate-migrated-case-submission-received` — not forwarded downstream

### Code Generation

Several Maven plugins generate source code from YAML/JSON schema definitions — do not hand-edit generated output:

- **pojo-generation-plugin** — POJOs from JSON schemas in `stagingdlrm-domain-event`
- **catalog-generation-plugin** — schema catalogs in `stagingdlrm-datatypes-common`, `stagingdlrm-domain-value-schema`
- **messaging-client-generator-plugin** — messaging clients in `stagingdlrm-command-api`, `stagingdlrm-event-processor`
- **rest-client-generator-plugin** — REST clients in `stagingdlrm-event-processor`

### CI/CD

Azure Pipelines (`azure-pipelines.yaml`) triggers on `main` and `team/*` branches:
- **PR builds** → `context-verify.yaml` (SonarQube analysis)
- **Merge builds** → `context-validation.yaml` (full validation + IT)

Build agents require `centos8-j17` capability (Java 17).

### Reconciliation Tooling

`tools/reconciliation/` holds standalone bash/Python/SQL scripts (not part of the Maven build) that cross-reference a migration batch across Blob Storage → stagingdlrm → pcfdlrm → Listing to produce a per-case CSV reconciliation report — see `tools/reconciliation/README.md`, the single authoritative reference for this directory: `## Key design facts` for the design, `## Report field reference` for each report's columns, plus environment variables, usage and known limitations. Run `./tools/reconciliation/run-all.sh <batch_id>` to execute the full pipeline; each stage script is independently runnable. All CSV output goes to `reconciliation/output/` (gitignored); `reconciliation/archived/` holds manually filed-away past runs.

### Key Dependencies (from parent POM)

- **Framework**: `uk.gov.justice.services:*` — CPP CQRS/ES framework
- **Event Store**: `uk.gov.justice.event-store:*`
- **Access Control**: `uk.gov.moj.cpp.access-control:*` (Drools-based)
- **ID Mapper**: `uk.gov.moj.cpp.system.id-mapper:*` — resolves case URN → CPP Case File UUID before forwarding to pcfdlrm
- **Azure**: `com.microsoft.azure:azure-functions-java-library`, `azure-storage-blob`, `azure-storage-queue`
- **Messaging**: Apache ActiveMQ (JMS via WildFly resource adapter)
- **Persistence**: DeltaSpike + JPA (Hibernate), PostgreSQL
- **Testing**: JUnit 5, Mockito, REST Assured, WireMock

## SDLC Orchestrator (hmcts-sdlc-orchestrator plugin)

The `hmcts-sdlc-orchestrator` plugin ships an 8-stage SDLC pipeline (Requirements →
Architecture & Design → User Story → Test Specs → Code → Code Review → Build & Test →
Deploy Sandbox). This repo's Java/Maven CQRS modules already match the plugin's legacy
context-service assumptions, so the pipeline and its agents are **reused as-is** — no
local overrides needed for that part of the codebase.

- **Reuse from the plugin as-is:** `requirements-analyst`, `architecture-designer`,
  `story-writer`, `test-engineer`, `implementation`, `code-reviewer`, `ci-orchestrator`,
  `deployer`, `context-scaffold`, `context-service-guide`, `api-contract-check`,
  `dependency-audit`, `review-pr`, the security hooks (`block-secrets`, `block-pii`,
  `guard-bash`, `guard-paths`).
- **Do NOT use:** `springboot-service-from-template`, `springboot-api-from-template`,
  `terraform-validate`, `helm-config-validator` — no Spring Boot, Terraform, or Helm chart
  in this repo.
- **One real delta — `tools/reconciliation/`:** standalone bash/Python3(stdlib)/SQL
  scripts, not part of the Maven build. No JUnit, no Maven test/CI wiring for this
  directory. Read `tools/reconciliation/README.md` first when a pipeline stage touches
  this directory — it's the authoritative context (design facts, env vars, CSV field
  reference, known limitations), not the plugin's generic `tech-stack.md`. `test-engineer`
  should scope tests to Python's stdlib `unittest` (no new dependency); `ci-orchestrator`'s
  Maven-triggered CI does not cover this directory — verification is a manual run against
  a real batch in a dev/sandbox environment.
- Pipeline artefacts go to one directory **per story**, named
  `docs/pipeline/<EPIC-KEY>-<STORY-KEY>-<slug>/` (created on first use, no pre-scaffolding
  required): `00-input-brief.md` → `01-requirements.md` → `02-design.md` → `03-stories.md`,
  plus a shared `docs/pipeline/adrs/` for any architecturally-significant decision. Both Jira
  keys sit in the directory name, so an epic's stories sort together and either key is
  greppable without opening a file — e.g. `DD-43067-DD-43078-test-hardening/`. A ticket with no
  parent epic keeps the single-key form (`DD-43014-reconciliation-report-enhancements/`), and
  existing directories are not renamed retroactively.
- **Each story directory is self-contained.** An SDLC stage run against one story must not need
  another story's files — epic-level framing (the epic's goal, cross-cutting design decisions,
  links to supporting analysis) is repeated in that story's `00-input-brief.md`. Decisions that
  several stories share go in `docs/pipeline/adrs/` once and are linked, not restated. There is
  no epic-level artefact directory.