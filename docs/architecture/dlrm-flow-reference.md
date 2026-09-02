# DLRM Flow Reference — stagingdlrm

Complete reference for the end-to-end migration submission flow, from Azure Blob Storage through `cpp-context-stagingdlrm` to `cpp-context-prosecution-casefile-dlrm` (pcfdlrm).

---

## 1. End-to-End Overview

### 1.1 Full Flow

```
Azure Blob Storage  (file landed)
  │
  │  Microsoft.Storage.BlobCreated EventGrid event
  ▼
[FUNCAPP] EventGridTriggerJava
  │  validates folder/batch path; enqueues submission folder path
  ▼
Azure Queue Storage  (dlrm_queue)
  │
  │  scheduled timer tick
  ▼
[FUNCAPP] TimerTriggerJava
  │  dequeues batch; lists blobs; validates + assembles payload
  │
  │  POST /stagingdlrm-command-api/.../receive-migrated-case-submission
  ▼
[STAGINGDLRM] stagingdlrm-command-api
  │  access control; routes to JMS command queue
  ▼
[STAGINGDLRM] stagingdlrm-command-handler  (stagingdlrm.handler.command)
  │  runs MigratedCaseSubmissionAggregate; appends domain event
  ▼
ActiveMQ  stagingdlrm.event  (topic)
  ├──> stagingdlrm-event-listener    →  persists to view store (PostgreSQL)
  └──> stagingdlrm-event-processor
         │  resolves Case File UUID via system-id-mapper
         │
         │  POST /pcfdlrm-service/.../receive-migrated-case-file
         ▼
[PCFDLRM] cpp-context-prosecution-casefile-dlrm
  │  processes migrated case file
  ▼
ActiveMQ  public.event  (topic)
  │  public.pcfdlrm.migrated-case-file-processed
  ▼
[STAGINGDLRM] PcfDlrmEventProcessor
  │  sends record-submission-processing-output command
  ▼
[STAGINGDLRM] stagingdlrm-command-handler
  │  appends MigratedCaseSubmissionProcessed event
  ▼
[STAGINGDLRM] stagingdlrm-event-processor
  │  publishes outcome to Azure EventGrid
  ▼
[FUNCAPP] EventGridMonitor
  │  writes outcome JSON files to Blob Storage
  ▼
Azure Blob Storage  (outcome files written)
```

---

### 1.2 Interface Points at a Glance

#### Inbound to stagingdlrm

| Caller | Protocol | Endpoint / Queue | Content-Type |
|---|---|---|---|
| Azure Function `TimerTriggerJava` | REST POST | `/stagingdlrm-command-api/command/api/rest/stagingdlrm/receive-migrated-case-submission` | `application/vnd.stagingdlrm.receive-migrated-case-submission+json` |
| Azure Function `TimerTriggerJava` | REST POST | `/stagingdlrm-command-api/command/api/rest/stagingdlrm/receive-error-migrated-case-submission` | `application/vnd.stagingdlrm.receive-error-migrated-case-submission+json` |
| `pcfdlrm` context | JMS Topic | `public.event` → `public.pcfdlrm.migrated-case-file-processed` | — |

Both REST endpoints expect **HTTP 202 Accepted**. Header `CJSCPPUID` carries the staging user ID (`staging_dlrm_uid` env var).

#### Outbound from stagingdlrm

| Target | Protocol | Endpoint / Topic | Triggered by |
|---|---|---|---|
| `cpp-context-prosecution-casefile-dlrm` | REST POST | `/pcfdlrm-service/command/api/rest/pcfdlrm/receive-migrated-case-file` | `stagingdlrm.events.migrated-case-submission-received` |
| `system-id-mapper` | REST GET | `/system-id-mapper-api/rest/systemid/mappings?sourceId=&sourceType=OU_URN&targetType=CASE_FILE_ID` | Pre-forward case URN resolution |
| Azure EventGrid | Event publish | outcome event | `stagingdlrm.events.migrated-case-submission-processed` or `error-migrated-case-submission-received` |
| Azure Blob Storage | Write | `{azureLocation}/outcome.json`, `{migrationSourceSystemName}/outcome/outcome-{submissionId}.json` | Outcome event received by `EventGridMonitor` |

