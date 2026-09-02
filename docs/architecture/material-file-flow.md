# Material File Flow — Ingestion Through Alfresco

Full lifecycle of a migrated case's material files: discovery inside `stagingdlrm`'s
Azure Functions app, then the cross-context hand-off through pcfdlrm, Material, Alfresco,
and Progression.

---

## 1. Ingestion (stagingdlrm + function app)

A material file is never read for its bytes anywhere in `stagingdlrm`. It is discovered,
cross-referenced, and forwarded purely as a blob-path pointer:

1. `EventGridTriggerJava` fires per file landed in Blob Storage and enqueues only the
   **submission folder path** — never the file itself.
2. `TimerTriggerJava` recursively lists every blob under that prefix, then splits the
   result into `metafiles` (`case.json`, `manifest.json`) and `materialFiles` (everything
   else).
3. `manifest.json`'s `files[]` array is the authoritative declared list — each entry is
   cross-referenced against `materialFiles` by filename suffix.
4. A count guard requires `materialFiles.size()` to exactly equal the manifest's declared
   `numberOfMaterials` before the submission proceeds.

See `dlrm-flow-reference.md` §2.2–§2.4 for the full processing-stage breakdown, exact
source-line citations, and the payload shape sent to stagingdlrm.

### Key Design Points

| Concern | Behaviour |
|---|---|
| Multiple files | All collected together via blob prefix listing; sent as a single batch |
| Ordering guarantee | None — blob listing is unordered; matching is by filename, not position |
| Missing file | Matched to `""` (empty string); `azureLocation` and `fileName` are omitted from that material entry |
| Count mismatch | Treated as a client error → `receive-error-migrated-case-submission` → `ErrorMigratedCaseSubmissionReceived` event → EventGrid outcome published as failure |
| Retries | Queue visibility timeout controls retry window; `retry_count` env var caps server-error retries before writing a failure outcome and dead-lettering |
| No streaming | Files are referenced by Azure blob path only — actual bytes are never read by stagingdlrm; pcfdlrm fetches them directly using `azureLocation` |

---

## 2. Cross-Context Flow (pcfdlrm → Material → Alfresco → Progression)

Cross-context trace of a migrated case's material files, commands/events only, picking up
where §1 leaves off (stagingdlrm has already forwarded `materials[]` to pcfdlrm).

### Diagram

```
DLRM FUNCTION APP (stagingdlrm repo)
  Blob upload -> EventGridTriggerJava -> queue msg
  -> TimerTriggerJava (downloads case.json + material files, builds materials[] payload)
       |
       v
STAGING DLRM (stagingdlrm repo)
  command: stagingdlrm.receive-migrated-case-submission
  -> MigratedCaseSubmissionAggregate
  -> event: stagingdlrm.events.migrated-case-submission-received (carries materials[])
  -> StagingDlrmEventProcessor
       |
       v
PCF DLRM
  command in:  pcfdlrm.command.receive-migrated-case-file
  -> MigratedCaseFileAggregate
  -> event: pcfdlrm.events.material-added (per material)
       |
       v
  MaterialEventProcessor (@Handles pcfdlrm.events.material-added)
       |
       v
  command: material.command.upload-file (xN)
       v
  MATERIAL SERVICE
    -> Material aggregate: FileUploaded event
    -> AlfrescoFileUploader (async job)
    -> ALFRESCO (bytes stored)
    -> material.add-material -> MaterialAdded event
    -> confirmation: material.material-added
       |
       v  (back to PCF DLRM)
  PCF DLRM
    -> add-case-court-document (internal loopback)
    -> gate: all N materials confirmed
    -> event: MigratedCaseFileReceived
    -> command: progression.initiate-court-proceedings ---> PROGRESSION (creates case)
       |
       v  (Progression confirms)
  public.progression.prosecution-case-created
       |
       v
  PCF DLRM
    -> accept-migrated-case (internal)
    -> event: pcfdlrm.events.material-ready-for-court-document (per material)
    -> command: progression.add-court-document (xN) ---> PROGRESSION
       |
       v
  PROGRESSION
    command in: progression.add-court-document
    -> CourtDocumentAggregate
    -> event: CourtsDocumentAdded (case record, references already-uploaded material)

  PCF DLRM
    -> terminal event: public.pcfdlrm.migrated-case-file-processed
```

### Key point

Progression does **not** sit between PCF DLRM and Material in the byte-upload path. The
actual file bytes go **PCF DLRM → Material → Alfresco** directly
(`material.command.upload-file`), confirmed back to PCF DLRM (`material.material-added`)
*before* PCF DLRM even creates the case in Progression.

Progression is only told about the material **after** upload is confirmed and the case
exists — via `progression.add-court-document`, which creates a `CourtDocument` record
that references the material by ID/location. It does not re-trigger an Alfresco upload.

**Materials are a cloud-storage reference, not file content, until they reach Material.**
From the DLRM function app all the way through Staging DLRM and PCF DLRM, a material is
just a string field pointing at a blob location:
- `azureLocation` — carried unchanged in `migrated-material.json` in both
  `stagingdlrm-domain-value-schema` and `pcfdlrm-domain-value-schema`
- `fileCloudLocation` (or `fileStoreId`) — the equivalent field on PCF DLRM's
  `pcf-material.json` and on the `material.command.upload-file` payload

