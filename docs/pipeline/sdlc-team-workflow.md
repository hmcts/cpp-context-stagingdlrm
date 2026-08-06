# SDLC Plugin Workflow — Team of 4

How a team of **1 tech lead, 2 developers, 1 QA** runs the `hmcts-sdlc-orchestrator`
8-stage pipeline on this repo.

The plugin's stages and gates are defined in
`~/.claude/plugins/cache/agentic-plugins-marketplace/hmcts-sdlc-orchestrator/1.0.0/CLAUDE.md`.
This document is the team overlay: the unit of work, who owns what, where work runs
in parallel, and the repo-specific caveats.

---

## The pipeline unit is a story

**One story = one complete pipeline run, all 8 stages.** The epic never enters the
pipeline — it is a Jira container that refinement splits into stories.

```
Epic DD-43100  (Jira only — split at refinement, may hold tens of stories)
  │
  ├─ DD-43100-libra-retry-limit       ──► 1 2 3 4 5 6 7 8    ┐
  ├─ DD-43100-xhibit-manifest-check   ──► 1 2 3 4 5 6 7 8    ├ this sprint
  ├─ DD-43100-outcome-file-naming     ──► 1 2 3 4 5 6 7 8    ┘
  ├─ DD-43100-…                            (backlog)
  └─ DD-43100-…                            (backlog)
```

`01-requirements.md` and `02-design.md` are the requirements and design for **that
story**, not for the epic. This scales to epics of any size — a twenty-story epic is
twenty independent pipeline runs, of which a sprint pulls only as many as the team has
owners for.

Two consequences worth stating up front:

- **A story belongs to exactly one repo.** Artefacts live in a repo, and stage 7 (CI)
  is per-repo. If a change needs work in two repos, that is two stories.
- **Stage 3 is not "split the epic"** — that already happened at refinement. At story
  level, stage 3 is writing the story properly: GDS format, testable acceptance
  criteria, ready for QA to build test specs against. If stage 3 reveals the story is
  too big, that is a signal to split it and start a second pipeline, not to carry on.

---

## Operating principle

The tech lead is the **approver of everything and the author of almost nothing**.

The obvious allocation — lead does the upstream stages, devs do the downstream ones —
fails on a team this small. The agents draft stage 5 as readily as they draft stages
1–3, so devs restricted to delivery end up reviewing agent output on the least
interesting part of the pipeline while all the design judgement concentrates in one
person. **Every owner therefore runs a full pipeline end to end**, devs included, and
the lead's distinct value is gate approval plus the cross-context calls nobody else
can make.

---

## Story ownership

Each story has one **owner** who carries it from stage 1 to stage 8.

**The sprint takes as many stories as there are owners — one in-flight story each.**
Owners are the developers plus the tech lead; QA does not own stories. For this team
that is **three concurrent pipelines** (Dev A, Dev B, tech lead), and the examples
below use three throughout. A team of four devs would run five.

| #  | Stage                           | Author                              | Gate  | Approver       |
|----|---------------------------------|-------------------------------------|-------|----------------|
| 1  | Requirements                    | Story owner                         | Human | Tech lead + QA |
| 2a | Design — *cross-context impact* | Tech lead **with** story owner      | Human | Tech lead      |
| 2b | Design — *inside this service*  | Story owner                         | Human | Tech lead      |
| 3  | User Story                      | Story owner drafts, team refines    | Human | Whole team     |
| 4  | Test Specs                      | **QA**                              | Human | QA + tech lead |
| 5  | Code                            | Story owner (+ peer dev if pairing) | Auto  | —              |
| 6  | Code Review                     | Peer dev first pass                 | Human | Tech lead      |
| 7  | Build & Test                    | Story owner                         | Auto  | —              |
| 8  | Deploy Sandbox                  | Story owner                         | Human | Tech lead      |

QA owns stage 4 on every story — it is their specialism, not a rotation, and they sit
across all three in-flight pipelines (see *Dual-track scheduling*).

### Who approves the tech lead's own story

The lead cannot approve their own gates — *cross-review, never self-review* applies to
them too. On the story the lead owns:

- **Stages 1, 2a, 2b, 3, 8** — approved by the developer who is not reviewing at the
  time; QA co-approves stage 1 as normal.
- **Stage 4** — QA authors it, so QA plus the peer developer approve.
- **Stage 6** — a developer does the first pass *and* signs the gate.

This is the one place the model needs a named substitute. Agree who it is at sprint
planning rather than discovering it at the gate.

### Why stage 2 splits

