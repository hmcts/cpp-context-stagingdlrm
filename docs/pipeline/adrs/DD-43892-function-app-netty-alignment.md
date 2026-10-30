# ADR `DD-43892-function-app-netty-alignment` — The Function App manages its own netty version, for the whole netty family

## Status

**Not adopted 2026-10-30.** Proposed at the Stage 2 gate of
[DD-43892](https://hmcts.atlassian.net/browse/DD-43892). The tech lead decided that story follows the
ticket as written (bump only `netty-handler`/`netty-common`) and that further needs are noted, not done
(DD-43892 `01-requirements.md` D1, N1). Kept as the record of the problem and a tested fix, for any
follow-up ticket that takes up the netty mix. Not mirrored: it applies only to
`stagingdlrm-azure-functions`, which has no counterpart in `cpp-context-prosecution-casefile-dlrm`.

## Date

2026-10-30

## Context

- netty versions across CPP are set by the shared `uk.gov.moj.cpp.common:common-bom`, which imports
  `io.netty:netty-bom:${netty.version}` (25.104.0: `4.1.119.Final`, `common-bom-25.104.0.pom:131,185-192`,
  commented *"controls all netty dependencies (azure-core-http-netty, lettuce-core, etc.)"*). It reaches
  this repo through `service-parent-pom`.
- `stagingdlrm-azure-functions/pom.xml:87-95` overrides just two artefacts, `netty-handler` and
  `netty-common`, to `4.2.6.Final`. The reason is unrecorded (bulk commit `82f11f8`). The result is a
  packaged mix: `netty-handler`, `netty-common` and `netty-codec-base` at 4.2.6, every other netty module
  at 4.1.119 (`mvn -o dependency:tree -Dincludes=io.netty`, `team/25.104.x` @ `7ac0aa5`).
- 4.2.6 carries CVE-2026-75595 (critical) and CVE-2026-44249, CVE-2026-50010, CVE-2026-45416 (high);
  fix is 4.2.17 or later (DD-43892).
- netty modules are released and tested as one set. A 4.2/4.1 mix is not a supported configuration.
- This ADR proposed moving the whole netty family in the Function App to the fixed version, beyond the
  ticket's two named artefacts. The decision below was **not adopted** (see Status).

## Decision (proposed, not adopted)

1. **`stagingdlrm-azure-functions` imports `io.netty:netty-bom` in its own `<dependencyManagement>`**,
   versioned by a module property **`netty.version`** = `4.2.17.Final` (or a later 4.2.x if one is
   current at implementation time). The property name matches `common-bom` and the fleet precedent
   (`cpp-context-reference-data`, `cpp-context-results` root poms).
2. **The explicit `<version>4.2.6.Final</version>` on `netty-handler` and `netty-common` is removed.**
   The two dependency declarations stay; the BOM now supplies their version.
3. **The import is module-level, not root-level.** The WARs keep the netty version `common-bom` gives
   them. Changing that is the shared parent's responsibility (DD-43892 out of scope), and a root-level
   import would move netty under every WAR's `audit-client` → Azure Data Lake path too.

## Evidence the mechanism works

Maven only adds an imported BOM's entries when that key is not already managed, so it was not obvious
that a module-level import would beat the inherited `common-bom` one. A spike on 2026-10-30 settled it.
It used `netty-bom` 4.2.10.Final, the only 4.2 BOM in the local cache, as a stand-in, and ran offline:
`dependency:tree` on the module resolved `netty-handler`, `netty-buffer`, `netty-codec`,
`netty-codec-http`, `netty-codec-http2`, `netty-transport-native-*` and the rest at **4.2.10.Final**,
not 4.1.119. The module-level import therefore wins for the whole family. The pom was restored after
the spike.

## Alternatives considered

- **Bump only `netty-handler`/`netty-common` to 4.2.17 (ticket literal).** Rejected at Stage 1: keeps the
  unsupported mix and leaves the 4.1.119 modules in the Function App unexamined.
- **Remove the pin and let the Function App follow `common-bom` (4.1.x).** Rejected for now: whether the
  4.1 line is affected by these CVEs is unconfirmed (01-requirements OQ3), and the ticket's stated fix
  is 4.2.17. Becomes viable if `common-bom` moves to a patched version that the vulnerability check
  accepts. Worth revisiting then, because it would remove a local override.
- **Explicit `<dependencyManagement>` entries per netty artefact.** Works (explicit entries always win)
  but lists ~20 artefacts by hand and drifts when netty adds a module. Kept as the fallback if the
  25.104.5 parent changes how netty is managed and the BOM import stops winning.

## Consequences

- One local override replaces two, and it now covers the whole family, so the Function App packages a
  single netty version.
- `azure-core-http-netty` 1.16.0 → `reactor-netty-http` 1.2.8, built against netty 4.1, runs on netty
  4.2 in the Function App. Unit tests mock the Azure clients and the ITs do not run the Function App, so
  **only a real Function App run proves this** (01-requirements FR11 / AC7).
- When `common-bom` later moves netty past this module's version, this override becomes a downgrade.
  Whoever bumps the parent must re-check this property, or remove it per the second alternative.
