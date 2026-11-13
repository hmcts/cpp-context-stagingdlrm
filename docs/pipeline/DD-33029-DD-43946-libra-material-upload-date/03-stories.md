# 03 — User Story

## DD-43946 — LIBRA material upload date (stagingdlrm)

**As** the DLRM migration service
**I want** to read each LIBRA material's upload date from `manifest.json` and pass it to pcfdlrm
**So that** CP can show it as the material's "Uploaded" date.

### Scope

- One repo: `cpp-context-stagingdlrm`, base `team/libra4`.
- `02-design.md` C-1, C-2, C-4, C-5.
- Jira: a sub-task under DD-43946.
- Pairs with the pcfdlrm story (same directory name in that repo). Either can merge first.

### Definition of done

- [x] Q1 – Q6 confirmed and recorded in `01-requirements.md`.
- [ ] C-1, C-2, C-4, C-5 implemented; tests in `02-design.md`.
- [ ] `mvn clean install` green. Integration tests green.

### Out of scope

- pcfdlrm, `material` and `progression` changes.
- Fixing the `files.items` tuple so every manifest entry is validated (Q3).
