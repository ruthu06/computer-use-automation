# Plan

What was planned, in what order, and where it stands. `design.md` explains how it is built.
`plan.txt` is the original plan written before any code.

## 1. Status

| Step | Work | State |
|---|---|---|
| 1 | Java project, pinned ParaBank, Playwright | Done |
| 2 | Contracts: surface, artifact, results, policy, redaction | Done |
| 3 | Playwright surface and evidence | Done |
| 4 | Claude discovery loop | Done. Claude completes a goal from the screen alone. |
| 5 | Compile the trace into an artifact | Done. Secrets absent, schema validated, run-specific data removed from locators. |
| 6 | Deterministic replay | Done. Zero model calls. |
| 7 | Business, recoverable and hard-failure paths | Done. Each returns its own result. |
| 8 | Safety and same-session hand-over | Done. Approval gate, takeover, session states, REST. |
| 9 | Tests, evidence, README, REPORT | Done. See `../README.md`, `../REPORT.md`, `../evidence/`. |

## 2. Changes against the original plan

- **Approve-and-continue came earlier than planned.** Discovery could not finish a goal that touched the
  risky button until a person could approve it, so the gate went into discovery as well as replay.
- **Table cells became controls** (step 4), because the balance on the dashboard had no handle.
- **Evidence run records** were added in step 6/7, once failures needed a screenshot that survived the run.
- **Known endings are supplied, not discovered.** Planned as "discovered"; a happy-path run cannot see them.
- **`FINISHED` added** to the four session states, because a run needs an end state.

## 3. What is left (not planned for this submission)

| Item | Why it matters |
|---|---|
| Authentication on the API, and who approved | Largest gap. Anyone reaching the port can approve a risky action. |
| Spend limit per discovery | Cost is bounded only by `max-turns`. |
| Model comparison (Sonnet, Haiku) with repeated runs | Discovery is the costly part; the setting is one line. |
| More takeover cases with a real person | Used once, doing the expected step. Untried: a different action, a different page, a long wait. |
| Discovering known endings (a deliberate failing run) | Today typed in by a person. |
| Session persistence and cleanup | In memory; abandoned paused sessions linger. |
| A second application | Everything was shown on ParaBank. |

## 4. How it was delivered

One small piece at a time, each with a test, explained and reviewed before the next.
