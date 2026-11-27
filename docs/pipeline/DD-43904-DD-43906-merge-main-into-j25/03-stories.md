# Stories — DD-43906: merge main into java25 for stagingdlrm

> Stage 3 artefact (user story). Source: [`01-requirements.md`](./01-requirements.md),
> [`02-design.md`](./02-design.md). One story, no split needed.
> Approved at the Stage 3 gate, 2026-11-02.

## DD-43906 — Merge main into team/25.104.x

**Jira:** [DD-43906](https://hmcts.atlassian.net/browse/DD-43906) (sub-task of story DD-43904)

As a **CPP platform engineer**,
I want **every change on `main` merged into `team/25.104.x` while the branch keeps its Java 25
versions and `jakarta` code**,
so that **the Java 25 release of stagingdlrm does not silently drop the Java 17 fixes made since the
branches split**.

### Acceptance criteria

As in [`01-requirements.md`](./01-requirements.md#acceptance-criteria), AC1–AC6:

1. **AC1** `main` head `c57ac88` is an ancestor of the merge commit.
2. **AC2** All poms keep `25.104.30-DLRMJ25-SNAPSHOT`; parent and `coredomain` 25.104.5,
   `system.id-mapper` 25.104.0, `progression` 17.0.297, `pcfdlrm` 17.103.24; netty 4.2.17.Final.
3. **AC3** No Jakarta-EE `javax.*` import in any `.java` file.
4. **AC4** `mvn clean install` passes on Java 25, zero unit test failures.
5. **AC5** `mvn clean && ./runIntegrationTests.sh` passes on Java 25, zero failures, 22 integration
   tests including main's 3 new ones.
6. **AC6** Lands on `team/25.104.x` by PR from `dev/merge-main-into-25` as one merge commit with a
   one-line message.

### Scope

Merge only, resolved per rules R1–R7 in [`02-design.md`](./02-design.md#resolution-rules).
Not in scope: pcfdlrm repo (Q2), `CLAUDE.md` wording (Q3), any version bump or refactor.

### Definition of done

AC1–AC6 met, PR approved and merged to `team/25.104.x`, CI green.
