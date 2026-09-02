# J25 Behavioural-Change Investigation Report

> **Provenance note.** This report is a copy taken from Confluence for the DD-43191 Java 25 epic.
> Its per-BC evidence files (`investigations/BC-*.md`), `j25-behavioural-change-candidates.md` and
> `j25-behavioural-investigation-orchestration-plan.md` were **not** carried across and those links do
> not resolve here — the summary table and each BC's own section below are the local evidence. Chase
> the originals in Confluence if a BC needs deeper backing. Per
> [the parity-method ADR](../../pipeline/adrs/DD-43191-j25-parity-method.md) decision 4, an executed
> J17 run outranks anything this report claims.

**Date:** 2026-07-03 · **Scope:** the Java 17 → Java 25 upgrade of the CPP Microservice Framework stack — JDK 17→25, WildFly 26.1→40, Jakarta EE 8→10/11, OpenEJB 8→10, and the ~30 transitive library bumps the J25 BOMs pull in.

This report synthesises a two-run, 66-agent Workflow investigation of all 24 behavioural-change candidates catalogued for the J25 upgrade. **Run 1 (54 agents)** ran recon (6 cluster agents fleet-sweeping `~/idea/cpp-context-*` for each multi-member cluster), then one *investigate* agent per catalogue entry (BC-01..BC-24) writing a findings file from a fixed template, then an independent *adversarial verify* agent per entry that re-derived every load-bearing claim from primary sources and was free to move the verdict or confidence. **Run 2 (12 agents)** synthesised the verified findings into nine per-cluster sections, updated the master catalogue, and assembled this document. The source catalogue of hypotheses — the 24 ranked entries, the full version-delta table, and the original discovery synthesis — is [`j25-behavioural-change-candidates.md`](j25-behavioural-change-candidates.md); the method is recorded in [`j25-behavioural-investigation-orchestration-plan.md`](j25-behavioural-investigation-orchestration-plan.md). Per-entry evidence, reproduction commands, and the adversarial verification notes live in `investigations/BC-01.md` .. `investigations/BC-24.md`. Verification depth this round was static analysis (git `diff`/`grep` across the eight local J17↔J25 context pairs and the ~40-repo fleet) plus targeted standalone JUnit / `java -cp` repros — no live WildFly 40 deployment (see the caveats below).

## Executive summary

Of the 24 hypotheses, **17 were CONFIRMED** as genuine J17→J25 behavioural changes (though not all need a code fix — two are release-management cherry-picks and several are already remediated, leaving only a coverage gap): BC-01, BC-04, BC-05, BC-06, BC-07, BC-08, BC-09, BC-10, BC-11, BC-12, BC-13, BC-15, BC-16, BC-17, BC-18, BC-19, BC-20. **3 were REFUTED** as non-issues for the migrated runtime: BC-03, BC-14, BC-23 — and, strikingly, two of those (BC-03 Drools, BC-14 CDI discovery-mode) were the catalogue's *only two* `critical`-rated entries. **2 came out MIXED / partially confirmed**: BC-02 (its no-match-500 half refuted as pre-existing, its multi-match `NonUniqueResultException` half left genuinely open) and BC-21 (its `getClassNames()` half confirmed, its scanner-discovery-count half downgraded and unproven). **2 remained INCONCLUSIVE** for lack of a live run: BC-22 (Tika) and BC-24 (pgjdbc).

The headline result is that **the two highest-nominal-impact hypotheses were inert.** BC-03 — Drools jumping three majors and silently flipping every context's access-control allow/deny, ranked `critical` and top of the list — is refuted: the platform BOM keeps the classic AST engine (`drools-mvel`) on the classpath and adds no executable-model artifact, so DRL compiles exactly as it did under Drools 7 (a 32-of-32 allow/deny reproduction on Drools 10, executed on JDK 25, was identical to the Drools 7 baseline). BC-14 — CDI 4's discovery-mode default silently emptying the access-control/audit interceptor chain, the other `critical` — is also refuted: every `beans.xml` on every J25 line carries an explicit `bean-discovery-mode="all"` (Weld 6 honours it even on the legacy namespace), and users-groups' access-control ITs pass green on the real WildFly 40 stack. Underneath all 24 entries the same reassurance holds: the framework's own request-processing code — REST/enveloper, JSON handling, hashing, multipart extraction — is byte-identical J17↔J25 wherever it was checked. That is exactly why none of this fails at compile time.

**The real risk concentrated in two places, matching the original discovery thesis.** First, the **DeltaSpike-removal migration pattern** in the persistence layer: BC-01 (a null-for-throw contract flip already producing a live HTTP 500/NPE in `cpp-context-hearing` and a 404 in `users-groups`), BC-04 (Hibernate 6 primitive-null 500s on legacy rows, with confirmed *still-unfixed* instances in `prosecution-casefile` and `hearing` — both nominally "validated green" reference migrations), and BC-06 (transaction-scope narrowing → `LazyInitializationException`, a documented real incident). Second, **stale-base forks** (BC-15, BC-19): real, already-shipped features simply *absent* from J25 lines that forked months ago and never merged forward — a silent schema-field loss and a silent access grant→deny flip — both invisible to CI because the covering tests were never forward-ported, both already latent in the program's own reference contexts, and both fixable only by a git cherry-pick, not a code change. Activiti (BC-09/BC-10) is real but narrowly scoped (4 contexts); the JSON/REST/messaging library-swap entries (BC-08, BC-11, BC-12, BC-13, BC-16, BC-17, BC-18) are confirmed but mostly narrow, already-partly-fixed, or operational-noise / test-fidelity rather than live functional breaks. The long tail (BC-22/BC-23/BC-24) carries little or no real exposure.

**A recurring, reassuring sub-pattern:** several confirmed entries were already caught and fixed during the pilot migration itself, leaving only a coverage or hardening gap — BC-05 (all four JPQL `!= null` sites already fixed in users-groups; residual is two untested methods), BC-07 (already fixed in the 15 framework repos; residual is a per-context sweep), and most of BC-08 (test assertions already updated across five repos; residual is one dormant `cpp-context-listing` map-key bug). The danger in these is not a live bug today but a re-introduction path — which is why several recommendations below are CI gates rather than code fixes.

### Re-run 2026-07-08 — libraries updated with fixes

The upstream libraries were re-pulled on **2026-07-08** after fixes landed. Seven of the 15 `framework/j25` repos advanced since the 2026-07-03 investigation; the other eight were unchanged (`git pull --ff-only` = "Already up to date"). The four entries the delta could plausibly touch were **deeply re-investigated (each independently verified)**; the other 20 got **evidence-drift checks** against their existing citations. All 24 per-entry files under `investigations/` now carry dated sections.

**Delta pulled (old SHA → new HEAD):**

| Repo | old → new | What changed |
|------|-----------|--------------|
| cp-microservice-framework | `304059a35` → `daac3292f` | ea3282623: `persistence-deltaspike` removed outright; its 4 non-DeltaSpike classes (`EntityManagerFlushInterceptor` + provider + exception, `EntityManagerProducer`) relocated verbatim into a NEW built `persistence/persistence-jpa` module (interceptor now `@PersistenceContext`; package unchanged), registered in reactor + framework-bom. 25.104.0-M3 release + M4-SNAPSHOT bumps. |
| cp-event-store | `622e0bc69` → `68a978e66` | pom version bumps only (M3/M4; framework.version → 25.104.0-M3) + CHANGELOG. No code. |
| cpp-platform-libraries | `580c55386` → `f8b4a24d9` | 5f6e0925 (CIMD-3294): re-authored restore of agent-prosecutor authority access in `access-control-sjp-providers`. 3a5ffbb5: wires `persistence-jpa` into the `event-listener` service-component. M6 → M7 / M8-SNAPSHOT. |
| cpp-platform-core-domain | `be1320a58` → `8a06af822` | version bumps + changelog only. No schema changes. |
| cpp-platform-maven-service-parent-pom | `e7ada4ae6` → `6ecd1762a` | version bumps only. |
| cpp-context-users-groups | `f7ba6c7e9` → `5adf1eb06` | platform-chain M6 bump + version resets; pom.xml + CHANGELOG only, no code. |
| cpp-context-prosecution-casefile | `7066f129d` → `34522f7e8` | platform-chain M6 bump + 0fe8567 test-only assertions in `ValidationErrorIT` (Sonar S2699). |

**Four deep re-checks (independently verified):**

- **BC-17** (messaging) — **partially remediated.** The dominant flush-attribution mechanism is fixed framework-side: `cp-microservice-framework` ea3282623 relocates the four classes verbatim into the new built `persistence-jpa` module (logic byte-identical bar `@Inject`→`@PersistenceContext`), and `cpp-platform-libraries` 3a5ffbb5 (M7) wires `persistence-jpa` into the `uk.gov.moj.cpp.common:event-listener` component — so an `EVENT_LISTENER` DB failure again surfaces at the mid-chain flush rather than `TransactionHandler.commit()`, confirmed on the two fully-upgraded reference contexts (users-groups, prosecution-casefile). NOT fully closed: the dedup hash is still not byte-identical to J17 (residual `javax`→`jakarta` `PersistenceException` rename + a verified +1 line-number shift), the secondary ByteBuddy/Javassist proxy-name mechanism and the unguarded `get(0)` defect are untouched (`cp-event-store` had no code changes), and — verified this re-run — only **2 of 6 locally-cloned `EVENT_LISTENER` contexts carry the fix**: cake-shop (whose `StreamErrorHandlingIT` the framework commit cites as proof, still has no `persistence-jpa` dep and still asserts the regressed `TransactionHandler` identity) plus businessprocesses, hearing, material and system-doc-generator remain pinned to pre-fix platform-libs.
- **BC-19** (stale-base) — **partially remediated.** The **consumer** side is restored in `cpp-platform-libraries` 25.104.0-M7 (5f6e0925 / CIMD-3294 — a re-author, not a cherry-pick of `76a7ecb`), but the **producer** side is unchanged: `cpp-context-users-groups` `team/25.104.x` still lacks `6accd837`, so the grant→deny regression remains live end-to-end. Two new untested defects were found in the restore that make the "cherry-pick the producer" step unsafe as written: (1) it parses `agentProsecutorAuthorityAccess` as an array of JSON *objects*, but every known producer emits an array of plain *strings* — naively forward-porting `6accd837` would trade today's silent deny for an uncaught `ClassCastException`; (2) `of(String,List)` mutates the shared static `NONE`/`ALL` singletons (cross-request race under the `@ApplicationScoped` provider).
- **BC-15** (stale-base) — **still outstanding.** `cpp-platform-core-domain` advanced `be1320a58` → `8a06af822` (now 25.104.0-M7-SNAPSHOT) with version bumps + changelog only; zero schema files changed. All 6 missing fields (and 2 schemas) remain absent at HEAD; the 4 cherry-pick commits remain valid, cleanly applicable targets.
- **BC-02** (persistence) — **unchanged.** The delta touches none of BC-02's cited files; a grep for `getSingleResult`/`getResultList`/`NoResultException`/`NonUniqueResultException` across all seven changed repos returns zero hits. The open multi-match risk (`CaseDetailsRepository`/`FeaturePermissionRepository`) stands exactly as on 2026-07-03. (The `persistence-deltaspike`→`persistence-jpa` relocation restores `EntityManagerFlushInterceptor` for BC-17's write-path mechanism, not BC-02's read-path DeltaSpike-Data removal, which is in `cp-maven-common-bom` and untouched.)

**Drift checks (other 20 entries):** all verdicts unchanged; **zero escalations (`[]`)**. 14 flagged benign drift only — 3 because a cited `persistence-deltaspike`/`EntityManagerProducer` path is now stale (relocated to `persistence-jpa`): BC-01, BC-06, BC-08; and 11 cosmetic SNAPSHOT version-string drifts in quoted build logs/footnotes: BC-04, BC-05, BC-07, BC-09, BC-10, BC-12, BC-14, BC-16, BC-18, BC-20, BC-21 — none touching a cited mechanism. The remaining 6 showed no drift at all: BC-03, BC-11, BC-13, BC-22, BC-23, BC-24.

**Verdict distribution: unchanged.** No verdict flipped — still **17 Confirmed / 3 Refuted / 2 Mixed / 2 Inconclusive**. Remediation status is a separate dimension tracked here: BC-17 and BC-19 move from open to *partially remediated*, BC-15 and BC-02 stay open; none of this alters the verdict counts, the matrix below, or any other total in this report.

### Cross-cutting caveats

Two things apply across the whole investigation and must be read alongside every individual verdict.

#### (a) A standing environment gap — several "decisive checks" are written but not yet executed

Multiple investigations independently hit the same two environment walls. Neither is a weakness in the investigations themselves; each is a gap for a human with real infrastructure access to close, and in every affected case the verdict rests on convergent static + reduced-repro evidence and the exact command is recorded and ready to run.

- **Wall 1 — the internal Artifactory (`libraries.mdv.cpp.nonlive`) is unreachable from this sandbox**, so Maven cannot resolve the `25.104.x`-line SNAPSHOT sibling modules or the pinned plugins (`jacoco`, `junit-platform-launcher`) that an in-place `mvn test` needs. The reproduction test therefore compiles by inspection but was never run to green/red. This affects, in whole or in part: **BC-01, BC-02, BC-04, BC-05, BC-06** (the entire persistence cluster hit it uniformly), **BC-11** (the native `generators-commons` coexistence test), **BC-15** and **BC-19** (both *worked around* it — a standalone compile against the resolved classpath, and a pure-function hand-trace, respectively), and **BC-18, BC-21, BC-22, BC-23**. Importantly, where an investigator could reduce the check to an offline `java -cp` / plain-`main` artifact, they *did* get a real execution — BC-03 (32/32), BC-08, BC-09, BC-10, BC-11 (the provider-collision crash itself), BC-12, BC-13, BC-16 (6/6), BC-17, BC-20 all produced executed, reproduced output — so this wall specifically blocked the *module-in-place test* tier, not the whole exercise.
- **Wall 2 — no local WildFly 40 Docker image exists** (the shared `cpp-developers-docker` infra is still on the 26.1.3 image) and no live J25 Postgres 15 stack is available, so the *live end-to-end* tier is untestable here. This affects the decisive live check for **BC-09, BC-10, BC-12** (including the flagged possible deploy-breaker), **BC-13, BC-14, BC-16, BC-17, BC-24**, plus the top-of-stack live checks for BC-01/BC-04/BC-06 and the reference-data/sjp access-control suites for BC-03.

Closing these needs a human with (i) network/Artifactory access and (ii) a local WF40 image; that is the single biggest lever that would raise the medium-confidence entries — notably **BC-09, BC-12, and BC-14** — to high. The affected entries are enumerated again, grouped by action, in "Recommended next actions" §4.

#### (b) Treat fleet-wide counts as directional, not precision-audited

BC-19's own adversarial verification found that two of its fleet-wide figures — the SJP "9 production / 9 test file" blast-radius count and a characterisation of `cpp-platform-libraries`' local `main` branch history — had been carried over verbatim from the recon inventory rather than freshly re-derived in that session, *despite the write-up's blanket claim that everything was independently re-verified.* BC-19's core finding is unaffected (the grant→deny flip, both fork points, and the full causal chain all independently re-derived). But this is a general calibration caution for the whole report: **read every fleet-wide count as directionally reliable and individually spot-checked in most cases, but not precision-audited in every instance.** The point is reinforced by the count corrections verification *did* catch elsewhere — BC-11's "~19 contexts" was actually 43 (>2×), BC-18's "208 files / 22 contexts" became 364 / 29, BC-20's "33 contexts" became 34 (with a mid-tier of 10 contexts the original arithmetic had dropped), BC-08's "8 sites" was 10, BC-04's "hearing: clean" was wrong (a `boolean` instance was missed), and BC-01's "material: 4 files" was 5. In every case the correction left the verdict intact and the order of magnitude right — which is the pattern to expect: quote these numbers as scale signals, and re-run the underlying grep before quoting any single one as precise.

## Verification summary matrix

Verdict/confidence reflect the state *after* the independent adversarial verification pass (which raised BC-18, split BC-02, and downgraded BC-07/BC-21/BC-23 from their first-pass ratings). "Impact" is the catalogue's original impact rating, retained for prioritisation.

| ID | Cluster | Verdict | Confidence | Impact | Detail |
|----|---------|---------|-----------|--------|--------|
| BC-01 | Persistence | **Confirmed** | High | High | [BC-01.md](investigations/BC-01.md) |
| BC-02 | Persistence | **Mixed** — no-match refuted, multi-match open | High (no-match) / Low–Med (multi-match) | High | [BC-02.md](investigations/BC-02.md) · 2026-07-08 re-run: unchanged (delta misses mechanism) |
| BC-03 | Access control & CDI | **Refuted** (verified) | Medium (upper end) | Critical* | [BC-03.md](investigations/BC-03.md) |
| BC-04 | Persistence | **Confirmed** | High | High | [BC-04.md](investigations/BC-04.md) |
| BC-05 | Persistence | **Confirmed** | High (mechanism/fix) / Med (surface) | High | [BC-05.md](investigations/BC-05.md) |
| BC-06 | Persistence | **Confirmed** | High | High | [BC-06.md](investigations/BC-06.md) |
| BC-07 | — (Liquibase, deploy-time)† | **Confirmed** | Medium (↓ from High) | High | [BC-07.md](investigations/BC-07.md) |
| BC-08 | JSON & validation | **Confirmed** (partial scope) | High | Medium | [BC-08.md](investigations/BC-08.md) |
| BC-09 | Activiti / BPMN | **Confirmed** | Medium | High | [BC-09.md](investigations/BC-09.md) |
| BC-10 | Activiti / BPMN | **Confirmed** | High | High (indep. Minor read) | [BC-10.md](investigations/BC-10.md) |
| BC-11 | JSON & validation | **Confirmed** | High (core) / Med (blast count) | Medium | [BC-11.md](investigations/BC-11.md) |
| BC-12 | REST engine | **Confirmed** | Medium | High | [BC-12.md](investigations/BC-12.md) |
| BC-13 | JSON & validation | **Confirmed** | High | High | [BC-13.md](investigations/BC-13.md) |
| BC-14 | Access control & CDI | **Refuted** (acute); latent hazard | Medium | Critical* | [BC-14.md](investigations/BC-14.md) |
| BC-15 | Stale-base | **Confirmed** — stale-base remediation | High | High | [BC-15.md](investigations/BC-15.md) · 2026-07-08 re-run: still outstanding (core-domain 8a06af822, 6 fields absent) |
| BC-16 | Messaging / observability | **Confirmed** | High | Medium | [BC-16.md](investigations/BC-16.md) |
| BC-17 | Messaging / observability | **Confirmed** | High (dominant) / Med (secondary) | Medium | [BC-17.md](investigations/BC-17.md) · 2026-07-08 re-run: partially remediated (persistence-jpa flush restored, M3+M7; cake-shop+4 contexts pre-fix) |
| BC-18 | Messaging / observability | **Confirmed** | High (↑ from Medium) | Medium | [BC-18.md](investigations/BC-18.md) |
| BC-19 | Access control & CDI / Stale-base | **Confirmed** — stale-base remediation | High | High | [BC-19.md](investigations/BC-19.md) · 2026-07-08 re-run: partially remediated (consumer restored M7 5f6e0925; producer 6accd837 still missing) |
| BC-20 | Access control & CDI | **Confirmed** | High | Medium | [BC-20.md](investigations/BC-20.md) |
| BC-21 | Codegen | **Partially confirmed** | Medium (↓ from High) | Medium | [BC-21.md](investigations/BC-21.md) |
| BC-22 | Long tail | **Inconclusive** | Medium | Low | [BC-22.md](investigations/BC-22.md) |
| BC-23 | Long tail | **Refuted** | Medium (↓ from High) | Low | [BC-23.md](investigations/BC-23.md) |
| BC-24 | Persistence (loose fit) | **Inconclusive** | Medium | Low | [BC-24.md](investigations/BC-24.md) |

\* BC-03 and BC-14 were the catalogue's only two `critical`-rated hypotheses; both refuted. The "Impact" column shows the *hypothesised* worst case, not the observed one.
† BC-07 (Liquibase 4→5 rejecting removed properties, a K8s pre-install migration-job failure) is the one catalogue entry that falls outside all nine narrative clusters below. It is **confirmed and already fixed in the 15 framework repos**; the residual is a per-context sweep of copied `liquibase.properties`. Full detail is in [BC-07.md](investigations/BC-07.md); it is folded into the "Recommended next actions" punch list.

## Findings by cluster

The nine sections below are the verified, per-cluster write-ups, in priority order. Each is reproduced essentially verbatim from its Run-2 section file; heading depth has been adjusted so the clusters nest under this section, and transitions lightly smoothed. Every quantitative claim carries the caveats above.

### Persistence / DeltaSpike + Hibernate 6

This cluster covers the persistence-layer fallout of two migrations the J25 upgrade forces together: the fleet-wide removal of DeltaSpike (every `@Query`-annotated repository finder hand-rewritten to plain JPA) and the Hibernate 5.4→6.6 major-version jump that rides along with it (WildFly 40 ships Hibernate 6.6.25). BC-01 and BC-02 are the two directions of the same DeltaSpike-removal defect — a finder that used to return `null` gets rewritten to throw, or vice versa. BC-04, BC-05 and BC-06 are three independent Hibernate 6 semantic changes (primitive-null strictness, JPQL `!= null` handling, and session/lazy-loading scope) that fire with zero application-code change and no compile-time signal. BC-24 (pgjdbc 42.3.2→42.7.7) is a looser fit — a JDBC driver bump, not a DeltaSpike/Hibernate change — grouped here as the remaining persistence-stack-change candidate; it is also the only entry that never reached a firm verdict, for lack of a live Postgres/WildFly-40 environment. A ceiling shared by every entry that wrote a unit-level reproduction test (BC-01, BC-02, BC-04, BC-05, BC-06): this sandbox's Maven cannot resolve several `25.104.x`-line SNAPSHOT artifacts because the fallback corporate Artifactory host doesn't resolve here — every such test compiles by inspection and was independently re-run to the identical dependency-resolution failure, but none could be executed to green/red.

**Re-run note (2026-07-08).** Two path-affecting updates for readers of this cluster. (1) **BC-02 is unchanged** by the 2026-07-08 library delta — a grep for `getSingleResult`/`getResultList`/`NoResultException`/`NonUniqueResultException` across all seven changed repos returns zero hits, so the open multi-match risk stands exactly as first written. (2) As of `cp-microservice-framework` 25.104.0-M3 (commit ea3282623), the `persistence-deltaspike` module has been **removed outright** and its 4 non-DeltaSpike classes (`EntityManagerFlushInterceptor` + provider + exception, `EntityManagerProducer`) relocated into a new built `persistence/persistence-jpa` module (java package unchanged, `uk.gov.justice.services.persistence`). Framework-side `persistence-deltaspike/...` paths cited in BC-01, BC-06 and BC-08 below now resolve under `persistence-jpa/...`; the DeltaSpike Data-module removal underlying BC-01/BC-02 (in `cp-maven-common-bom`) is untouched, and the relocation restores `EntityManagerFlushInterceptor` for BC-17's mechanism, not this cluster's.

#### BC-01 — DeltaSpike→JPA no-result null propagates to HTTP 404
**Verdict: Confirmed | Confidence: High**

DeltaSpike's no-result behaviour under a single-result `@Query` finder was never one contract: a bare `@Query` (or one with `max = 1` — a third shape this session newly identified) throws `NoResultException`, while `singleResult = OPTIONAL` swallows it and returns `null`. The standard migration idiom, `.getResultList().stream().findFirst().orElse(null)`, faithfully ports only the OPTIONAL case; applied to a throwing declaration it silently turns "no match" from an exception into `null`, orphaning any `catch (NoResultException)` at the call site. This is confirmed twice over: the pre-existing users-groups case (`OrganisationRepository` → dead catch → `null` payload → HTTP 404, was 200 `{}`) and a new instance found this session in `cpp-context-hearing`, where the identical shape produces a worse outcome — an uncaught `NullPointerException` (HTTP 500) rather than a clean 404, because the `null` travels one call further before a field is dereferenced. The migration's own test suite proves the flip directly: the same test method asserts `NoResultException` on J17 and asserts `null` on J25.

**Affected surface:** fleet-wide, 40 of 55 `cpp-context-*` repos use the DeltaSpike data API in main code; narrowing to the specific ingredient a regression needs — a `catch(NoResultException)` at a call site that assumed a throw — gives 11 directories across 8 families. Of those, only 3 have local J25 checkouts to diff, and **2 of 3 (67%) show a confirmed regression** (users-groups, hearing); prosecution-casefile's one catch site is the separately-tracked BC-02 shape instead. The remaining 5 families (`defence`, `listing`, `progression`, `reference-data`, `sjp`) carry the same risk ingredient but have no local branch to check yet.

**Evidence:**
- `cpp-context-hearing/hearing-viewstore/.../repository/DefendantRepository.java` — `main:19-20` (`@Query(..., max = 1)`, throws) vs. `team/25.104.x:20-29` (`.orElse(null)`)
- `cpp-context-hearing/.../repository/DefendantRepositoryTest.java` — the identical method asserts `@Test(expected = NoResultException.class)` on `main:92-95` and `assertThat(..., is(nullValue()))` on `team/25.104.x:88-91`
- `cpp-context-hearing/hearing-query/hearing-query-view/.../HearingQueryView.java` — dead `catch (NoResultException)` at `team/25.104.x:366-369`, unconditional dereference (the real NPE site) inside `convertToOutstandingFinesQuery` at `:379`; `HearingQueryApi.java:354` (`@Handles("hearing.defendant.outstanding-fines")`) and `hearing-query-api.raml:464` confirm this is a live, routed endpoint, not dead code

**How to verify (decisive check):** the diff/test-assertion evidence above was fully executed and is the strongest evidence obtained. A repro test asserting the NPE directly (`HearingQueryTest#should_throw_npe_...`) was written but could not be run: `mvn -pl hearing-query/hearing-query-view -Dtest=...` fails at dependency resolution before any test executes (the Artifactory host is unresolvable from this sandbox), independently reproduced against three unrelated modules in two other repos — confirming a standing environment gap, not anything specific to this test.