#### Domain Events Published on `stagingdlrm.event` Topic

| Event | Appended by | Consumed by |
|---|---|---|
| `stagingdlrm.events.migrated-case-submission-received` | `StagingdlrmCommandHandler` | `StagingDlrmEventProcessor` → sends to pcfdlrm |
| `stagingdlrm.events.error-migrated-case-submission-received` | `StagingdlrmCommandHandler` | `StagingDlrmEventProcessor` → publishes error outcome to EventGrid |
| `stagingdlrm.events.migrated-case-submission-processed` | `StagingdlrmCommandHandler` | `StagingDlrmEventProcessor` → publishes success outcome to EventGrid |
| `stagingdlrm.events.duplicate-migrated-case-submission-received` | `StagingdlrmCommandHandler` | — |

---

### 1.3 Context Dependency Map

A single-hub view of everything that talks to `stagingdlrm`, independent of message ordering:

```
                        ┌─────────────────────┐
  Azure Blob Storage ──>│  stagingdlrm        │──> pcfdlrm-service   (REST)
  Azure EventGrid    ──>│  (this context)     │──> system-id-mapper  (REST)
  public.event topic ──>│                     │──> Azure EventGrid   (publish)
                        │                     │──> Azure Blob Storage (write outcomes)
                        │                     │──> Azure Queue Storage
                        └─────────────────────┘
```

| Direction | Counterpart | Protocol |
|---|---|---|
| Inbound | Azure Blob Storage | EventGrid trigger → Queue |
| Inbound | `pcfdlrm` context | JMS `public.event` topic (`public.pcfdlrm.migrated-case-file-processed`) |
| Outbound | `pcfdlrm` context | REST POST `receive-migrated-case-file` |
| Outbound | `system-id-mapper` | REST GET mappings |
| Outbound | Azure EventGrid | Event publish (outcome) |
| Outbound | Azure Blob Storage | Write outcome files |

---

## 2. Azure Functions App — Processing Detail

**Runtime:** Azure Functions v2 (Java), Extension Bundle 4.x  
**Timeout:** 2 minutes. **Host-level retry:** fixed-delay, max 5, 10 s delay.  
**Module:** `stagingdlrm-azure-functions` — standalone JAR, runs outside the WildFly/JMS stack.

### 2.1 Blob Path Convention

Every blob in the container follows a strict 5-segment path:

```
{migrationSourceSystemName} / {batchIdentifier} / {caseIdentifier} / {submissionId} / {fileName}

e.g.  XHIBIT / Batch0001 / 28DI10000175 / a3f1c2d4-... / report.pdf
```

The first four segments form the **submission folder** — the key passed through the queue and used as the blob listing prefix.

---

### 2.2 Function 1 — `EventGridTriggerJava` (entry gate)

**Trigger:** `Microsoft.Storage.BlobCreated` EventGrid event  
**Purpose:** Validate the blob path and enqueue the submission folder for the timer. Does not communicate with stagingdlrm.

**Processing stages:**

```
1. Null/empty blob content guard  (bytes bound via @BlobInput — checked only for null/empty)
2. Parse URL  →  tokens = [folder, batch, caseFolder, submissionId, fileName, ...]
3. Token count guard  (<4 tokens → return)
4. Folder name validation  (tokens[0] must match dlrm_folder_name env var)
5. Batch name validation   (tokens[1] must be in dlrm_batch_name list, or "*" accepts all)
6. Assemble queue message  →  "folder/batch/caseFolder/submissionId"
7. Enqueue to dlrm_queue   (TTL = visibility_time_in_days)
```

