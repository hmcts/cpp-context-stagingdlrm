# 03 — User Story

## DD-43944 — LIBRA first court hearing location (stagingdlrm)

**As** the DLRM migration service
**I want** to accept a LIBRA case's first court hearing location in `migrationSourceSystem` and pass it to pcfdlrm
**So that** the court the prosecutor first sent the case to is kept, even when no hearings were migrated.

### Scope

- One repo: `cpp-context-stagingdlrm`, base `team/libra4`.
- `02-design.md` C-1 – C-3.
- Pairs with the pcfdlrm story (same directory name in that repo), which merges first.

### Definition of done

- [x] Q1 – Q5 confirmed and recorded in `01-requirements.md`.
- [ ] C-1 – C-3 implemented; tests in `02-design.md`.
- [ ] `mvn clean install` green. Integration tests green.

### Out of scope

- pcfdlrm, core-domain and Progression changes.
