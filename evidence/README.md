# Evidence

Records of real runs against a local ParaBank (a demo bank, run in Docker). All customers, names and account
numbers are synthetic. No password appears in any file here (checked), and a person's typing is never recorded.

The example artifact is [`example-artifact-parabank.open-savings-account.json`](example-artifact-parabank.open-savings-account.json):
the recipe Claude found, with its risky step marked.

## Discovery (a real Claude run)

| Folder | Goal | Result |
|---|---|---|
| `20261013T195127-bdaad42d` | Log in, read the first account's number and balance | `COMPLETED`, 6 model calls, 10,252 tokens in / 954 out, 19.4 s. `discovery.json` lists every step and Claude's reason for it. `final-page.png` is where it ended |
| `20261013T195157-871868ad` | Log in, open a savings account | Paused at the **Open New Account** button; approved over HTTP; `COMPLETED`, 38.0 s. `approval-turn-6.png` is what the person was asked about |

## Replay (no model)

Every replay below was made with the saved recipe, for a customer discovery never saw.

| Folder | Case | Result |
|---|---|---|
| `20261013T210239-523c95e5` | Normal run | `Success`, account number and balance read |
| `20261013T210238-ded4468f` | **Business outcome**: wrong password | `BusinessOutcome` `INVALID_CREDENTIALS` at `step-4-click`. An answer, not a crash |
| `20261013T210241-8bac72aa` | **Recoverable**: the page's data is held back 3.5 s | `Success`, with one entry in `recoveries` |
| `20261013T210246-adbf40da` | **Drift**: the recipe's preferred locator for a button no longer matches | `Success` by the fallback, and a `recoveries` note: "found by locator 2 of 3" |
| `20261013T210301-87e7ac71` | **Hard failure**: a control that is gone | `Failure` `TARGET_NOT_FOUND` at `step-6-read`, with `replay-…-step-6-read.png` and `.txt` |
| `20261013T210236-c12eb503` | **Human did the step**: the person presses the button, hands back | `Success`, only because "Account Opened" was then **seen** on the page |
| `20261013T210250-dff0982f` | **Human did the wrong thing**: hands back without doing the step | `Failure` `HUMAN_STEP_NOT_DONE`, with a screenshot of what was actually there |
| `20261013T210246-e6836d2d` | **Nobody answered** (3-second limit in this test; 5 minutes by default) | `Failure` `NO_HUMAN_RESPONSE`, nothing done. `session.json` is the full story |

The three human cases use a scripted stand-in for the person, so they are repeatable.

## A person at the live window

| File | What it is |
|---|---|
| `takeover-by-a-person-20261013T201137-c0bb5a21.json` | The author took the window, pressed the button themselves, handed back: `Success`, and `events` shows `clicked input "Open New Account"` |
| `takeover-person-did-nothing-20261013T201648-2ea724ea.json` | The author took the window, did other things, handed back without pressing it. The run stopped at the next check. Pasted from the author's terminal, so an excerpt |

The author also ran these hand-over cases by hand more than ten times (the step, the wrong thing, another page,
nothing, no answer). Those outputs were not saved; the repeatable tests above cover the same cases.

## Repeated runs and three models

`stability-*.json` are summaries from `StabilitySmokeTest`. A discovery trial is the whole chain: Claude finds the
way, the run is compiled, and **that recipe is replayed with no model** for a new customer. It counts only if the
replay succeeds.

| File | Worked |
|---|---|
| `stability-replay-20.json` | 20 of 20 |
| `stability-discover-read-claude-opus-5-5-5.json` | 5 of 5 |
| `stability-discover-read-claude-sonnet-5-5-5.json` | 5 of 5 |
| `stability-discover-read-claude-haiku-5-5-5.json` | 5 of 5 |
| `stability-discover-open-claude-opus-5-5-3.json` | 3 of 3 |
| `stability-discover-open-claude-sonnet-5-5-3.json` | 3 of 3 |
| `stability-discover-open-claude-haiku-5-5-3.json` | 3 of 3 |

## Reading a record

- `replay.json`: the result. `outcome` is `success`, `businessOutcome` or `failure`. A failure names the step, what was
  expected, what was seen, and the screenshot and snapshot.
- `discovery.json`: status, outputs, and the cost in calls, tokens and seconds. The test-made one also lists every step
  with Claude's reason. The run made over HTTP keeps its steps in the saved recipe instead.
- `session.json`: for a run that paused, every state it went through and what each person did.
- `*.png` / `*.txt`: the screen at that moment (secret fields blacked out) and its scrubbed text.

## Reproducing

```bash
docker compose up -d --wait
./mvnw test -Psmoke -Dtest=ReplaySmokeTest -Devidence.root=evidence                      # no key needed
./mvnw test -Psmoke -Dtest='DiscoverySmokeTest#claudeLogsInAndReadsTheAccountDetails' \
        -Dlive=true -Devidence.root=evidence                                              # paid, needs ANTHROPIC_API_KEY
```

By default runs go to `runs/` (git-ignored); `-Devidence.root=evidence` keeps them here.