**Key behaviour:** Every file for the same submission enqueues the same folder path. Duplicates accumulate on the queue; the timer de-dupes implicitly by listing blobs under the prefix at processing time.

---

### 2.3 Function 2 — `TimerTriggerJava` (core processor)

**Trigger:** Timer, schedule from `%TimerTriggerSchedule%` env var  
**Purpose:** Drain the queue, discover all blobs for each submission, validate schemas, assemble the command payload, and POST to stagingdlrm.

**Processing stages:**

```
1. Feature flag check  (case_processing_enabled must be "true")

2. Batch dequeue  (`StorageCloudClient.receiveMessages()`, `StorageCloudClient.java:61`)
   ├── count = min(approximateMessagesCount, batch_size_per_min)
   └── for each QueueMessageItem:
         listFiles(blobContainerClient, queueMessage)  ← recursive prefix listing (`StorageCloudClient.java:165`)
         → QueueMessage { queueName, deliveryCount, listOfBlobNames }

3. Per-message processing  [`processQueueMessage()`, `TimerTriggerJava.java:106`]
   │
   ├── 3a. Split blobs by type  (`TimerTriggerJava.java:359–365`)
   │       metafiles     = blobs ending ".json"       (case.json, manifest.json)
   │       materialFiles = everything else             (PDFs, docs, etc.)
   │
   ├── 3b. Meta-file presence guard  (`TimerTriggerJava.java:122–128`)
   │       REQUIRES both "{prefix}/case.json" AND "{prefix}/manifest.json"
   │       MISSING → deleteQueueMessage + sendMessageToLogQueue + return
   │
   ├── 3c. Download meta files only  (`TimerTriggerJava.java:133–139`)
   │       case.json and manifest.json downloaded; material file bytes are NEVER downloaded
   │
   ├── 3d. JSON schema validation
   │       case.json     → stagingdlrm.case-submission.json schema
   │       manifest.json → stagingdlrm.manifest.json schema
   │
   ├── 3e. [VALID] Payload assembly  →  see §2.4 below
   │   [INVALID] error POST to /receive-error-migrated-case-submission
   │
   ├── 3f. Count guard  (`processBaseUriArray()`, `TimerTriggerJava.java:177–210`)
   │       materialFiles.size() == metadata.numberOfMaterials  → proceed
   │       MISMATCH → error POST to /receive-error-migrated-case-submission
   │
   └── 3g. POST to stagingdlrm command API
           HTTP 2xx → deleteQueueMessage
           HTTP 4xx → error POST to /receive-error-migrated-case-submission
           HTTP 5xx → retry logic (see §2.5)
```

---

### 2.4 Payload Assembly — `StagingDlrmCommandHelper`

```
generateMigratedCaseSubmissionPayload() — StagingDlrmCommandHelper.java:72

Inputs:  caseJsonInput, manifestJsonInput, materialFiles (blob paths), submissionId, azureLocation

Steps:
  1. manifest.json "files" array present?
       YES → numberOfMaterials = files[].length
       NO  → numberOfMaterials = 0, no materials array
             (confirmed by test shouldProcessTimerTriggerSuccessfullyWhenNoMaterialsAreAttached)

  2. For each entry in manifest "files":
       { fileName, fileType, documentType }
       └── find matching blob: materialFiles.filter(f -> f.endsWith(fileName)).findFirst()
           FOUND  → { id: UUID, fileType, documentType, azureLocation: blobPath, fileName }
           MISSING → { id: UUID, fileType, documentType }  (azureLocation/fileName omitted)

  3. Build final payload:
     {
       "migratedCase": {
         "caseDetails": { ... },           ← from case.json migratedCase.caseDetails
         "hearings":    [ ... ],            ← from case.json migratedCase.hearings
         "defendants":  [ ... ],            ← from case.json migratedCase.defendants
         "migrationSourceSystem": { ... }   ← from manifest.json
       },
       "materials": [ { id, fileType, documentType, azureLocation, fileName }, ... ],
       "metadata":  { "id": UUID, "numberOfMaterials": N },
       "submissionId": "...",
       "azureLocation": "XHIBIT/Batch0001/28DI10000175/submissionId"
     }
```

