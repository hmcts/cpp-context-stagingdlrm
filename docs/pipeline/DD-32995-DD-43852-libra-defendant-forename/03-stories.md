# 03 — User Story

## DD-43852 — LIBRA defendant without a forename

**As** the DLRM migration service
**I want** a LIBRA submission whose person defendant has no forename to be rejected in stagingdlrm
**So that** CP never holds a LIBRA case whose defendant cannot be edited without adding a forename.

### Scope

- One repo: `cpp-context-stagingdlrm`.
- `02-design.md` C-1 – C-4.
- LIBRA only, every case type: SJP, Summons, Charge, Requisition.
- Every defendant in the submission; one without a forename rejects it.
- Absent, empty or whitespace-only forename counts as missing.

### Acceptance criteria

See `01-requirements.md` AC-1 – AC-3.

### Definition of done

- [ ] C-1 – C-4 implemented. No other production change.
- [ ] Tests in `02-design.md` added; fixtures updated.
- [ ] `mvn clean install` green. Integration tests green.

### Out of scope

- XHIBIT, and the shared canonical `personal-information.json`.
- Parent/guardian and alias forenames.
- pcfdlrm: no code change. The DD-43493 D2 note in the pcfdlrm repo
  (`docs/pipeline/DD-32995-DD-43493-defendant-validation/01-requirements.md`) needs a one-line
  update pointing here — a doc-only change in that repo.
- The SPI / CP DLQ issue raised in the ticket thread.
