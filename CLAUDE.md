# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Branch note — `team/25.104.x`.** This branch is cut from `main` for the DD-43191 Java 25 upgrade
> epic. It does **not** carry the LIBRA-era work that lives on `team/libra1`: the `*-test-support`
> module and its scenario-test DSL, the source-system-keyed Function App validators, the
> `docs/analysis/libra-ingestion/` schema study and `tools/` (whose own `.gitignore` is therefore absent
> — the root one guards its leftovers). Anything describing those is out of
> scope here — see `docs/pipeline/adrs/DD-43191-j25-parity-method.md` decision 7 for the
> J25 stories' scope against what this branch actually contains.

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

For a full source-cited trace of every stage (payload assembly, retry/dead-letter rules, outcome file
writers) see `docs/architecture/dlrm-flow-reference.md`. `docs/architecture/material-file-flow.md`
continues the trace of material (document) files past pcfdlrm into Material/Alfresco/Progression. Both
predate the Java 25 work, so their version pins are the J17 ones — read them as the *before* picture.

`docs/architecture/` holds settled reference material for the service as it is today. Pre-pipeline
investigation — feasibility studies, schema deltas, behavioural-change reports —
lives in `docs/analysis/<topic>/`; this is the "supporting analysis" that story `00-input-brief.md`
files link to. Each topic folder is **self-contained**: the write-ups, the source data they derive
from, and any generated artefacts sit together. `docs/analysis/j25-upgrade/` is the worked example on
this branch — the behavioural-change investigation report behind the DD-43191 Java 25 epic.

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
- **ADRs are named `<JIRA-KEY>-<slug>.md`, never numbered.** `docs/pipeline/adrs/DD-43191-j25-parity-method.md`.
  A sequential number has to be allocated by someone at authoring time, which cannot work with stories
  running in parallel on separate branches: two authors both reach for the next integer and neither sees
  the other until merge. Every ADR is born from an epic or story that already carries a unique key, so
  keying to it makes collision impossible, needs no register and no approval round-trip, gives a mirrored
  ADR the same filename in both repos automatically, and makes `grep DD-43191` find the stories and their
  ADRs together. An epic with several ADRs distinguishes them by slug, exactly as its story directories
  do. Pre-existing numbered ADRs keep their names — they are not renumbered retroactively.
- **Shared ADRs are mirrored, not linked across.** `cpp-context-stagingdlrm` and
  `cpp-context-prosecution-casefile-dlrm` are worked independently, by separate developers, so each
  repo's pipeline must be self-contained. A decision shared with the other DLRM repo is authored once
  and committed to **both**, under the same filename, carrying a header line that names its mirror. Amend both copies in the same pair of PRs, or neither; sections applying to only one repo say
  so inline so the two files stay byte-identical. Never reference the other repo by URL for anything a stage needs to read.
- Pipeline artefacts go to one directory **per story**, named
  `docs/pipeline/<EPIC-KEY>-<STORY-KEY>-<slug>/` (created on first use, no pre-scaffolding
  required): `00-input-brief.md` → `01-requirements.md` → `02-design.md` → `03-stories.md`,
  plus a shared `docs/pipeline/adrs/` for any architecturally-significant decision. Both Jira
  keys sit in the directory name, so an epic's stories sort together and either key is
  greppable without opening a file — e.g. `DD-43067-DD-43078-test-hardening/`. A ticket with no
  parent epic keeps the single-key form, and
  existing directories are not renamed retroactively.
- **Each story directory is self-contained.** An SDLC stage run against one story must not need
  another story's files — epic-level framing (the epic's goal, cross-cutting design decisions,
  links to supporting analysis) is repeated in that story's `00-input-brief.md`. Decisions that
  several stories share go in `docs/pipeline/adrs/` once and are linked, not restated. There is
  no epic-level artefact directory.