**Ordering guarantee: none.** Blob prefix listing is unordered — step 2's match is by filename suffix, never by position, so `materials[]` order does not reflect upload order.

---

### 2.5 Retry and Dead-Letter Behaviour

| Condition | Delivery count | Action |
|---|---|---|
| 5xx server error | within `retry_count` | Leave on queue; re-visible after `queue_visibility_timeout_seconds` |
| 5xx server error | `> retry_count + 1` | Write failure outcome blob, delete from queue, send to log queue |
| 4xx on main POST | any | POST to `/receive-error-migrated-case-submission` |
| 4xx on error POST | any | Write failure outcome blob directly, delete, log queue |
| Schema validation failure | any | POST to `/receive-error-migrated-case-submission`, then delete |
| Missing case.json or manifest.json | any | Delete immediately, send to log queue |
| Material count mismatch | any | POST to `/receive-error-migrated-case-submission` |

---

### 2.6 All Outcome File Write Paths

Two files are always written together regardless of which path triggers the write:

| File | Path |
|---|---|
| Per-submission outcome | `{migrationSourceSystemName}/outcome/outcome-{submissionId}.json` |
| Rolling summary | `{azureLocation}/outcome.json` |

Content shape: `{ caseUrn, success, description }`

There are two distinct writers: **EventGridMonitor** (async, via stagingdlrm EventGrid publish) and **TimerTriggerJava directly** (sync fallback via `EventGridMonitorHelper`). Which one fires depends on how far the submission got before failing.

---

#### Path 1 — Happy path (`success: true`)

```
stagingdlrm appends MigratedCaseSubmissionProcessed
  └─> StagingDlrmEventProcessor → publishes to Azure EventGrid
        └─> EventGridMonitor writes outcome files  (success: true)
```

Writer: `EventGridMonitor`  
`caseUrn`: populated from the processed case

---

#### Path 2 — Error submission accepted by stagingdlrm (`success: false`)

Triggered by any of: schema validation failure, material count mismatch, or 4xx on main POST. The function app POSTs to `/receive-error-migrated-case-submission` and stagingdlrm returns 2xx.

```
TimerTriggerJava → POST /receive-error-migrated-case-submission → 2xx
  └─> stagingdlrm appends ErrorMigratedCaseSubmissionReceived
        └─> StagingDlrmEventProcessor → publishes to Azure EventGrid
              └─> EventGridMonitor writes outcome files  (success: false)
```

Writer: `EventGridMonitor`  
`caseUrn`: `""` (empty) for schema/count failures — case.json was not trusted at that point; populated for 4xx-on-main-POST failures (URN was already extracted before the POST)

---

#### Path 3 — Error POST itself gets 4xx (`success: false`, direct write)

If the `/receive-error-migrated-case-submission` POST returns a 4xx, the function app writes outcome files directly without going through stagingdlrm or EventGrid.

```
TimerTriggerJava → POST /receive-error-migrated-case-submission → 4xx
  └─> processClientError(message, caseUrn, responseString)   [line 329–334]
        └─> writeOutcome() → EventGridMonitorHelper.processEvent()
              └─> writes both outcome files directly to Blob Storage  (success: false)
              └─> deleteQueueMessage + sendMessageToLogQueue
```

Writer: `TimerTriggerJava` (direct)  
`caseUrn`: `""` when originating from schema/count failures; populated when originating from 4xx-on-main-POST

---

#### Path 4 — 5xx past retry limit (`success: false`, direct write)

Applies when either the main POST or the error POST exceeds `retry_count`. `deliveryCount > retry_count + 1` triggers an immediate direct write.

