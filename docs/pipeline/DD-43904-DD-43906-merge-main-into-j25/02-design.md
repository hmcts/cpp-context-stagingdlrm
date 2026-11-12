# Design — DD-43906: merge main into java25 for stagingdlrm

> Stage 2 artefact (architecture & design). Source: [`01-requirements.md`](./01-requirements.md).
> Approved at the Stage 2 gate, 2026-11-02.
> No new components, events, endpoints or schemas are designed here — this story only carries
> existing `main` changes across. The design is the set of rules for settling each clash.

## Approach

A normal `git merge origin/main` on a working branch cut from `team/25.104.x`, merged back by PR
(AC6). Not a rebase or squash: a merge commit keeps main's history, so AC1 can be checked with
`git merge-base --is-ancestor`, and a later merge of main only brings what is new since this one.

```
team/25.104.x  4e1b077 ──┬────────────────────────── (PR) ── merge commit
                         └─ dev/merge-main-into-25 ──┘
main           c57ac88 ──────────────────────────────┘
```

## Resolution rules

| # | Where | Rule | Requirement |
|---|---|---|---|
| R1 | Project `<version>` / parent `<version>` in all 26 poms | Keep team's `25.104.30-DLRMJ25-SNAPSHOT`. Keep everything else main changed in the file (e.g. the `junit-jupiter-params` test dependency in `stagingdlrm-event-processor/pom.xml`). | NFR1, FR3 |
| R2 | Root pom `coredomain.version`, `system.id-mapper.version` | Keep team's (25.104.5, 25.104.0) — Java 25 builds of these libraries. | NFR2 |
| R3 | Root pom `progression.version` | Keep team's 17.0.297, chosen in the J25 stage-1 commit `7789e63`. Main's 17.104.299 is not required by the enforcer. | NFR3 |
| R4 | Root pom `pcfdlrm.version` | Keep team's 17.103.24 (decision D1 — main's 17.104.21 has byte-identical RAML). pcfdlrm's latest Java 25 build, `25.104.26-DLRMJ25-SNAPSHOT`, was checked too: its RAML is also identical. This pin only feeds REST-client generation at build time; the pcfdlrm that actually runs is set by `pcfdlrm_service_image_tag` in `cpp.pipeline`, which already points at the J25 build. | NFR3 |
| R5 | `ProgressionStub.java` imports | Keep team's `jakarta.ws.rs.core.HttpHeaders`; add main's `SC_NOT_FOUND`, which main's new `stubProgressionProsecutionCaseNotFound` uses. | NFR4, FR3 |
| R6 | `CLAUDE.md` | Take team's version whole (decision D2). | — |
| R7 | Files that merged without a clash | Take as merged, then check NFR4 (no `javax`) over the whole tree, because main is a `javax` branch and a clean merge would not flag a `javax` import. | NFR4, FR2 |

With R1–R4 the root `pom.xml` ends up **identical** to team's: main contributes no dependency change
to it.

## What is deliberately not changed

- No code is edited beyond R5. Main's Java changes are taken as merged; if they did not compile on
  Java 25, the fix would be a design change and come back to this gate.
- `tools/reconciliation/` is not wired into the Maven build — it is not on main either.
- `CLAUDE.md`'s "does not carry `tools/`" note stays out of date (Q3, answered "leave it").

## Risks

| Risk | Handling |
|---|---|
| A clean merge brings Java 17-only API use that compiles but misbehaves on Java 25 | Full unit and integration test runs on Java 25 (AC4, AC5) |
| Main's tests depend on main's newer `pcfdlrm` / `progression` RAML | R3/R4 shown safe by AC4/AC5; for `pcfdlrm` the RAML is identical |
| CI differs from local (WildFly 40 base image) | CI runs on the PR; the DevOps base-image fix is already on `team/25.104.x` (`25dd82f`) |

No ADR is needed: R3/R4 apply the existing [upgrade-mechanics ADR](../adrs/DD-43191-j25-upgrade-mechanics.md)
decision 2 rather than make a new decision.
