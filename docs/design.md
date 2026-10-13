# Design

How system built, and why. Describes code as it stands now.
`plan.md` lists build order. `../REPORT.md` has results and limits.

## 1. Idea

Bank AI agent must operate old web apps with no API. Only way in: use screen like a person.

Two phases:

- **Discovery (once).** Claude looks at screen, picks next move, repeats until goal reached.
- **Replay (many times).** Saved JSON recipe followed by deterministic engine. No AI in loop.

> Model discovers. Recipe becomes reusable capability. Replay is how agent invokes it.

A move that cannot be undone stops and waits for a person, in both phases.

## 2. Pieces

| Piece | Role |
|---|---|
| ParaBank | Banking app to automate. Pinned Docker image, localhost only. |
| Playwright for Java | Hands: open, click, type, read, wait |
| Claude | Brain, discovery only |
| This project | Policy, redaction, recipe, replay, hand-over, API |

## 3. Packages

```
com.computeruse.automation
├── surface/        Surface interface + vocabulary (Action, Observation, Conditions)
│   └── web/        PlaywrightSurface: only browser-aware code
├── artifact/       Recipe ("capability"): records, JSON, validation, {{placeholders}}
├── policy/         Allowlist and risky-control rules, engine, loader
├── redaction/      Scrubs secrets before log, save or send
├── result/         Three ways a replay ends
├── discovery/      Brain, Move, ClaudeBrain, DiscoveryLoop, CapabilityCompiler
├── replay/         ReplayEngine: follows a recipe, no AI
├── handoff/        ApprovalGate, Session (owns one live browser), HumanTurn
├── evidence/       RunLog: one folder per run (written to runs/; curated ones live in /evidence/)
└── api/            REST: AutomationApi, SessionManager, CapabilityStore
```

Direction: `api` uses `discovery`, `replay`, `handoff`. `discovery` and `replay` use `surface`, `policy`,
`redaction`, `artifact`. `surface` and `artifact` know nothing of the rest. Only `surface/web` imports Playwright.
`replay` has no Brain and no Anthropic import: it cannot call Claude.

## 4. Key decisions

### 4.1 One `Surface`

`observe()`, `act(Action)`, `captureEvidence(...)`, plus `location()` (cheap address) and
`beginHandoff()/endHandoff()`. Discovery, replay and hand-over talk only to it.
Why: recipe and replay must not know they drive a browser. `Conditions` sees address, visible text,
role plus name. Never HTML.

### 4.2 Closed menu of six actions

`Navigate, Click, Type, Select, Read, Wait`. Sealed. Compiler forces every `switch` to cover all.
Claude answers with one tool call whose fields are the menu plus `complete`, `ask_human`.
Bad field or unknown word becomes `ask_human`. Never a guess.

### 4.3 Locators with fallbacks

Per control, in order: `Role` (role + name), `Label` (caption before it), `Structure` (CSS path).
First that matches exactly one control wins. Two matches count as none. Winner is logged.
Table cells count as controls, so a balance can be read.

### 4.4 Brain never holds a secret

Claude sees input names, types `{{password}}`. `MoveTranslator` swaps real value at last moment.
Recorded step taken from the move, not the action. Every screen, error and history entry goes through `Redactor`.
Marked inputs registered with redactor before first look.

### 4.5 Policy before every action, both phases

`PolicyEngine.evaluate`: action type allowed? page allowed? risky control?
Answers `ALLOW`, `REQUIRE_APPROVAL`, `DENY`. Checked again after each action (a click can navigate).
Rules live in `policy/parabank.json`. `DENY` never reaches a person.

### 4.6 The approval gate

`ApprovalGate.ask(Intervention)` blocks until a person answers:
`APPROVE` (do it), `REJECT` (stop), `DONE_BY_PERSON` (they did it, **check**, then carry on),
`OFF_SCRIPT` (they went to another page or tab: stop), `NO_ANSWER` (nobody answered in time: stop).
A person's "done" is a claim. Replay looks for the step's expectation, else the recipe's next wait.
Not seen: `HUMAN_STEP_NOT_DONE`. Nothing to check against: `HUMAN_STEP_UNVERIFIED`. Never success on their word.
A paused run waits `human-wait-seconds` (5 min) for anyone, then stops with `NO_HUMAN_RESPONSE`. No limit once
a person holds the window. Each session writes `session.json` (states, what each person did, result) beside its evidence.
Asked when policy says `REQUIRE_APPROVAL` **or** recipe step is `RISKY`. Default gate is `NEVER` = `REJECT`.
Discovery records an approved step as `RISKY`, so replay asks again.