```
TimerTriggerJava → POST (main or error) → 5xx, deliveryCount > retry_count + 1
  └─> processServerError()   [line 213–219]
        └─> writeOutcome() → EventGridMonitorHelper.processEvent()
              └─> writes both outcome files directly to Blob Storage  (success: false)
              └─> deleteQueueMessage + sendMessageToLogQueue
```

Writer: `TimerTriggerJava` (direct)  
`caseUrn`: populated (available from main POST path); `""` when originating from error POST path

---

#### Outcome Write Path Summary

| Scenario | Who writes | `success` | `caseUrn` |
|---|---|---|---|
| Processing confirmed by pcfdlrm | `EventGridMonitor` | `true` | populated |
| Schema invalid, error POST → 2xx | `EventGridMonitor` | `false` | `""` |
| Material count mismatch, error POST → 2xx | `EventGridMonitor` | `false` | `""` |
| 4xx on main POST, error POST → 2xx | `EventGridMonitor` | `false` | populated |
| Any error POST → 4xx | `TimerTriggerJava` direct | `false` | `""` or populated (see above) |
| Main or error POST → 5xx, past retry limit | `TimerTriggerJava` direct | `false` | populated or `""` |
| Missing case.json / manifest.json | **Not written** — dead-lettered only | — | — |

> **Note:** When `TimerTriggerJava` writes directly, the outcome `success` field is the string `"false"` rather than a boolean, because the event map is built as `Map<String, Object>` at line 277. `EventGridMonitor` receives it as a string from the EventGrid event payload in the normal paths.

---

### 2.7 Function 3 — `EventGridMonitor` (outcome writer)

**Trigger:** Azure EventGrid custom outcome event from `StagingDlrmEventProcessor`  
**Purpose:** Write the final processing outcome back to Blob Storage.

**Processing stages:**

```
1. Extract from event:  azureLocation, caseUrn, success, description, submissionId, caseId

2. Derive migrationSourceSystemName = azureLocation.split("/")[0]

3. Write per-submission outcome:
   path: {migrationSourceSystemName}/outcome/outcome-{submissionId}.json
   body: { caseUrn, success, description }

4. Write rolling outcome summary:
   path: {azureLocation}/outcome.json
   body: { caseUrn, success, description }
```

**Triggered by these stagingdlrm domain events:**

| Event | `success` value |
|---|---|
| `stagingdlrm.events.migrated-case-submission-processed` | `true` |
| `stagingdlrm.events.error-migrated-case-submission-received` | `false` |

---

### 2.7 Environment Variables

| Variable | Used by | Purpose |
|---|---|---|
| `AzureWebJobsStorage` | All | Azure Storage connection string |
| `dlrm_container` | `StorageCloudClient`, `EventGridMonitorHelper` | Blob container name |
| `dlrm_queue` | `StorageCloudClient` | Main processing queue name |
| `dlrm_log_queue` | `StorageCloudClient` | Dead-letter log queue name |
| `dlrm_folder_name` | `EventGridTriggerJava` | Expected source system folder (e.g. `XHIBIT`) |
| `dlrm_batch_name` | `EventGridTriggerJava` | Comma-separated allowed batch names, or `*` for wildcard |
| `batch_size_per_min` | `StorageCloudClient` | Max messages dequeued per timer tick |
| `visibility_time_in_days` | `StorageCloudClient` | Queue message TTL (days) |
| `queue_visibility_timeout_seconds` | `StorageCloudClient` | Visibility timeout after dequeue (default: 300 s) |
| `case_processing_enabled` | `TimerTriggerJava` | Feature flag — `"true"` to enable processing |
| `TimerTriggerSchedule` | `TimerTriggerJava` | CRON expression for timer |
| `staging_dlrm_base_uri` | `TimerTriggerJava` | Comma-separated base URIs of stagingdlrm-command-api |
| `staging_dlrm_uid` | `TimerTriggerJava` | User ID sent in `CJSCPPUID` header |
| `staging_dlrm_content_type` | `TimerTriggerJava` | Content-Type for `receive-migrated-case-submission` |
| `staging_dlrm_error_content_type` | `TimerTriggerJava` | Content-Type for `receive-error-migrated-case-submission` |
| `retry_count` | `TimerTriggerJava` | Max server-error retries before dead-lettering (default: 3) |

