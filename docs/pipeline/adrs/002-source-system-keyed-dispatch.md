# ADR-002: Source-system divergence is dispatched by a keyed map, at every layer

## Status

**Accepted 2026-08-11** — by the tech lead, at stage 3 of
[DD-43081](https://tools.hmcts.net/jira/browse/DD-43081).
[DD-43086](https://tools.hmcts.net/jira/browse/DD-43086) specifies the same shape independently
(its FR4); this ADR makes the correspondence deliberate rather than coincidental.

Both stories may proceed against it. The [compliance notes](#compliance-notes) are the review
checklist for any story touching source-system behaviour — for DD-43081 that is T1, T2 and T5.

## Date

2026-08-11

## Scope — two stories, one repo, two layers

| Story | Layer | Artefact |
|---|---|---|
| [DD-43081](../DD-43067-DD-43081-schema-enablement/01-requirements.md) FR12b | stagingDLRM domain | `MigratedCaseValidationRuleEngine` in `stagingdlrm-domain-aggregate` |
| [DD-43086](../DD-43067-DD-43086-funcapp-libra-ingest/01-requirements.md) FR4 | Function App gate | schema selection in `TimerTriggerJava` |

A third instance exists one hop downstream — PCFDLRM's `CcProsecutionValidationRuleProvider`, which
is keyed by `CaseType × Channel` and would gain a source-system axis. That is a different repo and
pipeline; this ADR does not bind it, but the shape is offered as precedent if it goes the same way.

## Context

The epic's design decision (analysis §2, §7) is that XHIBIT and LIBRA share one endpoint, one
schema family and one command/event set, with source-system-specific behaviour as **pluggable
strategies inside the shared path**. That decision says divergence must be pluggable; it does not
say how the plug is selected.

Adding LIBRA surfaces exactly two selection points in this repo, in runtimes with different
capabilities:

- The **Function App** picks a validation schema per source system. It is a plain Java app; the
  source-system token comes from the blob path and is validated by the gate before use.
- The **aggregate** picks a validation rule set per source system. It is a `Serializable` POJO
  instantiated by the event-source framework, **not a CDI bean** — nothing can be injected into it.

Without a stated convention these would plausibly be built three different ways: injected strategy
beans in the func-app, an `if` ladder in the aggregate, or dispatch pushed into the rules themselves.
The neighbouring `cpp-context-results` precedent
(`HearingFinancialResultsAggregate` → `ResultNotificationRuleEngine`) does the last of these — each
rule carries `appliesTo(input)` — which is a real, working pattern and a live temptation to copy
wholesale.

## Decision

**1. Selection is a map keyed by source system, resolved once per invocation.** Not conditionals in
the shared path, and not `appliesTo`-style self-selection inside the plugged-in unit.

**2. Dispatch lives outside the thing being dispatched to.** A validation rule does not know which
source system it serves; a schema does not know which folder selected it. This is the deliberate
divergence from `cpp-context-results`: the map keeps dispatch in one readable place, and a reviewer
can diff a source system's entire behaviour against the requirement by eye. A unit needed by both
source systems is listed under both keys.

**3. Initialisation follows the runtime.** Where the container manages the component, the map may be
CDI-provided and resolved once. Where it does not — the aggregate — the map is `static final` and
its entries must be **stateless and thread-safe**, because one instance is shared across every
aggregate instance and thread. Being static also keeps it out of the aggregate's serialized
snapshot.

**4. One class may serve several keys as differently-configured instances.** Prefer parameterised
construction over a class per constraint — e.g. `InitiationCodeValidationRule.withAllowedValues(…)`
registered under `XHIBIT` with one code set and under `LIBRA` with another. Immutable
construction-time configuration is not per-submission state, so this stays inside rule 3.

**5. Adding a source system is a map entry.** No new endpoint, schema family, command, event type,
class hierarchy or parallel test tree. If adding one requires more, the divergence has leaked out of
the keyed values and back into the shared path.

**6. The key is validated before it reaches the map.** In stagingDLRM it is the generated
`MigrationSourceSystemName` enum (`["LIBRA","XHIBIT"]`, `required`), so an unrecognised value cannot
arrive and no fallback behaviour is defined — a missing key is a programming error, not a runtime
input case. In the Function App the token is a path segment, checked against `dlrm_folder_name` by
the gate and derived through the shared helper (DD-43086 FR7), so the value the map keys on is
provably the value the gate admitted.

**7. The two layers divide responsibility, not duplicate it.** Function App schemas stay
**structural** — shape, types, presence. Business rules stay in stagingDLRM. Both layers being
source-system-aware is accepted; both layers expressing the *same* rule is not.

## Options considered

| Option | Why not |
|---|---|
| **`appliesTo()` on each rule** (the `cpp-context-results` shape) | Scatters dispatch across ~25 classes. Reading "what does XHIBIT enforce?" means opening all of them, and a rule that forgets the check silently applies to both source systems. |
| **Conditionals in the shared path** | The failure mode the epic design explicitly rejects — divergence leaks into code both source systems depend on, and every new source system edits the same methods. |
| **Injected strategy beans everywhere** | Impossible in the aggregate, which is not container-managed. Forcing it means moving the decision out of the aggregate and into the handler, which takes the invariant away from the only component that can append events. |
| **One shared registry class across both layers** | The two run in different processes with different lifecycles and different key sources. Sharing the class couples deployments for no behavioural gain; sharing the *shape* gets the benefit without the coupling. |
| **Separate schema/endpoint per source system** | Already rejected at epic level — analysis §7 records the five evidence-based reasons. |

## Consequences

- **The key appears in two places.** Adding a third source system means two map entries in this
  repo, plus a `dlrm_folder_name` config value. That is the accepted cost of two layers legitimately
  needing to know.
- **Duplication within a map is expected.** A rule or schema serving both source systems appears
  under both keys. This is deliberate: an explicit second entry is cheaper to review than an
  implicit "applies to everything" default.
- **The maps can drift in behaviour.** Nothing enforces that the func-app's LIBRA schema and
  stagingDLRM's LIBRA rules agree. Rule 7 is the mitigation, and it is a review discipline, not a
  compile-time guarantee.
- **`cpp-context-results` and this repo now differ.** A developer moving between them will find two
  shapes for the same problem. That is why this ADR exists rather than a code comment.
- **The design docs must not "harmonise" the two layers into one mechanism.** They are the same
  shape and deliberately not the same code.

## Compliance notes

What a reviewer checks on any story that touches source-system behaviour:

1. No `if`/`switch` on source system in a shared code path.
2. No source-system awareness inside a rule, schema or other dispatched unit.
3. The map is the only place a source system is named in control flow.
4. In the aggregate: the map is `static final`, its entries hold no per-submission state, and it is
   never a field on the aggregate.
5. Adding a hypothetical third source system would be a map entry plus its values — walk it through
   in review.