There is one genuinely lead-shaped question in stage 2: does this story cross a
context boundary? Which services change, what crosses to `pcfdlrm`, whether a new
event belongs on `public.event`, what `system-id-mapper` has to resolve. That needs
cross-context knowledge a dev may not have yet — **that is 2a**.

Everything below it is **2b**, and the person building it should own it:

- aggregate changes in `MigratedCaseSubmissionAggregate`
- new domain events and their JSON schemas
- view-store shape and Liquibase changesets
- whether a change belongs in the event processor or the event listener

Splitting 2a from 2b is what stops "architecture" being a black box the devs receive.
For most stories 2a is a single line — *no cross-context impact* — and the real work
is all in 2b.

---

## Before the pipeline: refinement

Splitting the epic into stories is a **human activity that happens outside the
pipeline**, at refinement, led by the tech lead. Two things must come out of it:

**Independently deliverable stories.** Each story must be shippable on its own. If two
stories cannot be deployed in either order, they are not really two stories.

**No module collisions.** This is the one job nobody but the tech lead can do.
Modules that change together in this repo:

- `stagingdlrm-command-handler` + `stagingdlrm-domain-aggregate`
- `stagingdlrm-event-processor` + its generated REST/messaging clients

Two concurrent stories both touching `MigratedCaseSubmissionAggregate` will conflict
badly. Catching that at refinement is worth more than any artefact produced later.

Note this is an **intra-repo** concern only — two stories in two different repos
cannot conflict.

---

## Dual-track scheduling

"One in-flight story per owner" counts stories being **built**. Because the six human
gates leave every pipeline parked at intervals, each owner also runs the next story's
early stages in the gaps:

- **Delivery track** — stages 5–8 for the story currently being built.
- **Discovery track** — stages 1–4 for the next story off the backlog.

Gates are where the tracks interleave: when a story is parked awaiting gate approval,
its owner moves to the other track rather than idling.

QA is in both tracks continuously — stage 4 for the stories entering the queue, stage
7 verification for the ones leaving it.

---

## Running the sprint's pipelines in parallel

One in-flight story per owner — for this team, three concurrent pipelines:

```
Sprint N
  Story 1  ──  Dev A       ──►  1 2 3 4 5 6 7 8
  Story 2  ──  Dev B       ──►  1 2 3 4 5 6 7 8
  Story 3  ──  Tech lead   ──►  1 2 3 4 5 6 7 8
```

Each runs on its own branch, in its own `docs/pipeline/` directory, with no shared
artefacts. The only sync points are the gates.

Stories are pulled from the backlog independently — they need not come from the same
epic. Three stories from three different epics is a perfectly normal sprint; the
directory prefix just makes their lineage visible.

### Cross-review, never self-review

The peer dev reviews first; the tech lead approves. The `code-reviewer` agent produces
the structured report, but a **human still signs the gate**.

---

## Artefact paths

The plugin's own `CLAUDE.md` writes to a flat layout (`requirements.md`,
`user-stories/`, `test-specs/`, …). **This repo overrides it** with a per-ticket
directory — see the root `CLAUDE.md`. With per-story pipelines the directory is named:

```
docs/pipeline/<EPIC-JIRA-ID>-<STORY-JIRA-ID>-<slug-for-story>/
```

The **epic's** Jira ID comes first, so an epic's stories sort together in a flat
listing however many there are. The **story's** own Jira ID follows it, so either key
can be grepped without opening a file:

```
docs/pipeline/
├── DD-43067-DD-43078-test-hardening/
│   ├── 00-input-brief.md      # epic framing + this story's request
│   ├── 01-requirements.md     # for THIS story
│   ├── 02-design.md           # for THIS story
│   └── 03-stories.md
├── DD-43067-DD-43079-schema-relaxation/
│   └── 00-input-brief.md
├── DD-43067-DD-43080-funcapp-schema-strategy/
│   └── 00-input-brief.md
└── adrs/                      # shared across all stories
```

Three rules that fall out of this:

- **The story key makes the directory unique**, so slugs only need to be *descriptive*.
  Still name them after what the story changes, not after the epic — `test-hardening`,
  not `libra-enabler-part-1`.
- **A story with no parent epic uses its own ticket ID alone** as the prefix, exactly as
  `DD-43014-reconciliation-report-enhancements/` already does in this repo.
- **Existing directories are not renamed** when the convention changes. The prefix form
  tells you which era a directory belongs to; that is cheaper than a rewrite that breaks
  every link into it.

The repo convention wins, and with concurrent per-story pipelines it is essential —
the plugin's flat layout means several story owners running stage 1 at the same time
overwrite each other's `requirements.md`. Make sure the root `CLAUDE.md` is loaded in
every session, or the agents will follow their own convention.