---

## 3. cpp-context-stagingdlrm — Internal Processing Detail

### 3.1 Module Map

| Module | Artifact | Role |
|---|---|---|
| `stagingdlrm-command-api` | WAR | REST entry point; access control; routes to JMS command queue |
| `stagingdlrm-command-handler` | WAR | Executes commands against `MigratedCaseSubmissionAggregate`; appends domain events |
| `stagingdlrm-event-listener` | WAR | Subscribes to `stagingdlrm.event` topic; persists to view store |
| `stagingdlrm-event-processor` | WAR | Converts domain events to downstream commands (pcfdlrm REST, Azure EventGrid) |
| `stagingdlrm-query-api` | WAR | Read-only query interface over the view store |
| `stagingdlrm-domain-aggregate` | JAR | `MigratedCaseSubmissionAggregate` — core domain logic |
| `stagingdlrm-domain-event` | JAR | Domain event POJOs (generated from JSON schemas) |
| `stagingdlrm-viewstore-persistence` | JAR | DeltaSpike/JPA persistence |
| `stagingdlrm-viewstore-liquibase` | JAR | Liquibase DB migrations |
| `stagingdlrm-azure-functions` | JAR | Standalone Azure Functions (outside WildFly/JMS) |

### 3.2 Happy-Path Command Flow

```
REST POST /receive-migrated-case-submission
  └─> StagingdlrmCommandApi.receiveMigratedCaseSubmission()
        └─> JMS: stagingdlrm.handler.command
              └─> StagingdlrmCommandHandler.receiveMigratedCaseSubmission()
                    └─> MigratedCaseSubmissionAggregate
                          └─> appends MigratedCaseSubmissionReceived
                                └─> stagingdlrm.event topic
```

### 3.3 Event Processor — Forwarding to pcfdlrm

`StagingDlrmEventProcessor` (`StagingDlrmEventProcessor.java:84`) subscribes to `stagingdlrm.event` and handles three events:

**`stagingdlrm.events.migrated-case-submission-received`**
1. Calls `systemMapperService.getCaseIdForPtiURN()` → resolves case URN to CPP Case File UUID via `system-id-mapper`
2. Calls `buildMaterials(materials, caseId)` (`MigratedCaseConvertor.java:314`) — maps each `MigratedMaterial` to pcfdlrm's schema (adds `caseId` to each)
3. Sends all materials in a single `ReceiveMigratedCaseFile` REST POST to pcfdlrm

**`stagingdlrm.events.migrated-case-submission-processed`**
1. Calls `EventGridService.sendEventToEventGrid()` with success outcome payload
2. Consumed by `EventGridMonitor` Azure Function

**`stagingdlrm.events.error-migrated-case-submission-received`**
1. Calls `EventGridService.sendEventToEventGrid()` with error outcome payload
2. Consumed by `EventGridMonitor` Azure Function

### 3.4 pcfdlrm Callback Flow

```
pcfdlrm processes ReceiveMigratedCaseFile
  └─> publishes public.pcfdlrm.migrated-case-file-processed on public.event topic
        └─> PcfDlrmEventProcessor (stagingdlrm)
              └─> sends record-submission-processing-output to stagingdlrm.handler.command
                    └─> StagingdlrmCommandHandler.recordMigratedCaseSubmissionOutput()
                          └─> appends MigratedCaseSubmissionProcessed
                                └─> StagingDlrmEventProcessor → Azure EventGrid (success outcome)
```

### 3.5 JMS Command Queue — Message Types

Queue: `stagingdlrm.handler.command`

