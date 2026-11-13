# 00 — Input Brief

> **Self-contained** — everything an SDLC stage needs for this story is in this directory.

| | |
|---|---|
| Epic | [DD-33029](https://tools.hmcts.net/jira/browse/DD-33029) — Receive case material for migrated case |
| Story | [DD-43946](https://tools.hmcts.net/jira/browse/DD-43946) — Add the date that the case materials was uploaded to Libra to the CP Schema (stagingdlrm half) |
| Follows | [DD-43891](https://tools.hmcts.net/jira/browse/DD-43891) — (Spike) Understand the behaviour of the case materials upload date (closed) |
| Repo | `cpp-context-stagingdlrm` — **this repo only**. Base branch `team/libra4`. |
| Shared decision | [ADR-DD-43946](https://github.com/hmcts/cpp-context-prosecution-casefile-dlrm/blob/team/libra4/docs/pipeline/adrs/ADR-DD-43946-libra-material-upload-date.md) in `cpp-context-prosecution-casefile-dlrm` (single copy, not repeated here) |

## Epic framing

LIBRA case materials are migrated with the case (Azure Blob → Function App → stagingdlrm → pcfdlrm →
`material` and `progression`). The Function App reads the file list from the submission's
`manifest.json` and builds one material per file. CP shows an "Uploaded" date for each case material,
and for migrated LIBRA materials it is currently the time the case is accepted, not the date the
material was uploaded to LIBRA.

## Request

Carry the date each material was uploaded to LIBRA through to CP, so it shows as "Uploaded". The
ticket comment says this needs the Function App, the stagingdlrm service and pcfdlrm. The pcfdlrm half
is a separate story in that repo.

## Current behaviour (code, `team/libra4`)

- **Function App:** `stagingdlrm.manifest.json` `files` is shared by XHIBIT and LIBRA (one manifest
  validator). Each entry has `fileName`, `fileType`, `documentType`. `files.items` is written as a
  **two-element tuple**, so draft-04 validates only the first two entries; the rest are not checked.
  Known existing behaviour, out of scope for this story.
  `StagingDlrmCommandHelper` builds each material as `id`, `fileType`, `documentType`,
  `azureLocation`, `fileName`. No date.
- **Service:** canonical `migrated-material.json` already has optional `receivedDateTime`
  (core `date-time`), and `MigratedCaseConvertor` (`:343`) already copies it to pcfdlrm. Nothing
  sets it today.