**How to avoid/mitigate:** replace the dead `catch (NoResultException)` in `HearingQueryView` with an explicit `if (defendantSearch == null) return jsonEnvelopeWithoutPayload;`, and fix the masking test (`should_send_an_empty_payload_when_defendant_does_not_exists`) to stub `thenReturn(null)` rather than `thenThrow(NoResultException.class)` — it currently passes only because it mocks away the exact thing that changed. Fleet-wide, script the check: find every migrated finder ending in `.orElse(null)`, diff its pre-migration `@Query` for the absence of both `OPTIONAL` and `max=N`, and flag any caller still holding a `catch(NoResultException)`.

**Verification caveats:** the core finding was independently re-derived from source at every cited line and survives unchanged. Four secondary citation slips were found and should be corrected: one wrong line pointer (a different `HearingEventRepository` finder is actually at `main:139-140`, not `:39-40` — the underlying OPTIONAL/`orElse(null)` claim still holds); `EmptyQueryResponseStatusTest.java` was described as a "pre-existing framework test" but is actually an untracked, uncommitted file, not real CI coverage; a prosecution-casefile "catch site" reference was attributed to the wrong file (it's `ProsecutionCasefileQueryView.java:88`, not `CaseDetailsService.java`); and `material` has 5 DeltaSpike-repository files, not 4. None of these touch the hearing or users-groups findings.

#### BC-02 — DeltaSpike→JPA inverse: `getSingleResult()` throws → HTTP 500
**Verdict: Mixed — no-match refuted, multi-match open | Confidence: High (no-match) / Low–medium (multi-match)**

*The source file's original one-word "refuted" tag under-states this: it mis-summarised its own split conclusion, and a reader of just that line would miss that a real risk stays open.*

This is the mirror image of BC-01: a finder that used to return `null`/an arbitrary row under DeltaSpike gets migrated to a bare `getSingleResult()`, which throws on both no-match and multi-match. Tracing all three catalogue-cited examples one hop further than the original recon — to their actual `@Handles` entry point — refutes the **no-match** half entirely: in all three (prosecution-casefile `CaseDetailsRepository`, hearing `HearingRepository.findByHearingIdAndJurisdictionType`, users-groups `FeaturePermissionRepository.fetchPermissionIdByFeatureId`), the old DeltaSpike `@Query` had no `OPTIONAL`/`max` flag and **already threw under J17** — two are real, unguarded 500s but pre-existing on both J17 and J25, and the third is fully protected by an unchanged outer `catch (NoResultException)`. The **multi-match** half is a genuinely different, still-open question: DeltaSpike silently returns an arbitrary first row on multi-match regardless of any flag, so "already threw on no-match" says nothing about it. Two predicates are structurally capable of matching more than one row today: `CaseDetailsRepository.findCaseDetailsByProsecutionCaseReference` (no DB unique constraint on the reference column) and, more strongly, `FeaturePermissionRepository.fetchPermissionIdByFeatureId` (the unique constraint covers only the `feature_id`+`permission_id` pair — a sibling method in the same file proves multi-permission-per-feature is an intended data shape). Neither is confirmed to have duplicate rows today; both would throw an uncaught `NonUniqueResultException` on J25 where J17 silently masked the problem.

**Affected surface:** 3/3 catalogue-cited no-match examples refuted (not new); 2 finders carry an open, schema-backed multi-match risk. Fleet-wide, ~66 finders across 8 undiffed context families still carry the old `OPTIONAL` flag and will face this same fork the moment they're migrated — `mi-reportdata`/`mi-systemdata` (28 files, 41 methods) is by far the largest concentration.

**Evidence:**
- `cpp-context-prosecution-casefile/.../CaseDetailsRepository.java:17-18` (main, throws) vs. `ProsecutionCasefileQueryView.java:82-94` — a correctly-typed, unchanged `catch (NoResultException)` at line 88 proving this path was never actually live
- `cpp-context-hearing/.../HearingRepository.java` `main:72-75` → `team/25.104.x:144-153` (bare `getSingleResult()`, no catch), with `Hearing.java:36-38` confirming `id` is `@Id` (multi-match structurally impossible here)
- `cpp-context-users-groups/.../FeaturePermissionRepository.java` `main:26-27` → `team/25.104.x:50-56`, plus `FeaturePermission.java`'s `@UniqueConstraint(columnNames={"feature_id","permission_id"})` and the sibling `findPermissionsByFeatureKey` returning `List<Permission>` — direct evidence multi-match is a real, intended shape

**How to verify (decisive check):** the pre-existing, already-committed `CaseDetailsRepositoryTest.shouldThrowException_whenGivenProsecutionCaseReference_notExist` is real H2/Hibernate-backed proof that the throw is genuine and pre-existing — the strongest evidence obtained, and it required no new test-writing. A new test pinning the dead-catch claim (`CaseDetailsServiceTest#shouldPropagateNoResultException...`) was written but hit the same Maven/Artifactory dependency-resolution wall as BC-01 and could not be executed.

**How to avoid/mitigate:** fix the two genuinely-live-but-pre-existing 500s by adding an explicit `catch (NoResultException)` around the `HearingService`/`PermissionService` calls, matching the `if (nonNull(...))` guard the code already implies was intended. For multi-match, decide the product contract first, then either enforce a real DB unique constraint (`CaseDetailsRepository`) or change the return shape to `List`/add an explicit `catch (NonUniqueResultException)` (`FeaturePermissionRepository`). Fix the shared migration playbook itself: its worked "Pattern 1" example migrates a name-derived finder straight to `getSingleResult()`, with the OPTIONAL-flag caveat buried several sections later — move it inline.

**Verification caveats:** stated plainly, this is the one entry in the cluster whose top-line verdict needed correcting — from an unqualified "refuted" to the explicit split above — because the bundled hypothesis genuinely has two different outcomes. Separately, the headline evidence for the 3-shape DeltaSpike table (cited to "two migration playbooks") does not hold up: neither playbook actually states the OPTIONAL/no-OPTIONAL distinction. Better, uncited evidence was found to support the same underlying claim — a real pre-migration DeltaSpike-backed unit test proving the exact throwing behaviour — so the conclusion survives, but the citation needed replacing. One "import-rename-only" diff claim was also found to be an overstatement for the whole `HearingService.java` file (three unrelated hunks exist, tied to a different BC-15 finding); the specific method chain this finding actually depends on remains confirmed unchanged.

#### BC-04 — Hibernate 6 primitive-null `PropertyAccessException` on legacy rows
**Verdict: Confirmed | Confidence: High**

Hibernate 6 throws `PropertyAccessException`/`IllegalArgumentException` when a JDBC `NULL` lands on a primitive-typed entity field (`long`, `int`, `boolean`, …); Hibernate 5 silently coerced it to `0`/`false`. This is a pure stack-change — the same GET against the same physical row returns 200 on J17 and 500 on J25 — and it is legacy-data-only, which is exactly why a green IT suite never catches it: every row an IT creates goes through the application layer and is never actually `NULL`; only genuinely historical rows (pre-dating a column, or written by a bypass path) are exposed. It is a confirmed, already-hit production defect: `cpp-context-users-groups`'s own migration notes document the real stack trace and two *different* applied fixes for two structurally different field roles — a plain `@Column` field was widened to a boxed type with a null-safe getter, while the `@Version` field was deliberately **left primitive** (widening it would trip a separate Hibernate 6 `IdentifierGenerationException` via `ForeignGenerator` on `@MapsId` children) and instead protected by a new Liquibase backfill-and-default changeset. The DDL shape is the real predictor: a column added via bare `addColumn`/`createTable` with neither `defaultValue` nor `nullable=false` is exposed; one with both is safe regardless of Hibernate version — a distinction invisible from the entity class alone.

**Affected surface:** users-groups — confirmed hit, confirmed fixed (both field shapes). `cpp-context-prosecution-casefile`'s `OffenceLegacy.orderIndex` is a **confirmed, still-unfixed** instance of the same shape, in one of only two contexts this workspace calls "fully upgraded and validated green" — exposed for the identical reason a green IT suite can't see it. `mi-reportdata`/`mi-systemdata`'s 13 `@Version` hits are, on inspection of the actual Liquibase DDL, **DDL-protected and not actually at risk**, refining the recon's flat "at-risk" count. `cpp-context-hearing` was reported clean in the original write-up, but verification found this **wrong**: `HearingEvent.alterable` (a `boolean`, added via the identical bare-`addColumn` shape) is a real, unflagged instance.

**Evidence:**
- `cpp-context-users-groups/CLAUDE.md` §8 — the literal stack trace and the `ForeignGenerator`/`@MapsId` rationale for leaving `@Version` primitive
- `.../liquibase/usersgroups-view-store-db-changesets/038-fix-null-persistence-version.xml` — the applied backfill-and-default fix, present only on `team/25.104.x`, confirmed wired into the real changelog
- `cpp-context-prosecution-casefile/.../entity/OffenceLegacy.java:74-75` + `.../001-create-offence-table.changelog.xml:23` — the unfixed instance (`private int orderIndex`, bare `<column type="INT"/>`, no default/not-null)

**How to verify (decisive check):** a repro test (`GroupJpaVersionNullPropertyAccessReproTest`) was written against the module's existing H2/Hibernate-6 harness — set a real row's `persistence_version` to `NULL` via native SQL, then assert the repository throws. It could not be executed: the same dependency-resolution failure as BC-01/BC-02, reproduced identically online and offline. The verdict instead rests on the already-applied, already-documented production fix plus the independently-found second, unremediated instance in a different context.

**How to avoid/mitigate:** two non-interchangeable fixes depending on field role. Widen plain `@Column` primitives to their boxed type with a null-safe getter. For `@Version` fields, do **not** widen — add a Liquibase backfill-and-default changeset instead (template: `038-fix-null-persistence-version.xml`). Auditing a context for this hazard requires checking both the entity *and* whether a matching DDL changeset exists; the entity alone gives false positives for already-protected `@Version` columns. Add a standing per-context static sweep: flag every `@Entity` primitive field whose `addColumn`/`createTable` lacks both `defaultValue` and `nullable=false`.

**Verification caveats:** verdict unchanged, but plainly: the blast-radius table's "`cpp-context-hearing`: confirmed clean" claim is wrong — it only checked `long`/`int`, not `boolean`, despite the report's own definition of the bug explicitly naming `boolean` as affected. `HearingEvent.alterable` should be added as a third confirmed-shape instance. Two minor citation pointers were also wrong (a test-usage citation named the wrong file/line; a `Document.java` line number was off by several) — neither touches a load-bearing claim.

#### BC-05 — Hibernate 6 JPQL `!= null` → silent empty result sets
**Verdict: Confirmed | Confidence: High (mechanism, fix, fleet blast-radius) / Medium (full affected-surface & "went undetected" framing)**

Hibernate 5 rewrote the JPQL courtesy-syntax `field != null` to `IS NOT NULL`; Hibernate 6 dropped that rewrite, so the same string now compiles to ANSI three-valued logic where `x <> NULL` is always `UNKNOWN` — silently excluding every row from an `AND`ed `WHERE` clause, with no exception and nothing logged. This is the purest stack-change in the cluster: the JPQL string is unchanged data, only what the provider does with it differs. The pattern existed verbatim in four sibling repositories, all in `cpp-context-users-groups`, each wrapping a boolean filter in a redundant `x != null and x = <boolean>` guard; all four were fixed — the guard fully removed, not reworded — in a single, generically-worded "Upgrade to Java 21 and Jakarta EE 10.0" commit, with no sign anyone flagged it as a distinct Hibernate 6 hazard rather than incidental tidy-up while hand-retyping the query.

**Affected surface:** `FeatureRepository.findByActive` backs the live, RAML-documented `usersgroups.get-enabled-features` query; `RoleFeatureRepository`/`FeaturePermissionRepository` feed the RBAC-critical `usersgroups.get-user-services` handler that drives UI entitlement visibility. A fleet-wide re-sweep (both refs of all 8 paired repos, plus HEAD of all ~56 contexts) found the pattern in **exactly these 4 files, in exactly 1 context, nowhere else** — a narrow, already-remediated footprint. The residual risk is a coverage gap, not a live bug: 2 of the 4 fixed methods have no real-Hibernate-session test at all, only Mockito stubs that would pass identically whether the underlying JPQL is correct or permanently broken.

**Evidence:**
- `cpp-context-users-groups/.../FeatureRepository.java:20` (main) — `fp.permission.active != null and fp.permission.active = ?1`; fully removed on `team/25.104.x`
- The same shape at `RoleFeatureRepository.java:17`, `GroupFeatureRepository.java:18`, `FeaturePermissionRepository.java:19` — all four fixed in one commit (`d99a0bb3d`)
- `usersgroups-query-api/.../FeatureQueryApi.java:23-25` (`@Handles("usersgroups.get-enabled-features")`) and `UserQueryApi.java:78-80` (`get-user-services`) — the live consumer wiring

**How to verify (decisive check):** the diff/fleet-sweep evidence above was independently re-executed twice with different methodologies and is treated as sufficient for a high-confidence verdict without a live run, since there is no Hibernate 6 flag that restores the old rewrite — this is a source-fix-only problem. A dedicated test replaying the exact pre-migration JPQL against real entities (`Bc05JpqlNotEqualsNullSemanticsTest`) was written but, like the rest of this cluster, blocked by the same Maven/Artifactory failure, independently reproduced.

**How to avoid/mitigate:** add a CI grep gate for quoted `!=`/`<> null` co-occurring with a JPQL marker (`@Query`/`createQuery`/`@NamedQuery`) — cheap, fast, catches the pattern the moment it's typed in any future migration. Close the two real coverage gaps with actual H2/Hibernate-backed tests (not mocks) for `FeatureRepository.findByActive` and `FeaturePermissionRepository.findFeatureByPermissionIds`. Document the mechanism in the shared migration playbook, which currently has zero mention of it despite covering every sibling Hibernate 6 hazard.

**Verification caveats:** two claims were downgraded plainly. First, one of the four cited "live" consumer chains (`GroupFeatureRepository` → `UserFeatureService`) is actually **dead code on both J17 and J25** — its only caller anywhere in the codebase is its own test — so "traced end-to-end" overstated that one case (the other three hold up). Second, the "hardest-to-detect, nobody identified it" framing is **contradicted**: `cpp-context-users-groups`'s own migration notes already contain a complete, correctly-written section on exactly this hazard by name — it just never made it into the separate, central pilot playbook, which is the only place the original evidence section checked. The narrower claim (central doc lacks a section) stands; the broader "went unrecognised" claim does not — hence the confidence split above.

#### BC-06 — Hibernate 6 / WildFly 40 stricter sessions → `LazyInitializationException`
**Verdict: Confirmed | Confidence: High**

The catalogue's "Hibernate 6 tightened sessions" framing attributes this to the wrong layer. The actual cause is the DeltaSpike-removal migration itself: pre-migration, DeltaSpike's `EntityManagerProducer` was `@ApplicationScoped`/`@Produces @RequestScoped` — a **CDI** scope that kept one Hibernate session open for an entire HTTP request regardless of transaction boundaries, so a lazy collection loaded early in a request stayed walkable anywhere later in it. Post-migration, repositories instead use a container-managed `@PersistenceContext`, transaction-scoped by default — the session closes the moment the enclosing JTA transaction ends, so a `find()` with no surrounding `@Transactional` returns an entity whose lazy collections are already unreachable. Diffing `EntityManagerProducer.java` confirms this precisely: its scoping logic is byte-identical (import-renames only) on the J25 line — the producer that used to mask the problem wasn't changed, it was simply dropped from the CDI graph. Hibernate 6 itself is at most a secondary contributor: `LazyInitializationException` on a closed session is decades-old JPA semantics, not a new H6 behaviour, and this codebase never used the one H6-specific escape hatch (`enable_lazy_load_no_trans`) that might have mattered. This is confirmed as a real, already-encountered incident: `cpp-context-users-groups`'s migration notes document the literal stack trace and a prescribed `@Transactional` fix, independently verified as actually applied at all 5 named call sites. A second, structurally identical precondition was independently found in `cpp-context-defence` — no local J25 checkout to confirm its own fix, but named explicitly in the canonical cross-context playbook's worked example — evidence this is a recurring class tied to the migration pattern, not one context's idiosyncrasy.

**Affected surface:** the fleet-wide ceiling (84 LAZY associations across 20 contexts, a recon figure) clearly overstates exploitable surface once call sites are checked. Of the locally-diffable contexts: users-groups is confirmed hit-and-fixed (three LAZY layers stack behind one call chain); hearing was already defensively `@Transactional`-heavy before the migration (32 of 38 public service methods, unchanged by it), so this is not a new J25 regression there, though roughly 6 methods were not individually cleared; material and businessprocesses have no LAZY associations at all; prosecution-casefile has the structural precondition but no query-view caller currently exercises it.

**Evidence:**
- `cpp-context-users-groups/CLAUDE.md:879-902` — the real `LazyInitializationException` stack trace and the 5 methods it names
- `git diff main...team/25.104.x -- .../UserFeatureService.java` (and `UserService.java`, `RoleService.java`) — confirms `@Transactional` genuinely added at all 5 sites, absent on J17
- `.../persistence-deltaspike/.../EntityManagerProducer.java` diff — import-renames only, proving the masking producer's scope logic is untouched, just no longer wired in
- `cpp-context-defence/.../entity/DefendantAllocation.java:95-96` + `.../DefendantAllocationService.java:36,86`, named explicitly in `cpp-framework-java-upgrade-pilot/guides/CLAUDE-upgrade-playbook.md:777-816`

**How to verify (decisive check):** live WF40 reproduction is infeasible (no WildFly 40 Docker template yet). A unit-level repro (`LazyInitializationExceptionReproTest`) driving the H2 harness's session open/close directly was written and is well-constructed, but hit the same Maven dependency-resolution wall as the rest of this cluster — re-confirmed both online and offline, and cross-checked against an unrelated already-committed test in a different repo hitting the identical failure shape, ruling out a module-specific cause. The verdict instead rests on convergent static evidence: the documented-and-verified-applied real fix, the independent second-context structural match named in the canonical playbook, and the mechanism trace through the unchanged producer class.

**How to avoid/mitigate:** prefer `LEFT JOIN FETCH ... DISTINCT` at the loading query (avoids widening a read method's transaction scope and avoids N+1) over blanket `@Transactional`; reserve `@Transactional` for genuinely conditional access, as users-groups actually did. Run a two-step grep-and-verify recipe per context before its own DeltaSpike migration: enumerate every LAZY-resolving association (including the JPA-default case with no explicit `fetch=`, which a naive grep for "LAZY" misses), then check whether every public caller of its getter carries `@Transactional`. Do not reach for `hibernate.enable_lazy_load_no_trans` as a stack-wide pin — there is no config-level fix for this class of defect.

**Verification caveats:** verdict unchanged, but plainly: the "Net assessment" tally in the original write-up silently omits 3 of the 8 locally-paired contexts (`system-doc-generator`, `boxworkmanagement`, `work-management-proxy`) from a paragraph that reads as a complete sweep. Verification spot-checked all three and confirmed they are genuinely not-applicable (zero `@OneToMany`/`@ManyToMany` anywhere), so the omission doesn't hide a live risk, but it should have been stated rather than left out. A fleet-residual count elsewhere doesn't fully reconcile (~12 claimed vs. ~15 actual undiffed directories) — low-stakes. One further method, `HearingService.getHearingsForToday`, was hand-traced during verification and remains genuinely unresolved either way.

#### BC-24 — pgjdbc 42.3.2 → 42.7.7 SQLState / temporal edge drift
**Verdict: Inconclusive | Confidence: Medium**

*Outside the DeltaSpike/Hibernate migration proper — this is a PostgreSQL JDBC driver bump — but grouped here as the cluster's remaining persistence-stack-change candidate.*

The driver jumps 5 minor versions (4.5 years, including CVE-2022-31197, CVE-2022-41946, and the critical CVE-2024-1597), all of which touch SQL string parsing/execution paths. The framework's own usage is defensive by construction: it keys no error handling on `SQLState` (zero call sites found), and all temporal reads funnel through `ZonedDateTimes.fromSqlTimestamp()`, which normalises via `Timestamp.toInstant()`/`ZonedDateTime.ofInstant()` and is resilient to representation drift. No behavioural change was found, but none of the three hypothesised risk dimensions (SQLState drift, temporal marshalling, stricter validation) were actually exercised against a live Postgres 15 + pgjdbc 42.7.7 connection, which is why the verdict stays a static-only "inconclusive" rather than "refuted."

**Affected surface:** potentially every DataSource fleet-wide (14 framework repos, 40+ context services), but the concretely audited surface is narrow — 18 `getTimestamp()` call sites in event-store, all normalised, and zero SQLState-keyed branches anywhere.

**Evidence:**
- `cp-maven-parent-pom/pom.xml:87` — the version pin itself, `42.3.2` (main) → `42.7.7` (release/25.104.x)
- `cp-framework-libraries/.../ZonedDateTimes.java:73-74` — the normalising conversion the temporal-resilience claim rests on
- `StreamErrorConverter.java:39-47` — bakes exception class name into the stream-error dedup hash; confirmed pgjdbc exceptions stay in the stable `org.postgresql.*` namespace, so no hash drift

**How to verify (decisive check):** static verification (SQLState grep, CVE-pattern grep, temporal-handling audit) was completed and reported clean. The decisive dynamic step — running the existing event-store/viewstore IT suites (e.g. `SnapshotJdbcRepositoryJdbcIT`) against a live Postgres 15 with the new driver — was **not executed**: the shared Docker infra has no WildFly 40 template yet, so no live J25 stack exists to test against here. The tests exist and were confirmed present; their pass/fail status is genuinely unknown.

**How to avoid/mitigate:** no action needed on the framework's own code — parameterized `PreparedStatement` usage and normalised temporal conversion already avoid the CVEs' actual mechanisms. Before a fleet-wide rollout, run the same static checks against each context's own custom JDBC code, and treat the first live deployment as a canary: watch `stream_error` hash rates for drift and run the identified IT suites for a genuine result once Postgres 15 + WildFly 40 infra exists.

**Verification caveats:** verdict and confidence essentially unchanged, but one claim was found slightly overstated on re-check: "all JDBC operations use parameterized `PreparedStatement` exclusively" is not quite true — `LinkEventsInEventLogDatabaseAccess.java:66-67` executes a non-parameterized `SET LOCAL statement_timeout` via `createStatement()`. This poses no realistic CVE risk (a fixed, numeric-only directive, not attacker-influenced), but "exclusively" should be corrected. All other spot-checks (SQLState grep, temporal-normalisation coverage, exception-class-name stability) reconfirmed as stated.


### Access control & CDI wiring

This cluster covers the machinery that decides whether a request is allowed and the CDI plumbing
that wires that machinery together, across the fleet's 40+ contexts. Two entries concern the Drools
rule engine that most access-control decisions run through — one on the 3-major-version engine bump
itself (BC-03), one on whether the fleet's own test harness can actually detect an engine regression
(BC-20, which turns out to be the load-bearing safety net for BC-03). A third concerns the CDI 4
bean-discovery-mode default change, which could in principle silently empty the interceptor chain
that carries access-control and audit enforcement (BC-14). The fourth (BC-19) is a stale-base
regression that happens to land on this same surface — an access-control provider whose grant
condition silently flipped to permanent deny — but it is a fork/merge problem, not an engine or CDI
problem, so only a pointer is given here; its full write-up lives in the Stale-base section.

#### BC-03 — Drools 7.69→10.1 recompiles access-control DRL, allow/deny can flip
**Verdict:** Refuted (independently verified — CONFIRMED) · **Confidence:** Medium, upper end

`cpp-platform-maven-common-bom` bumps Drools three majors (7.69.0.Final → 10.1.0) and MVEL one minor
(2.4.13 → 2.5.2) for every context that gates access on DRL. Despite the size of the jump, this
proves inert: the BOM keeps the classic AST engine (`drools-mvel`) on the classpath and adds no
Drools-8+ executable-model artifact (`drools-model-compiler`/`drools-canonical-model`), so
`getKieClasspathContainer()` compiles DRL exactly as it did under Drools 7; and both the DRL text and
the access-control Java code are byte-identical J17→J25 bar `javax`→`jakarta` imports, so any drift
would be 100% engine-attributable. A faithful repro exercising every real construct used fleet-wide —
`name==` matching, `eval(varargs)`, `eval(List)`, overload dispatch, `eval() or eval()`,
`hasPermission`, unconditional grants, and progression's one `from collect(...)` clause — fires
identical allow/deny on Drools 10 in all 32 checks.

**Affected surface:** 624 `.drl` files across 51 context checkouts (93% use `eval(`); had a flip
occurred, blast radius would have been every access-controlled command/query across ~40 contexts.
Observed regression: none.

**Strongest evidence:**
- `cpp-platform-maven-common-bom/pom.xml:92-93` — Drools/MVEL version bump; resolved test classpath
  confirmed to contain `drools-mvel-10.1.0` and no `*model-compiler*`/`*canonical-model*` artifact.
- `access-control-drools/src/main/java/uk/gov/moj/cpp/accesscontrol/drools/LocalRulesDroolsAccessPolicyEvaluator.java:35-51` — evaluation logic untouched.
- `.../drools/Outcome.java:8` — `private boolean success = false;`, the fail-closed default, unchanged.
- DRL diff `main...release/25.104.x` is empty for users-groups/hearing/material; a single 0/0-line
  rename for prosecution-casefile — no DRL text could have caused a flip.

**Most decisive verification:** the session's repro driver, compiled offline via targeted plugin
goals (`mvn -o … compiler:testCompile`) and executed as a plain `java -cp …` against JDK 25 (full
Maven lifecycle unavailable in this sandbox), produced **32/32 checks PASS, exit 0**, via both
`getKieClasspathContainer()` (the real production path) and `KieFileSystem`. The verifier
independently re-ran the identical command and got the identical result, and additionally re-swept
all 624 fleet DRLs (not just the sampled ones) confirming zero advanced constraint elements
(`accumulate`, `matches`, `memberOf`, `in(`, etc.) outside what the repro covers — including in the
two largest unpaired owners, reference-data (273 DRLs) and sjp (77), which is what moved confidence
to the "upper end" of medium.

**How to avoid/mitigate:** pin the classic engine explicitly — an enforcer/dependency assertion in
`cpp-platform-maven-common-bom` that `drools-mvel` stays present and `drools-model-compiler`/
`drools-canonical-model` stay absent, since the entire refutation rests on that fact holding for
future Drools bumps too. Pair with closing the BC-20 blind spot (a rule-count assertion in the shared
test harness, so a real rule-loss can't hide), a fleet-wide golden guard per `kmodule.xml` (0 ERROR +
≥1 rule), and running reference-data's and sjp's own access-control suites on the J25 stack before
cutover — the two largest DRL owners never had their literal source compiled in this investigation.

#### BC-14 — CDI 4 discovery-mode + legacy beans.xml can empty interceptor chains
**Verdict:** Refuted (acute symptom), with a real latent hazard · **Confidence:** Medium

WildFly 26.1→40 moves CDI from 1.2/Weld 3.1.4 to 4.1/Weld 6.0.0, and CDI 4 changes the *default*
bean-discovery mode: an empty or version-less `beans.xml` now resolves to `annotated` instead of
`all` (an explicit `all` is still honoured regardless of namespace). That matters because none of the
framework's request-processing wiring carries a bean-defining annotation — the 10
`InterceptorChainEntryProvider` implementations in `cpp-platform-libraries/service-components/*` have
only `@Inject`/`@PostConstruct`, the access-control/audit/feature-control/metrics interceptors are
plain classes, and `@ServiceComponent`/`@FrameworkComponent` are meta-`@ApplicationScoped` but not
`@Stereotype` — so all of it is a bean only under `all` mode. If a provider archive fell to
`annotated`, `InterceptorChainObserver` would collect an empty provider list, `LocalAccessControlInterceptor`/`LocalAuditInterceptor` would never run, and a 403 would silently become 200
with audit rows silently stopping. In practice this doesn't fire: every `beans.xml` on every J25 line
— including the 21 files in `cpp-platform-libraries` still on the legacy `jcp.org` namespace — carries
an explicit `bean-discovery-mode="all"`, and Weld 6 honours explicit `all` even on that legacy
namespace, which is exactly what users-groups' green access-control ITs on real WildFly 40 confirm.

**Affected surface:** mechanism scope is every access-controlled or audited request in every
framework-based context. Hygiene debt (not a live bug, but the thing that would matter if a future
descriptor were ever left empty/mode-less): 21 legacy-namespace files in `cpp-platform-libraries`
(all 10 service-components provider modules plus audit-client, feature-control, id-mapper-client,
metrics-micrometer, etc.) and all 12 `cpp-context-system-doc-generator` descriptors. Structurally, no
existing test can catch a real regression here: `InterceptorChainObserverTest` is pure Mockito
(stubs `BeanManager`, never exercises real discovery), and `core`'s container tests run on
OpenEJB/OpenWebBeans with explicit `@Classes` lists — bypassing `beans.xml` discovery-mode entirely
and using a different container than WildFly's Weld besides.

**Strongest evidence:**
- `cp-microservice-framework/core/.../interceptor/InterceptorChainObserver.java:31,49` —
  `beanManager.getBeans(InterceptorChainEntryProvider.class, AnyLiteral.create())`, with no
  programmatic bean-promotion fallback anywhere in the sibling `ServiceComponentScanner.java` either.
- `cpp-platform-libraries/service-components/query/query-view/.../QueryViewInterceptorChainProvider.java:19` — representative of all 10 providers: only `@Inject`/`@PostConstruct`, no scope annotation.
- `cp-framework-libraries/.../annotation/ServiceComponent.java:15-18` — meta-`@ApplicationScoped`, not `@Stereotype` (so not bean-defining under `annotated` mode).
- Fleet-wide descriptor sweep (independently re-run by the verifier): zero empty or
  `bean-discovery-mode`-less `beans.xml` on any J25 line; exactly 2 pre-existing, intentional
  `annotated` files fleet-wide, neither a J25 regression.

**Most decisive verification:** users-groups' access-control ITs — `GetRolesIT` (unknown user →
unconditional `SC_FORBIDDEN`) and `GetGroupDetailsByNameIT` (non-system user → `SC_FORBIDDEN`) — pass
green on the real WildFly 40/JDK 25/Weld 6 stack per the upgrade pilot's status doc. Since that 403
path requires the legacy-namespace `query-api` provider archive to have been discovered by Weld 6,
these passing ITs are the direct refutation of the empty-chain symptom for the migrated runtime. The
more *direct* check — grep a WF40 boot log for `"Found interceptor chain provider"` and assert a
non-zero count per component type — was specified but **not executed**: no local WildFly-40 image
exists in the shared Docker infra yet (still on 26.1.3). That single boot-log observation is the one
thing that would lift this from medium to high confidence.

**How to avoid/mitigate:** make the wiring annotation-driven so it stops depending on discovery mode
at all — add `@ApplicationScoped` to the 10 `*InterceptorChainProvider` classes and to
`LocalAccessControlInterceptor`/`LocalAuditInterceptor`/`FeatureControlInterceptor`/the metrics
interceptors, and add the same annotation via the two JavaPoet generators that regenerate providers
per-context. Separately, migrate the 21+12 legacy-namespace descriptors to the explicit jakarta.ee
4.0 form, and add a build-time guard that fails on any `beans.xml` that is empty, missing
`bean-discovery-mode`, or not `all` (with an allowlist for the two known-intentional `annotated`
files).

#### BC-19 — SJP agent-prosecutor access silently regressed grant→deny
**Verdict:** Stale-base remediation — CONFIRMED by independent re-verification · **Confidence:** High
(core finding solid; 4 secondary citation inaccuracies found and corrected during verification)

A shipped feature (CCT-2473, agent-prosecutor authority access) landed on the release lines of both
`cpp-platform-libraries` and `cpp-context-users-groups` on the same day, but both repos' J25 lines had
already forked before it landed and have never merged forward — so `ProsecutingAuthorityAccess.hasAccess()` on the J25 line silently returns deny where the released code returns grant
for the identical input, with zero compile error and zero failing test (the regression test for this
exact scenario was simply never forward-ported). This is the same stale-fork pattern as BC-15, not an
access-control-engine or CDI defect, so it is grouped with this cluster only by subject matter. Full
detail — commit-level evidence, the sjp/users-groups/mi-reportdata blast radius, the two-repo
cherry-pick remediation, and the verifier's corrections — lives in the **Stale-base section**; see
BC-19 there.

#### BC-20 — Drools test harness silently loads zero rules from JAR rulebases
**Verdict:** Confirmed · **Confidence:** High — re-executed independently with matching output, but
the flagship mitigation snippet was found broken and two blast-radius figures were found
overstated/miscounted (see below)

As part of the same Drools 10 upgrade behind BC-03, the shared test harness
`BaseDroolsAccessControlTest.setup()` was rewritten from a one-line `getKieClasspathContainer()` call
into a hand-rolled loader, carrying its own comment that this is a deliberate workaround for
"Drools 10.x classpath scanning ... does not work correctly when surefire uses a manifest-only JAR
classpath." The rewrite resolves `kmodule.xml` via a *singular* `getResource()` and only walks the
filesystem to add `.drl` files into the `KieFileSystem` when that URL starts with `file:` — there is
no `else` branch, so a `jar:`-resolved `kmodule.xml` builds silently with **zero rules and zero
errors**. Because `Outcome.success` defaults `false`, a rule-less KieBase makes every fired action
evaluate to "denied," indistinguishable from a genuinely-passing deny-expectation test. A
session-executed side-by-side repro (identical DRL/kmodule content, one laid out as a directory, one
packaged into a real jar) confirmed exactly this: `ruleCount=1, granted=true` vs. `ruleCount=0,
granted=false` for the same action, with the only tell being an easily-missed Drools `WARN` log line.
This is a test-integrity defect, not a live-traffic one — the actual production path
(`KieContainerProvider`) is untouched and still uses the jar-aware `getKieClasspathContainer()` — but
it matters because it is the safety net for BC-03: it could mask exactly the kind of Drools-version
rule-semantics drift that entry investigates.

**Affected surface:** 302 test files extend the shared harness, across **34 contexts** (the verifier
corrected this from the report's stated "33 of ~40," and found the original breakdown's own
sub-counts only summed to 30 — a mid-tier of 10 contexts with 3-5 files each, e.g. resulting/progression/notification at 5 each, had been silently dropped from the narrative). Fleet-wide,
372 `assertFailureOutcome(` call sites carry the dangerous "would vacuously pass" exposure, against
454 `assertSuccessfulOutcome(` call sites that would fail but look like an unrelated engine
regression — roughly 45% of ~826 outcome assertions are the risky flavor. A targeted probe built a
real Surefire-shaped manifest-only jar and found that mechanism alone does **not**, by itself, turn a
module's own co-located `kmodule.xml` into a `jar:` URL — so the shipped workaround's own stated
trigger is unconfirmed for the fleet's normal co-located layout, though the defect stays live for any
classpath shape that genuinely routes `kmodule.xml` through a jar (e.g. a centralized rulebase
consumed as a dependency jar).

**Strongest evidence:**
- `access-control-test-utils/.../BaseDroolsAccessControlTest.java:65` — singular
  `getResource("META-INF/kmodule.xml")`, replacing the J17 one-liner's classpath-merging scan.
- Same file, line 83 — `if (kmoduleUrlStr.startsWith("file:")) { … addDrlFilesToKfs(...); }` with no
  `else`; lines 92-95 — the only build guard is `Message.Level.ERROR`, structurally unable to catch a
  zero-rule build.
- `access-control-drools/.../Outcome.java:8` — the fail-closed default that makes zero rules
  indistinguishable from a real deny.

**Most decisive verification:** the session's ported repro (compiled offline via targeted plugin
goals, executed as plain `java -cp …`) produced `buildErrorCount=0, ruleCount=1, granted=true` for the
directory-classpath control and `buildErrorCount=0, ruleCount=0, granted=false` for the identical
action under a jar-classpath. The verifier independently re-ran both artifacts already on disk and got
byte-identical output, including the same `WARN … KieProject - No files found for KieBase kbase-bc20`
log line — this is executed, reproduced evidence, not an inference.