| Message type | Handler method | Event appended |
|---|---|---|
| `stagingdlrm.command.handler.receive-migrated-case-submission` | `receiveMigratedCaseSubmission()` | `MigratedCaseSubmissionReceived` |
| `stagingdlrm.command.handler.receive-error-migrated-case-submission` | `receiveErrorMigratedCaseSubmission()` | `ErrorMigratedCaseSubmissionReceived` |
| `stagingdlrm.command.handler.record-submission-processing-output` | `recordMigratedCaseSubmissionOutput()` | `MigratedCaseSubmissionProcessed` |

---

## 4. Q&A

### Q: How are material files ingested and handled end-to-end?

**Phase 1 — EventGrid (per file):** `EventGridTriggerJava` fires for every individual file landed in Blob Storage — whether `case.json`, `manifest.json`, or a material file like `report.pdf`. It enqueues only the **submission folder path**, never the file itself. All files for a submission produce the same folder path in the queue.

**Phase 2 — Timer (blob listing):** `TimerTriggerJava` recursively lists all blobs under the submission prefix at dequeue time, producing a complete `List<String>` of blob names. By the time processing starts, the timer has a full snapshot of everything that has arrived.

**Phase 3 — Separation:** Blobs are split into `metafiles` (ending `.json`) and `materialFiles` (everything else).

**Phase 4 — Assembly:** `manifest.json`'s `files[]` array is the authoritative declared list. For each entry, the matching blob path is located by filename suffix. Material file **bytes are never downloaded** — only their blob paths (`azureLocation`) are recorded in the payload.

**Phase 5 — Count guard:** `materialFiles.size()` must exactly equal `manifest.json`'s declared `numberOfMaterials`. A mismatch routes to the error submission path.

**Phase 6 — Forwarding:** All materials travel as an array in the single `ReceiveMigratedCaseFile` POST to pcfdlrm. pcfdlrm fetches the actual bytes directly from Blob Storage using the `azureLocation` pointers.

---

### Q: What does the function app do (and not do) with material files?

| Operation | Does the function app do it? |
|---|---|
| Discover blob paths by prefix listing | Yes |
| Cross-reference paths against manifest `files[]` | Yes |
| Pass `azureLocation` pointer to stagingdlrm | Yes |
| Download/read material file bytes | **No** |
| Validate material file contents | **No** |
| Move, copy, or transform material files | **No** |
| Upload material files | **No** |

The function app is a **metadata broker** for materials. Actual bytes stay in Blob Storage and are fetched directly by pcfdlrm.

---

### Q: What happens when case.json or manifest.json is missing?

The queue message is **deleted immediately** from `dlrm_queue` and sent to the dead-letter log queue (`dlrm_log_queue`). No REST call is made to stagingdlrm. The submission is not retried — it is up to the upstream caller to re-upload a complete submission folder.

---

### Q: What happens when a material file declared in manifest.json has no matching blob?

The material entry is still included in the payload but with `azureLocation` and `fileName` omitted:

```json
{ "id": "uuid", "fileType": "1", "documentType": 5 }
```

However, the **count guard** (Phase 5 above) will catch this scenario if the total number of non-JSON blobs in storage does not match `numberOfMaterials` from the manifest, routing the submission to the error path.

---

### Q: How does the retry / dead-letter mechanism work?

- **Server errors (5xx):** The queue message is left visible after `queue_visibility_timeout_seconds`. On each dequeue attempt, `deliveryCount` increments. Once `deliveryCount > retry_count + 1`, a failure outcome blob is written, the message is deleted, and it is sent to `dlrm_log_queue`.
- **Client errors (4xx):** No retry. The error payload is immediately POSTed to `/receive-error-migrated-case-submission`. If that also fails (4xx), the failure outcome is written directly to Blob Storage.
- **Schema validation failures:** No direct retry, but the error POST can trigger one. The invalid payload is POSTed to `/receive-error-migrated-case-submission`; the queue message is deleted on a 2xx or 4xx response. If the error POST returns 5xx, `processServerError()` applies the normal retry logic — the message stays on the queue until `deliveryCount > retry_count + 1`, at which point a failure outcome blob is written, the message is deleted, and it is sent to `dlrm_log_queue`.
- **Missing case.json or manifest.json:** No retry. The queue message is deleted immediately and sent to `dlrm_log_queue`. No call is made to stagingdlrm and no outcome file is written.