### 4.7 One thread per live browser

Playwright objects belong to the thread that made them. `Session` runs a job on its own thread.
Other threads send commands (`APPROVE, REJECT, TAKEOVER, RESUME`) and wait for state change.
Thread waits in one place only: the gate.
States: `AUTOMATION, PAUSED, HUMAN, RESUMING, FINISHED`. History kept.

### 4.8 What a person did

While `HUMAN`, a script in the page notes clicks and changes. Never typed text, only
"typed N characters into input#name". Notes survive page changes via sessionStorage.
`HumanTurn` holds note, before/after address, events, times.

Every tab is also watched: pages reached (query dropped) and any second tab. On hand-back, any page other than
the one the run paused on, or any second tab, gives `OFF_SCRIPT`. Replay stops with `HUMAN_OFF_SCRIPT`;
discovery ends `NEEDS_HUMAN`. Events then include `went to <page>`. Background requests are not page changes.

### 4.9 Three endings for a replay

`Success`, `BusinessOutcome` (known answer, e.g. `INVALID_CREDENTIALS`), `Failure`
(kind, step, expected, observed, evidence). Slow-but-worked steps go in `recoveries`.
Known outcomes checked after clicks and when a step fails. Policy refusal never turned into one.

### 4.10 Evidence

`RunLog.start()` makes `runs/<time>-<tag>/`. Result JSON, screenshot, snapshot go there.
Everything scrubbed before write. Secret fields blacked out in screenshots before PNG exists.
Run ids avoid long digit runs (redactor would read them as card numbers).

### 4.11 One process, files on disk, synchronous API

No queue, no database. Recipes are JSON in `artifacts/`. A call returns when run needs a person,
finishes, or `wait-seconds` pass.

## 5. Recipe

Identity, version, app (relative entry path), typed inputs/outputs (each can be `sensitive`),
ordered steps, known outcomes, success condition, provenance (`DISCOVERY` or `HAND_WRITTEN`).

- Step can carry `expect`; replay waits for it.
- `CapabilityValidator` reports all problems at once. Placeholders must name declared inputs;
  READ must store into declared output.
- Compiler: full address becomes path; reason becomes description; READ locators built from the
  value read are dropped; approved steps stay `RISKY`; success condition = page discovery ended on
  (session ids stripped).
- Known outcomes are **supplied by a person**. Discovery walks the happy path only.

## 6. Flows

```
discovery:  start page (policy) -> loop{ observe -> redact -> brain -> translate -> policy
                                          -> (gate if risky) -> act -> remember } -> compile -> save
replay:     inputs check -> register secrets -> per step{ resolve -> policy -> (gate) -> act
                                          -> location check -> known outcome? -> expect } -> success condition
```

## 7. Testing

- `./mvnw test`: 165 unit tests. Fake surface, scripted brain. No browser, network, key.
- `./mvnw test -Psmoke`: live against bank (29 tests) + 4 paid tests (discovery, stability) behind `-Dlive=true`.
- `StabilitySmokeTest`: repeats replay or whole discover-compile-replay chain, counts, writes `stability-*.json`.
- Fault injection: `PlaywrightSurface.injectDelay` holds a request. Missing control made by editing recipe.

## 8. Boundaries and next steps

- No authentication on API. Approval is a bare call; no record of who.
- Sessions in memory; abandoned paused session never cleaned up.
- No spend limit per discovery beyond `max-turns`.
- Takeover: used by a person twice. Going elsewhere, doing the wrong thing, doing nothing, and no answer in time
  are all stopped (proven in a real browser with scripted stand-ins), but the new checks are not yet repeated by a
  person. The proof is only as strong as the recipe's own check. Cannot cancel from the window; no limit once held.
- One application. No model comparison. Stability not measured.
- Known outcomes typed in, not discovered.
- Recorder sees plain page events only (not frames, canvas).