**How to avoid/mitigate:** add a hard rule-count assertion to `setup()` right after the existing
`ERROR`-level check, failing the build when total rule count is zero. **This is where verification
found a real bug in the report's own proposed fix**: the suggested snippet calls
`kieContainer.getKieBase(kSessionName)`, but `BaseDroolsAccessControlTest` only ever has the
*ksession* name in scope, and a real fleet `kmodule.xml`
(`cpp-context-users-groups/.../usersgroups-command-api/.../kmodule.xml`) proves kbase name ≠ ksession
name is the normal convention (`COMMAND_API` vs. `COMMAND_API_SESSION`) — the verifier built and ran a
probe showing the snippet as written throws `RuntimeException` for the ordinary *passing* case
fleet-wide, not just the buggy jar case. The robust fix is to sum rule counts across all of
`kieContainer.getKieBaseNames()` instead of resolving one kbase name from the ksession name. Beyond
that: add the missing `else` branch so `jar:`-packaged `kmodule.xml` is genuinely supported (not just
detected-as-failed), and add a harness-level self-test that packages a known-good fixture into a jar
and asserts the harness rejects it once the rule-count guard lands.

**Errors found during verification — stated plainly:** the core defect and its executed repro hold up
exactly as reported, but three supporting claims did not survive independent re-checking: (1) the
flagship mitigation snippet above is broken as written and would break the passing case, not just
guard the buggy one; (2) the fleet context count is 34, not 33, with an unstated mid-tier of 10
contexts the original arithmetic dropped; (3) "the only path to `getKieClasspathContainer`/
`newKieClasspathContainer` in the whole workspace is this one file" is an overstatement contradicted
by the report's own adjacent text — `KieContainerProvider.java` and `KieSessionFactory.java` are two
more legitimate (and unaffected) call sites in the same repo; the true, defensible claim is "the only
file among the fleet's *consumer contexts*." None of these change the verdict or the high-confidence
rating on the core finding, but the mitigation fix must not be applied verbatim.


### Activiti/BPMN

This cluster covers the two entries against `cpp-platform-libraries`' embedded Activiti 5.22.0 BPM
engine (`activiti-parent/activiti-embedded-rest`), consumed by 4 of the ~55-56 `cpp-context-*` repos
(`prosecution-casefile`, `sjp`, `staging-enforcement`, `staging-prosecutors-spi`). Both entries trace
to the same forcing function: getting this module off `javax.transaction`/`javax.servlet` for
Jakarta EE 10+/WildFly 40, while keeping Activiti pinned at 5.22.0 and its transitively-pulled
`spring-tx 4.1.5.RELEASE` (still javax-based). In both cases the chosen fix was to **cut** the
javax-dependent piece rather than adapt it — a real JTA-backed transaction manager became a no-op
(BC-09), and a full Spring-MVC `DispatcherServlet` REST surface became 3 narrow JAX-RS resources
(BC-10). Both changes landed on the same commit lineage and are already present on
`release/25.104.x` (not just the `java-25-wildfly-40-upgrade-spike`), and both share the same
structural blind spot: the module's own unit tests and both HTTP-facing consumers'
`ActivitiHelper`-based integration tests exercise only happy-path/fully-mocked scenarios, so neither
regression fails CI.

#### BC-09 — Activiti NoOpPlatformTransactionManager — atomicity/rollback lost

**Verdict: Confirmed | Confidence: Medium**

The J17 engine wired a real `JtaTransactionManager` bound to WildFly's JNDI `TransactionManager`. On
J25 it was replaced by a hand-rolled `NoOpPlatformTransactionManager`, justified by a comment
claiming the manager "is wired by Spring but never called for commit/rollback" — verified **false**
against the real jar: `SpringProcessEngineConfiguration` always installs a
`SpringTransactionInterceptor` on the command chain, even with `transactionsExternallyManaged=true`,
so the NoOp sits squarely on the per-command commit/rollback path. That flag also makes Activiti pick
MyBatis's `ManagedTransactionFactory`, whose `commit()`/`rollback()` are themselves no-ops — Activiti's
persistence layer never calls `connection.commit()` and depends entirely on an *external* transaction.
The result splits by invocation path: delegates run synchronously inside the event-processor MDB still
get SQL atomicity from the container JTA tx, but Activiti's post-commit listeners now fire at
Activiti-command completion via the NoOp rather than at the real JTA boundary, and any post-commit
failure is silently swallowed (`catch (Exception ignored)`). The serious path is Activiti's own
**JobExecutor** thread pool, which drives every timer and `activiti:async="true"` continuation in all
three shared-library consumers — those threads carry no container transaction at all, so on J25 they
get **zero** transactional boundary: partial process state (or writes that never commit) on failure,
and `sender.send(...)` firing regardless of whether the rest of the job succeeded — phantom events with
no rollback-driven retry.

**Affected surface:** 4 of ~56 `cpp-context-*` repos reference `org.activiti`; 3 share
`activiti-embedded-rest` and all set `transactionsExternallyManaged=true` +
`jobExecutorActivate=true` + `asyncExecutorActivate=false` — exactly the config that routes every
timer/async continuation onto the untransacted JobExecutor path (verified in all three, not just
one). Concretely: prosecution-casefile has 5 timer BPMNs whose delegates call `sender.sendAsAdmin`
(`PendingMaterialExpiredDelegate`, `PendingIdpcMaterialExpiredDelegate`,
`BulkScanPendingMaterialExpiredDelegate`, plus 2 more), sjp's `SendExpirationCommandDelegate`, and
staging-enforcement's async service tasks via `ProcessManagerService`. `staging-prosecutors-spi` runs
its own bespoke engine (real javax JNDI lookup) and isn't directly hit by this NoOp, but is flagged as
a trap: migrating it to EE10/11 will throw on that javax lookup, and copying the shared
`NoOpPlatformTransactionManager` to silence the error would import this exact atomicity loss onto its
own timer flow.

**Strongest evidence:**
- `cpp-platform-libraries` `git diff main release/25.104.x -- .../ActivitiEngineConfiguration.java`: `-new JtaTransactionManager(InitialContext.doLookup("java:jboss/TransactionManager"))` → `+new NoOpPlatformTransactionManager()`.
- `NoOpPlatformTransactionManager.java:36-55` (commit — fires before/after-commit callbacks, each `catch (Exception ignored)` at :42,45,48,51) and `:57-67` (rollback — fires only `afterCompletion(ROLLED_BACK)`, catch-ignored at :63).
- Mechanism proof against the real jar on JDK 25: default command chain with `transactionsExternallyManaged=true` is `LogInterceptor → SpringTransactionInterceptor → CommandContextInterceptor` — the manager **is** called per command, directly contradicting the migration's justifying comment.
- `application.properties:3-6` in all three shared-lib consumers: `jobExecutorActivate=true` + `transactionsExternallyManaged=true`, byte-identical J17→J25 in prosecution-casefile.

**How to verify (decisive check):** the unit-level mechanism proof (`Bc09Runner.java`, a plain
`main` run against the real `activiti-engine/-spring 5.22.0`, `mybatis 3.3.0`, `spring-tx
4.1.5.RELEASE` jars on JDK 25.0.3) was **executed twice** — by the investigator and independently
re-run verbatim by the verifier — both times printing `ALL CHECKS PASSED` for all 5 assertions
(transaction-factory selection, interceptor chain contents, transaction-context-factory type). The
**definitive** end-to-end reproduction — deploy prosecution-casefile/sjp on WildFly 40, fail a
delegate inside a `<timerEventDefinition>` job, and assert leftover partial `ACT_RU_*` state plus a
phantom emitted event — was written up as a concrete two-scenario protocol but **not executed**: no
WildFly 40 Docker image exists locally (shared infra is still on 26.1.3).

**How to avoid/mitigate:** don't ship the NoOp on the JobExecutor path. Replace it with a hand-rolled
`PlatformTransactionManager` that delegates to WildFly's real `jakarta.transaction.TransactionManager`
(same JNDI name, jakarta type on WF40) — the Spring `PlatformTransactionManager` *interface* doesn't
reference `javax.transaction`, only spring-tx's `JtaTransactionManager` *implementation* does, so a
hand-rolled impl avoids the `NoClassDefFoundError` while restoring real begin/commit/rollback
semantics and re-coupling post-commit callbacks to the true JTA boundary. At minimum, remove the
`catch (Exception ignored) {}` swallowing so post-commit failures are logged and can mark the
transaction rollback-only. Promote the mechanism test into a permanent fleet regression guard that
fails if a `NoOpPlatformTransactionManager` is wired while `jobExecutorActivate=true`, and explicitly
forbid copying it onto `staging-prosecutors-spi`'s engine when that context is migrated.

#### BC-10 — Activiti REST API reimplemented — null params 500, surface cut

**Verdict: Confirmed | Confidence: High**