---

### Q: How is the submission folder path structured and why is it important?

The path `{migrationSourceSystemName}/{batchIdentifier}/{caseIdentifier}/{submissionId}` is:
- The **queue message payload** passed from `EventGridTriggerJava` to `TimerTriggerJava`
- The **blob listing prefix** used to discover all files for a submission
- The `azureLocation` field in the command payload sent to stagingdlrm and forwarded to pcfdlrm
- The prefix under which `EventGridMonitor` writes outcome files
- Used by `EventGridMonitor` to derive `migrationSourceSystemName` for the outcome path (`splitStr[0]`)

---

### Q: What is the outcome loop — how does the caller know processing succeeded or failed?

The original uploader polls or monitors Blob Storage for `{migrationSourceSystemName}/outcome/outcome-{submissionId}.json`. That file contains `{ caseUrn, success, description }` and is the only feedback channel — there is no synchronous response to the original upload.

The file is written by one of two writers depending on where in the pipeline the submission ended up. See **§2.6** for the complete breakdown of all paths (happy path, error POST, direct fallback writes) and what `success` and `caseUrn` values to expect in each case.

**Key distinction:** `EventGridMonitor` writes the outcome when stagingdlrm successfully records the event (either success or error). `TimerTriggerJava` writes it directly — bypassing stagingdlrm entirely — only when the error POST itself fails (4xx) or when 5xx retries are exhausted.

---

## 5. Supporting Classes (Function App)

| Class | Role |
|---|---|
| `StorageCloudClient` | Azure Queue + Blob SDK; dequeue, send, delete, blob listing, blob download |
| `BlobCloudStorage` | Legacy Azure Blob SDK (`CloudBlockBlob`); used only by `EventGridMonitorHelper` to upload outcome files |
| `StagingDlrmCommandHelper` | Builds command payloads; sends REST POSTs with SSL trust-all; `JsonObject` → String serialisation |
| `EventGridMonitorHelper` | Generates outcome JSON content; delegates upload to `BlobCloudStorage` |
| `JsonSchemaValidator` | Loads JSON Schema v4 from classpath (`networknt`); validates `case.json` and `manifest.json` |
| `EventGridSchema` | Deserialisation model for inbound EventGrid blob-created events |
| `EventGridEvent` | Deserialisation model for inbound EventGrid outcome events |
| `QueueMessage` | Record: `(queueName, deliveryCount, listOfBlobNames)` |

## 6. JSON Schemas (Function App Classpath)

| Schema file | Validates | Key required fields |
|---|---|---|
| `stagingdlrm.case-submission.json` | `case.json` top level | `migratedCase` object |
| `migrated-case.json` | `migratedCase` | `caseDetails` |
| `case-details.json` | `caseDetails` | `prosecutorCaseReference`, `originatingOrganisation`, `initiationCode`, `prosecutor`, `dateReceived`, `retrialIndicator`, `receiptType`, `receivingCourt` |
| `stagingdlrm.manifest.json` | `manifest.json` | `migrationSourceSystem`; `files[]` entries each require `fileName`, `fileType`, `documentType` |
| `migrationSourceSystem.json` | `migrationSourceSystem` block | `migrationSourceSystemName` (enum), `migrationSourceSystemCaseIdentifier` |
| `migrationSourceSystemName.json` | enum | Values: `"LIBRA"`, `"XHIBIT"` |
| `pcf-prosecutor.json` | `prosecutor` | `prosecutingAuthority` (string) |
| `definitions.json` | Shared | UUID pattern |