### Shared design decisions go in an ADR

When several stories in an epic depend on the same decision, it goes in
`docs/pipeline/adrs/` **once**, and each story's `02-design.md` links to it. Single
source of truth, no epic-level artefact, and the plugin's `adr-template` skill already
covers the format.

Do not restate the decision in each story's design doc — the copies drift the moment
the decision changes, and nobody can tell which is current.

---

## Stories that span repos

A story lives in one repo. An epic whose stories land in different repos is just
several independent pipelines, each in its own repo — nothing special is needed
**unless the stories depend on each other**.

When they do — a REST call, or an event one publishes and the other consumes:

**Write the contract as an ADR before either story starts stage 5.** The schema/RAML
change is settled up front, not discovered during implementation. Two devs building
against unstated assumptions about the same payload is the failure mode, and it stays
invisible until integration. Validate both sides afterwards with the
`api-contract-check` skill.

**Stage 4 needs a third test scope.** Per-repo tests cover each story; the interaction
between them is covered by neither. That test belongs in `cpp-apitests`, so QA's stage
4 output is three sets of specs, not two. Use the `cpp-test-authoring` skill for it.

**Stage 8 needs a deploy order.** CI is per-repo, so the two builds are independent,
but deploy order matters. Decide it at 2a alongside the contract: if the change is
backward-compatible, deploy the consumer first.

**Slice test:** if the two stories cannot be deployed independently, the split is
wrong. Either make the change backward-compatible, or accept that this is one unit of
work delivered by a pair — not two stories running in parallel.

---

## Right-sizing the gates

Per-story pipelines multiply gate approvals: **six human gates × every story in the
sprint**. For this team that is around eighteen, nearly all landing on the tech lead —
who is also carrying a story of their own. That is the real cost of this model, and it
grows linearly as the team does.

Three things keep it manageable:

**Batch the upstream gates.** Approve stages 1–3 for every story in one refinement
session rather than being interrupted per story. This alone collapses roughly half the
gate count into a single meeting.

**Stagger pipeline starts.** If all three stories hit stage 2 the same afternoon, the
lead becomes a queue. Starting them a day apart spreads the gates across the sprint at
no cost to throughput.

**Keep trivial artefacts trivial.** The plugin's hard rules forbid skipping or
reordering stages, so do not mark them N/A — but a stage's output can legitimately be
one line. For a small story, `02-design.md` reading *"2a: no cross-context impact. 2b:
adds one field to the view store, see changeset."* is a complete and honest artefact.
The gate still happens; it just takes ten seconds.

If the load is still unworkable after all three, the next lever is dropping stage 2a
to lead-*notification* rather than lead-*approval* for stories with no cross-context
impact. That is a real deviation from the plugin's hard rules, so make it a team
decision and record it as an ADR — do not let it happen by drift.

---

## Repo-specific caveats

### `tools/reconciliation/` breaks the pipeline at stage 7

Standalone bash / Python 3 (stdlib) / SQL scripts, outside the Maven build — CI does
not cover this directory. If a story touches it, stage 7 is a **manual run against a
real batch in dev/sandbox**. QA should own that verification, and the tech lead needs
to know stage 7 is not a green tick there.

Read `tools/reconciliation/README.md` first when any stage touches this directory —
it is the authoritative context, not the plugin's generic `context/tech-stack.md`.
Scope any tests to Python's stdlib `unittest` (no new dependencies).

### Skills that do not apply here

Do **not** use `springboot-service-from-template`, `springboot-api-from-template`,
`terraform-validate`, or `helm-config-validator` — there is no Spring Boot, Terraform,
or Helm chart in this repo.

The `architecture-designer` agent will offer the MbD-vs-context-service choice at
stage 2a; for changes inside this service the answer is already **CQRS context
service**.

---

## Tech lead load — the known risk

The lead carries refinement, stage 2a on every story, story slicing, six gate
approvals per story they do not own, **and** a full pipeline of their own. That is the
most loaded role in this model by a wide margin, and it is the first thing to watch.

Mitigations, in the order to reach for them:

1. **Batch and stagger the gates** (see *Right-sizing the gates*).
2. **Delegate the stage 6 first pass** to the peer developer, with the lead approving
   only once that pass is clean.
3. **Drop the lead's own story.** If gates are slipping, the lead owning a pipeline is
   the thing to give up — not the gate quality. The sprint then takes one fewer story,
   which is the honest trade rather than a hidden one.

Option 3 is the release valve. A sprint of two well-gated stories beats three with
rubber-stamped approvals, and the whole value of the pipeline is in the gates being
real.
