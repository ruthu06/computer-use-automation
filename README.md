# Computer-use automation

An AI agent needs to operate an old web application that has no API. The only way in is the screen.

This project lets **Claude work out a task once**, saves what worked as a reusable **recipe**, and then
**replays that recipe with no AI at all**. A risky action (one that cannot be undone) always stops and
waits for a person, who can approve it, decline it, or take over the live browser and hand it back.

> The model discovers. The recipe becomes a capability. Deterministic replay is how an agent uses it.

The target is [ParaBank](https://parabank.parasoft.com), a demo bank, run locally in Docker.
For the reasoning behind the choices, results and limits, read [REPORT.md](REPORT.md).
For how the code is organised, read [docs/design.md](docs/design.md).

## What it looks like

```
POST /discover   goal: "log in and open a savings account"
   Claude looks at the screen, picks one move at a time, the policy checks each one
   -> PAUSED at the "Open New Account" button: "cannot be undone from the UI"
POST /sessions/{id}/resume   {"approve": true}
   -> COMPLETED, recipe saved: artifacts/parabank.open-savings-account.json

POST /capabilities/parabank.open-savings-account/replay   (a different customer, no Claude)
   -> PAUSED at the same button  ->  approve  ->  Success {"newAccountNumber": "22113"}
```

| | Discovery (Claude) | Replay (recipe) |
|---|---|---|
| Time | 20 to 40 s | 0.6 to 1.6 s |
| Model calls | 6 to 9 | **0** |
| Cost | about $0.06 to $0.09 | **$0** |

## Requirements

- Java 21 (the Maven wrapper `./mvnw` is included)
- Docker with Compose
- An Anthropic API key, **only for discovery**. Replay and every test except the two paid ones need no key.

## Run it

```bash
# 1. The bank, pinned by image digest, on localhost:8080 only
docker compose up -d --wait

# 2. The browser Playwright drives (once)
./mvnw -q exec:java -Dexec.mainClass=com.microsoft.playwright.CLI "-Dexec.args=install chromium"

# 3. Your key, for discovery only. Never commit it: .env is git-ignored.
cp .env.example .env          # then put the key after ANTHROPIC_API_KEY=
set -a; source .env; set +a

# 4. The service, on http://localhost:8090  (opens a visible browser window)
./mvnw spring-boot:run
#    headless instead:  AUTOMATION_HEADLESS=true ./mvnw spring-boot:run
```

A visible window is what lets a person take over. Set `AUTOMATION_HEADLESS=true` for unattended runs.

### Demo path: run the agent on a goal, then replay the artifact it made

With the bank and the service running (steps above), in a second terminal:

```bash
source scripts/demo.sh

discover      # Claude runs the goal on the live bank. It stops at the risky button (PAUSED)
approve       # you approve; Claude finishes; the artifact is saved to artifacts/parabank.open-savings-account.json
begin         # REPLAY that saved artifact, with no model, for a brand-new customer. It stops at the button again
approve       # you approve; the run finishes -> Success with the new account number
```

`discover` needs the API key in the service's environment (about $0.09 on the default model). Everything after it
does not. The artifact is plain JSON; open it to see the steps, locators, inputs, outputs and success condition.
Evidence from earlier runs, including a replay that hits a business outcome, a slow page, a drifted locator and a
missing control, is in [`/evidence/`](evidence/).

### Running without live services

| You have | You can run |
|---|---|
| Only Java | `./mvnw test`: 165 unit tests, no bank, no browser, no key |
| Java, Docker, no key | The bank and `./mvnw test -Psmoke`, which replays the saved recipes and runs the live checks. The paid tests skip themselves |
| All three | Everything, including `discover` |

### Try it with the helper script (easiest)

`scripts/demo.sh` makes a new customer in the bank, starts the recipe, and gives you one word per action.
Source it, do not paste snippets: some shells (zsh) treat `#` and `$USERNAME` and `$RANDOM` in surprising ways.

```bash
source scripts/demo.sh

begin                    # new customer, replay starts, stops at the risky button (state PAUSED)
approve                  # the run presses the button itself          -> Success, new account number
decline                  # nothing is pressed, the run stops          -> Failure POLICY_DENIED

begin; takeover          # you take the live window (state HUMAN); do something in it
handback "what I did"    # the run checks the result is really there  -> Success, or HUMAN_STEP_NOT_DONE
show                     # where the session is now
```

### Try it with curl

Register a customer first (ParaBank needs someone to log in as), then:

```bash
# Replay the recipe that ships in this repo. It pauses at the risky button.
curl -s -X POST localhost:8090/capabilities/parabank.open-savings-account/replay \
  -H 'Content-Type: application/json' \
  -d '{"inputs":{"username":"YOUR_USER","password":"YOUR_PASSWORD"}}'
#  -> {"id":"20261013T...","state":"PAUSED","intervention":{"stepId":"step-7-click","reason":"Opens a real account ..."}, ...}

curl -s -o shot.png localhost:8090/sessions/<id>/screenshot        # what the person is asked about

curl -s -X POST localhost:8090/sessions/<id>/resume \
  -H 'Content-Type: application/json' -d '{"approve":true}'
#  -> {"state":"FINISHED","resultType":"Success","result":{"outputs":{"newAccountNumber":"..."}, ...}}
```

To take over instead of approving: `POST /sessions/<id>/takeover`, act in the browser window,
then `POST /sessions/<id>/resume` with `{"note":"what I did"}`. The run continues after the step you handled.

### API

| Call | What it does |
|---|---|
| `GET /capabilities` | The saved recipes, with `needsApproval` |
| `GET /capabilities/{id}` | One whole recipe |
| `POST /capabilities/{id}/replay` | Replay it. Body: `{"inputs": {...}}` |
| `POST /discover` | Let Claude work out a goal and save the recipe. Needs the API key. |
| `GET /sessions/{id}` | Where a run is now |
| `GET /sessions/{id}/screenshot` | The screenshot taken when the run asked for a person |
| `POST /sessions/{id}/takeover` | A person takes the live window (only while paused) |
| `POST /sessions/{id}/resume` | `{"approve": true/false}` when paused, or hand control back from a person |

Calls are synchronous. A call that starts or continues a run returns when the run next needs a person,
is over, or `automation.wait-seconds` have passed. Read `state`:
`AUTOMATION`, `PAUSED`, `HUMAN`, `RESUMING`, `FINISHED`. When finished, `resultType` says
`Success`, `BusinessOutcome` (a known answer such as a wrong password), `Failure`, or `DiscoveryOutcome`.

A `POST /discover` body: `goal`, `inputs` (the real values), `sensitiveInputs` (which are secrets),
`outputs` (names to read back), `capabilityId`, `name`, `description`, optional `knownOutcomes`.

## Tests

```bash
./mvnw test                          # 165 unit tests, no browser, no network, no key
./mvnw test -Psmoke                  # live tests against the bank (needs docker compose up)
```

The four paid tests (two discovery, two stability) are skipped unless asked for, so a plain smoke run never spends
money. The live suite is 29 tests; with no key and no `-Dlive=true`, 4 are skipped:

```bash
set -a; source .env; set +a
./mvnw test -Psmoke -Dtest=DiscoverySmokeTest -Dlive=true      # real Claude calls, about $0.15 in total
./mvnw test -Psmoke -Dtest=ReplaySmokeTest                      # no key needed
```

Add `-Dheaded=true` to watch the browser. Runs are written to `runs/` (git-ignored); add `-Devidence.root=evidence`
to keep them in `/evidence/`.

How often does it work? `StabilitySmokeTest` repeats the whole chain and counts. Replay is free; discovery is paid.
Each discovery trial finds the way, compiles the run, then replays that recipe for a customer it has never seen.

```bash
./mvnw test -Psmoke -Dtest='StabilitySmokeTest#replayingTheSavedRecipeRepeatedlyForNewCustomers' -Drepeats=20
set -a; source .env; set +a
./mvnw test -Psmoke -Dtest='StabilitySmokeTest#discoveringAReadOnlyGoalRepeatedly' -Dlive=true -Drepeats=5 -Dmodel=claude-haiku-5-5
./mvnw test -Psmoke -Dtest='StabilitySmokeTest#discoveringTheRiskyGoalWithApprovalRepeatedly' -Dlive=true -Drepeats=3
```

### Try the takeover yourself

The one thing the tests cannot do is be a person. This takes about two minutes.

1. Start the service with a visible window: `./mvnw spring-boot:run` (headless is off by default).
2. Register a customer in ParaBank, then `POST /capabilities/parabank.open-savings-account/replay` with that
   customer's username and password. A Chromium window opens and stops on the Open New Account page. The answer
   says `PAUSED`.
3. `POST /sessions/<id>/takeover`. The state becomes `HUMAN`; the window is yours.
4. In the window, press **Open New Account** yourself (SAVINGS is already chosen).
5. `POST /sessions/<id>/resume` with `{"note": "pressed it myself"}`.

Expected: `state: FINISHED`, `resultType: Success` with the new account number, and under `humanTurns` an `events`
list of what you did (something like `clicked input "Open New Account"`). The run carried on from the step after
the one you handled, and the account was opened once, by you, not twice.

**Now do it wrongly, on purpose.** Repeat steps 1 to 3, then in step 4 go somewhere else instead: click **Bill Pay**
in the same window, or open a second tab. Then `resume`.

Expected: `resultType: Failure` with `kind: HUMAN_OFF_SCRIPT`, nothing carried on, and `humanTurns[0].events` listing
`went to .../billpay.htm` (or `went to another tab or window`). The run does not guess that all is well.

Two more ways to get it wrong, and what the run does about each:

| What you do | What you should see |
|---|---|
| Take the window, click some **other** button on the same page (or nothing), then `resume` | `Failure` `HUMAN_STEP_NOT_DONE`: "a person handed the window back, but the result of this step was not seen on the page". A screenshot of what was there. Never `Success`. |
| Do not answer at all | After 5 minutes (`automation.human-wait-seconds`) the run stops: `Failure` `NO_HUMAN_RESPONSE`, nothing done, a warning in the service log, and `session.json` in the run's evidence folder. To see it quickly, start the service with `AUTOMATION_HUMAN_WAIT_SECONDS=20`. |

## What is in the repo

```
src/main/java/com/computeruse/automation/
  surface/      the seam: observe, act, capture evidence. surface/web/ is the only code that knows Playwright
  artifact/     the recipe ("capability"): steps, locators with fallbacks, validation, placeholders
  policy/       what is allowed, and which controls need a person  (policy/parabank.json)
  redaction/    removes secrets from anything logged, saved or sent to Claude
  discovery/    Claude as a brain, the discovery loop, and compiling a run into a recipe
  replay/       follows a recipe with no AI; maps every ending to one of three result shapes
  handoff/      the approval gate and the session that owns a live browser
  evidence/     RunLog: one folder per run (result, screenshot, page snapshot), written to runs/
  api/          the REST layer
artifacts/      recipes (JSON). Two are included and were found by Claude.
evidence/       run records kept as proof (the brief's /evidence/): discovery, replay success, business
                outcome, slow page, drifted locator, missing control, human hand-over cases, and an example artifact
scripts/        demo.sh: one word per action for trying it by hand
REPORT.md       the design write-up, in the brief's seven sections
docs/           design.md (how it is built), plan.md (what was built, in order), plan.txt (the original plan)
```

## Configuration

`src/main/resources/application.properties`, any of which can be set as an environment variable
(`automation.target-base-url` becomes `AUTOMATION_TARGET_BASE_URL`).

| Property | Default | |
|---|---|---|
| `automation.target-base-url` | `http://localhost:8080` | Where the app being automated lives |
| `automation.headless` | `false` | `false` shows a window, which a person needs in order to take over |
| `automation.model` | `claude-opus-5-5` | Discovery only |
| `automation.max-turns` | `25` | Cap on moves in one discovery |
| `automation.wait-seconds` | `120` | How long a request waits for a run to need a person or finish |
| `automation.human-wait-seconds` | `300` | How long a paused run waits for a person before it stops itself. No limit once a person holds the window |
| `automation.artifacts-dir` | `artifacts` | Where recipes are kept |
| `automation.evidence-dir` | `runs` | Where each run leaves its record (git-ignored). The curated runs are in `/evidence/` |

## Safety, in short

- **Allowlist.** `policy/parabank.json` lists the allowed origin, pages and action types. It is checked before
  every action in discovery **and** in replay, and again after each action, because a click can navigate.
- **Risky actions stop.** "Open New Account" is on a risk list. It is never done without a person's answer, and
  a recipe remembers it was risky, so replay asks again every time.
- **Secrets stay out.** Claude sees input names and types `{{password}}`; the real value exists for one moment
  inside the translator. Screens, errors, history, evidence and recipes are all scrubbed. Screenshots black
  out secret fields before the file is written.
- **A person's typing is never recorded**, only how many characters went into which field.

## Scope and next steps

[REPORT.md](REPORT.md) sets out the scope of the evidence and the next steps in order. The first is
**authentication and recording who approved**: the API has none today. Sessions live in memory only, and every
claim above was shown on one demo application, in small numbers of runs.
