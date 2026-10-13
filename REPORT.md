# REPORT

How to run it: [README.md](README.md). Run records for everything below: [`/evidence/`](evidence/).

## 1. The task

Banks and credit unions run back-office applications that have **no API**. The only way in is the screen, the way a
person would use it. An AI agent needs a safe, repeatable way to operate them.

The system had to:

1. Take a goal in plain language and have an LLM complete it on a **real application**.
2. Save the successful run as a **typed, reusable artifact**.
3. **Replay** that artifact with no model in the loop, and report success, a known business outcome, or a clear failure.
4. **Hand over to a person** when it cannot or should not finish alone, on the same live session.
5. Stay inside **safety guardrails**: an allowlist, care with irreversible actions, and no leaking of sensitive data.
6. Leave **evidence** of what happened, and have a credible design for other application types and for many tenants.

The stand-in application is ParaBank, a demo bank, run locally in Docker.

## 2. What was implemented

Claude finds its own way through the bank from the screen alone. What worked is saved as a recipe. The recipe replays
with no model call, for customers it has never seen. Anything that cannot be undone stops and waits for a person.

### Architecture

```
goal ─► discovery loop ─► compile ─► artifact (JSON) ─► replay engine ─► success | business outcome | failure
        Claude + policy                                  no model         + evidence
              └──────── approval gate / live-session hand-over, in both phases ────────┘
```

- **Surface**: the one seam to an application (observe, act, capture evidence, hand over). The web version uses Playwright.
- **Discovery loop**: observe, redact, ask Claude for one move, check policy, act. Claude answers with one tool call from
  a closed menu: `navigate, click, type, select, read, wait`, plus `complete` and `ask_human`.
- **Replay engine**: follows an artifact. It has no model client, so it cannot call Claude.
- **Policy and redaction**: shared by both phases. **Session**: owns one live browser and carries the control transfer.
- **REST layer**: start discovery, replay a capability, and control a paused session.

One process, files on disk, a synchronous API. The model is one setting.

### Artifact schema

One versioned JSON file, separate from the model transcript
([example](evidence/example-artifact-parabank.open-savings-account.json)):

- identity and version, plus a description a calling agent can read;
- the application (product, version, surface, a **relative** entry path so it is not tied to one host or tenant);
- **typed inputs and outputs**, each input flagged `sensitive` if it is a secret;
- **ordered steps**, each with an action, a target, a `{{placeholder}}` value, a risk level and an optional checkpoint;
- each target is an **ordered list of locators**: role and name, then caption, then structure path;
- **known outcomes** (such as "wrong password") and a **success condition**;
- provenance: where it came from.

### Determinism and error handling

- No model decides anything on replay. The first locator that matches exactly one control wins; two matches count as
  none. Waits are bounded: 5 s to find a control, 10 s for a checkpoint.
- Every replay ends as exactly one of three results:

| Result | Meaning | Example |
|---|---|---|
| `Success` | Success condition holds, and every output was read | account number and balance |
| `BusinessOutcome` | A known, legitimate answer | wrong password: `INVALID_CREDENTIALS` |
| `Failure` | It cannot continue. Names the step, what was expected, what was seen, with a screenshot | control not found |

- A slow page is waited out and recorded. A control found by a fallback locator is reported as a sign the page changed.

### Heterogeneity and multi-tenant (design)

- **Other surfaces.** The recipe and replay engine only talk to `Surface`, and conditions describe what is perceived
  (address, text, role, name), never HTML. A desktop surface would implement the same methods using an accessibility API
  or OS automation. Caption and structure locators already exist for legacy pages with no test ids.
- **Many tenants.** Record once per vendor product and version as a base recipe. A tenant is a small overlay (its base
  address, changed locators or wording, its own outcome texts and policy), validated with a canary replay. Drift is
  seen per tenant from what replay already reports. This is designed, not built.

### Escalation and handoff

- A live hand-over opens when policy marks a control risky or a recipe step is marked risky. The request carries the
  step, the reason, the page, and a screenshot.
- A person can **approve, decline, or take over the same live browser**. State is one value:
  `AUTOMATION, PAUSED, HUMAN, RESUMING, FINISHED`. Handing back resumes on the same session.
- What the person did is recorded (clicks, changes, pages reached in any tab), never what they typed.
- A hand-back is **checked against the page**, not trusted:

| The person | Result |
|---|---|
| Did the step | Its result is seen on the page, and the run continues |
| Did something else, or nothing | `HUMAN_STEP_NOT_DONE` |
| Went to another page or tab | `HUMAN_OFF_SCRIPT` |
| Did not answer in 5 minutes | `NO_HUMAN_RESPONSE`, nothing done, logged |

The operator console is mocked on purpose: REST calls and a small shell helper.

### Safety

- **Allowlist** of origins, pages and action types, checked before every action and again afterwards, in both phases.
- **Risky actions** (such as opening an account) are blocked until a person decides. An approved step is stored as
  risky, so replay asks again every time.
- **Sensitive data**: Claude sees input names and types `{{password}}`; secrets and common patterns (SSN, card, session
  id, API key) are scrubbed from screens, logs, evidence and recipes; replay screenshots black out secret fields.

## 3. What was tested

| Layer | What | Result |
|---|---|---|
| Unit tests | Schema validation, policy, redaction, discovery loop, compiler, replay engine, session, REST layer. No bank, browser or key | **165 pass** |
| Live tests | Real browser against the live bank: surface actions, replay paths, hand-over behaviour | **29 pass** |
| Paid tests | Real Claude runs, on request only | 4, kept out of normal runs |
| Discovery | Claude finds its way on the live bank | Read-only goal in 6 model calls; opening an account in 9 |
| Replay | Same recipe, a new customer each time, no model | **20 of 20**, 0.3 to 1.6 s, $0 |
| Three models | Discover, compile, then replay what was found, for another customer | **24 of 24** (Opus, Sonnet and Haiku, 3 to 5 trials each) |
| Error paths | Wrong password, slow page, changed locator, missing control | Each returned its own result with evidence |
| Hand-over | Person does the step, does the wrong thing, goes elsewhere, nobody answers | Each behaved as described. Run by hand more than ten times, and by repeatable tests with a scripted stand-in |

**Cost** (estimated from list prices): discovery is about $0.002 to $0.09 depending on the model; replay is free.

**Scope of the evidence.** One application, small numbers of runs (5 of 5 does not mean 100%), and the person in the
hand-over trials was the author.

## 4. Future scope

In the order I would build them:

1. **Authentication and a recorded approver.** The API has none today, so anyone who can reach it can approve a risky
   action. This is the largest gap.
2. **Live hand-over for more situations**: when Claude is stuck, and when a replay fails.
3. **A "cancel" command** for a person holding the window.