No service before Material ever reads or holds the actual bytes. **Only the Material
service downloads the blob** — `AlfrescoFileUploader.uploadFileFromAzureToAlfresco()`
calls `StorageCloudClientService.downloadBlobContents(cloudLocation)`, which opens a
`BlobContainerClient` against Azure Blob Storage and streams the bytes straight into
Alfresco. Every other hop in the chain (Staging DLRM, PCF DLRM, Progression) only ever
passes the location string around.

### Command/Event reference

| # | From → To | Message | Type |
|---|---|---|---|
| 1 | Blob storage → DLRM function app | `EventGridTriggerJava` / `TimerTriggerJava` | trigger |
| 2 | DLRM function app → Staging DLRM | `stagingdlrm.receive-migrated-case-submission` | command (REST) |
| 3 | Staging DLRM (internal) | `stagingdlrm.events.migrated-case-submission-received` | domain event, carries `materials[]` |
| 4 | Staging DLRM → PCF DLRM | `pcfdlrm.command.receive-migrated-case-file` | command |
| 5 | PCF DLRM (internal) | `pcfdlrm.events.material-added` (×N) | domain event |
| 6 | PCF DLRM → Material | `material.command.upload-file` (×N), sent by `MaterialEventProcessor` (`@Handles pcfdlrm.events.material-added`) | command |
| 7 | Material (internal) | `FileUploaded` → Alfresco upload job → `material.add-material` → `MaterialAdded` | events / async job |
| 8 | Material → PCF DLRM | `material.material-added` (×N) | public event |
| 9 | PCF DLRM (internal) | `add-case-court-document` (loopback) → gate → `MigratedCaseFileReceived` | command/event |
| 10 | PCF DLRM → Progression | `progression.initiate-court-proceedings` | command (case creation) |
| 11 | Progression → PCF DLRM | `public.progression.prosecution-case-created` | public event |
| 12 | PCF DLRM (internal) | `accept-migrated-case` → `pcfdlrm.events.material-ready-for-court-document` (×N) | command/event |
| 13 | PCF DLRM → Progression | `progression.add-court-document` (×N) | command |
| 14 | Progression (internal) | `CourtsDocumentAdded` | domain event |
| 15 | PCF DLRM → public | `public.pcfdlrm.migrated-case-file-processed` | terminal public event |

---

## 3. Access Control (ACL)

CPP command-api modules gate REST-exposed commands with Drools `.drl` rules. Domain events
(internal or public) and internal-only commands dispatched straight to a command-handler
(never via REST/`command-api`) carry no ACL. Internal commands sent with
`sendAsAdmin(...)` bypass ACL by design.

| Message | ACL type | Detail |
|---|---|---|
| `stagingdlrm.receive-migrated-case-submission` | System-user only | `command-migrate-case-submission-api.drl`: `userAndGroupProvider.isSystemUser($action)` — no end-user role can call this |
| `pcfdlrm.command.receive-migrated-case-file` | System-user only | `command-receive-migrated-case-file-api.drl`: `isSystemUser($action)` (RAML action is actually named `pcfdlrm.receive-migrated-case-file`) |
| `material.command.upload-file` | Role/permission-based | `material-command-api.drl`: non-CPS-document-upload permission OR membership in a broad group list (System Users, Court Clerks, Legal Advisers, Judiciary, Defence Lawyers, etc.) |
| `add-case-court-document` (internal loopback) | None — bypassed | Not in `pcfdlrm-command-api` RAML, no `.drl`; handled directly in `pcfdlrm-command-handler`, dispatched via `sendAsAdmin(...)` |
| `progression.initiate-court-proceedings` | Group-membership only | `command-initiate-court-proceedings-api.drl`: requires membership in one of Court Clerks, Crown Court Admin, Listing Officers, Court Administrators, Legal Advisers, System Users, Probation Admin, Court Associate |
| `accept-migrated-case` (internal) | None — bypassed | Not in `pcfdlrm-command-api` RAML, no `.drl`; routed straight to `pcfdlrm-command-handler`, never passes through REST/ACL |
| `progression.add-court-document` | Group-membership + RBAC check | `command-add-court-document.drl`: broad group list **and** `rbacProvider.isLoggedInUserAllowedToUploadDocument($action)`. A separate `command-add-court-document-for-defence.drl` applies a narrower defence-only group list for the `-for-defence` variant |
| `material.add-material` | Role/permission-based | Same `material-command-api.drl`: non-CPS-upload permission OR broad group list |
| Azure blob download (`StorageCloudClientService.downloadBlobContents`) | Infrastructure-level, not Drools | No `.drl` rule exists — this runs as an async `MaterialAlfrescoUploadTask` job-store task, not a user-triggered REST action, so no `Action`-based ACL applies. Access is gated purely by the `BlobContainerClient` credential: a static `azure.storage.connection-string` + `azure.storage.container-name` (storage-account-key based, externalized via config) — not a SAS token or managed identity |
| domain events (`*.events.*`, `public.*`) | None | Consumed by internal subscriptions/event processors, not gated by Drools ACL |

Source: `docs/flows/stagingdlrm-to-progression-material-flow.md` in
`cpp-context-prosecution-casefile-dlrm`, plus code in each of the five repos.
