# Requirements — DD-43906: merge main into java25 for stagingdlrm

> Stage 1 artefact (requirements). Source: [`00-input-brief.md`](./00-input-brief.md).
> The ticket has a title only, so the requirements below are derived from it and from the repo.
> Approved at the Stage 1 gate, 2026-11-02 — see [Open questions](#open-questions).

## Goal

Everything on `main` reaches `team/25.104.x`, and the Java 25 branch still builds, tests and behaves as
a Java 25 branch.

## Functional requirements

| ID | Requirement |
|---|---|
| FR1 | Every commit on `main` (head `c57ac88`) is contained in `team/25.104.x` after the merge. |
| FR2 | The fixes main brings (DD-43208, DD-43226, the null-pointer fix, the "case already exists" fix and failed-remap throw) behave on Java 25 as they do on main. |
| FR3 | Main's new and changed tests come across and run: unit tests in `stagingdlrm-azure-functions` and `stagingdlrm-event-processor`, and the new `ReceiveCaseFileSubmissionIT` scenarios. |
| FR4 | `tools/reconciliation/` and main's pipeline docs come across unchanged. |

## Non-functional requirements — the branch stays Java 25

| ID | Requirement |
|---|---|
| NFR1 | Every module keeps version `25.104.30-DLRMJ25-SNAPSHOT`; no `17.104.x` project version remains. |
| NFR2 | `service-parent-pom` and `coredomain.version` stay at 25.104.5, `system.id-mapper.version` at 25.104.0. |
| NFR3 | Interface pins `progression.version` (17.0.297) and `pcfdlrm.version` (17.103.24) stay at team's values — the upgrade-mechanics ADR forbids bumps the enforcer does not require. |
| NFR4 | No `javax.*` Jakarta-EE import comes in from main; Java code uses `jakarta.*`. |
| NFR5 | The DD-43892 netty fix stays: Function App `netty-handler`/`netty-common` at 4.2.17.Final. |
| NFR6 | Integration tests are not weakened or skipped to go green. |

## Acceptance criteria

1. **AC1** `git merge-base --is-ancestor c57ac88 <merge commit>` succeeds.
2. **AC2** NFR1–NFR3 and NFR5 hold, checked with `grep` across all `pom.xml` files.
3. **AC3** No `javax.(ws|inject|json|persistence|ejb|enterprise|annotation|transaction|jms|servlet|xml.bind|validation)` import in any `.java` file.
4. **AC4** `mvn clean install` succeeds on Java 25, with zero unit test failures.
5. **AC5** `mvn clean && ./runIntegrationTests.sh` passes on Java 25 with zero failures, and the run
   includes main's 3 new integration tests (22 `@Test` in total, up from 19).
6. **AC6** The change reaches `team/25.104.x` by PR from `dev/merge-main-into-25`, as a single merge
   commit with a one-line message.

## Decisions

| ID | Decision | Who / when |
|---|---|---|
| D1 | `pcfdlrm.version` stays 17.103.24. Main's 17.104.21 was checked: the `pcfdlrm-command-api` RAML in both versions is byte-identical, so the bump changes nothing and is an opportunistic bump under the ADR. | Gopal Saha, 2026-11-02 |
| D2 | `CLAUDE.md` takes team's version unchanged. | Gopal Saha, 2026-11-02 |

## Open questions

All answered at the Stage 1 gate (Gopal Saha, 2026-11-02).

| ID | Question | Answer |
|---|---|---|
| Q1 | DD-43906 and DD-43904 have no description or acceptance criteria. Are AC1–AC6 above the right "done"? | Yes — AC1–AC6 stand. |
| Q2 | Does DD-43904 also cover `cpp-context-prosecution-casefile-dlrm`? | No — stagingdlrm only. |
| Q3 | With D2, `CLAUDE.md` still says this branch does **not** carry `tools/`, which stops being true once `tools/reconciliation/` arrives. Correct it in a follow-up, or leave it? | Leave it. No change to `CLAUDE.md`. |

## Out of scope

- Any change to code beyond resolving the merge (no refactors, no version bumps).
- Merging `team/25.104.x` back into `main`.
- `team/libra1` LIBRA-era work.