On J17, `activiti-embedded-rest` exposed Activiti's entire bundled REST API
(`org.activiti:activiti-rest`, ~88 `URL_*` paths — process-definitions, deployments, executions,
tasks, variables, etc.) via a Spring `DispatcherServlet`. On J25 that registration is dropped
(`WebConfigurer` stubbed to an empty 12-line class) and replaced with 3 hand-written JAX-RS resources
at the same base path — process-instances (GET), historic-process-instances (GET), and jobs
(GET/POST) — with no pagination. This wasn't purpose-built for J25: it's carried over unmodified from
one earlier commit (`dbdfc55`, "Upgrade Framework E to Java 21 and Jakarta EE 10.0"), present
identically on both `release/25.104.x` and the spike, never revisited since. Within the 3 surviving
endpoints there are three further, independently-confirmed bugs: unconditional passthrough of
optional query params into Activiti's `*QueryImpl` setters — several of which throw
`ActivitiIllegalArgumentException` on `null` → HTTP 500 — in `ProcessInstancesResource` and
`JobsResource` specifically (**verified not to reproduce** in `HistoricProcessInstancesResource`: its
MyBatis mapper null-guards both criteria, a confirmed narrowing of the original blanket claim that
all three resources share the pattern); `JobsResource` declares but never branches on `timersOnly`
(always calls `.timers()`); and `ProcessInstancesResource` writes the caller's raw query-param value
into *every* result row's `processDefinitionKey` instead of each instance's own value. The old
`activiti-rest` dependency and `DispatcherServletConfiguration` remain fully compiled into the module
as orphaned dead code — unreachable by any live mechanism (Spring-MVC `@RequestMapping` controllers
can't be picked up by a JAX-RS `Application`), not merely unused.

**Affected surface:** same 4 contexts as BC-09, but only 2 (`prosecution-casefile`, `sjp`) call this
REST surface over HTTP at all, via test-only `ActivitiHelper.java` classes that always send the full
happy-path parameter set — so their green ITs give false confidence and would not catch any of these
bugs. `staging-enforcement` drives Activiti purely in-process (no REST calls); `staging-prosecutors-spi`
runs a fully independent, non-shared engine (out of scope for BC-10). A real consumer outside the
recon/catalogue fleet sweep was found: `cpp.platform.tools.business-data-fixes`, an actively
maintained ops CLI (v1.0.613-SNAPSHOT, used for on-demand production data remediation) whose
non-deprecated `ActivitiRestClient.java` calls `management/jobs` with no `processInstanceId` — the
exact null shape confirmed to throw — and targets several sub-resources (`runtime/executions`,
`process-instances/{id}/variables/{name}`) that don't exist in the new surface at all, which a
null-guard fix cannot repair. This repo has zero J25-related git refs, i.e. nobody has audited it
against this migration, and it's precisely the kind of tool that will hit these bugs live during a
future production incident, not just in tests.

**Strongest evidence:**
- `ProcessInstancesResource.java:32-35` (unconditional `.processDefinitionKey(...)`/`.processInstanceBusinessKey(...)`) and `:41` (wrong-key bug: writes the raw query-param, not `instance.getProcessDefinitionKey()`).
- `JobsResource.java:32-39` (`timersOnly` read at :33, never branched on again; `.timers()` called unconditionally at :37) and `:56-58` (`executeJob`'s `body` parameter declared but never referenced; job always executes).
- `ActivitiSpringContextStartup.java` Javadoc, verbatim: "The Activiti REST management API (.../service/*) is not registered here; add a separate servlet registration if it is needed" — the cut is a documented, deliberate trade-off, not an oversight.
- Introducing commit `dbdfc55` is the sole commit touching every changed/new file, identical on `release/25.104.x` and the spike, with no follow-up on either.

**How to verify (decisive check):** live `curl` against a deployed WF40 WAR is infeasible (no WF40
Docker template exists yet). The load-bearing reproduction — whether Activiti 5.22's `*QueryImpl`
classes really throw on `null`, and for which ones — **was executed**: standalone `java` programs
(bypassing missing `junit-platform-launcher`/`jacoco` artifacts by invoking `compiler:compile` /
`dependency:build-classpath` directly) against the real, unmocked Activiti classes on JDK 25.0.3,
independently re-run byte-for-byte identical by the verifier:
`ProcessInstanceQueryImpl.processDefinitionKey(null)`/`.processInstanceBusinessKey(null)` and
`JobQueryImpl.processInstanceId(null)` all throw `ActivitiIllegalArgumentException`; the
`HistoricProcessInstanceQueryImpl` equivalents do **not** throw, confirming the narrowing above. A
second executed repro, wiring the real `ProcessInstancesResource` to two Mockito-mocked instances with
different real keys, confirmed the wrong-key bug: both rows echoed the caller's query-param value
instead of their own key.

**How to avoid/mitigate:** null-guard every optional filter in `ProcessInstancesResource` and
`JobsResource` before handing it to Activiti (explicit `if (param != null) query = query.filter(...)`
— **not** needed in `HistoricProcessInstancesResource`, which is already correct and should stay
that way); make `JobsResource.getJobs` actually branch on `timersOnly`; make `executeJob` validate the
body's `action` field instead of ignoring it; fix line 41 to serialise `instance.getProcessDefinitionKey()`.
Add fleet-wide tests that exercise the real (unmocked) `*QueryImpl` classes instead of full mocks,
since full mocks are exactly what let all three bugs ship unnoticed. Before `sjp`/`prosecution-casefile`
adopt this line, specifically audit `business-data-fixes`'s `ActivitiRestClient` — several of its calls
target sub-resources that don't exist at all in the new surface and need new hand-written JAX-RS
resources, not just a null-guard. Separately, make an explicit decision (and document it) on whether
the ~85-of-88-path surface cut is permanent policy, rather than leaving `activiti-rest`/
`DispatcherServletConfiguration` sitting as confusing, fully-compiled dead code — but do not attempt
to resurrect the Spring `DispatcherServlet` itself, since `javax.servlet`-based Spring MVC is the
actual EE10/11 incompatibility that forced this change.

**Verification nuance (not a downgrade, but worth surfacing):** both entries kept their original
top-line verdict under independent adversarial re-verification — BC-09 stayed Confirmed/Medium, BC-10
stayed Confirmed/High, and every spot-checked citation and both executable repros reproduced
byte-for-byte on a fresh run. Two things are worth flagging plainly rather than smoothing over: (1)
BC-10's own investigation already corrected the source catalogue's blanket claim that all three REST
resources share the null-passthrough 500 risk — `HistoricProcessInstancesResource` does not reproduce
it, confirmed by direct execution, so only 2 of the 3 endpoints (plus the two other independent bugs)
carry that specific risk; and (2) the verifier notes that an earlier, independent adversarial pass
(`findings.json` U19-3) rated the null-500 bug's real-world *impact* as **Minor, not Major/High** —
reasoning that both known IT consumers always send the full happy-path parameter set and the only
confirmed real no-param caller today is the `business-data-fixes` ops tool — which sits one severity
level below this catalogue entry's carried-over `Impact: high`/`Confidence: high`. This doesn't change
whether the underlying bugs are real (they are, on both counts), only how urgently a human should
prioritise fixing them.


### JSON & validation

This cluster groups three independent dependency bumps in the JSON-handling stack that the J25 BOMs pull in — Jackson's jsr310 module, the JSON-P provider (`javax.json`/`jakarta.json`), and `org.json`/everit-json-schema — each arriving through a framework-owned, single-instance factory class (`ObjectMapperProducer`, `JsonObjects`, `PayloadExtractor`/`JsonSchemaValidator`) that every context inherits unmodified. All three share the same signature: the JSON *text* on the wire never changes, but something about how the framework represents or resolves that JSON in-process does — a deserialized object's zone identity, which concrete class services a JSON-P call, or whether a schema check accepts or rejects a given numeric literal. All three were independently re-verified with live executions against the real, BOM-pinned J25 jars on JDK 25 (not simulated substitutes), and all three reproductions matched the original investigator's output byte-for-byte on the headline mechanism. Where the verifications diverge is in the surrounding claims: one flagged an uninvestigated second mechanism, one downgraded a blast-radius count by more than 2x, one found a wrong secondary citation — each is called out plainly below rather than smoothed over.

#### BC-08 — Jackson jsr310 'Z' → ZoneOffset.UTC identity drift

**Verdict:** Confirmed (Jackson mechanism) · **Confidence:** High (Jackson mechanism only; independently reviewed 2026-07-03)

**What changed / why behavioural.** `cp-maven-common-bom` bumps `jackson.version` 2.12.7 → 2.21.4 (jsr310 tracks it). Two accompanying edits in the framework's shared `ObjectMapperProducer` — a `getTimeZone(ZoneOffset.UTC)`→`getTimeZone("UTC")` swap and a new `ADJUST_DATES_TO_CONTEXT_TIME_ZONE=true` setting — were both isolated and live-proven to be **inert no-ops** for the framework's actual `"UTC"`-configured mapper. The real cause is the Jackson core bump alone: deserializing a literal `'Z'`-suffixed ISO-8601 timestamp (exactly what the framework's own serializer emits) now yields a `ZonedDateTime` whose zone is a `java.time.ZoneOffset` instead of the old `java.time.ZoneRegion`. Wire format, `.toInstant()`, and schema validation are all untouched — this is a pure in-process object-identity change, invisible to functional/HTTP round-trip tests and surfacing only where code keys a `Map`/`Set` by `ZonedDateTime` or asserts exact `.equals()`, since `ZoneOffset` and `ZoneRegion` never cross-match.

**Affected surface / blast radius.** Framework-wide by construction (one shared factory). Confirmed via pre-existing test-diff fixes across 5 repos (`cp-event-store`, `cp-cake-shop`, `cpp-platform-libraries`, `cpp-context-hearing` — 10 sites, corrected up from the report's claimed 8 — and `cpp-context-prosecution-casefile`); an 8-context sweep found no further hits. The standout finding is a new, currently dormant, concrete bug: `cpp-context-listing` (not yet migrated) keys a `Map<ZonedDateTime, HearingDay>` from event-replayed (Jackson-sourced) state, looked up against a hardcoded `ZoneId.of("UTC")` constant built entirely in application code — once listing migrates to J25, every default-path hearing-day reassignment will silently reset its sequence number to `0` (a map miss with no exception, no log line). Confirmed as the only `Map<ZonedDateTime` pattern anywhere in the ~40+ context fleet.

**Evidence:**
- `cp-maven-common-bom/pom.xml` — `jackson.version` `2.12.7`→`2.21.4`
- `cp-framework-libraries/framework-utilities/utilities/utilities-core/src/main/java/uk/gov/justice/services/common/converter/jackson/ObjectMapperProducer.java` — full diff isolating the two inert changes from the real cause
- `cpp-platform-libraries/CHANGELOG.md` (`## [21.0.0-SNAPSHOT] - 2026-04-14`) — first-party confession: *"updated `ZonedDateTime` assertions from `ZoneId.of("UTC")` to `ZoneOffset.UTC` to match Jackson 2.21.x deserialisation of the 'Z' timezone token"*
- `cpp-context-listing/listing-domain/listing-domain-aggregate/src/main/java/uk/gov/moj/cpp/listing/domain/aggregate/Hearing.java:1075,1105,2351-2365,2812-2829` + `.../HearingDaysCalculator.java:30,77-90` — the traced latent bug

**How to verify (decisive).** A plain-`main()` repro (`Bc08ZonedDateTimeIdentityManualRepro`) was compiled and executed under JDK 25 against the real `ObjectMapperProducer` and the real Jackson 2.21.4 jar. Actual result: `getZone()` returns a `java.time.ZoneOffset` equal to `ZoneOffset.UTC` and **not** equal to `ZoneId.of("UTC")`, and a `HashMap<ZonedDateTime,String>` lookup built the old way misses despite an identical instant. This was independently re-executed by the verifier from a clean recompile with byte-identical output. The symmetric J17/Jackson-2.12.7 live run could not be performed — no module in this workspace builds standalone offline on either line — so the J17 side rests on the 10-site test-diff evidence plus the CHANGELOG confession rather than a second live run; that gap is the stated reason this sits at "high," not "certain."

**How to avoid / mitigate.** Re-key `cpp-context-listing`'s `Hearing.mergeHearingDaySequences` map by `HearingDay::getStartTime().toInstant()` instead of the raw `ZonedDateTime` — a plain J17 backport that fixes the bug today, independent of migration timing. Fleet-grep for the same `Map<ZonedDateTime`/`Set<ZonedDateTime` pattern elsewhere (currently zero other hits). At the framework level, add a custom `ZonedDateTime` deserializer in `ObjectMapperProducer` that normalizes any UTC-equivalent value to one canonical constant, closing this class of drift against any future Jackson bump.

**Verification found a real gap — stated plainly.** The core mechanism was not downgraded; the independent re-run reproduced it byte-for-byte and even surfaced a stronger smoking gun than the report itself cited (a pre-existing test literal that directly proves Jackson 2.12.7 produced a `ZoneRegion`). But verification confirmed BC-08's own catalogue entry names a *second* mechanism — DeltaSpike's `EntityManagerProducer` dropping its `TimeZone.setDefault(UTC)` side effect on Hibernate reads — that this investigation never engages with at all (zero mentions, confirmed by grep). The verifier checked independently and confirmed that second mechanism is a real, separate, unresolved J17→J25 change (the whole `persistence-deltaspike` module is dropped from the J25 reactor), left open as a question of whether it needs its own investigation. A few minor citation slips were also found (the cake-shop test line number off by 3; the hearing site count understated as 8 vs. an actual 10) — none affecting the core claim.

#### BC-11 — JSON-P glassfish→Parsson provider-lookup rewrite

**Verdict:** Confirmed · **Confidence:** High on the core mechanism (independently re-reproduced byte-for-byte); Medium on the fleet-wide blast-radius count (downgraded — a re-run shows it's materially wrong)

**What changed / why behavioural.** J17 pins one JSON-P provider (`org.glassfish:javax.json:1.1.4`); J25 pins **four** JSON-P artifacts simultaneously with zero cross-exclusions (parsson 1.1.7, glassfish `jakarta.json` 2.0.1, glassfish `javax.json` 1.1.4 retained, johnzon dormant). The framework's shared `JsonObjects.java` factory replaced the standard `JsonProvider.provider()` bootstrap with a hand-rolled `findProvider()` that also silently drops the `jakarta.json.provider` system-property override. The live-reproduced root cause is sharper than a generic "provider swap": **both glassfish artifacts (`javax.json` and `jakarta.json`) use the identical fallback class name `org.glassfish.json.JsonProviderImpl`** despite implementing incompatible interfaces, so when both land on one classpath, whichever the classloader resolves for that name services every caller — and any caller still on the legacy `javax.json.spi.JsonProvider.provider()` path gets a `ClassCastException`. Not hypothetical: the same migration commit had to patch **five** live framework code-generator modules that had already hit this exact crash in their own build. Live testing also **refuted** the catalogue's own headline numeric-drift example for the pair that actually coexists today — parsson and glassfish's `jakarta.json` produce byte-identical output for every value tested; the one proven difference between them, `getConfigInUse()` (`null` vs. `{}`), has zero live callers anywhere in the fleet.

**Affected surface / blast radius.** Structural, framework-wide (every `JsonObject` build, every persisted event, every JMS/wire envelope). Confirmed unfixed today: `generators-commons` — the shared base the five already-patched modules depend on — carries no equivalent protection itself. Confirmed present, unfixed, in **3 of the 8 J25-paired production contexts**: `cpp-context-hearing` (2 modules), `cpp-context-businessprocesses` (2 modules), `cpp-context-boxworkmanagement` (3 modules) — 7 modules still directly declaring the old `org.glassfish:javax.json` coordinate with no exclusion; `hearing-event-listener` even carries a first-party comment skipping a whole contract-test class because of exactly this incompatibility. **The one figure this entry gets materially wrong**: the report claims "~19 more contexts" at J17 HEAD carry the same leftover coordinate; independently re-running the report's own cited grep returns **43 distinct contexts** — more than double, missing whole named families (the `staging*` family alone contributes 7+). This makes the true exposure larger, not smaller, but the number must be treated as unreliable until regenerated.

**Evidence:**
- `cp-maven-common-bom/pom.xml:86-91,635-652` — four-artifact JSON-P coexistence, no cross-exclusions
- `cp-framework-libraries/framework-utilities/utilities/utilities-core/src/main/java/uk/gov/justice/services/messaging/JsonObjects.java:50,58-66` — `findProvider()`, no `System.getProperty` guard
- `cp-microservice-framework/framework-generators/messaging-adapter-generator/pom.xml` (+4 sibling `-generator` modules) — the already-hit, already-fixed crash, commit `27aebc18f`
- `cpp-context-hearing/hearing-event/hearing-event-listener/pom.xml:82-86,101` — live, unfixed, first-party-acknowledged instance today

**How to verify (decisive).** A live repro (`Bc11JsonProviderManualRepro`) was run twice against the real production `JsonObjects` class under JDK 25 with the real pinned jars. First run (parsson + glassfish `jakarta.json`, matching `generators-commons`'s actual dependency set): both lookup paths agree, no crash. Second run — after temporarily re-adding `org.glassfish:javax.json` test-scope to reach the true pre-migration provider — **actually threw**: `javax.json.JsonException: Provider org.glassfish.json.JsonProviderImpl could not be instantiated: java.lang.ClassCastException: class org.glassfish.json.JsonProviderImpl cannot be cast to class javax.json.spi.JsonProvider` — the identical crash the five framework modules were patched for. Independently re-executed by the verifier from a clean recompile with byte-identical output, including the exact stack trace — the single strongest piece of evidence in this cluster. A native `generators-commons` test (`JsonProviderCoexistenceTest.java`) was written but **never executed**, blocked by missing-sibling-SNAPSHOT dependency resolution in that module.

**How to avoid / mitigate.** Add the identical, already-proven exclude-`javax.json`/keep-`parsson` fix directly to `generators-commons/pom.xml` (the one place in the chain still unprotected) and drop its redundant main-scope glassfish `jakarta.json` dependency entirely. Retire the 7 confirmed leftover `org.glassfish:javax.json` dependencies in `hearing`/`businessprocesses`/`boxworkmanagement`. Harden `JsonObjects.findProvider()` itself: restore the system-property override, log a WARN if more than one provider is `ServiceLoader`-visible, and catch `ServiceConfigurationError` the same way `ObjectMapperProducer`'s sibling fix (same commit) already does.

**Verification found real problems — stated plainly.** Two claims were explicitly downgraded on re-run: the "~19 contexts" fleet count (actual 43, confirmed by direct re-run of the report's own grep), and a provenance claim that the five-module fix was "one commit per file, same SHA" — actually 7 commits per file, and one of the extra six is itself even stronger first-party corroboration of the exact mechanism (an explicit commit message: *"having both on the test classpath causes ServiceLoader conflicts"*), so this error cuts in the report's favor rather than against it. The core crash mechanism, the five-module fix, and the context-level findings are otherwise fully confirmed on independent re-execution.

#### BC-13 — org.json + everit validation strictness shift

**Verdict:** Confirmed · **Confidence:** High

**What changed / why behavioural.** `cp-maven-common-bom` bumps `org.json.version` 20231013 → 20251224 (a ~2-year jump); the framework's own everit-json-schema pin stays at **1.6.0 on both sides** (the diff tool's "removed then re-added" was just property reordering) — so for `cp-microservice-framework`, the entire "shift" the catalogue names is 100% attributable to `org.json`, not everit. (`cpp-platform-libraries` consumers separately move off an older, differently-coordinated everit pin — `org.everit.json:1.3.0` — onto the same 1.6.0, but an isolated A/B test proved everit's own version change contributes zero measurable difference.) Live-reproducing the real call shape (`new JSONObject(text)` → everit `Schema.validate()`) against both org.json versions **refutes the catalogue's own guessed trigger values** (`12345678901234567890`, `1e3`, `10.0` — all identical on both versions) and pins the real, narrower trigger: numeric literals with a **leading zero before/instead of a decimal point** (`007`, `01`, `.5`) — previously silently coerced to a `Number`, now preserved as a `String` — flip `type:integer`/`type:number` schema validation from ACCEPT to REJECT. Since RFC 8259 forbids these literals outright, the flip is a compliance *tightening* that fails safely (a clean 400 via the correctly-caught `ValidationException`), not a silent-wrong-data regression — it only bites a caller that had come to depend on the old lenient coercion. Separately, the framework's `PayloadExtractor`→validator call chain has zero catch for `org.json.JSONException` and no generic `ExceptionMapper`, so any future org.json rejection would surface as an unmapped HTTP 500 on the REST path — a real, pre-existing gap, but not proven to be newly *triggered* by this specific bump (every malformed-input probe tested threw identically on both versions).

**Affected surface / blast radius.** Framework-wide via the single shared `BackwardsCompatibleJsonSchemaValidator` CDI bean, which gates both REST and JMS inbound traffic for every context that runs JSON schema validation. Scale signal (bounded sample): `type:integer` schema declaration counts — `cpp-context-reference-data` 409, `cpp-context-prosecution-casefile` 67, `cpp-context-hearing` 39, `cpp-context-users-groups` 7. Two other call sites carry the identical or worse gap (`cpp-platform-libraries`'s `JsonDocumentValidator`; `cpp-context-scheduling`'s `SchemaValidatorUtil`, which catches *neither* exception type) but both were traced to receive only already-valid, re-serialized JSON-P output rather than raw wire text, making the leading-zero trigger unreachable there in practice — the realistic trigger surface is narrower than "every REST payload," requiring genuinely raw, externally-controlled request/message text.

**Evidence:**
- `cp-maven-common-bom/pom.xml` — `org.json.version` `20231013`→`20251224`; everit pin unchanged at `1.6.0`
- `cp-microservice-framework/core/src/main/java/uk/gov/justice/services/core/json/PayloadExtractor.java` — byte-identical, 3 lines total, no try/catch anywhere in the class
- `cp-microservice-framework/core/src/main/java/uk/gov/justice/services/core/json/FileBasedJsonSchemaValidator.java:38-46` / `SchemaCatalogAwareJsonSchemaValidator.java:53-58` — `extractPayloadFrom` sits outside the try block; catch limited to `ValidationException`
- `cpp-platform-maven-common-bom/pom.xml:73,269-271` — old `org.everit.json:1.3.0` pin on `main`, removed entirely on `release/25.104.x`

**How to verify (decisive).** Three from-scratch, fully-offline Maven probe projects (J17 org.json+everit 1.6.0; J25 org.json+everit 1.6.0; J17 org.json + old everit 1.3.0 coordinate), each running an identical JUnit probe through the real call shape against a `type:integer` schema and a set of borderline literals. Actual, executed result: `007`/`01`/`.5` flip `ACCEPT`→`REJECT` (type `Integer`/`Number`→`String`) between org.json 20231013 and 20251224, while every other tested value — including the catalogue's own guessed triggers — is byte-identical on both. Independently re-run by the verifier from scratch with byte-identical output on every line, confirming both the flip and the correction to the catalogue's original hypothesis. The one residual gap: the literal compiled `PayloadExtractor`/`FileBasedJsonSchemaValidator` classes could not be exercised directly, because `cp-microservice-framework`'s `core` module won't build standalone offline on either J17 or J25 (confirmed genuine, not a shortcut) — judged low-risk since those classes contain no logic beyond the library calls actually tested.

**How to avoid / mitigate.** Add `catch (final JSONException e) { throw new JsonSchemaValidationException(...); }` around the `extractPayloadFrom` call in both `FileBasedJsonSchemaValidator` and `SchemaCatalogAwareJsonSchemaValidator`, mirroring the framework's own existing correct pattern in `cp-framework-libraries`'s `JsonToSchemaConverter.java:30-35`. Apply the same fix to `JsonDocumentValidator` and (the worst offender) `SchemaValidatorUtil`. Land both the fix and a pinned regression-test table (`0`, `007`, `01`, `.5`, `10.0`, `1e3`, `12345678901234567890` → named expected outcomes) once at the `core` module level, since no context ever overrides the org.json/everit version itself.

**Verification found one citation error — stated plainly.** Verdict and confidence are unchanged, but the mitigation section's secondary citation is wrong: `CatalogJsonSchemaLoader.java:133-135` was cited alongside `JsonToSchemaConverter.java` as a second example of the "catch and throw" pattern to copy — it actually does the opposite (logs and **skips** the malformed entry, never throws `SchemaCatalogException`). The primary citation and the recommended fix shape are unaffected; that one secondary reference should not be reused as a second proof point.


### REST engine

This cluster covers the single biggest transport-layer change in the J25 migration: WildFly's
bundled JAX-RS implementation jumps from RESTEasy 3.15.x/4.x to 6.2.15.Final/7.0.0.Final, and a new
WAR-packaging exclusion mechanism was introduced fleet-wide to stop contexts from bundling their own
(now-incompatible) RESTEasy jars alongside WildFly's server-provided ones. Every generated REST
endpoint in every context dispatches through this engine, so the cluster's central question is
whether the swap is truly transparent (same bytes in, same bytes out) or whether it silently changes
multipart decoding, content negotiation, or — the concrete defect actually found — which jars end up
excluded from which WAR.

#### BC-12 — RESTEasy 3.15→6.2.15/7.0.0.Final engine swap, WARs exclude bundled RESTEasy
**Verdict:** Confirmed · **Confidence:** Medium (unchanged on independent verification, but two
supporting claims were corrected — see below)

**What changed / why behavioural.** Three coordinated pom changes drive this: `cp-maven-common-bom`
bumps `resteasy.version`/`resteasy-multipart-provider.version`/`resteasy-client.version` from
`3.15.5.Final`/`3.15.1.Final`/`4.7.7.Final` to `7.0.0.Final` across the board; `cpp-platform-maven-common-bom`
bumps `jboss.resteasy.version` `4.3.0.Final` → `6.2.15.Final`; and both the OSS and CPP-platform
parent POMs add `maven-war-plugin` `packagingExcludes` so WARs no longer bundle RESTEasy (it must come
from WildFly's own modules instead), using a broad glob on the OSS side but a long enumerated list on
the platform side. The framework's own multipart code (`AdditionalPropertiesExtractor`) is
byte-identical J17→J25, so any change in behaviour is attributable entirely to the jar swap, not a
context code change. A real, from-scratch reproduction against the actual 7.0.0.Final jars proved
`InputPart.getBodyAsString()` — the exact method the framework calls — delegates to a live JAX-RS
`Providers.getMessageBodyReader()` lookup rather than doing a self-contained decode, meaning its
output can depend on whatever `MessageBodyReader<String>` the surrounding deployment resolves.
Independently, and more concretely, the platform's enumerated exclusion list was found to have two
uncaught gaps — `resteasy-client-*.jar` and `resteasy-jackson2-provider-*.jar` — and a real fleet
consumer of one of them was confirmed.

**Affected surface / blast radius.** The engine swap and its exclusion mechanism apply
unconditionally to every WAR in the fleet: `cp-cake-shop` via `cp-maven-parent-pom`, and every
`cpp-context-*` command-api/query-api/query-view/event-listener/event-processor WAR via
`cpp-platform-maven-service-parent-pom` — no opt-out exists. The confirmed packaging gap currently
reaches at least `cpp-context-reference-data`'s `referencedata-event-processor` (a direct consumer of
`cpp-platform-azure-utils`, which carries the unexcluded `resteasy-client` dependency); any other
context integrating Azure blob storage through that library is equally exposed, though a full fleet
count was out of scope. The `resteasy-jackson2-provider` gap is latent — no consumer was found across
five checked repos. Three contexts (`cpp-context-hearing`, `cpp-context-material`,
`cpp-context-prosecution-casefile`) already hand-manage RESTEasy/Jackson-provider module conflicts via
pre-existing `jboss-deployment-structure.xml` exclusions, showing this class of conflict is already a
live concern independent of J25. A fleet-wide published test helper, `HeadersBuilder.java`, had to be
rewritten off real RESTEasy onto a hand-rolled stub that hard-codes empty
languages/cookies/date and mis-handles multi-value `Accept` headers — a test-fidelity regression that
reduces every generated REST-adapter test's ability to catch a real symptom of this cluster, on either
RESTEasy version.

**Strongest evidence.**
- `cp-maven-common-bom/pom.xml` (`main` vs `release/25.104.x`): version bumps at lines 56-59; new
  dependencyManagement block at 353-410 — critically, three transitive exclusions on
  `resteasy-multipart-provider` (`resteasy-jaxrs`, `resteasy-jaxb-provider`, `resteasy-client`,
  present on `main` around lines 220/224/228) are simply gone on `release/25.104.x` (384-405).
- `cpp-platform-maven-service-parent-pom/pom.xml:100` (enumerated `packagingExcludes`) vs
  `cp-maven-parent-pom/pom.xml:407` (broad `resteasy-*.jar` glob) — the enumerated list is a
  non-additive full override (no `combine.children="append"`), and it omits `resteasy-client-*` and
  `resteasy-jackson2-provider-*`.
- `cpp-platform-libraries/cpp-platform-library-utils/cpp-platform-azure-utils/pom.xml:77-79` —
  `resteasy-client` at default (compile) scope, uncaught by the exclusion list above; real consumer
  confirmed at `cpp-context-reference-data/referencedata-event/referencedata-event-processor/pom.xml`
  (checked at HEAD).
- `cp-microservice-framework/framework-generators/rest-adapter-core/src/main/java/uk/gov/justice/services/adapter/rest/multipart/AdditionalPropertiesExtractor.java`
  — zero diff `main...release/25.104.x`, confirming the behavioural attribution falls entirely on the
  jar swap.

**Most decisive verification.** The packaging-gap check was fully executed (not just proposed): a
short shell script matches both exclusion lists' patterns against the actual J25 BOM-resolved jar
filenames and prints a table. Actual output obtained: `resteasy-client-*` and
`resteasy-jackson2-provider-*` are the only two of eight RESTEasy jars that print
`platform-excluded=no` — i.e., confirmed uncaught by `cpp-platform-maven-service-parent-pom`'s list.
This was independently re-derived by hand by the verifier from the same primary-source exclude-list
strings and matched exactly. By contrast, the more dramatic-looking "live engine" repro (crafting a
raw multipart body and feeding it through the real `MultipartFormDataReader`/`MultipartInputImpl`
chain) was executed only as far as reproducing the `Providers.getMessageBodyReader()` NPE — it never
reached a decoded value, and a full live-WildFly-40 A/B comparison could not be run at all (no WF40
image available locally). A newly written `AdditionalPropertiesExtractorTest` (4 tests, real
production class, Mockito `InputPart`) was also genuinely executed via a scratch reflection runner
with a real `PASS x4` result, but by construction it cannot exercise RESTEasy's real decode path.

**How to avoid / mitigate.** Highest-leverage fix: change
`cpp-platform-maven-service-parent-pom/pom.xml:100`'s `packagingExcludes` from its enumerated list to
the same broad `WEB-INF/lib/resteasy-*.jar` glob the OSS parent already uses — a one-line,
superset-safe change that immediately closes both confirmed gaps and any future RESTEasy artifact.
Pair it with restoring the three dropped transitive exclusions on `cp-maven-common-bom`'s
`resteasy-multipart-provider`, and fix `cpp-platform-azure-utils` itself by moving its `resteasy-client`
dependency to `<scope>provided</scope>`, copying the pattern already proven safe in
`cp-file-service/file-service-alfresco/file-alfresco/pom.xml:37-59`. Longer-term, bind a
`maven-enforcer-plugin` rule at the shared parent-POM level that inspects the built WAR's `WEB-INF/lib/`
and fails the build if any `resteasy-*.jar`/`jakarta.ws.rs-api-*.jar` slipped through — this converts
"we hope the exclude-list enumerates everything" into a verified build-time guarantee and would have
caught the `resteasy-client` gap immediately.

**Verification found real problems — stated plainly.** The verdict and confidence were not
downgraded, but two supporting claims in the original write-up did not hold up and a new risk was
found that the original session missed entirely:
1. **Factual error.** The report's "one dependency fixed, one left behind" narrative for
   `cpp-platform-azure-utils` is wrong. Three independent diff methods confirm **neither**
   `resteasy-servlet-initializer` nor `resteasy-client` changed at all between `main` and the J25
   branch — both remain untouched, compile-scope dependencies on every ref. The practical conclusion
   (`resteasy-client` is a real, unexcluded, live gap) still stands, but `resteasy-servlet-initializer`
   is safe only because it happens to be independently caught by the platform's enumerated
   `packagingExcludes`, not because anyone removed it.
2. **Overstated novelty.** The live-engine NPE trace is a genuine execution, but checking RESTEasy's
   own public `3.15.5.Final` source shows `getBodyAsString()` already delegated to the identical
   `Providers`-based lookup on the old engine. So this proves the mechanism, not a version-introduced
   change — the actual open question (whether the *set or priority order* of `MessageBodyReader`
   candidates differs between 3.x and 6.2.15/7.x) is exactly as unconfirmed as the original one-line
   catalogue hypothesis.
3. **New compounding risk, not previously flagged.** `cpp-context-prosecution-casefile`'s
   `prosecutioncasefile-query-api` is REST-facing (real RAML with HTTP GET actions, `packaging: war`,
   no `web.xml`), inherits the new fleet-wide `packagingExcludes` that strips its own bundled RESTEasy
   out of `WEB-INF/lib`, **and** carries a pre-existing `jboss-deployment-structure.xml` that
   explicitly disables WildFly's own `jaxrs` subsystem. Combined, there may be no remaining path for a
   JAX-RS runtime to activate in this deployment at all. This could not be confirmed (no WF40 image
   available) but is flagged as a higher-priority check than the original report's proposed
   multipart-charset repro — if it doesn't come up, it is a deploy-breaking regression, not an
   edge case, and should be checked first once a WF40 target exists.


### Messaging/observability

This cluster covers three places where the J17→J25 migration changed something an operator, on-call
engineer, or test harness *relies on to observe or diagnose* the system, without touching core business
logic: an HTTP metrics/health surface that quietly stopped answering (BC-16), the recorded identity of
stream-processing failures silently drifting so identical errors no longer hash the same (BC-17), and a
JMS test helper that now silently connects to the wrong broker instead of the one under test (BC-18).
None of the three crash loudly — the shared risk is an operator, dashboard, or CI pipeline trusting a
signal that quietly stopped meaning what it used to. All three were independently re-verified in full
this session; none were downgraded, and two (BC-16, BC-18) came out of verification with their blast
radius shown to be *larger*, or their confidence *higher*, than originally stated.

#### BC-16 — `/internal/metrics/*` gutted to 404
**Verdict: Confirmed — Confidence: High** (unchanged and unchallenged by verification)

`cp-microservice-framework`'s `metrics/metrics-servlet` module dropped its
`io.dropwizard.metrics:metrics-servlets` dependency outright (not swapped for a jakarta-namespaced
equivalent) and rewrote `MetricsAdminServlet` from a 14-line `AdminServlet` subclass into a 39-line
hand-rolled `HttpServlet` that recognises only `/ping` (→ 200 "pong"); every previously-served sub-path
(`/metrics`, `/threads`, `/healthcheck`, `/pprof`), and even a bare `GET /internal/metrics` with no
sub-path, now falls through to `sendError(404)`. This is a deliberate rewrite, not a version-bump side
effect, and it is behavioural in the strictest sense: an identical GET to a previously-200 URL now
returns 404. It is compounded by two context-listener Javadoc comments claiming CDI now "manages" what
was removed — traced and shown false: `HealthcheckServlet` is a separate, pre-existing mechanism serving
a different path (`/internal/healthchecks/all`), and the still-live `metrics-interceptor` `MetricRegistry`
was never bridged anywhere except a JMX reporter, so it lost its only HTTP export.

**Blast radius:** exhaustively checked, not just re-stated. `cpp-aks-deploy`'s 49 liveness/readiness-probe
files (one per context) all target only `.../internal/metrics/ping`; the generic `cpp-helm-chart` default
and a captured live-cluster deployment manifest (`cpp-context-listing`) confirm the same; the shared
`cpp-developers-docker` health-check script does a substring `grep pong` on that one path, robust to the
rewrite. Zero hits anywhere under `~/idea` — across `cpp-aks-deploy`, `cpp-helm-chart`, and the full
40+-repo `cpp-context-*` fleet — for the three gutted paths, and no context ships its own copy of the
removed mechanism. Verification went further and found cake-shop's own IT suite has zero references to
any `/internal/metrics/*` path at all — slightly *stronger* than the report's own claim, not weaker. The
one unclosable gap: no visibility into any Grafana/PagerDuty/runbook dependency living purely outside
this workspace.

**Evidence:**
- `cp-microservice-framework/metrics/metrics-servlet/pom.xml` — dropwizard deps deleted outright (`git diff main...release/25.104.x`)
- `cp-microservice-framework/metrics/metrics-servlet/src/main/java/uk/gov/justice/services/metrics/servlet/MetricsAdminServlet.java` — 14→39 line rewrite, full `doGet()` logic
- `~/idea/cpp-aks-deploy/ansible/group_vars/*.yaml.j2` (49 files) — exact-path grep resolves to only `/internal/metrics/ping`
- `~/idea/cpp-developers-docker/build-scripts/healthcheck-functions.sh:31,34` — substring `grep pong` check, status-code-insensitive

**How to verify:** a live, executed reproduction — 5 new `@Test` methods added to the real
`MetricsAdminServletTest.java`, run via `mvn -pl metrics/metrics-servlet -Dtest=MetricsAdminServletTest
test` under JDK 21 (with transient CLI plugin-version overrides to route around this sandbox's
uncached-artifact/no-network wall; no files changed on disk). Result: **6/6 tests passed, BUILD
SUCCESS** — reproduced identically both in the original investigation and in the independent verifier's
own re-run (`-o` offline). A full HTTP round-trip against a live WildFly 40 was not attempted — no such
deployment target exists anywhere in this workspace.

**Mitigate:** fix or delete the two misleading Javadoc comments before a future maintainer treats them as
proof of parity; if the per-action Codahale timer metrics need HTTP reachability again, feed them into
the Micrometer `PrometheusMeterRegistry` that already survives at `/internal/metrics/prometheus` rather
than resurrecting Dropwizard; keep the 5 added unit tests permanently and add a companion cake-shop IT
once a WF40 target exists, to make the 404 contract an enforced regression rather than an
inferred-by-absence fact.

#### BC-17 — `stream_error` hash/identity shift for identical failures
**Verdict: Confirmed — Confidence: High (dominant mechanism); Medium (secondary mechanism)** — unchanged
on verification, but the causal chain was closed end-to-end through a class the original report never
cited

The catalogue's framing — "javax→jakarta renames change the recorded identity of identical failures" —
does not hold up as the mechanism; the hashing/filtering code itself (`ExceptionHashGenerator`,
`FrameworkClassNameFilter`, `HashFromStringGenerator`) is byte-identical or import-only-changed on both
refs. The real driver is that `cp-microservice-framework`'s `persistence-deltaspike` module is orphaned
from the Maven reactor (its `<module>` line removed from `persistence/pom.xml`, while its 9 source files
remain on disk, unbuildable, since DeltaSpike is gone from the BOM). That silently drops
`EntityManagerFlushInterceptor`, which used to force a synchronous `entityManager.flush()` immediately
after every `EVENT_LISTENER` handler ran. Without it, the same underlying failure now surfaces later — at
implicit JTA-commit-time flush via a plain container-managed `EntityManager` — as a different exception
class, from a different frame, with a different message. This is proven with already-captured, real
before/after regression data: for the same deterministic not-null-constraint trigger, J17 records
`javax.persistence.PersistenceException` thrown from `EntityManagerFlushInterceptor`, while J25 records
`uk.gov.justice...TransactionException` ("Failed to commit UserTransaction") thrown from
`TransactionHandler` — only the root `causeClassName`/`causeMessage` (the real `PSQLException`) stays
stable. A secondary, medium-confidence mechanism (Hibernate 6 ByteBuddy proxy names now passing
`FrameworkClassNameFilter`'s `$$`-only exclusion, where Hibernate 5's Javassist names were excluded) sits
alongside it — proven only at the pure string-matching level, not against a real running Hibernate
instance.

**Blast radius:** every context whose `EVENT_LISTENER` handlers persist via JPA with
`event.stream.self.healing.enabled=true` — confirmed, on verification, `true` in all 40 per-context
config declarations checked in `cpp-developers-docker` (6 generated `standalone-<ctx>.xml` files
originally, all 34 remaining per-context YAMLs added on verification), i.e. this is the fleet-default
deployment configuration, not an opt-in. `stream_error`'s only unique constraint is `(stream_id, source,
component_name)`, not hash, so any stream already sitting in an error state at cutover that hits the same
real failure again post-upgrade gets recorded as a brand-new error rather than a continuation — a burst
of apparently-new rows immediately after redeploy, not a functional break. No fleet context code depends
on the hash-generation internals directly (the only hit is an incidental test-cleanup table-truncation
call in `cpp-context-listing`), so the in-workspace blast radius is operational noise, not a broken
dependency — but any external Grafana panel or runbook keyed on the exact old class/message strings would
silently stop matching.

**Evidence:**
- `cp-microservice-framework/persistence/pom.xml` — `<module>persistence-deltaspike</module>` removed (`git diff main...release/25.104.x`); `git ls-tree -r release/25.104.x -- persistence/persistence-deltaspike` shows all 9 files still present but unbuildable
- `~/idea/cpp-developers-docker/containers/config/common.yml:328-333` — fleet-wide `event.stream.self.healing.enabled: "true"` default
- `cp-cake-shop/cakeshop-integration-test/.../it/StreamErrorHandlingIT.java` (~lines 73-79 on J25) — the before/after exception-identity table itself
- `cp-event-store/event-sourcing/subscription-manager/src/test/java/.../error/TransactionHandlerTest.java` — pre-existing unit test independently proving the J25-side `TransactionException` wrapping

**How to verify:** the strongest evidence is the already-captured, real `StreamErrorHandlingIT`/
`RestResourcesIT` before/after diff itself — someone had to run both stacks to know to hardcode those
exact literal strings. This is corroborated by a pre-existing, infra-free unit test,
`TransactionHandlerTest.shouldThrowTransactionExceptionIfCommitUserTransactionThrowsRollbackException`,
which mocks `UserTransaction.commit()` and confirms the exact J25-side wrapping message. Verification
traced the missing link between the two: `SubscriptionEventProcessor` runs the interceptor chain then
calls `transactionHandler.commit()`, closing the causal chain end-to-end (not previously cited). A live
side-by-side WF26-vs-WF40 run of `StreamErrorHandlingIT` was **described but not executed** — no WF40
Docker template exists in this workspace. A standalone `javac`/`java` smoke test of
`FrameworkClassNameFilter`'s production logic (secondary mechanism only) *was* executed, all 5 assertions
passing.

**Mitigate:** fix a related, pre-existing (non-regression) defect found in the same code path —
`StreamErrorConverter.asStreamError()`'s unguarded `stackTraceElements().get(0)` should become a
`.stream().findFirst().orElseGet(...)` so a frame-less exception doesn't throw
`IndexOutOfBoundsException` and silently drop the error row entirely. For the main issue, either
deliberately relocate `EntityManagerFlushInterceptor`/`Provider` (zero DeltaSpike imports; only two new
same-reactor dependencies needed) into the surviving `persistence-jdbc` module to restore the flush as an
explicit choice, or add a "stream_error identity stability" characterisation test that asserts only on
the stable root-cause fields; either way, document in the cutover runbook that pre-existing `stream_error`
rows should be treated as stale/expected-to-churn at the moment of a J17→J25 redeploy.

*Verification note:* one minor citation-precision gap was found and is worth naming rather than smoothing
over — the report's evidence-section path citations use a `.../it/…` elision that implies an `example`
package segment which doesn't actually exist in the real path (`uk.gov.justice.services.cakeshop.it`).
Harmless once resolved (the diff content and line numbers behind it all check out), but it cost real time
during verification.

**Update 2026-07-08 (re-run): partially remediated.** The "deliberately relocate `EntityManagerFlushInterceptor`/`Provider`" mitigation above has substantially landed — `cp-microservice-framework` ea3282623 (25.104.0-M3) removes `persistence-deltaspike` outright and relocates its four non-DeltaSpike classes verbatim into a NEW built `persistence/persistence-jpa` module (interceptor now `@PersistenceContext`), and `cpp-platform-libraries` 3a5ffbb5 (M7) wires `persistence-jpa` into the `event-listener` service-component — so on the two fully-upgraded reference contexts an `EVENT_LISTENER` DB failure again surfaces at the mid-chain flush, not at `TransactionHandler.commit()`. Not fully closed: the dedup hash is still not byte-identical to J17 (residual `javax`→`jakarta` rename + a verified +1 line shift), the secondary ByteBuddy/Javassist mechanism and the unguarded `get(0)` defect are untouched (`cp-event-store` had no code changes), and only 2 of 6 locally-cloned `EVENT_LISTENER` contexts carry the fix — cake-shop (whose `StreamErrorHandlingIT` is cited above as proof, still has no `persistence-jpa` dep) plus businessprocesses, hearing, material and system-doc-generator remain on pre-fix platform-libs. See investigations/BC-17.md (dated section).

#### BC-18 — Artemis client 2.53 "eager broker-URL pinning"
**Verdict: Confirmed (root cause corrected) — Confidence: High** (raised from Medium during independent
verification)

The catalogue's own causal story — "Artemis 2.53 newly pins the broker URL eagerly, so classes that still
call `setBrokerURL()` now break" — does not survive a direct source check: `ActiveMQConnectionFactory`'s
`readOnly`/`checkWrite()`/`makeReadOnly()` guard was fetched live from GitHub at both the 2.24.0 and
2.53.0 tags and is textually identical, tracing to 2015/2019-era commits, years before this migration.
What *is* real and confirmed: of three near-identical published JMS test-utils helper classes across two
artifacts, exactly one — `cp-microservice-framework`'s `JmsSessionFactory` — had its
`activeMQConnectionFactory.setBrokerURL(queueUri)` call deleted during the migration, with its unit
test's matching `verify(...).setBrokerURL(...)` assertion deleted in the very same diff (production code
and test hardened away together — the strongest signal this was a deliberate edit reacting to a real
problem, not incidental churn). The two sibling classes (`cp-framework-libraries`'s `JmsSessionFactory`
and `cp-microservice-framework`'s `MessageProducerClient`) still call and still test `setBrokerURL()`
unchanged. The practical effect: the broken class now always dials Artemis's compiled-in default
`tcp://localhost:61616`, silently ignoring the caller-supplied `INTEGRATION_HOST_KEY`-derived host — a
real problem specifically because this framework's own default Docker topology puts each context's
broker behind a per-context loopback alias, not plain `localhost`.

**Blast radius:** verification found this *larger* than originally scoped. The original grep ("208 files
/ 22 contexts") only matched `JmsMessageProducerClient`; broadening to the report's own stated lineage
(`JmsMessageProducerClient|JmsMessageConsumerClient`) raises it to **364 files across 29 contexts** —
including `users-groups` and `prosecution-casefile`, the two contexts this workspace's own documentation
calls fully upgraded and validated green. The still-safe sibling lineage spans 239 files / 29 contexts,
unaffected by this specific defect (though it carries its own narrower, fleet-unverified,
reuse-after-`close()` `IllegalStateException` risk that would misfire identically on J17 — not new to
J25). Framework CI would not catch this: `cp-cake-shop`'s own IT harness rolls its own safe,
constructor-with-URL `ActiveMQConnectionFactory` and never touches the broken shared helper, so the
stack's own reference suite stays green while every fleet consumer of the published helper silently
mis-targets its broker.

**Evidence:**
- `cp-microservice-framework/test-utils/integration-test-utils-jms/src/main/java/.../jms/JmsSessionFactory.java` — `setBrokerURL(queueUri)` line deleted (`git diff main...release/25.104.x`), with its `JmsSessionFactoryTest.java` matching `verify` deleted in the same diff
- `cp-framework-libraries/framework-utilities/test-utils/test-utils-core/.../messaging/JmsSessionFactory.java:31` and `cp-microservice-framework/test-utils/test-utils-core/.../MessageProducerClient.java:75` — the two unaffected siblings, still calling and still testing `setBrokerURL()`
- `~/idea/cpp-context-users-groups/usersgroups-integration-test/.../util/QueueUtil.java` — silent-null-on-timeout hardened to `orElseThrow(AssertionError)`, circumstantial corroboration this exact symptom class was chased live during migration in a "validated green" context

**How to verify:** a 4-test JUnit reproduction (`BrokerUrlEagerPinningReproTest.java`, exercising the real
unmocked `ActiveMQConnectionFactory`) was written and reasoned through but **not executed** — both the
original investigation and, separately, the verifier hit the identical wall re-running it: a missing
sibling SNAPSHOT artifact (`test-utils-logging-simple`) unresolvable because the internal Artifactory
host is unreachable from this sandbox (confirmed not a general network outage, since public GitHub
fetches succeed). The check that *was* actually executed — twice, once by the original investigation and
again independently by the verifier via a second live fetch from `github.com/apache/activemq-artemis` —
is the byte-for-byte tag comparison proving the `readOnly` guard is unchanged 2.24.0→2.53.0, which is what
overturns the catalogue's stated mechanism and is the reason confidence was raised to High.

**Mitigate:** stop mutating a shared no-arg-constructed factory via `setBrokerURL()` in all three classes
and instead construct `new ActiveMQConnectionFactory(queueUri)` directly — the pattern every safe
fleet-owned helper and cake-shop's own `JmsBootstrapper` already use. This simultaneously restores the
dropped host and makes the two still-correct siblings immune to their separate reuse-after-close risk in
the same fix. Replace the `Mockito.verify(...).setBrokerURL(...)`-style regression test with a real-object
assertion on `factory.toURI().toString()`, since this investigation shows a `verify` can simply be deleted
alongside the bug it was meant to catch; once the fix ships, add a build-time lint (Enforcer rule or CI
grep) banning bare no-arg `new ActiveMQConnectionFactory()` construction fleet-wide, since no legitimate
caller would be left.

*Verification note:* one of the two git-blame commits cited for dating the `readOnly` guard's origin is
weaker than presented — a mass 3,597-file "automatic checkstyle change" commit, a classic git-blame trap
— though this doesn't affect the actual conclusion, since the direct 2.24.0-vs-2.53.0 tag diff (which
doesn't depend on blame dates at all) independently proves the guard is unchanged.


### Codegen/build-time-with-runtime-effect

This cluster is reserved for behavioural drift that originates in build-time tooling — annotation/classpath scanning and code-generation libraries — rather than in framework runtime code or a service's own source. Its defining trait is that a consuming service's code doesn't change at all and compiles clean, yet the *artifact* the build produces (generated adapter/client classes, discovered resource sets) can silently differ, so the effect only surfaces when the generated code runs. That mechanism is structurally distinct from the plain runtime-dependency swaps that make up most of the rest of the catalogue, which is why it gets its own group even though, in this batch, it holds a single entry.

#### BC-21 — reflections 0.9.10→0.10.2 scanning-contract change

**Verdict: partially-confirmed** (downgraded from the investigator's original *confirmed*) · **Confidence: medium** (downgraded from *high*)

cp-maven-common-bom bumps `reflections` 0.9.10→0.10.2 — a major version with a documented breaking API (`ResourcesScanner`→`Scanners.Resources`, `SubTypesScanner`→`Scanners.SubTypes`, and the `SubTypes` store changing shape from `Multimap<String,String>` to `Map<String,Set<String>>`). Two consumers in cp-framework-libraries were migrated to match, and the entry actually bundles two separable findings with different fates on verification:

- **`JavaCompilerUtility.getClassNames()` (test-utils-core) — CONFIRMED, high confidence.** The M5 migration unioned the new store's keys (supertypes) with its values (subtypes) — `Stream.concat(types.keySet().stream(), types.values().stream()...)` — so `compiledInterfaceOf()`/`compiledClassesOf()` returned extra/wrong types for the M5-only window. M6, shipped the same day per the CHANGELOG (2026-06-09), corrected this to return only `types.values()`. Code at HEAD matches the CHANGELOG claim exactly.
- **`FileTreeScanner` discovering 2 extra classpath resources — the part that got downgraded.** The investigator attributed 2 newly-discovered files (`external-3.raml`, `external-4.raml`) under an unchanged wildcard include/exclude pattern to `Scanners.Resources`'s different scanning semantics, citing a same-day workaround (test-fixture excludes) added to keep a test's expected count at 2. Verification found `FileTreeScanner` actually has two independent scanning backends: `getFromPath()` (uses Reflections/`Scanners.Resources` — the changed API) and `getFromClasspath()` (uses ClassGraph, untouched by this bump). The test that motivated the workaround, `GenerateMojoTest`, defaults to `sourceDirectory=CLASSPATH`, which routes through `getFromClasspath()`/ClassGraph — not through the changed Reflections path at all. The workaround exists and does suppress the extra files, but pinning that effect on the reflections bump is unsupported by the code path actually exercised; whether the 2 files were ever really discovered by scanning (versus excluded pre-emptively) and whether the real cause is ClassGraph itself remain open.

**Affected surface / blast radius.** Any service generating JAX-RS adapter/client classes from RAML via `GenerateMojo` with wildcard CLASSPATH includes is the theoretical exposure class, but no concrete count of such services was established — the report supplies only the grep to find them (`sourceDirectory.*CLASSPATH`, wildcard RAML includes across `cpp-context-*`), not its output. The one context actually traced, `cpp-context-users-groups`, is confirmed clear: it resolves cp-framework-libraries to 25.104.0-M10 via service-parent-pom M4 → cpp-platform-libraries M5 (`cpp-platform-libraries/pom.xml:49`), and M10 postdates the M6 fix. The `JavaCompilerUtility` bug's exposure window is a single calendar day (M5, 2026-06-09) — only a build that hard-pinned exactly that version would have seen it. Net: zero contexts confirmed actually affected by either sub-issue at HEAD.

**Evidence:**
- `cp-maven-common-bom/pom.xml:144` — the triggering version bump (0.9.10→0.10.2)
- `cp-framework-libraries/framework-utilities/test-utils/test-utils-core/src/main/java/uk/gov/justice/services/test/utils/core/compiler/JavaCompilerUtility.java:220-231` — confirmed M6 fix: `Scanners.SubTypes` + values-only extraction
- `cp-framework-libraries/generator-maven-plugin/generator-io-utils/src/main/java/uk/gov/justice/maven/generator/io/files/parser/io/FileTreeScanner.java:66-73` (Reflections/`Scanners.Resources` path) vs `:75-87` (ClassGraph path) — the backend split that undermines the discovery-count attribution
- `cp-framework-libraries/generator-maven-plugin/generator-plugin/src/test/resources/includes-excludes-external/pom.xml:24-26` — the M5-added masking excludes for `external-3/4.raml`

**How to verify (most decisive).** A test was authored for the confirmed half: `BC21_ReflectionsVersionTest.java` in `test-utils-core` (`cp-framework-libraries/framework-utilities/test-utils/test-utils-core/src/test/java/uk/gov/justice/services/test/utils/core/compiler/BC21_ReflectionsVersionTest.java`), run via `mvn -pl . -Dtest=BC21_ReflectionsVersionTest test` from that module directory. This is written-but-not-confirmed-executed in the record: the write-up states an "expected result" (passes against M6+ HEAD, would fail if M5 code were restored) rather than a captured pass/fail from an actual run, and gives no reason for the gap — so treat the `JavaCompilerUtility` confirmation as code-read-verified, not test-run-verified. The `FileTreeScanner` discovery-count half has no equivalent repro at all: the source explicitly notes that comparing 0.9.10-vs-0.10.2 scanning behaviour needs dual-classloader isolation and calls it "not feasible in a single JVM test," so that claim rests solely on the existence of the masking excludes, not on an observed before/after diff.

**How to avoid/mitigate.** No action needed for the `JavaCompilerUtility` bug — it's fixed at M6 and the only downstream consumer traced so far is already past it; just don't let anything re-pin cp-framework-libraries to exactly 25.104.0-M5. For the `FileTreeScanner` question, don't treat the test-fixture excludes as a root-caused fix: run `FileTreeScannerTest.java`'s `getFromPath()` cases (the actual `Scanners.Resources` path) separately from `GenerateMojoTest`'s default-CLASSPATH (`getFromClasspath()`/ClassGraph) cases to establish which backend actually produces the 2 extra files — and confirm they're genuinely discovered rather than excluded out of caution — before relying on the workaround. More generally, for any reflections 0.10.x consumer: always read `SubTypes.index()` values only (never union with keys), and add an explicit resource-count regression assertion after any scanner-library version bump rather than discovering drift via a failing fixture count.


### Stale-base (route to release management)

Both entries here share one root cause: a Java-25 migration branch (or, in BC-19, two paired branches across two repos) forked from its release/main line at a point in time and was never merged or rebased forward. Real, already-shipped, JIRA-tracked feature commits that landed on the release line *after* the fork are consequently just missing from the J25 line — not stubbed, not reimplemented differently, simply absent. Both gaps are invisible by construction: nothing references a now-nonexistent symbol, so the build stays green, and the regression tests that would have caught the gap were never forward-ported either, so CI has always been green with respect to the missing feature. In both cases the fix is a git operation against specific, already-identified commits, not a code change — which is why this cluster routes to release management rather than to a component owner.

#### BC-15 — core-domain forked from stale base, missing schema fields

**Verdict: stale-base-remediation | Confidence: high** (independently re-verified as CONFIRMED, with two already-live production regressions found, not just a schema diff — see caveats below for write-up-only corrections)

`cpp-platform-core-domain`'s J25 lines (`java-25-wildfly-40-upgrade-spike` and `release/25.104.x`, content-identical on every path examined) forked from `main` at merge-base `912985986e` (2025-11-25) — the catalogue's stated fork point, `82cd4cad`, turns out to be a later, J25-only commit, not the true merge-base. Since the fork, 4 JIRA-tracked commits (CCT-2357, CHD-1798/CHD-2236, cad-833, DD-42378) landed schema-only changes on the release line, adding 8 fields/refs across 6 schemas plus 2 brand-new schema files. The full repo diff (`release/25.104.x...main --stat`) is exactly 25 files — the 8 schema concepts × 2 source trees, plus 9 version-bump/CI files — confirmed as the complete set. All 6 modified schemas set `additionalProperties:false` at the relevant object root, so every gap fails the same way. This produces two distinct, independently-confirmed behavioural mechanisms: (1) a payload carrying any of the 8 fields validates as `200` against a 17.104.4-based catalog and is **rejected** (400/422, "extraneous key") against the 25.104.x one — confirmed by actually executing schema validation, not just diffing text; (2) contexts consuming these fields via generated Java POJOs simply don't get those types/methods generated on the J25 line, so the feature is silently compiled out with no exception and a still-green test suite — the more dangerous mechanism, since nothing signals the gap. Both mechanisms are already live, not hypothetical: `cpp-context-prosecution-casefile` and `cpp-context-hearing` — the program's own canonical "validated green" reference migrations — both contain real, confirmed regressions from this exact gap.

**Affected surface:** every context depending on `cpp-platform-core-domain` (~40 contexts transitively, via the JSON-schema catalog and/or generated POJOs) inherits this the moment `coredomain.version` moves off the 17.104.x line onto any `25.104.0-M1..M4` build.
- `cpp-context-prosecution-casefile` (`main` vs `team/25.104.x`): inactive-migrated-case hearing-request suppression logic, driven by `MigrationCaseStatus.INACTIVE`, is entirely absent on the J25 line — the identical incoming `CcCaseReceived` event produces hearing requests where 17.104.4 suppresses them.
- `cpp-context-hearing`: `DeletedJudicialResultTransformer` is a literal 19-line no-op stub on the J25 line (down from 170 lines on `main`), with the migration author's own comment confirming the coredomain types were removed in 25.104.0-M4; its 9-method test class was hollowed out to keep the method names but delete every assertion, so `deletedJudicialResults` silently never reaches the published `hearing-resulted` event while CI stays green.
- `cpp-context-progression` (still pinned at `coredomain.version=17.103.13`, not yet even on the 17.104.x train) has a **live** native JSONB SQL predicate keyed on the same `migrationCaseStatus` field, guaranteeing it hits this gap on its own eventual migration.
- Fleet `$ref` grep (a lower bound only — it misses POJO-only consumers, which is exactly how both confirmed regressions above were found) shows `hearing.json` alone referenced from 11 contexts, led by `progression` (86 refs) and `progression-archived` (82).

**Evidence:**
- `cpp-platform-core-domain`: `git merge-base main release/25.104.x` → `912985986ee3833df135c7c6f30f79cb492f9603`; `git diff release/25.104.x...main --stat` → 25 files exactly, matching the 8-schema-concept accounting.
- `cpp-context-prosecution-casefile`: `prosecutioncasefile-domain-aggregate/.../ProsecutionCaseFile.java:511-518` and `prosecutioncasefile-event-processor/.../CCCaseToProsecutionCaseConverter.java:76-82` on `main` — zero `MigrationCaseStatus` hits anywhere in `team/25.104.x`.
- `cpp-context-hearing`: `hearing-event-processor/.../DeletedJudicialResultTransformer.java` (170 lines on `main`, no-op stub on `team/25.104.x`) and its call site at `hearing-event-processor/.../PublishResultsDelegateV3.java:159-162`, removed entirely on the J25 line.
- `cpp-context-progression`: `progression-viewstore-persistence/.../ProsecutionCaseRepository.java:44` — `CAST(p.payload AS jsonb) -> 'migrationSourceSystem' ->> 'migrationCaseStatus' = 'INACTIVE'`.

**How to verify (decisive check):** Executed, not merely written. A JSON-Schema repro test was added to the real module (left uncommitted). Running it via plain `mvn test` was blocked in-sandbox (enforcer required JDK 25; then `jacoco-maven-plugin`/`junit-platform-launcher` couldn't resolve from the internal Artifactory). Worked around by compiling the same load/strip/validate logic standalone against the module's real resolved classpath (`org.everit.json.schema` 1.6.0) and running it directly against the real `person.json` on disk. Actual output:
```
[CHECK 2] Validating {"isAddressConfidential": true} against THIS BRANCH's person.json: REJECTED (extraneous key [isAddressConfidential] is not permitted)
[CHECK 3] Same payload with the field restored (main/17.104.x shape): ACCEPTED
RESULT: CONFIRMED
```
Independently re-executed with a freshly-written, separately-authored verification runner during re-verification, producing matching output.

**How to avoid/mitigate:** Cherry-pick the 4 confirmed schema-only commits from `origin/release/17.104.x` — `ad7c0afc`, `1f3263e9`+`f92312fc`, `1b2db5e0`, `e5752bb0`, in that order — onto `release/25.104.x`. Each touches only schema files the J25 line has never modified (confirmed: 43 intervening J25 commits, none touch these files), so conflict risk is effectively zero. Target `origin/release/17.104.x`, not `main` — it's the branch that actually ships the `coredomain.version` artifacts the fleet consumes; a full rebase onto its tip is the cleaner alternative given the two lines' schema trees are already byte-identical. Durably: add a CI gate that fails when a long-lived branch's merge-base with its release line is older than that line's newest tag; add a schema-catalog key-set regression test in `criminal-court-public-model` (the added repro test is a working template); flag the "hollowed-out test" pattern found in `cpp-context-hearing` as a mandatory migration-review checklist item; and block `cpp-context-progression` (or any context using these 8 fields) from bumping `coredomain.version` into `25.104.0-M1..M4` until the cherry-pick lands.

**Verification caveats:** the verdict was not downgraded — every load-bearing claim (merge-base, the 25-file diff, all commit hashes, all schema line-number citations, both production regressions) was independently re-derived and held up, if anything slightly *understating* the `cpp-context-hearing` regression's severity. The independent re-check did find write-up-only errors worth flagging: the fork-point "correction" cites the wrong pair of git-log commands as proof of its "20 not 15" count (a path-filtered run actually returns 6; only the unfiltered form returns 20 — the underlying conclusion still holds, the cited proof doesn't); a file-count arithmetic slip (stated 8×2+8=24 against a correct, separately-confirmed 25 — the real non-schema count is 9, not 8); and a genuine shell bug in the sample CI-gate snippet (`A || B && C` operator precedence causes an unconditional `exit 1`, even on the non-stale path — needs an explicit `if`/`{ }` guard). None of these affect the confirmed regressions or the remediation plan.

**Update 2026-07-08 (re-run): still outstanding.** `cpp-platform-core-domain` advanced `be1320a58`→`8a06af822` (now 25.104.0-M7-SNAPSHOT) with version bumps + changelog only — zero schema files changed. All 6 missing fields (and the 2 missing schemas) remain absent at HEAD; the 4 cherry-pick commits above remain valid, cleanly applicable targets. No remediation.

#### BC-19 — SJP agent-prosecutor access silently regressed grant→deny

**Verdict: stale-base-remediation — CONFIRMED by independent re-verification | Confidence: high** (core finding solid; 4 secondary citation inaccuracies found and corrected — see caveats below)

CCT-2473 ("agent prosecutor authority access") is a real feature spanning two repos, landed the same day (2026-04-24) by the same author — and the J25 line of **both** repos forked away before it landed and never merged forward since. In `cpp-platform-libraries`, commit `76a7ecbdd7` adds an `agentProsecutorAuthorityAccess` field/getter and a third OR-clause to `ProsecutingAuthorityAccess.hasAccess()`; its J25 fork point (`51907bd6c`, 2025-12-16) predates the feature by **over 4 months**, and the spike branch has been static since 2026-06-04. In `cpp-context-users-groups`, commit `6accd837a0` adds the matching field to `UserToJsonUtils`, emitted only when a user is linked to 2+ distinct prosecuting authorities (the agent case); its J25 fork point (`97fc0244e4`, 2026-04-02) predates the feature by 22 days, and unlike the platform-libraries side, `team/25.104.x` here is still actively committed (tip 2026-07-02) — a live branch that has simply never merged `main` forward. Net effect on the J25 lines today: the producer never computes or sends the field, and the consumer never reads it even if sent — a full silent round-trip no-op, with zero compile errors (the J25 call sites just use one fewer constructor argument, not a missing symbol) and zero failing tests (the covering test was never forward-ported, so nothing is red — it never existed on this line). Because `hasAccess()` is a pure, side-effect-free 3-clause boolean expression, this is a clean, deterministic flip: for the released test's own fixture, it returns `true` (GRANT) on the released code and `false` (DENY) on the current J25 code for the identical input — no crash, just a wrong answer. In production this trips a Drools `eval()` access-control rule, surfacing as a 403/empty result set on an action that used to succeed.

**Affected surface:** a fresh fleet grep confirms exactly 3 contexts touch this code at all.
- `cpp-context-sjp` — the feature's actual intended beneficiary, and the confirmed end-to-end call chain (below). 8 production `.java` files + 10 test files (including 1 IT) reference `ProsecutingAuthorityAccess`/`ProsecutingAuthorityProvider` — corrected from the write-up's internally inconsistent "9 production/9 test" claim. SJP itself pins a legacy, unrelated version (`access-control-sjp-providers 4.1.4-ATCM`, `coredomain 17.103.11`) and has no paired J25 clone yet, so its own before/after can't be diffed directly — but its source is unconditionally wired to the regressed method, so it inherits the regression the moment it (or a shared artifact) advances onto the 25.104.x `cpp-platform-libraries` line.
- `cpp-context-users-groups` — the producer (4 hits). Notably this is also the fleet's flagship "96/96 ITs green" reference context named in `j25/CLAUDE.md`; that green suite does not exercise this feature at all on the J25 side, since the covering tests are `main`-only.
- `cpp-context-mi-reportdata` — 2 files, confirmed **not actually at risk**: its `ProsecutingAuthorityUtils.getProsecutingAuthority()` only reads `.getProsecutingAuthority()`, never calls `.hasAccess()` or `.getAgentProsecutorAuthorityAccess()`.

No other of the 40+ production contexts references any of these symbols.

**Evidence:**
- Fork-point dating: `cpp-platform-libraries` — `git merge-base origin/release/17.104.x java-25-wildfly-40-upgrade-spike` → `51907bd6cd65dd2e404e284c64d957d1a1419e84` (2025-12-16, 4+ months before the feature); `cpp-context-users-groups` — `git merge-base main team/25.104.x` → `97fc0244e412432869c6daf8807d1666a439b8d5` (2026-04-02, 22 days before).
- `access-control-sjp-providers/.../ProsecutingAuthorityAccess.java`: feature commit `76a7ecbdd79365efc555e6ea36ea848c17074043` (adds the field + 3rd `hasAccess()` clause) vs. the current J25-line file, confirmed still the pre-feature 38-line, single-arg-only shape.
- Causal chain: `sjp-query-api/.../accesscontrol/query-access-control.drl:51,61,71,81` (4× `eval(sjpProvider.hasProsecutingAuthorityToCase($action))`) → `access-control-sjp-providers/.../SjpProvider.java:40-54` → `ProsecutingAuthorityProvider` → the regressed `ProsecutingAuthorityAccess.hasAccess()`.
- `cpp-context-users-groups`: feature commit `6accd837a0e45b29e9e577b307f9e81c11fba38c`; `usersgroups-query-view/.../UserToJsonUtils.java` — the `size() > 1` gate deciding when `agentProsecutorAuthorityAccess` is emitted at all, confirmed absent from `team/25.104.x`.

**How to verify (decisive check):** A real JUnit test (`shouldReturnTrueIfUserHasSingleProsecutingAuthorityAccessForOtherButAgentHasAccess`, copied from the released test) was added to `access-control-sjp-providers/.../ProsecutingAuthorityProviderTest.java` and left uncommitted — **written but not executed**: `mvn -pl access-control-parent/access-control-providers/access-control-sjp-providers -Dtest=ProsecutingAuthorityProviderTest test` fails to resolve `access-control-drools`/`system-users-library:25.104.0-M6-SNAPSHOT` and `jacoco-maven-plugin` from the internal Artifactory, unreachable from this sandbox — re-run independently during verification and reproduced the identical failure both times, confirming it's a stable environment gap, not a flake. In its place, the decisive check actually performed is a manual trace of the pure `hasAccess()` function using the released test's own fixture values (`prosecutingAuthority="ANOTHER_TEST"`, `agentList=["PROC1"]`, checked `"PROC1"`), reading the real source at both refs: released code hits the 3rd OR-clause and returns `true` (GRANT); current J25 code has no 3rd clause and returns `false` (DENY) for the identical input. Because the function is pure and side-effect-free, reading the quoted source is equivalent to executing it — this trace was independently reproduced from raw file contents during re-verification (not merely re-read from the report) and matched bit-for-bit.

**How to avoid/mitigate:** Cherry-pick `76a7ecbdd79365efc555e6ea36ea848c17074043` — **not** the near-duplicate `10a3eedb5`, which is the same patch landed earlier on the unrelated `release/17.103.x` train — onto `java-25-wildfly-40-upgrade-spike` (forward-port to `release/25.104.x`); note the commit actually touches 5 files, not 4 (see caveats), and its `pom.xml` hunk (a `referencedata.version` bump now stale against the J25 line's current value) should be dropped rather than applied. Separately, cherry-pick `-n 6accd837a0e45b29e9e577b307f9e81c11fba38c` onto `cpp-context-users-groups`'s `team/25.104.x`, keeping only the 4 substantive hunks (`UserToJsonUtils.java`, `UserToJsonUtilsTest.java`, `user-details-schema.json`, `user-details.json`) and resolving `pom.xml`/`runIntegrationTests.sh` against 3 months of independent churn on that branch. Land the `cpp-platform-libraries` side first or together — porting only the producer would send the field into a consumer that still silently ignores it. Durably, this shares BC-15's exact root cause, so one fleet-wide check covers both: alert when a J25 branch's merge-base with its release/main line drifts more than ~10 commits or 30 days behind without a re-integration — both `cpp-platform-libraries` (4+ months stale) and `cpp-context-users-groups` (live but unmerged for 3 months) already breach any sane threshold today. Keep the added test permanently in the J25 suite once the pick lands — its total prior absence (never-existed, not deleted-and-red) is exactly what let this regression through the original migration and the "96/96 green" validation unnoticed.

**Verification caveats:** the verdict was not downgraded — the full causal chain, both fork points, both feature commits, the current regressed code shape, and the grant→deny flip itself all independently re-derived to the same result. Four secondary inaccuracies were found and corrected, none affecting the verdict: (1) "`76a7ecb` touches exactly 4 files, no build files" is wrong — it's 5, including the stale `pom.xml` hunk noted above; (2) the claim that the old 1-arg `of(String)` factory "becomes private" is wrong — it was removed and replaced outright (no orphaned callers, confirmed separately); (3) the SJP blast-radius count ("9 production/9 test files") is internally inconsistent with its own file list and doesn't match a fresh recount (8 production/10 test) — this specific figure appears carried over from the recon document rather than freshly computed, despite the write-up's claim that everything was independently re-derived this session; (4) the description of `cpp-platform-libraries`'s local `main` as "a disconnected single-commit squash" is wrong — it's an ordinary 100-commit history — though the conclusion drawn from it (don't use local `main` as a stand-in for the released baseline) remains correct. Two of the four (#3, #4) trace verbatim to the recon document, which the re-verifier flags as a general caution about "freshly re-derived" claims in entries sharing that recon source.

**Update 2026-07-08 (re-run): partially remediated.** The **consumer** side is now restored in `cpp-platform-libraries` 25.104.0-M7 (commit 5f6e0925, "Restore agent prosecutor authority access (CIMD-3294)") — a re-author, not a cherry-pick of `76a7ecb`. The **producer** side is unchanged: `cpp-context-users-groups` `team/25.104.x` still lacks `6accd837`, so the grant→deny regression is still live end-to-end. The re-run also found two new, untested defects in the restore that make the producer-cherry-pick step above unsafe as written: (1) it parses `agentProsecutorAuthorityAccess` as an array of JSON objects while every known producer emits plain strings — naively forward-porting `6accd837` would trade today's silent deny for an uncaught `ClassCastException`; (2) `of(String,List)` mutates the shared static `NONE`/`ALL` singletons (cross-request race). Fix the parsing (revert to `JsonString`) and the singleton mutation before porting the producer. See investigations/BC-19.md (dated section).


### Long tail

The last two entries in the ranked candidate list (#22, #23) are grouped here as the "long tail": both are solo (unclustered), low-priority, routine third-party dependency bumps pulled in by the J17→J25 BOM refresh rather than by anything JDK-25-specific — Apache Tika in `cp-file-service` and Quartz Scheduler in `cpp-context-system-scheduling`. Each has exactly one internal consumer, giving both a narrow, well-bounded blast radius. They also share a methodological ceiling: this sandbox's Maven cannot resolve dependencies against remote Artifactory, so neither investigation could execute the regression tests it identified as decisive — both verdicts rest on static code inspection plus reasoning about the upstream library change, not an observed test run under JDK 25.

#### BC-22 — Apache Tika 1.28→3.3 MIME-detection drift
**Verdict: inconclusive | Confidence: medium** (verifier confirmed this verdict as written — no change)

Tika jumped two major versions (1.28.3 → 3.3.0) in the dependency BOM, a bump the CHANGELOG lists with no behavioural migration notes. Phase-1 findings flag known heuristic changes across that span (OOXML/zip container detection, text/charset detection, renamed media types). The one internal consumer, `ContentTypeDetector`, is byte-identical between J17 and J25 branches apart from a `javax.ws.rs` → `jakarta.ws.rs` import rewrite, so if behaviour changed at all, it changed entirely inside Tika, not in framework code calling it. This is why the verdict is "inconclusive" rather than "confirmed": the drift is plausible and the mechanism is real, but nobody has actually fed representative files through both Tika versions to see whether classifications diverge.

**Affected surface:** Single consumer chain — `cp-file-service` (`file-service-utils` → `file-service-persistence`) — used by any context that stores binaries through file-service. Impact is confined to the persisted `mediaType` metadata string; the stored binary content itself is unaffected, and a fallback to `application/octet-stream` exists if detection fails outright.

**Evidence:**
- `cp-maven-common-bom/pom.xml:131` sets `apache.tika.version` to `3.3.0`, vs. `maven-common-bom/pom.xml:31` at `1.28.3` on the J17 baseline — confirms the two-major-version jump.
- `cp-file-service/file-service-utils/src/main/java/uk/gov/justice/fileservice/common/file/ContentTypeDetector.java` — verified byte-identical to the J17 version except the jakarta import; detection API usage (`TikaConfig.getDetector()`, `detector.detect(...)`) unchanged.
- `cp-file-service/file-service/file-service-persistence/src/main/java/uk/gov/justice/services/fileservice/repository/MetadataUpdater.java:58-64` — the single call site that persists the detected type as `"mediaType": "type/subtype"`.
- `cp-file-service/file-service/file-service-it/.../FileServiceIT.java:126` — existing regression assertion (`mediaType == "image/jpeg"`) that would catch drift for at least one format.

**How to verify (decisive check):** `mvn -pl file-service-it verify` in `cp-file-service/file-service` (with `ContentTypeDetectorTest`, covering JPEG/PDF/PNG/ZIP/CSV, as the unit-level companion). **Not executed** — Maven cannot resolve remote artifacts in this session, so this remains a written-but-unrun repro; only the static byte-diff of `ContentTypeDetector` and the existing test's assertions were inspected.

**How to avoid/mitigate:** Run `ContentTypeDetectorTest` + `FileServiceIT` at the next opportunity a real JDK-25 build is available, with explicit attention to the CSV and ZIP/OOXML cases (the two formats Tika 1.x→3.x heuristic changes most plausibly affect); treat `mediaType` as informational rather than an exact-match contract downstream (prefer `startsWith("text/")`-style checks over equality); update the test's expected values only if a genuine, confirmed drift is observed.

#### BC-23 — Quartz 2.3→2.5 JobDataMap deserialization hardening
**Verdict: refuted | Confidence: medium** — **downgraded from the investigator's originally claimed HIGH confidence during verification**

Quartz was upgraded 2.3.2 → 2.5.0 specifically to fix CVE-2023-39017 (unsafe `JobDataMap` deserialization, CVSS 9.8), which the fix addresses by adding a class allowlist (`ObjectStreamClassFilter`). The behavioural concern was that the new allowlist could reject data types the framework was previously relying on. It doesn't apply here: `CppJobScheduler` puts exactly one value into `JobDataMap` — a JSON string (`parameters.put("jobDetails", jobDetails.toString())`) — and `CppGenericJob` reads it back with `getString(...)`. No custom objects ever cross the deserialization boundary, so the new allowlist has nothing to reject. The verdict is "refuted" (the hypothesized risk doesn't materialize), but the verifier flagged a real gap: the claim that "Quartz 2.5.0's allowlist includes `java.lang.String` by default" is asserted, not cited to Quartz source or release notes, and the unit test written to demonstrate safety uses plain Java `ObjectInputStream`/`ObjectOutputStream` — it never actually invokes Quartz's `ObjectStreamClassFilter`, so it cannot validate the one assumption the whole verdict depends on. That gap is why confidence was pulled down to medium rather than left at the investigator's claimed high.

**Affected surface:** Single context — `cpp-context-system-scheduling` is the only one of the 40+ `cpp-context-*` repos that uses Quartz at all (confirmed by a grep across all `cpp-context-*/pom.xml` for "quartz", which returned matches only in the two `systemscheduling-*` poms).

**Evidence:**
- `systemscheduling-event/systemscheduling-event-processor/src/main/java/uk/gov/moj/cpp/system/scheduling/event/processor/scheduler/CppJobScheduler.java:64-65` — `parameters.put("jobDetails", jobDetails.toString())`, the only value ever placed in `JobDataMap`, and it's a `String`.
- `.../scheduler/CppGenericJob.java:18` — `jobDataMap.getString("jobDetails")` confirms the round trip stays String-typed.
- `systemscheduling-event-processor/src/main/resources/quartz.properties` — no `org.quartz.serializer.jobDataMapAllowedTypes` override present, consistent with relying on Quartz's default allowlist rather than a custom one.
- The investigation's own `QuartzDeserializationTest.java` (created, not committed) — cited as evidence of safety, but the verifier's inspection found it exercises plain JDK serialization, not Quartz's `ObjectStreamClassFilter`, so it does not actually test the claim it was written to support.

**How to verify (decisive check):** `SystemSchedulingIT` (`systemscheduling-integration-test/.../SystemSchedulingIT.java:80-96`) schedules a job, confirms it persists to the `qrtz_*` JDBC tables, and waits for it to fire — a real deserialization-allowlist rejection would surface as a failure at that fire step. **Not executed**: `cpp-context-system-scheduling` isn't cloned in a state usable for a live IT run in this session, and the self-authored `QuartzDeserializationTest` — while it compiles — was never run either (blocked by Maven artifact resolution) and, per the verifier, would not have settled the question even if it had run, since it bypasses Quartz's real filter.

**How to avoid/mitigate:** No code change needed for current usage — String-only `JobDataMap` entries are safe under Quartz 2.5.0's default allowlist. Before treating this as fully closed, either run `SystemSchedulingIT` against a real J25/WildFly-40/Quartz-2.5.0 deployment, or directly inspect Quartz 2.5.0's `ObjectStreamClassFilter` source/release notes to confirm `java.lang.String` is allow-listed by default (neither was done here). If any future change adds non-primitive types to `JobDataMap`, pre-emptively add them to `org.quartz.serializer.jobDataMapAllowedTypes` in `quartz.properties`.

## Recommended next actions

A prioritised punch list, grouped by the kind of action required. Within each group, items are ordered by urgency. Each entry links to its per-entry file for the exact diff, commit hash, or command.

### 1. Immediate code fixes — confirmed live defects

1. **BC-01 — `cpp-context-hearing` NPE/500 on a benign no-match query** ([BC-01.md](investigations/BC-01.md)). In `HearingQueryView.getOutstandingFinesQueryFromDefendantId`, replace the now-dead `catch (NoResultException)` with an explicit `if (defendantSearch == null) return jsonEnvelopeWithoutPayload;`, and fix the masking test `should_send_an_empty_payload_when_defendant_does_not_exists` to `thenReturn(null)`. Live, RAML-routed endpoint (`hearing.defendant.outstanding-fines`).
2. **BC-09 — Activiti atomicity loss on the JobExecutor path** ([BC-09.md](investigations/BC-09.md)). Replace `NoOpPlatformTransactionManager` with a hand-rolled `PlatformTransactionManager` delegating to WildFly's `jakarta.transaction.TransactionManager`; at minimum stop swallowing post-commit failures (`catch (Exception ignored)`). Explicitly forbid copying the NoOp onto `staging-prosecutors-spi` when that context migrates.
3. **BC-10 — Activiti REST null-param 500s and mis-mapped fields** ([BC-10.md](investigations/BC-10.md)). Null-guard the optional filters in `ProcessInstancesResource` and `JobsResource` (**not** `HistoricProcessInstancesResource`, which is already correct); fix the wrong-key serialisation at `ProcessInstancesResource:41`; make `JobsResource` branch on `timersOnly`; validate the `executeJob` body. Audit `cpp.platform.tools.business-data-fixes`' `ActivitiRestClient` — it calls sub-resources that no longer exist — before sjp/prosecution-casefile adopt this line.
4. **BC-11 — JSON-P provider collision** ([BC-11.md](investigations/BC-11.md)). Add the already-proven exclude-`javax.json`/keep-`parsson` fix to `generators-commons/pom.xml` (the one place in the chain still unprotected); retire the 7 leftover `org.glassfish:javax.json` coordinates in hearing/businessprocesses/boxworkmanagement; harden `JsonObjects.findProvider()` (restore the system-property override, catch `ServiceConfigurationError`).
5. **BC-12 — RESTEasy packaging gap** ([BC-12.md](investigations/BC-12.md)). Move `cpp-platform-azure-utils`' `resteasy-client` dependency to `<scope>provided</scope>`, and restore the three dropped transitive exclusions on `cp-maven-common-bom`'s `resteasy-multipart-provider`. (The broad-glob `packagingExcludes` change is the CI-gate item in §2.)
6. **BC-13 — uncaught `JSONException` → HTTP 500** ([BC-13.md](investigations/BC-13.md)). Wrap the `extractPayloadFrom` call with `catch (JSONException) → JsonSchemaValidationException` in `FileBasedJsonSchemaValidator` and `SchemaCatalogAwareJsonSchemaValidator` (and in `JsonDocumentValidator` and the worst offender `SchemaValidatorUtil`).
7. **BC-04 — Hibernate 6 primitive-null 500s** ([BC-04.md](investigations/BC-04.md)). Fix the two confirmed *unfixed* instances: `prosecution-casefile` `OffenceLegacy.orderIndex` and `hearing` `HearingEvent.alterable`. Use a boxed type + null-safe getter for plain `@Column` primitives; use a Liquibase backfill-and-default changeset for `@Version` fields (template: `038-fix-null-persistence-version.xml`) — do not widen `@Version`.
8. **BC-17 — silently-dropped error rows** ([BC-17.md](investigations/BC-17.md)). Fix the pre-existing unguarded `StreamErrorConverter.asStreamError()` `stackTraceElements().get(0)` → `.stream().findFirst().orElseGet(...)` so a frame-less exception can't throw `IndexOutOfBoundsException` and drop the whole `stream_error` row.
9. **BC-18 — JMS test-utils silently mis-targets the broker** ([BC-18.md](investigations/BC-18.md)). Replace `setBrokerURL()` mutation with `new ActiveMQConnectionFactory(queueUri)` in all three helper classes, and swap the deleted `verify(...).setBrokerURL(...)` assertion for a real `factory.toURI()` check.
10. **BC-08 — dormant `cpp-context-listing` map-key bug** ([BC-08.md](investigations/BC-08.md)). Backport now (J17-safe, independent of migration timing): re-key `Hearing.mergeHearingDaySequences` by `HearingDay::getStartTime().toInstant()` so hearing-day sequence numbers don't silently reset to 0 once listing migrates to J25.
11. **BC-16 / BC-20 — hygiene / test-infra** ([BC-16.md](investigations/BC-16.md), [BC-20.md](investigations/BC-20.md)). Fix or delete BC-16's two misleading "CDI now manages this" Javadoc comments and keep the 5 added metrics-servlet unit tests. Land BC-20's harness rule-count assertion — summing across `kieContainer.getKieBaseNames()`, **not** the report's own snippet, which is broken for the fleet's normal kbase≠ksession convention — plus the missing `else` branch so `jar:`-packaged `kmodule.xml` is genuinely supported.

### 2. CI gates to add before J25 cutover — prevent (re-)introduction

- **BC-01** — script the migrated-finder check: flag any finder rewritten to `.orElse(null)` whose pre-migration `@Query` lacked both `OPTIONAL` and `max=N` *and* whose caller still holds a `catch (NoResultException)`.
- **BC-04** — static sweep flagging every `@Entity` primitive field whose `addColumn`/`createTable` lacks **both** `defaultValue` and `nullable=false` (checking the entity alone gives false positives for already-protected `@Version` columns).
- **BC-05** — grep gate for a quoted `!=`/`<> null` co-occurring with a JPQL marker (`@Query`/`createQuery`/`@NamedQuery`); plus real H2/Hibernate tests for the two currently mock-only users-groups methods ([BC-05.md](investigations/BC-05.md)).
- **BC-03** — an enforcer/dependency assertion in `cpp-platform-maven-common-bom` that `drools-mvel` stays present and `drools-model-compiler`/`drools-canonical-model` stay absent (the *entire* refutation rests on this holding for future Drools bumps); plus a golden per-`kmodule.xml` guard (0 ERROR + ≥1 rule).
- **BC-12** — change `cpp-platform-maven-service-parent-pom:100`'s enumerated `packagingExcludes` to the broad `WEB-INF/lib/resteasy-*.jar` glob (superset-safe, closes both confirmed gaps and any future artifact), backed by a `maven-enforcer` rule that inspects the built WAR's `WEB-INF/lib/` and fails on any stray `resteasy-*.jar`/`jakarta.ws.rs-api-*.jar`.
- **BC-14** — build-time guard failing on any `beans.xml` that is empty, missing `bean-discovery-mode`, or not `all` (allowlist the 2 known-intentional `annotated` files); and make the wiring annotation-driven (`@ApplicationScoped` on the 10 `*InterceptorChainProvider` classes and the access-control/audit/feature-control/metrics interceptors, including via the two JavaPoet generators) so it stops depending on discovery mode at all. Migrate the 21+12 legacy-namespace descriptors.
- **BC-09** — a fleet regression guard that fails if a `NoOpPlatformTransactionManager` is wired while `jobExecutorActivate=true`.
- **BC-11 / BC-18** — an Enforcer/CI lint banning bare no-arg `new ActiveMQConnectionFactory()` construction fleet-wide; a WARN when more than one JSON-P provider is `ServiceLoader`-visible.
- **BC-13** — land the pinned numeric-literal regression table (`0`, `007`, `01`, `.5`, `10.0`, `1e3`, `12345678901234567890` → named expected outcomes) once at the `core` module.
- **BC-15 / BC-19** — the single highest-leverage durable guard for both stale-base entries: a scheduled check that fails when a J25 branch's merge-base with its release/main line drifts beyond a threshold (~10 commits / 30 days) without re-integration. Both repos breach any sane threshold today.

### 3. Release-management cherry-pick / rebase — no code change

- **BC-15 — restore the 8 missing core-domain schema fields / 2 schemas** ([BC-15.md](investigations/BC-15.md)). Cherry-pick the 4 schema-only commits (`ad7c0afc`, `1f3263e9`+`f92312fc`, `1b2db5e0`, `e5752bb0`) from `origin/release/17.104.x` onto `cpp-platform-core-domain`'s `release/25.104.x`, in that order (or do a full rebase — the schema trees are byte-identical, so conflict risk is ~zero). **Block** `cpp-context-progression` (and any consumer of the 8 fields) from bumping `coredomain.version` into `25.104.0-M1..M4` until this lands.
- **BC-19 — restore the SJP agent-prosecutor grant** ([BC-19.md](investigations/BC-19.md)). Cherry-pick `76a7ecbdd7` onto `cpp-platform-libraries`' J25 line (**drop** the stale `pom.xml` `referencedata.version` hunk; **not** the near-duplicate `10a3eedb5`), and cherry-pick `-n 6accd837a0` onto `cpp-context-users-groups`' `team/25.104.x` (keep only the 4 substantive hunks, resolve `pom.xml`/`runIntegrationTests.sh` against branch churn). **Order matters:** land the `cpp-platform-libraries` side first or together — porting only the producer sends the field into a consumer that still silently ignores it.

### 4. Only fully closeable once a local WildFly 40 image (and/or live J25 Postgres) exists

These verdicts already rest on convergent static + reduced-repro evidence; the decisive *live* check is written and ready, and is blocked only by the environment gaps in caveat (a).

- **BC-12 — check this first.** Whether `prosecutioncasefile-query-api` retains *any* JAX-RS runtime path at all: the new fleet-wide `packagingExcludes` strips its bundled RESTEasy out of `WEB-INF/lib` **and** its pre-existing `jboss-deployment-structure.xml` disables WildFly's own `jaxrs` subsystem. If both hold, this is a deploy-breaking regression, not an edge case — higher priority than the multipart-charset repro. ([BC-12.md](investigations/BC-12.md))
- **BC-14** — grep a WF40 boot log for `"Found interceptor chain provider"` and assert a non-zero count per component type. This single observation is what lifts BC-14 from medium to high confidence.
- **BC-09** — deploy prosecution-casefile/sjp, fail a delegate inside a `<timerEventDefinition>` job, and assert leftover partial `ACT_RU_*` state plus a phantom emitted event.
- **BC-10** — live `curl` the 3 retained endpoints with omitted params (expect 500 on process-instances and jobs).
- **BC-16 / BC-17** — an HTTP round-trip asserting the 404 contract on the three gutted metrics paths (plus a companion cake-shop IT); a side-by-side WF26-vs-WF40 run of `StreamErrorHandlingIT`.
- **BC-24 / BC-13** — run the event-store/viewstore IT suites against live Postgres 15 + pgjdbc 42.7.7; exercise the real compiled `PayloadExtractor`/validator classes.
- **BC-22 / BC-23** — `ContentTypeDetectorTest` + `FileServiceIT` (Tika) and `SystemSchedulingIT` (Quartz) under a real JDK-25 build; both also need Artifactory access, so they close together with the group below.
- **Also blocked only by Artifactory access (caveat a, Wall 1)** — run the already-written repros for **BC-01, BC-02, BC-04, BC-05, BC-06, BC-11, BC-18, BC-21** to green/red, and commit the strongest ones as permanent regression guards. Several currently exist only as uncommitted working-tree files (e.g. the BC-01 `HearingQueryTest` method, the BC-19 `ProsecutingAuthorityProviderTest` method) and will vanish on a workspace reset — capturing them is itself a to-do.

---

## Appendix A — Original discovery synthesis (verbatim)

Reproduced from [`j25-behavioural-change-candidates.md`](j25-behavioural-change-candidates.md) for context — this is the pre-investigation hypothesis that the 24 entries were drawn from. The investigation broadly upheld its thesis (risk in persistence + access control), while refuting its single highest-impact prediction (Drools allow/deny flips).

> The discovery found that J17→J25 behavioural risk concentrates in two places: (1) the persistence layer, where the stack-wide removal of DeltaSpike forced every single-result "finder" to be hand-rewritten and Hibernate jumped 5→6, and (2) access control, where Drools jumped three majors (7→10) and CDI/Weld moved to 4.1/6. The confirmed anchor case (usersgroups laaContractNumber returning 404 instead of 200-empty) is not a framework bug at all — the REST/enveloper code is byte-identical J17 vs J25 — but a migration-pattern contract drift: the rewritten call site returns null on no-result where the old DeltaSpike @Query threw NoResultException, and the same shape recurs across 513 DeltaSpike classes in 31 of 44 contexts (BC-01), plus its inverse where getSingleResult() now throws 500 (BC-02). Hibernate 6 adds three silent/loud stack regressions on the same data (primitive-null 500s BC-04, `!= null` empty result sets BC-05, LazyInit 500s BC-06). The highest-impact single item is Drools 10 + MVEL 2.5 recompiling every context's access-control rulebase (BC-03, critical, ~40 contexts, fail-closed but flippable), compounded by a test harness that can silently load zero rules (BC-20) and a CDI discovery-mode/legacy-namespace hazard that could disable interceptor chains entirely (BC-14). Confirmed-and-cheap wins float up: the Liquibase 4→5 deploy blocker (BC-07), the framework-wide ZonedDateTime 'Z' representation change (BC-08), and the gutted /internal/metrics endpoints (BC-16). Two entries (BC-15, BC-19) are stale-base issues remediated by rebase/cherry-pick rather than code fixes but produce the same identical-payload-different-outcome symptom. The remaining entries are library semantic drifts on the JSON (BC-11, BC-13), REST (BC-12), messaging (BC-17, BC-18), and codegen (BC-21) paths, tailing into lower-probability stack bumps (Tika, Quartz, pgjdbc). The dominant reassurance is that the framework's own request-processing code is behaviourally unchanged — nearly every risk is either a library/server default shift with no context code change (stack-change) or a subtly different contract in code rewritten during the migration (migration-pattern), which is precisely why none of it fails at compile time.

## Appendix B — Version delta (J17 → J25)

The library/platform version moves behind every entry, reproduced from [`j25-behavioural-change-candidates.md`](j25-behavioural-change-candidates.md).

| Component | From | To | Defined in / notes |
|---|---|---|---|
| JDK | 17 | 25 | all repos; OSS chain compiles to bytecode 21, CPP platform chain to 25, everything runs on JDK 25 |
| WildFly | 26.1.2.Final | 40.0.0.Final | cp-maven-common-bom; docker infra still on 26.1.3 image (no WF40 template authored yet) |
| Jakarta EE | EE8 (javax:javaee-api 8.0.1) | EE10 (framework) / EE11 (jakartaee-api 11.0.0, platform) | cpp-platform-maven-parent-pom (java.ee.version 10→11) |
| RESTEasy | 3.15.5 / resteasy-client 4.7.7 | 7.0.0 (BOM); WildFly-40 runtime module = 6.2.15 | cp-maven-common-bom; platform jboss.resteasy 4.3.0→6.2.15; WARs now exclude bundled resteasy jars |
| Servlet | 4.0.1 | 6.1.0 | cp-maven-common-bom |
| CDI API / Weld | CDI 1.2 / Weld 3.1.4.Final | CDI 4.1.0 / Weld 6.0.0.Final | cp-maven-common-bom |
| Hibernate ORM | 5.4.24.Final (org.hibernate) | 6.6.1.Final (org.hibernate.orm); WF40 runtime 6.6.25 | cp-maven-common-bom (scope provided) |
| DeltaSpike | 1.9.6 | **removed entirely** | cp-maven-common-bom (8 artifacts); persistence-deltaspike module dropped from cp-microservice-framework reactor + BOM |
| Jakarta Persistence API | javax 2.2 | jakarta 3.1.0 (framework) / 3.2.0 | cp-maven-common-bom / EE target |
| Jackson (core/databind) | 2.12.7 / 2.12.7.1 | 2.21.4 | cp-maven-common-bom; jackson-dataformat-yaml intentionally pinned back at 2.14.3 |
| JSON-P impl | glassfish javax.json 1.1.4 | Eclipse Parsson 1.1.7 (+ glassfish jakarta.json 2.0.1, johnzon 2.0.2 co-managed) | cp-framework-libraries M5 / cp-maven-common-bom |
| Liquibase | 4.10.0 | 5.0.3 (liquibase-commercial dropped) | cp-maven-parent-pom / cp-maven-framework-parent-pom; jobstore-liquibase was ahead at 4.27.0 |
| Drools / KIE | 7.69.0.Final | 10.1.0 | cpp-platform-maven-common-bom (undocumented in CHANGELOG) |
| MVEL | 2.4.13.Final | 2.5.2.Final | cpp-platform-maven-common-bom + cp-maven-common-bom |
| Artemis client | org.apache.activemq artemis-* 2.24.0 | org.apache.artemis artemis-jakarta-client 2.53.0 | cp-maven-common-bom; WF40 embeds ~2.53/2.54 |
| OpenEJB (embedded test) | 8.0.13 | 10.1.4 | cp-maven-common-bom |
| byte-buddy | 1.12.22 | 1.18.8 (+ `-Dnet.bytebuddy.experimental=true` on surefire/failsafe) | cp-maven-common-bom |
| Mockito | 5.3.1 | 5.23.0 | cp-maven-common-bom |
| PostgreSQL JDBC | 42.3.2 | 42.7.7 | cp-maven-parent-pom (CVE-2022-31197, CVE-2024-1597) |
| org.json | 20231013 | 20251224 | cp-maven-common-bom (feeds everit validator) |
| everit-json-schema | 1.3.0 (platform) / 1.6.0 | 1.6.0 (single source, groupId com.github.everit-org.json-schema) | pin moved to cp-maven-common-bom |
| reflections | 0.9.10 | 0.10.2 | cp-maven-common-bom (default Scanners + SubTypes store changed) |
| Tika | 1.28.3 | 3.3.0 | cp-maven-common-bom (consumer cp-file-service) |
| Quartz | 2.3.2 | 2.5.0 | cpp-platform-maven-common-bom (CVE-2023-39017, JobDataMap hardening) |
| netty (BOM) | 4.1.77.Final | 4.1.119.Final | cpp-platform-maven-common-bom (Azure SDKs left pinned — transport-incompat risk) |
| Elasticsearch | 7.17.23 | 7.17.23 (unchanged) | cpp-platform-maven-common-bom |
| snakeyaml | 1.33 | 1.33 app / 2.3 relocated in liquibase-runner fat jar | two-tier split, cp-maven-parent-pom / framework-parent-pom |
| jandex | (none) | 3.1.6 (new jandex-index profile for WF40 CDI scanning) | cp-maven-framework-parent-pom |
| JAXB API | javax jaxb-api 2.3.1 | jakarta.xml.bind-api 4.0.0 runtime; 2.3.2 pinned for RAML codegen plugins ("DO NOT upgrade") | cpp-platform-maven-parent-pom / service-parent-pom |
| jacoco | 0.8.8 | 0.8.12 (parent) — insufficient for JDK 25 bytecode; local overrides to 0.8.14 | cpp-platform-maven-parent-pom |
| CI agent pool | centos8-j17-postgres | ubuntu-j25-postgres | all azure-pipelines.yaml (OS distro + JDK swap together) |

## Fleet-wide Usage Inventory (full ~/idea/ sweep)

Every entry above scoped its "affected surface" to whatever the investigator actually diffed — typically the eight local J17↔J25 context pairs plus, for the multi-member clusters, a recon sweep of `~/idea/cpp-context-*`. This section widens each entry's affected-surface analysis from that investigated scope to **literally every repository under `~/idea/`** — the production `cpp-context-*` fleet, the `~/idea/framework/` J17 baseline copy, the `~/idea/framework/j25/` J25 clones, the assorted `cpp.*`/`cpp-platform-*`/`cpp-mbd-*` utility repos, and the stale/nested checkouts under `old/`, `features/`, and `defence-investigation/` that a top-level name-glob structurally never reaches — so a reader can see at a glance the true, complete blast radius if a fix for a given entry were rolled out fleet-wide. Each pane condenses that entry's full sweep file (`investigations/_usage-sweep/BC-NN.md`), grouped by context as originally written. A recurring cross-cutting finding: counts run larger than the investigations reported, chiefly because dot-named contexts (`cpp.context.*`), the whole J17 `framework/` tree, and nested duplicate clones were outside every original sweep's glob — but in no case does the wider scope overturn a verdict.

**Re-run note (2026-07-08):** as of `cp-microservice-framework` 25.104.0-M3, the `persistence-deltaspike` module has been relocated to a new `persistence-jpa` module (its 4 non-DeltaSpike classes moved verbatim; DeltaSpike itself stays removed). Framework-side module paths shown in the panes below therefore reflect the **pre-M3** layout — read `persistence/persistence-deltaspike/...` as `persistence/persistence-jpa/...` where it names those four classes.

### Persistence / DeltaSpike + Hibernate 6

<details>
<summary><strong>BC-01</strong> - DeltaSpike→JPA no-result null propagates to HTTP 404/500 (60 checkouts)</summary>

Signal: `org.apache.deltaspike.data.api` usage and/or a `catch(...NoResultException)` call site, in main code. **60 checkouts** fleet-wide carry ≥1 signal (16 new beyond BC-01.md's 44); **14 show the full both-signal risk shape** (11 known + 3 new).

- DeltaSpike usage reconfirmed at **40 of 55** top-level `cpp-context-*` (calibration reproduced BC-01.md's figure exactly). Heaviest owners: `reference-data` (165 files), `mi-reportdata` (97), `mi-systemdata` (25), `progression` (44). `catch(NoResultException)` concentrated in `progression` (20 sites), `reference-data` (5), `defence` (4), `hearing` (3) — the named 8-family risk list (`defence`, `hearing`, `listing`, `progression`, `prosecution-casefile`, `reference-data`, `sjp`, `users-groups`) reconfirmed byte-for-byte.
- New: **`framework/cake-shop`** uses DeltaSpike (5 repos) yet its J25 twin `cp-cake-shop` is the *one* genuinely clean migrated repo (zero DeltaSpike, zero `NoResultException` even in tests). New both-signal stale dupes: `old/cpp-context-sjp` (9 catch sites, a 2017 snapshot), `old/cpp-context-users-groups`, `defence-investigation/cpp-context-defence`.
- Framework/platform library repos (`cp-microservice-framework`, `cp-event-store`, `cp-framework-libraries`, `cpp-platform-*`) show **zero** BC-01 signal in all three checkout locations — every real exposure lives in downstream per-context code, exactly why the BOM change compiled clean. `users-groups` `UserRepository` is a second instance of the `@MaxResults`-without-`OPTIONAL` "third shape". The five catch-having families with no J25 checkout remain undiffable from this workspace even after the whole-tree walk.

</details>

<details>
<summary><strong>BC-02</strong> - DeltaSpike→JPA inverse: getSingleResult() throws → HTTP 500 (13 checkouts)</summary>

Signal: unguarded `.getSingleResult()` (no `NoResultException`/`NonUniqueResultException` catch at the call site). **13 checkouts** (10 new beyond BC-02.md's 3); 68 call sites across 48 files (63,104 java files scanned). The 3 known J25 checkouts reconfirmed exactly (16/4/1 counts).

- Most new hits are structurally-immune `COUNT`/aggregate/`nextval()` calls that bypass DeltaSpike's method layer and are already live today: `mi-reportdata` (14 COUNT sites), `mi-systemdata`, `progression`, `listing-courtscheduler`, `staging-dcs`, `cpp.context.staging.darts` (a Postgres sequence pull), top-level `prosecution-casefile`.
- Genuinely live **non-count** finders with an open multi-match risk: `reference-data` `DirectionRepository.findByDirectionId` and `NowsMetadataRepository.findByNowMetaDataId` — `directionId`/`metaDataId` are non-`@Id` columns, so `NonUniqueResultException` is structurally possible; callers catch `NoResultException` one layer up but never the multi-match — the same open shape BC-02 flagged for `CaseDetailsRepository`/`FeaturePermissionRepository`.
- Conspicuous absences: `sjp` (which *hosts* the fleet's correct both-catch template) and `defence` have zero raw `.getSingleResult()` — still DeltaSpike-managed, will only surface on their own migration, at which point BC-02's `OPTIONAL`-risk table becomes the map. `old/spring-framework` hits excluded as vendored non-CPP.

</details>

<details>
<summary><strong>BC-04</strong> - Hibernate 6 primitive-null PropertyAccessException on legacy rows (36 checkouts)</summary>

Signal: a primitive (unboxed) `long`/`int`/`short`/`boolean` field on an `@Entity`. **36 checkouts**, 257 real hits (after filtering `serialVersionUID` and builder-inner-class noise); 14 logical contexts genuinely new. `@Version`-on-primitive exists in exactly 3 contexts fleet-wide (`mi-reportdata`, `mi-systemdata`, `users-groups`).

- Known instances reconfirmed and expanded on the J25 line: `prosecution-casefile` `OffenceLegacy.orderIndex` still unfixed (`main` + J25); `hearing` `HearingEvent.alterable` + `HearingEventDefinition`/`Defendant.proceedingsConcluded`/`Offence.shadowListed` (15 fields, J25-confirmed unremediated); **`users-groups` J25 still carries 6 unfixed primitives** the two-pattern fix never touched (`Feature.rank`/`.active`, `Permission.active`, `Role.selectable`, `Service.rank`, `UserRolePlacement.home`).
- New contexts: `sjp` and `progression` each carry the same `Offence*.orderIndex` shape as prosecution-casefile; `reference-data` (18), `staging-enforcement` (12), `mi-reportdata`/`mi-systemdata` share a 18-`int` `DateSeriesRd` calendar entity; `system-announcement` holds the sole `short` fleet-wide; also `applications-courtorders`, `defence`, `listing`, `subscriptions`, `resulting`, `staging-bulkscan`, `cpp.context.staging.darts`.
- Most conspicuous: **`framework/cake-shop`** — the framework's own reference/verification impl — carries `Recipe.glutenFree` unremediated on both J17 baseline and J25 spike; a legacy `NULL` `gluten_free` row would fail its own IT suite for exactly the "write path fine, read path untested" reason. `mi-*`'s `@Version` hits are DDL-protected on inspection.

</details>

<details>
<summary><strong>BC-05</strong> - Hibernate 6 JPQL != null → silent empty result sets (3 locations, 1 context)</summary>

Signal: a quoted `!=`/`<>` `null` co-occurring with a JPQL marker in the same file. 194 raw same-line hits fleet-wide narrowed to **exactly 9 files — all the same 4-file `users-groups` bug — across 3 checkouts**. Narrow, already-remediated footprint reconfirmed as the complete total.

- Locations: top-level `cpp-context-users-groups` (`main`, still unfixed); J25 `users-groups` (fixed — the only textual hit is BC-05's own repro test fixture); **NEW `old/cpp-context-users-groups`** — a stale nested checkout, byte-identical unfixed copy, invisible to the `cpp-context-*` glob BC-05's sweep used.
- Every one of the other ~117 candidate files fleet-wide was a plain Java ternary/guard, a logger call, or a code-generator emitting `"if ($L != null)"` as a *string template* (e.g. `jsonschema-pojo-generator`'s `*Generator` classes) — never JPQL, confirmed at file level, not asserted. Zero XML hits. Reinforces BC-05's one-off conclusion and flags that any future glob-based sweep keeps missing `old/`/`defence-investigation/` nested dupes.

</details>

<details>
<summary><strong>BC-06</strong> - Transaction-scope narrowing → LazyInitializationException (10 checkouts)</summary>

Signal: a query-view/service method calling a repository `find*` then walking a `@OneToMany`/`@ManyToMany` LAZY getter, all with no enclosing `@Transactional`. 150 LAZY-resolving associations across 16 on-disk locations; **10 genuine hit-contexts** (4 whole new + new call sites in 2 known). The fleet `cpp-context-*` subtotal reproduces BC-06's cited "84 LAZY associations" figure exactly.

- **4 new contexts**: `sjp` (the largest surface found — `CaseService.java`, 656 lines, zero `@Transactional`, 7 query-view entry points + 3 sibling services all funnelling through one `CaseView` constructor that touches 3 LAZY getters), `listing` (`HearingQueryView` — the `@Handles` dispatch layer itself, REST-reachable), `mi-reportdata` (`CaseDetailService`), `staging-enforcement`.
- New call sites in known contexts: `defence` (+2 classes beyond BC-06's one) and `hearing` — the latter a genuinely **new mechanism variant** in `HearingEventQueryView`: an entity fetched inside an inner `@Transactional` method is then walked for LAZY fields by its *non*-transactional caller after that tx has closed — not covered by either the blanket-`@Transactional` or `LEFT JOIN FETCH` fix as BC-06 writes them. Present on both J17 and J25 (not migration-introduced).
- Framework/platform repos, plus `material`/`businessprocesses`/`prosecution-casefile`, confirmed no-LAZY or latent-only. The `sjp` and `hearing`-variant findings are worth promoting into BC-06.md's "Affected surface"/"How to avoid".

</details>

<details>
<summary><strong>BC-24</strong> - pgjdbc 42.3.2 → 42.7.7 SQLState / temporal edge drift (3 repos)</summary>

Signal: `.getSQLState()` branching + temporal JDBC marshalling. **Zero `.getSQLState()` anywhere fleet-wide** — no error-code keying exists in any repo. 54 temporal-JDBC lines across 3 repos, all standard conversions.

- `cpp.platform.tools.business-data-fixes` (26 `setObject(Timestamp)`, all in test/data-loading, non-production), `framework/j25/cpp-platform-libraries` `DataRepository` (2 `OffsetDateTime` via `atStartOfDay(UTC)` — safe), `event-store` (26+, all normalised via `ZonedDateTimes.fromSqlTimestamp()`).
- **Zero BC-24 patterns across the entire `cpp-context-*` fleet** — contexts either abstract temporal handling via DeltaSpike/Hibernate or rely on the already-verified shared framework JDBC utilities; no bespoke context-level temporal code found. The fleet is well-positioned for the driver bump; no temporal-handling changes required anywhere.

</details>

### Deployment / Liquibase

<details>
<summary><strong>BC-07</strong> - Liquibase 4→5 rejects removed properties, migration job fails (60 locations)</summary>

Signal: a live `liquibase.properties` carrying `liquibase.hub.mode` and/or `liquibase.searchPath` (filesystem content-grep, so untracked files and non-git checkouts are reached — 125 raw lines in 81 files, narrowed to 73 genuine config files at **60 distinct locations**; 49 reconfirm BC-07.md exactly, **11 new**).

- **Most significant new cluster — the `~/idea/framework/` J17 baseline chain (4 repos, 8 files), never previously swept**: `cp-file-service`, `event-store` (5 files), `framework-libraries` `jobstore-liquibase` (the module behind the real K8s 900s-timeout incident), `microservice-framework` `framework-system-liquibase`. This includes **the only live `liquibase.searchPath:  CLI` hit found anywhere in the entire tree** (`event-store/event-buffer/event-buffer-liquibase/src/main/resources/liquibase.properties:7`) — the half of BC-07 that Liquibase 5.x turns into a startup `FileNotFoundException`, reported as zero everywhere else. All 5 fix commits BC-07.md cites exist **only** in the `framework/j25/` clones — `git cat-file` proves they are absent from the baseline repos' object databases entirely, so nothing has backported the fixes to the J17 line.
- `~/idea/cpp-context-*` reconfirmed byte-for-byte: 45 of 55 contexts carry `liquibase.hub.mode: off` (10 archived), zero `searchPath` among them. `material` remains the **only armed** location fleet-wide (`hub.mode` + `strict: true` in the same file, plus its byte-identical `-archived` twin and `framework/j25` clone) — and is conspicuously *absent* from every stray duplicate below, so the one case that matters today has a fully enumerated, non-growing blast radius.
- New stray/duplicate checkouts a `cpp-context-*` glob structurally misses: `old/` (results, sjp, staging-dvla, users-groups ×2 files — plain file copies, not git repos), `features/file-uc21/` (mi-reportdata, notification-notify), `defence-investigation/` (defence), plus an **untracked** nested copy inside `cpp-context-results-archived` that `git grep`-based sweeps can never see (methodology gap flagged). All 38 misc `cpp.*`/`cpp-platform-*`/utility repos: zero hits; `framework/cake-shop`'s liquibase.properties is minimal and clean.

</details>

### Access control & CDI wiring

<details>
<summary><strong>BC-03</strong> - Drools 7.69→10.1 recompiles access-control DRL, allow/deny can flip (65 dirs)</summary>

Signal: `.drl` files containing `eval(`, cross-referenced against `global <Provider>` declarations. **65 hit-dirs** (770 `.drl` files, 681 with `eval(`, 2,551 occurrences); 15 outside BC-03.md's `cpp-context-*` scope (14 real fleet evidence + 1 BC-03's own repro fixture).

- Largest DRL owners: `reference-data` (273 files), `sjp` (77), `progression` (72), `users-groups` (130 `eval` occurrences on 2 files), `hearing` (99). Provider surface dominated by `UserAndGroupProvider` (557 global declarations); context-specific providers: `HearingProvider`, `SjpProvider`, `ProgressionProvider`, `CourtDocumentProvider`, `SubscriptionProvider`, `RbacProvider`.
- New: **4 dotted-name context repos** invisible to a `cpp-context-*` glob (`cpp.authorisation.service`, `cpp.context.directions-management`, `cpp.context.staging.darts`, `cpp.context.staging.hmi`); the 7 paired J25 checkouts (DRL identical to their J17 twins); `old/`, `features/`, `defence-investigation/` stale dupes.
- **Zero `.drl` anywhere under `~/idea/framework/` (excluding `j25/`)** — DRL rule *content* lives exclusively in `cpp-context-*`; `cpp-platform-libraries`' `access-control-parent` holds only the Drools engine wiring. This is the structural reason BC-03's refutation holds. Notable: `boxworkmanagement` uses no Drools at all in either era; `staging-pubhub` is the one context with a `.drl` but no `eval(`.

</details>

<details>
<summary><strong>BC-14</strong> - CDI 4 discovery-mode + legacy beans.xml can empty interceptor chains (84 checkouts)</summary>

Signal: an empty / missing-mode / legacy-namespace / `annotated`-mode `beans.xml`, or a hand-written `InterceptorChainEntryProvider`-style class with no bean-defining annotation. **84 hit-checkouts** (75 new beyond BC-14.md's 9); 1,076 `beans.xml` scanned.

- **Core mechanism reconfirmed with zero exceptions at 43× scale**: 913 of 1,076 `beans.xml` (85%) still use the legacy `jcp.org` namespace, and *every single one* carries explicit `bean-discovery-mode="all"` — **zero** missing-mode, **zero** `"none"`. All 117 production `InterceptorChainEntryProvider` implementations across 31 context-groups lack a bean-defining annotation (the only exception fleet-wide is one test fixture, `TestQueryApiInterceptorChainProvider` with `@ApplicationScoped`).
- **2 new live 0-byte `beans.xml` defects** not in BC-14.md: `users-groups` `usersgroups-support-tool` (a real standalone Weld SE app — empty on J17 `main`, silently *repaired* to a 322-byte `all` descriptor on J25, the "already bit once, fixed reactively" pattern) and `sjp` `sjp-domain-{event,aggregate}` (pure POJO modules, lower relevance). Both also present in `old/` dupes.
- The 2 pre-existing intentional `annotated` files (`activiti-embedded-rest`, `json-schema-catalog/schema-service`) reconfirmed as the only ones, now also sighted in the J17 baseline and archived copies. `idam-events-consumer` is an empty-shell checkout (no code at all).

</details>

<details>
<summary><strong>BC-19</strong> - SJP agent-prosecutor access silently regressed grant→deny (12 contexts)</summary>

Stale-base remediation — the full fleet-wide pane is under **Stale-base (route to release management)** below; grouped here only by subject matter. In short: 721 raw hits / 12 real contexts (7 new), core chain `sjp` → `SjpProvider` → regressed `ProsecutingAuthorityAccess.hasAccess()`, with the J17 baseline `framework/cpp-platform-libraries` confirmed to *carry* the feature (proving release lines have the fix and only the J25 fork lacks it). See BC-19 under Stale-base for the per-context detail.

</details>

<details>
<summary><strong>BC-20</strong> - Drools test harness silently loads zero rules from JAR rulebases (67 checkouts)</summary>

Signal: test classes `extends BaseDroolsAccessControlTest`, plus physical copies of the harness class itself. **67 hit-locations** (563 raw `extends` hits); 35 reproduce BC-20.md's exact 302 files / 34 contexts, 32 new.

- **Major qualitative addition**: `cpp-context-sjp` (and its `old/` dupe) hides **23 more** consumers behind a local `SjpDroolsAccessControlTest extends BaseDroolsAccessControlTest` wrapper — invisible to a literal-`extends BaseDroolsAccessControlTest` grep, so sjp's true consumer count is **90, not 67** (fleet #2 after `reference-data`'s 104). It is the only such intermediate wrapper anywhere in the tree.
- The harness class exists in 3 physical copies: J25 `cpp-platform-libraries` (the defective rewrite BC-20 dissects) vs. `cpp-platform-libraries-archieved` + the J17-baseline `framework/cpp-platform-libraries` (both still on the *safe* pre-rewrite one-liner — the defect is confined to the J25 fork). `getKieClasspathContainer`/`newKieClasspathContainer`: zero fleet reimplementation confirmed.
- New: 4 dotted-name repos (`cpp.authorisation.service` — oldest tip fleet-wide at 2022, still a live consumer; `cpp.context.directions-management`/`.staging.darts`/`.staging.hmi`), 7 paired J25 checkouts (identical counts J17↔J25), 6 `old/` dupes. `sjp` — the true #2 consumer — has no J25 clone anywhere, so it is undiffable from this workspace.

</details>

### Activiti/BPMN

<details>
<summary><strong>BC-09</strong> - Activiti NoOpPlatformTransactionManager — atomicity/rollback lost (32 hit-repos; 4 true Activiti)</summary>

Signal: BPMN/Activiti-or-Camunda integration with a timer/async continuation, plus infra/tooling that references one. **32 hit-repos** (27 new) — but **two distinct engine families**, only one of which is BC-09: "true" Activiti 5.22.0 via the shared `activiti-embedded-rest` module = **exactly 4 contexts** (`prosecution-casefile`, `sjp`, `staging-enforcement`, `staging-prosecutors-spi`), unchanged from BC-09.md. A separate **Camunda 7** family (`businessprocesses`, `boxworkmanagement`, `work-management-proxy`) structurally matches the search but is a different engine, already IT-green (incl. timer-job execution) on a real 7.17→7.24 bump — **not** a second BC-09.

- The 4 Activiti contexts reconfirmed (timer BPMNs → `JavaDelegate` → `sender.send`). `staging-prosecutors-spi` runs its own bespoke engine (a real javax JNDI lookup) — a migration trap, not the NoOp.
- Strong external corroboration: `cpp.platform.tools.business-data-fixes` is a recurring ops client of the Activiti REST surface (9+ ATCM fix packages targeting sjp timer state); `cpp-aks-ops`/`devops_dba_toolkit` name `sjpactiviti`/`hearingactiviti`/`progressionactiviti` databases; `f-parent/sjp-framework-d-error.csv` captures `SendExpirationCommandDelegate` firing at volume on a JobExecutor thread pool. Vestigial/decommissioned Activiti signatures (BPMN or property but no engine dependency) in `hearing`, `progression`, `resulting`, `prosecution-casefile-dlrm` — cleanup candidates. Two J17-baseline/archived `cpp-platform-libraries` copies remain on the real `JtaTransactionManager` (the "before" side of BC-09's diff).

</details>

<details>
<summary><strong>BC-10</strong> - Activiti REST API reimplemented — null params 500, surface cut (9 locations)</summary>

Signal: code or ops/runbook calling Activiti's `/management/`, `/runtime/`, `/history/` REST endpoints, or reading its REST DTOs. **9 locations**, 5 new — but only one (`cpp-apitests`, its own independent `ActivitiHelper`) is a functionally new consumer; the other 4 are physical dupes of known code.

- Known affected: `prosecution-casefile` + `sjp` call the REST surface only via test-only `ActivitiHelper` classes that always send the full happy-path parameter set — their green ITs give false confidence. `staging-enforcement`/`staging-prosecutors-spi` drive Activiti in-process (zero REST hits — confirms BC-10).
- **`business-data-fixes` materially expanded**: 33 files (BC-10.md cited only 2), including `ATCM4169VerifyPhase` calling `getJobsWithException()` with no `processInstanceId` (the exact null shape confirmed to throw HTTP 500) and calls to `runtime/executions` / `process-instances` DELETE/POST sub-resources that **don't exist in the new 3-endpoint surface at all** (unfixable by a null-guard, needing new hand-written resources). `resulting` firmed up as a confirmed dead REST target (no engine, no code, just a leftover datasource property). Archived + J17-baseline `cpp-platform-libraries` carry the full pre-rewrite Spring-MVC `DispatcherServlet` REST layer.

</details>

### JSON & validation

<details>
<summary><strong>BC-08</strong> - Jackson jsr310 'Z' → ZoneOffset.UTC identity drift (18 families)</summary>

Signal: `Map`/`Set`/`Cache<ZonedDateTime>` keys, `getZone().getId()`, `ZonedDateTime.equals()`, and exact-equality Hamcrest assertions. 210 hits across **18 context families** (10 new); heuristically split (71 `exact-eq?`, 61 `safe:string`, 34 `safe:toLocalDate`, 23 map/cache-key, ...). Key caveat: `ZonedDateTimes.fromString()` hardcodes `ZoneOffset.UTC` on every Jackson version, so values routed through it are self-consistent — the real exposure is a *raw* Jackson-jsr310 POJO field compared to a hand-built `ZoneId.of("UTC")` or used as a map key.

- `cpp-context-listing`'s `Map<ZonedDateTime, HearingDay>` reconfirmed as the one confirmed structural bug (still dormant — listing isn't migrated). **New strongest exact-eq candidate**: `progression` `HearingAtAGlanceServiceTest` asserting a real `getHearingDay()` getter against a hardcoded `ZoneId.of("UTC")`; `progression` also holds the sole `getZone().getId()` hit fleet-wide. `cpp-apitests` carries 19 `Map<ZonedDateTime, JsonObject>`. `sjp` already implements a `.toInstant()`-based matcher — the exact fix BC-08 recommends.
- None of the 10 newly-found contexts (`cpp-apitests`, `defence`, `mi-reportdata`, `mi-systemdata`, `notification-notify`, `progression`, `results`, `sjp`, `cpp.context.staging.hmi`, `business-data-fixes`) has a J25 clone yet — every hazard is dormant until that context's own Jackson-2.21 migration. `event-store`/`cp-cake-shop` sites are already fixed to `.toInstant()`.

</details>

<details>
<summary><strong>BC-11</strong> - JSON-P glassfish→Parsson provider-lookup rewrite (95 locations)</summary>

Signal: raw `Json.createXxx()`/`JsonProvider.provider()`, framework `JsonObjects.` usage, and `jboss-deployment-structure.xml` files. **95 locations** (41 new); 4,838 raw JSON-P construction hits and 8,837 `JsonObjects.` hits fleet-wide — direct JSON construction is load-bearing in essentially every command/event/query module, so nearly every context with real code shows a hit.

- **jboss-deployment-structure.xml clean at 67 files (was 16)**: zero reference `parsson` or any glassfish JSON-P module anywhere — the only JSON-related module ever excluded/included is the unrelated Jackson `jackson-jaxrs-json-provider` (BC-12 territory). BC-11's crash has no jboss-structure footprint.
- **New on-mechanism finding**: `cp-file-service` (both J17 and J25) carries a *third, previously-undocumented* copy of the framework's `JsonObjects` (`uk.gov.justice.fileservice.common.messaging.JsonObjects`) — mechanically `javax`→`jakarta` renamed but never given the protective `findProvider()` rewrite, still calling unguarded `Json.createBuilderFactory(null)`; corroborates the already-flagged `test-utils-core` sibling. The 43/43 `javax.json` pom census reconfirmed exactly. Zero-hit-but-adjacent (flagged, not folded away): `cpp-platform-core-domain` (133 files, plain POJOs) and `system-id-mapper` (sits next to its own hit-bearing `id-mapper-client`).

</details>

<details>
<summary><strong>BC-13</strong> - org.json + everit validation strictness shift (52 locations)</summary>

Signal: direct `org.json.JSONObject`/`JSONArray`/`JSONException` or `org.everit.json.schema` usage. **52 locations** (47 new), ~604 files; every production catch-shape read individually.

- **Worst catch-shape found**: `cpp.platform.tools.business-data-fixes` `JsonHelper` catches *neither* `ValidationException` nor `JSONException` (only `IOException`) — a second independent instance of the fully-uncaught pattern beyond BC-13's `cpp-context-scheduling` `SchemaValidatorUtil`. **Best (correct) example**: `notification-notify` `GenerateEmailNotification` catches `ValidationException` and `IOException | JSONException` side-by-side — a live production template of the exact fix BC-13 recommends.
- New external-response parse sites lacking a `JSONException` catch: `hearing` `ProvisionalBookingService`, `mi-reportdata` `NcesMiExtractService`, `staging-prosecutors-spi` `RefDataQueryService`, `notification` `NotificationQueryView`. A whole J25-only module `catalog-effective-json-schema-generation` (one correct catch, one open). `cpp-context-material` genuinely zero on both sides despite being a flagship paired context. The entire `framework/framework-libraries` J17 baseline (242 hits/84 files) was never swept before — mostly build-time codegen consuming the schema *object model*, outside BC-13's inbound-payload framing.

</details>

### REST engine

<details>
<summary><strong>BC-12</strong> - RESTEasy 3.15→6.2.15/7.0.0 engine swap, WARs exclude bundled RESTEasy (63 locations)</summary>

Signal: `@Multipart`/`MultipartFormDataInput`, `implements ExceptionMapper`, and direct `resteasy-*` references in `pom.xml`. **63 locations** (58 new); 81 multipart hits, 8 ExceptionMapper files, 190 pom `resteasy-` hits.

- **Headline new finding**: `cpp-context-progression` (+ `-archived`) ships a real hand-written multipart upload endpoint (`UploadCaseDocumentsResource`/`UploadCaseDocumentsFormParser`) calling `InputPart.getBody(InputStream.class, null)` directly — the same `Providers.getMessageBodyReader()`-dependent method family BC-12's probe exercised — the first concrete **production** consumer of the provider-lookup path, not just framework internals or test fixtures. It even pins an ancient `resteasy-multipart-provider` 3.0.7.
- **Custom `ExceptionMapper` is 100% centralised**: all 8 hits sit inside the framework's own `common-rest`/`rest-adapter-core` (both J17 + J25) — not one of the ~90 other repos declares its own. `reference-data` pins `resteasy-client` to the pre-migration `4.3.0.Final` in 3 modules (insulated from the BOM bump), while the one module BC-12 flagged rides the bumped version via two paths; `material-query-view` gained a dated new `resteasy-client` test dependency *for* J25. Broad `resteasy-client`/multipart usage across staging Azure-Functions modules and dotted-name contexts; `cpp-mbd-idam-integration` is Spring Boot (different stack). Generator-produced multipart RAML endpoints (sjp, system-doc-generator, cake-shop) are invisible to a Java-source grep and reported separately.

</details>

### Messaging/observability

<details>
<summary><strong>BC-16</strong> - /internal/metrics/* gutted to 404 (0 genuine consumers)</summary>

Signal: literal `internal/metrics/{metrics,threads,healthcheck}` sub-paths. **Zero genuine fleet consumers** anywhere in `~/idea` — all 31 raw matches are this investigation's own report/review output under `framework/j25/`. Reconfirms BC-16.md's zero-consumer claim across all 97 top-level directories.

- Newly swept clean: the full J17 `framework/` baseline, every `cpp.*`/`cpp-mbd-*` utility repo, and remaining infra repos. Only `/internal/metrics/ping` (185 hits) and `/prometheus` (32) are referenced fleet-wide — `cpp-aks-deploy`'s 49 probe files, `cpp-helm-chart`, and `cpp-developers-docker`'s healthcheck script all target only `/ping` (substring `grep pong`, robust to the rewrite).
- Most telling absence: **no CHANGELOG anywhere in `~/idea`, on any branch, ever announced the 3 gutted sub-paths** (unlike `/prometheus`, whose addition *is* announced) — the only reason anyone knows they existed is inference from Dropwizard's `AdminServlet` defaults. Strengthens, rather than undermines, BC-16's "low blast radius" conclusion.

</details>

<details>
<summary><strong>BC-17</strong> - stream_error hash/identity shift for identical failures (19 contexts)</summary>

Signal: `stream_error`/`stream_error_hash` fields or dashboard/config/log surfaces keyed on `exceptionClassName`/`javaClassName`/`causeClassName`. **19 genuine hit-contexts** (16 new).

- **Directly contradicts BC-17.md's "no live consumer in this workspace" claim**: `cp-framework-stream-error-dashboard` — a top-level, non-`cpp-`-prefixed sibling a `cpp-*` glob never reaches — renders `streamErrorHash.{exceptionClassName,causeClassName,javaClassName}` and `streamErrorDetails.{hash,exceptionMessage}` as literal table columns, backed by the `framework-stream-rest-resources` endpoints living (un-swept) in the J17 `event-store`; its committed wiremock stub captures a now-stale J17 `javax.persistence.PersistenceException` shape. A real captured external call from an `mireportdata-query-api` consumer sits in `parent/mi-reportdata/test-local.http`.
- `cpp-context-listing`'s `DatabaseCleaner` reconfirmed (the one application-code hit). `cpp-framework-java-upgrade-pilot` documents a *prior* 17.104.x break where Hibernate 6 stopped wrapping `ConstraintViolationException` in `PersistenceException` — a competing/complementary mechanism alongside BC-17's interceptor-removal explanation. `data/` and `parent/mi-reportdata/` hold extensive ad hoc `stream_error` SQL. Still no *automated* Grafana/PagerDuty alert-rule keys on the exact strings anywhere — BC-17's narrower claim holds, its broader "no live consumer at all" framing does not.

</details>

<details>
<summary><strong>BC-18</strong> - Artemis client 2.53 'eager broker-URL pinning' (73 locations)</summary>

Signal: `JmsSessionFactory`/`MessageProducerClient`/`MessageConsumerClient`/`ActiveMQConnectionFactory` (+ an `INTEGRATION_HOST_KEY` cross-check). **73 locations** (696 files); 24 new (6 wholly-new identities, 13 new physical locations, 5 first-named on this exact pattern).

- The 6 new identities are all dot-named or utility repos a `cpp-context-*` glob can't reach: `cpp-apitests`, `cpp-mbd-idam-integration` (Spring Boot — its production `ExternalActiveMqConfig` `@Bean` has the same no-arg-then-mutate shape), `business-data-fixes`, `cpp.context.staging.darts` (carries the exact class #2/#3 `setBrokerURL` static-field pattern), `cpp.context.staging.hmi`, `cpp.context.directions-management`.
- `progression` is the largest consumer (110 files); `users-groups`/`prosecution-casefile`/`material` (the "validated green" paired contexts) all carry the affected lineage. **New fact**: `microservice-framework`'s `messaging-adapter-generator` has a whole 5-file JMS-adapter-generation IT package on J17 that is **completely absent on J25**. `cpp-developers-docker/build-scripts` is the actual source of the `-DINTEGRATION_HOST_KEY`/`-DARTEMIS_HOST_KEY` JVM args. The dash-vs-dot naming convention is the structural reason the 3 `cpp.context.*` contexts were missed by every prior sweep.

</details>

### Codegen/build-time-with-runtime-effect

<details>
<summary><strong>BC-21</strong> - reflections 0.9.10→0.10.2 scanning-contract change (52 repos)</summary>

Signal: poms with wildcard RAML/JSON `<include>` patterns in generator / raml-maven / json-schema-catalog plugin config. **52 repos** (25 new). The wildcard-include pattern is near-universal: essentially every `cpp-context-*` (3-5 hits typical; `mi-reportdata` 14+), all framework generator modules, both the J17 and J25 copies.

- New scope beyond BC-21.md: `framework-libraries`/`cp-framework-libraries` test fixtures (20+ hits, including the `includes-excludes-external` fixtures that carry the M5 workaround), `microservice-framework` framework-generators (6), `cpp-platform-core-domain`, `cpp-platform-libraries` audit-client, `cpp-platform-maven-service-parent-pom`, `features/file-uc21`, `old/`, and archived copies.
- This is a broad *theoretical-exposure* census (any wildcard-CLASSPATH RAML generator), not a confirmed-affected list — matching BC-21's downgraded verdict: the `JavaCompilerUtility` bug's window was a single M5 calendar day and no context is confirmed affected at HEAD. Recommended follow-up remains to separate the `getFromPath()` (Reflections) from `getFromClasspath()` (ClassGraph) backends per producer context and confirm the M5 excludes rather than treating them as a root-caused fix.

</details>

### Stale-base (route to release management)

<details>
<summary><strong>BC-15</strong> - core-domain forked from stale base, missing schema fields (16 contexts)</summary>

Signal: the 7 stale-base field literals (`isAddressConfidential`, `isDeemedServed`, `deletedJudicialResult`, `crackedIneffectiveSubReasonId`, `welshProsecutorCost`, `migrationCaseStatus`, `defendantFineAccountNumber`). **16 genuine hit-contexts** (13 new); 9,991 raw lines (dominated by `isDeemedServed` fixture data in `results`).

- **2 new confirmed J25 regressions beyond BC-15.md's 2**, both silent, both in supposedly "validated green" references: J25 `hearing` `HearingTrialTypeDelegate` drops the `.withCrackedIneffectiveSubReasonId(...)` builder line (net −1 vs `main`) and its `SetHearingTrialTypeIT` goes 6-arg → 5-arg; J25 `prosecution-casefile` has orphaned `mcc-inactive`/`mcc-fine-account-number` IT fixture files whose covering `InitiateCCProsecutionIT` scenarios were removed outright (23 deletions).
- Structural insight BC-15 missed: **`reference-data` owns the reference-data backbone** for `ResultDefinition`/`isDeemedServed` and `CrackedIneffectiveSubReason` (the master lookup-table owner). `results` is a test-covered *downstream consumer* of hearing's `deletedJudicialResults` event, closing the producer→consumer loop. `progression`/`defence`/`system-doc-generator` shadow `isAddressConfidential`/`welshProsecutorCosts` as *local* shapes (safe for those two fields), whereas `migrationCaseStatus` imports the real generated enum everywhere (no safe fallback anywhere). The J17-baseline `cpp-platform-core-domain` is itself stale (missing `migrationCaseStatus`/`defendantFineAccountNumber` via ordinary fetch staleness — the same risk shape). `cpp.static-data.patches` confirms CCT-2357 required a real production ref-data patch.

</details>

<details>
<summary><strong>BC-19</strong> - SJP agent-prosecutor access silently regressed grant→deny (12 contexts)</summary>

Signal: `ProsecutingAuthorityAccess`/`ProsecutingAuthorityProvider`/`hasProsecutingAuthorityToCase`/`agentProsecutorAuthorityAccess`. **721 raw hits, 12 real contexts** (7 new) — confirming BC-19.md's "3 contexts" was accurate only for the `cpp-context-*` glob it was scoped to, not `~/idea/` as a whole.

- Core 3 reconfirmed: `sjp` (the consumer — 120 hits / 24 files; the causal chain `sjp-query-api` `query-access-control.drl` → `SjpProvider.hasProsecutingAuthorityToCase()` → the regressed `hasAccess()`; SJP has **no J25 clone anywhere**, so its own before/after is undiffable), `users-groups` (the producer — the `size() > 1` agent-emission gate absent on J25), `mi-reportdata` (confirmed *not* at risk). SJP file count settled at 8 production / 15 test+IT / 1 DRL (correcting BC-19's internally-inconsistent "9/9").
- New: `mi-systemdata` (a genuine 4th member — a dead `@Inject`, not at risk); `cpp-platform-priming` (61 hits but a naming coincidence — plain `String prosecutingAuthorityAccess` getters, no import of the real class); **`features/file-uc21/cpp-context-mi-reportdata`** — a *richer, later* `main` checkout that grew the full agent-authority feature (2-arg `of(...)` factory), i.e. mi-reportdata's real `main` is ahead of its stale top-level clone; the J17-baseline `framework/cpp-platform-libraries` **has** the feature in non-regressed shape (proving release lines carry the fix, only the J25 fork lacks it); `cpp-platform-libraries-archieved` + `old/` dupes carry the pre-feature shape. None of the 11 legitimately `-archived` context repos shows even a coincidental hit.

</details>

### Long tail

<details>
<summary><strong>BC-22</strong> - Apache Tika 1.28→3.3 MIME-detection drift (17 contexts)</summary>

Signal: file-service API usage (`FileStorer`/`FileRetriever`/`FileServiceException`/`FileService`). 23 checkouts / **17 distinct context names** (16 genuinely new — BC-22.md enumerated no context consumers, only the producer + cake-shop harness). Far broader actual usage than the original "inconclusive / single-consumer" framing implied.

- Heaviest consumers: `progression` (16 files), `material` (15, with live upload tasks), `sjp` (17). The contexts most exposed to `ContentTypeDetector`'s output are the live `FileStorer.store()` callers — `material`, `prosecution-casefile` (`AddMaterialApi`), `results`/`resulting`, `progression`, `staging-dvla`, `staging-prosecutors`. `FileRetriever`-only consumers (`sjp`, `system-doc-generator`, `prosecution-documentqueue`) are resilient (metadata already persisted).
- Recommendation stands: given 17 real consumers, upgrade the verdict from "inconclusive" toward "requires validation" — run `FileServiceIT` plus the ITs of the 3 heaviest uploaders against Tika 3.3.0, with attention to CSV/ZIP-OOXML. `users-groups`/`listing`/`work-management-proxy`/`hearing` are absent or dependency-only.

</details>

<details>
<summary><strong>BC-23</strong> - Quartz 2.3→2.5 JobDataMap deserialization hardening (1 context)</summary>

Signal: Quartz `JobDataMap` usage. **Exactly 1 context fleet-wide**: `cpp-context-system-scheduling` (the only `cpp-context-*` declaring a Quartz dependency at all). Its 5 `JobDataMap` entries are all String or Integer (primitive) — no custom object ever crosses the deserialization boundary, so Quartz 2.5's new `ObjectStreamClassFilter` allowlist has nothing to reject.

- No custom `org.quartz.serializer.jobDataMapAllowedTypes` override present (correct — none is needed). `old/` holds only Spring's own reference Quartz support (test/reference, not production). The original **REFUTED** verdict holds across the entire fleet; no mitigation or code change needed anywhere, for current usage.

</details>
