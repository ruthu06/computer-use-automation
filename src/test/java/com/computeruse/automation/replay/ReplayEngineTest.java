package com.computeruse.automation.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.computeruse.automation.artifact.Capability;
import com.computeruse.automation.artifact.Condition;
import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Risk;
import com.computeruse.automation.artifact.Step;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.artifact.ValueType;
import com.computeruse.automation.handoff.ApprovalGate;
import com.computeruse.automation.handoff.Intervention;
import com.computeruse.automation.policy.Policy;
import com.computeruse.automation.policy.PolicyEngine;
import com.computeruse.automation.redaction.Redactor;
import com.computeruse.automation.result.ReplayResult;
import com.computeruse.automation.result.ReplayResult.FailureKind;
import com.computeruse.automation.surface.Action;
import com.computeruse.automation.surface.ActionResult;
import com.computeruse.automation.surface.ActionType;
import com.computeruse.automation.surface.Conditions;
import com.computeruse.automation.surface.Evidence;
import com.computeruse.automation.surface.Observation;
import com.computeruse.automation.surface.Surface;
import com.computeruse.automation.surface.SurfaceException;
import com.computeruse.automation.surface.TargetNotFoundException;
import com.computeruse.automation.surface.WaitTimedOutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReplayEngineTest {

    private static final String BASE = "http://localhost:8080";
    private static final String OVERVIEW = BASE + "/parabank/overview.htm";

    private static final Target USERNAME_BOX = target("textbox Username");
    private static final Target PASSWORD_BOX = target("textbox Password");
    private static final Target LOGIN_BUTTON = target("button Log In");
    private static final Target BALANCE_CELL = target("cell balance");

    private static final String BAD_LOGIN_TEXT = "The username and password could not be verified.";
    private static final Capability.Outcome BAD_LOGIN = new Capability.Outcome(
            "INVALID_CREDENTIALS", "The bank rejected the login", new Condition.TextVisible(BAD_LOGIN_TEXT));

    private static Target target(String name) {
        return new Target(name, List.of(new Locator.Structure("#" + name.replace(' ', '-'))));
    }

    private static Step step(String id, ActionType action, Target target, String value, String output, Condition expect) {
        return new Step(id, id, action, target, value, output, Risk.SAFE, expect);
    }

    /** Log in, then read the balance. */
    private static Capability recipe(Condition expectAfterLogin, List<Capability.Output> outputs) {
        return new Capability(Capability.SCHEMA_VERSION, "parabank.read", "1.0.0", "Read", "Reads the balance",
                new Capability.App("ParaBank", "2.1.0", "web", "/parabank/index.htm"),
                List.of(new Capability.Input("username", ValueType.STRING, "login", true, false, List.of()),
                        new Capability.Input("password", ValueType.STRING, "secret", true, true, List.of())),
                outputs,
                List.of(step("step-1-navigate", ActionType.NAVIGATE, null, "/parabank/index.htm", null, null),
                        step("step-2-type", ActionType.TYPE, USERNAME_BOX, "{{username}}", null, null),
                        step("step-3-type", ActionType.TYPE, PASSWORD_BOX, "{{password}}", null, null),
                        step("step-4-click", ActionType.CLICK, LOGIN_BUTTON, null, null, expectAfterLogin),
                        step("step-5-read", ActionType.READ, BALANCE_CELL, null, "balance", null)),
                List.of(BAD_LOGIN),
                new Condition.LocationContains("/parabank/overview.htm"),
                null);
    }

    private static Capability recipe() {
        return recipe(null, List.of(new Capability.Output("balance", ValueType.STRING, "balance", false)));
    }

    private static final Map<String, Object> INPUTS = Map.of("username", "john", "password", "hunter2");

    // ── test doubles ─────────────────────────────────────────────────────────

    /** A pretend browser: remembers the page, what it was asked to do, and what to break. */
    private static final class FakeSurface implements Surface {
        String location = "about:blank";
        String text = "";
        final List<Action> acted = new ArrayList<>();
        final Map<String, String> clickGoesTo = new HashMap<>();
        final Map<String, String> clickShows = new HashMap<>();   // target name -> text on the page afterwards
        long sleepOnClickMs = 0;                                   // makes a click slow
        final Map<String, Locator> foundBy = new HashMap<>();      // target name -> the locator that "matched"
        final Set<String> cannotFind = new HashSet<>();
        final Map<String, String> reads = new HashMap<>();
        SurfaceException breakWith;
        List<Target> maskedInEvidence;

        @Override public Observation observe() { return new Observation(location, "t", text, List.of(), null); }

        @Override public String location() { return location; }

        @Override public ActionResult act(Action action) {
            if (breakWith != null && !(action instanceof Action.Navigate)) throw breakWith;
            acted.add(action);
            switch (action) {
                case Action.Navigate a -> location = a.url();
                case Action.Wait a -> {
                    if (!Conditions.holds(a.until(), observe())) throw new WaitTimedOutException(a.until());
                }
                case Action.Click a -> {
                    find(a.target());
                    if (sleepOnClickMs > 0) {
                        try {
                            Thread.sleep(sleepOnClickMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    String to = clickGoesTo.get(a.target().name());
                    if (to != null) location = to;
                    String shown = clickShows.get(a.target().name());
                    if (shown != null) text = shown;
                }
                case Action.Type a   -> find(a.target());
                case Action.Select a -> find(a.target());
                case Action.Read a -> {
                    find(a.target());
                    return new ActionResult(reads.get(a.target().name()), matchedFor(a.target()));
                }
            }
            return new ActionResult(null, matchedFor(switch (action) {
                case Action.Click a  -> a.target();
                case Action.Type a   -> a.target();
                case Action.Select a -> a.target();
                default              -> null;
            }));
        }

        /** Which locator "found" the control: the one a test chose, otherwise the first one listed. */
        private Locator matchedFor(Target target) {
            if (target == null || target.locators().isEmpty()) return null;
            return foundBy.getOrDefault(target.name(), target.locators().get(0));
        }

        private void find(Target target) {
            if (cannotFind.contains(target.name())) throw new TargetNotFoundException(target);
        }

        @Override public Evidence captureEvidence(String label, List<Target> sensitiveTargets) {
            maskedInEvidence = sensitiveTargets;
            return new Evidence("shot.png", "snap.txt");
        }

        @Override public void close() {}
    }

    private static PolicyEngine policy() {
        return new PolicyEngine(new Policy(
                List.of(BASE),
                List.of("/parabank/index.htm", "/parabank/overview.htm"),
                Set.of(ActionType.values()),
                List.of()));
    }

    private static FakeSurface happySurface() {
        FakeSurface surface = new FakeSurface();
        surface.clickGoesTo.put("button Log In", OVERVIEW);
        surface.reads.put("cell balance", "$515.50");
        return surface;
    }

    private static ReplayResult replay(FakeSurface surface, Capability recipe, Map<String, Object> inputs) {
        return new ReplayEngine(surface, policy(), new Redactor(), BASE).run(recipe, inputs, "run-1");
    }

    private static ReplayResult.Failure failed(ReplayResult result) {
        assertThat(result).isInstanceOf(ReplayResult.Failure.class);
        return (ReplayResult.Failure) result;
    }

    // ── success ──────────────────────────────────────────────────────────────

    @Test
    void aGoodRunReturnsTheOutputsAndUsedTheRealValues() {
        FakeSurface surface = happySurface();

        ReplayResult result = replay(surface, recipe(), INPUTS);

        assertThat(result).isInstanceOf(ReplayResult.Success.class);
        assertThat(((ReplayResult.Success) result).outputs()).containsExactly(Map.entry("balance", "$515.50"));
        assertThat(surface.acted.get(0)).isEqualTo(new Action.Navigate(BASE + "/parabank/index.htm"));
        assertThat(surface.acted).contains(new Action.Type(USERNAME_BOX, "john"),
                new Action.Type(PASSWORD_BOX, "hunter2"));
    }

    // ── drift: the preferred locator stopped matching ────────────────────────

    private static final Locator PREFERRED = new Locator.Role("button", "Log In");
    private static final Locator FALLBACK = new Locator.Structure("#button-Log-In");

    /** The recipe, with the login button findable two ways: by role first, by structure second. */
    private static Capability withTwoWaysToFindLogin() {
        Target twoWays = new Target("button Log In", List.of(PREFERRED, FALLBACK));
        Capability base = recipe();
        List<Step> steps = base.steps().stream()
                .map(s -> s.id().equals("step-4-click")
                        ? new Step(s.id(), s.description(), s.action(), twoWays, s.value(), s.output(), s.risk(),
                                s.expect())
                        : s)
                .toList();
        return new Capability(base.schemaVersion(), base.id(), base.version(), base.name(), base.description(),
                base.app(), base.inputs(), base.outputs(), steps, base.outcomes(), base.successCondition(),
                base.provenance());
    }

    @Test
    void aFallbackLocatorBeingUsedIsReportedAsASignThePageHasChanged() {
        FakeSurface surface = happySurface();
        surface.foundBy.put("button Log In", FALLBACK);

        ReplayResult result = replay(surface, withTwoWaysToFindLogin(), INPUTS);

        assertThat(result).isInstanceOf(ReplayResult.Success.class);   // it still worked
        assertThat(result.recoveries()).singleElement().satisfies(recovery -> {
            assertThat(recovery.stepId()).isEqualTo("step-4-click");
            assertThat(recovery.condition()).contains("role and name").contains("structure path");
            assertThat(recovery.response()).contains("fallback");
        });
    }

    @Test
    void whenThePreferredLocatorMatchesNothingIsReported() {
        FakeSurface surface = happySurface();   // matches by the first locator listed

        ReplayResult result = replay(surface, withTwoWaysToFindLogin(), INPUTS);

        assertThat(result).isInstanceOf(ReplayResult.Success.class);
        assertThat(result.recoveries()).isEmpty();
    }

    @Test
    void theDriftNoteNeverCarriesTheTextOfALocator() {
        FakeSurface surface = happySurface();
        surface.foundBy.put("button Log In", FALLBACK);

        ReplayResult result = replay(surface, withTwoWaysToFindLogin(), INPUTS);

        // "Log In" and "#button-Log-In" are locator text. Only the kind of locator may appear.
        assertThat(result.recoveries().get(0).condition()).doesNotContain("Log In").doesNotContain("#button");
    }

    // ── a person's say ───────────────────────────────────────────────────────

    /** The same recipe, with the login click marked as needing a person. */
    private static Capability withRiskyClick(Capability recipe) {
        List<Step> steps = recipe.steps().stream()
                .map(s -> s.id().equals("step-4-click")
                        ? new Step(s.id(), s.description(), s.action(), s.target(), s.value(), s.output(),
                                Risk.RISKY, s.expect())
                        : s)
                .toList();
        return new Capability(recipe.schemaVersion(), recipe.id(), recipe.version(), recipe.name(),
                recipe.description(), recipe.app(), recipe.inputs(), recipe.outputs(), steps, recipe.outcomes(),
                recipe.successCondition(), recipe.provenance());
    }

    /** Remembers what it was asked and answers the same way every time. */
    private static final class Answering implements ApprovalGate {
        final List<Intervention> asked = new ArrayList<>();
        private final Answer answer;
        private final Runnable whilePersonActs;

        Answering(Answer answer) { this(answer, () -> { }); }

        Answering(Answer answer, Runnable whilePersonActs) {
            this.answer = answer;
            this.whilePersonActs = whilePersonActs;
        }

        @Override public Answer ask(Intervention intervention) {
            asked.add(intervention);
            whilePersonActs.run();
            return answer;
        }
    }

    private static ReplayResult replayWithGate(FakeSurface surface, Capability recipe, ApprovalGate gate) {
        return new ReplayEngine(surface, policy(), new Redactor(), BASE, ReplayEngine.DEFAULT_SLOW_STEP_MS, gate)
                .run(recipe, INPUTS, "run-1");
    }

    @Test
    void aRiskyStepIsNeverDoneWhenNobodyCanApproveIt() {
        FakeSurface surface = happySurface();

        var failure = failed(replay(surface, withRiskyClick(recipe()), INPUTS));

        assertThat(failure.kind()).isEqualTo(FailureKind.POLICY_DENIED);
        assertThat(failure.stepId()).isEqualTo("step-4-click");
        assertThat(surface.acted).noneMatch(action -> action instanceof Action.Click);
    }

    @Test
    void anApprovedRiskyStepIsDoneAndTheRunFinishes() {
        FakeSurface surface = happySurface();
        Answering gate = new Answering(ApprovalGate.Answer.APPROVE);

        ReplayResult result = replayWithGate(surface, withRiskyClick(recipe()), gate);

        assertThat(result).isInstanceOf(ReplayResult.Success.class);
        assertThat(gate.asked).singleElement().satisfies(question -> {
            assertThat(question.stepId()).isEqualTo("step-4-click");
            assertThat(question.reason()).contains("risky");
            assertThat(question.evidence()).isNotNull();
        });
        assertThat(surface.acted).anyMatch(action -> action instanceof Action.Click);
    }

    @Test
    void aDeclinedRiskyStepStopsTheRunWithoutDoingIt() {
        FakeSurface surface = happySurface();
        Answering gate = new Answering(ApprovalGate.Answer.REJECT);

        var failure = failed(replayWithGate(surface, withRiskyClick(recipe()), gate));

        assertThat(failure.kind()).isEqualTo(FailureKind.POLICY_DENIED);
        assertThat(failure.observed()).contains("not approved");
        assertThat(surface.acted).noneMatch(action -> action instanceof Action.Click);
    }

    // ── a person says they did it: show me ───────────────────────────────────

    private static final String RESULT_TEXT = "Accounts Overview";

    /** The recipe, with the login click carrying the proof that it worked. */
    private static Capability withProofOnTheClick() {
        return withRiskyClick(recipe(new Condition.TextVisible(RESULT_TEXT),
                List.of(new Capability.Output("balance", ValueType.STRING, "balance", false))));
    }

    /** The recipe, with the proof as a separate wait step right after the risky click. */
    private static Capability withProofAsALaterWait() {
        Capability base = withRiskyClick(recipe());
        List<Step> steps = new ArrayList<>();
        for (Step s : base.steps()) {
            steps.add(s);
            if (s.id().equals("step-4-click")) {
                steps.add(new Step("step-4b-wait", "wait for the result", ActionType.WAIT, null, null, null,
                        Risk.SAFE, new Condition.TextVisible(RESULT_TEXT)));
            }
        }
        return new Capability(base.schemaVersion(), base.id(), base.version(), base.name(), base.description(),
                base.app(), base.inputs(), base.outputs(), steps, base.outcomes(), base.successCondition(),
                base.provenance());
    }

    @Test
    void aStepThePersonDidThemselvesCountsOnlyWhenItsResultIsOnThePage() {
        FakeSurface surface = happySurface();
        // The person really logs in: the page moves on and shows what the step should have produced.
        Answering gate = new Answering(ApprovalGate.Answer.DONE_BY_PERSON, () -> {
            surface.location = OVERVIEW;
            surface.text = RESULT_TEXT;
        });

        ReplayResult result = replayWithGate(surface, withProofOnTheClick(), gate);

        assertThat(result).isInstanceOf(ReplayResult.Success.class);
        assertThat(surface.acted).noneMatch(action -> action instanceof Action.Click);
        assertThat(surface.acted).anyMatch(action -> action instanceof Action.Read);
    }

    @Test
    void aPersonWhoClickedSomethingElseOnTheSamePageIsNotCountedAsSuccess() {
        FakeSurface surface = happySurface();
        // Same page, a different button, a different result. Not the one the step was meant to produce.
        Answering gate = new Answering(ApprovalGate.Answer.DONE_BY_PERSON, () -> {
            surface.location = OVERVIEW;
            surface.text = "Funds transferred";
        });

        var failure = failed(replayWithGate(surface, withProofOnTheClick(), gate));

        assertThat(failure.kind()).isEqualTo(FailureKind.HUMAN_STEP_NOT_DONE);
        assertThat(failure.stepId()).isEqualTo("step-4-click");
        assertThat(failure.expected()).contains(RESULT_TEXT);
        assertThat(failure.observed()).contains("not seen");
        assertThat(surface.acted).noneMatch(action -> action instanceof Action.Read);   // nothing carried on
    }

    @Test
    void aPersonWhoHandedBackWithoutDoingAnythingIsNotCountedAsSuccess() {
        FakeSurface surface = happySurface();
        Answering gate = new Answering(ApprovalGate.Answer.DONE_BY_PERSON);   // page untouched

        var failure = failed(replayWithGate(surface, withProofOnTheClick(), gate));

        assertThat(failure.kind()).isEqualTo(FailureKind.HUMAN_STEP_NOT_DONE);
        assertThat(failure.evidence()).isNotNull();   // a screenshot of what was actually there
    }

    @Test
    void theProofCanBeALaterWaitStepAndIsCheckedAtTheStepThePersonHandled() {
        FakeSurface done = happySurface();
        Answering didIt = new Answering(ApprovalGate.Answer.DONE_BY_PERSON, () -> {
            done.location = OVERVIEW;
            done.text = RESULT_TEXT;
        });
        assertThat(replayWithGate(done, withProofAsALaterWait(), didIt))
                .isInstanceOf(ReplayResult.Success.class);

        FakeSurface notDone = happySurface();
        var failure = failed(replayWithGate(notDone, withProofAsALaterWait(),
                new Answering(ApprovalGate.Answer.DONE_BY_PERSON)));

        // Named at the person's step, not at some later step that merely shares the wait.
        assertThat(failure.kind()).isEqualTo(FailureKind.HUMAN_STEP_NOT_DONE);
        assertThat(failure.stepId()).isEqualTo("step-4-click");
    }

    @Test
    void aStepWithNothingToCheckItAgainstIsNeverReportedAsDone() {
        FakeSurface surface = happySurface();
        Answering gate = new Answering(ApprovalGate.Answer.DONE_BY_PERSON, () -> surface.location = OVERVIEW);

        // withRiskyClick(recipe()) has no expectation on the click and no wait after it.
        var failure = failed(replayWithGate(surface, withRiskyClick(recipe()), gate));

        assertThat(failure.kind()).isEqualTo(FailureKind.HUMAN_STEP_UNVERIFIED);
        assertThat(failure.observed()).contains("cannot be told whether it was done");
        assertThat(surface.acted).noneMatch(action -> action instanceof Action.Read);
    }

    @Test
    void whenNobodyAnswersTheRunStopsAndSaysSoAndDoesNothing() {
        FakeSurface surface = happySurface();

        var failure = failed(replayWithGate(surface, withRiskyClick(recipe()),
                new Answering(ApprovalGate.Answer.NO_ANSWER)));

        assertThat(failure.kind()).isEqualTo(FailureKind.NO_HUMAN_RESPONSE);
        assertThat(failure.observed()).contains("no person answered").contains("Nothing was done");
        assertThat(surface.acted).noneMatch(action -> action instanceof Action.Click);
    }

    @Test
    void aPersonWhoWentElsewhereStopsTheRunInsteadOfLettingItCarryOn() {
        FakeSurface surface = happySurface();
        // They also "did" the step on the paused page, so the page looks as if all is well.
        Answering gate = new Answering(ApprovalGate.Answer.OFF_SCRIPT, () -> surface.location = OVERVIEW);

        var failure = failed(replayWithGate(surface, withRiskyClick(recipe()), gate));

        assertThat(failure.kind()).isEqualTo(FailureKind.HUMAN_OFF_SCRIPT);
        assertThat(failure.stepId()).isEqualTo("step-4-click");
        assertThat(surface.acted).noneMatch(action -> action instanceof Action.Click);
        assertThat(surface.acted).noneMatch(action -> action instanceof Action.Read);
    }

    @Test
    void aPolicyRuleCanAskForAPersonEvenWhenTheRecipeDoesNotSayRisky() {
        FakeSurface surface = happySurface();
        PolicyEngine guarded = new PolicyEngine(new Policy(
                List.of(BASE), List.of("/parabank/index.htm", "/parabank/overview.htm"),
                Set.of(ActionType.values()),
                List.of(new Policy.RiskRule(ActionType.CLICK, "/parabank/index.htm", "button",
                        "(?i)^log in$", "logging in is sensitive"))));
        Answering gate = new Answering(ApprovalGate.Answer.APPROVE);

        ReplayResult result = new ReplayEngine(surface, guarded, new Redactor(), BASE,
                ReplayEngine.DEFAULT_SLOW_STEP_MS, gate).run(recipe(), INPUTS, "run-1");

        assertThat(result).isInstanceOf(ReplayResult.Success.class);
        assertThat(gate.asked).singleElement()
                .satisfies(question -> assertThat(question.reason()).contains("logging in is sensitive"));
    }

    @Test
    void aPolicyDenialNeverReachesAPerson() {
        FakeSurface surface = happySurface();
        Answering gate = new Answering(ApprovalGate.Answer.APPROVE);

        var failure = failed(new ReplayEngine(surface, policy(), new Redactor(), "https://evil.example",
                ReplayEngine.DEFAULT_SLOW_STEP_MS, gate).run(withRiskyClick(recipe()), INPUTS, "run-1"));

        assertThat(failure.kind()).isEqualTo(FailureKind.POLICY_DENIED);
        assertThat(gate.asked).isEmpty();
    }

    // ── recoverable: slow, but it worked ─────────────────────────────────────

    private static ReplayResult replayWithSlowThreshold(FakeSurface surface, long slowStepMs) {
        return new ReplayEngine(surface, policy(), new Redactor(), BASE, slowStepMs).run(recipe(), INPUTS, "run-1");
    }

    @Test
    void aStepThatWorkedOnlyAfterWaitingIsNotedAndTheRunCarriesOn() {
        FakeSurface surface = happySurface();
        surface.sleepOnClickMs = 80;

        ReplayResult result = replayWithSlowThreshold(surface, 20);

        assertThat(result).isInstanceOf(ReplayResult.Success.class);
        assertThat(result.recoveries()).singleElement().satisfies(recovery -> {
            assertThat(recovery.stepId()).isEqualTo("step-4-click");
            assertThat(recovery.condition()).contains("took");
            assertThat(recovery.response()).contains("waited");
        });
    }

    @Test
    void aRunThatWasNeverSlowHasNoRecoveries() {
        ReplayResult result = replayWithSlowThreshold(happySurface(), 5_000);

        assertThat(result).isInstanceOf(ReplayResult.Success.class);
        assertThat(result.recoveries()).isEmpty();
    }

    @Test
    void whatWasWaitedOutIsStillReportedWhenTheRunLaterFails() {
        FakeSurface surface = happySurface();
        surface.sleepOnClickMs = 80;
        surface.cannotFind.add("cell balance");

        ReplayResult result = replayWithSlowThreshold(surface, 20);

        assertThat(result).isInstanceOf(ReplayResult.Failure.class);
        assertThat(result.recoveries()).extracting(ReplayResult.Recovery::stepId).containsExactly("step-4-click");
    }

    // ── business outcomes ────────────────────────────────────────────────────

    @Test
    void aKnownRejectionIsAnAnswerNotAFailureAndStopsRightThere() {
        FakeSurface surface = happySurface();
        surface.clickShows.put("button Log In", BAD_LOGIN_TEXT);   // the bank says no; the page does not change

        ReplayResult result = replay(surface, recipe(), INPUTS);

        assertThat(result).isInstanceOf(ReplayResult.BusinessOutcome.class);
        var outcome = (ReplayResult.BusinessOutcome) result;
        assertThat(outcome.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(outcome.stepId()).isEqualTo("step-4-click");
        assertThat(surface.acted).noneMatch(action -> action instanceof Action.Read);
    }

    @Test
    void aMissingControlIsAKnownAnswerWhenTheScreenShowsOne() {
        FakeSurface surface = happySurface();
        surface.text = BAD_LOGIN_TEXT;                  // already showing before the failing step
        surface.cannotFind.add("cell balance");

        ReplayResult result = replay(surface, recipe(), INPUTS);

        assertThat(result).isInstanceOf(ReplayResult.BusinessOutcome.class);
        assertThat(((ReplayResult.BusinessOutcome) result).code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void aPolicyRefusalIsNeverTurnedIntoABusinessOutcome() {
        FakeSurface surface = happySurface();
        surface.text = BAD_LOGIN_TEXT;
        surface.clickGoesTo.put("button Log In", "https://evil.example/steal");

        var failure = failed(replay(surface, recipe(), INPUTS));

        assertThat(failure.kind()).isEqualTo(FailureKind.POLICY_DENIED);
    }

    // ── inputs ───────────────────────────────────────────────────────────────

    @Test
    void aMissingRequiredInputStopsBeforeAnythingHappens() {
        FakeSurface surface = happySurface();

        var failure = failed(replay(surface, recipe(), Map.of("username", "john")));

        assertThat(failure.kind()).isEqualTo(FailureKind.INVALID_INPUT);
        assertThat(failure.observed()).contains("password");
        assertThat(surface.acted).isEmpty();
    }

    @Test
    void anInputTheRecipeDoesNotDeclareIsRejectedAsAProbableTypo() {
        FakeSurface surface = happySurface();

        var failure = failed(replay(surface, recipe(),
                Map.of("username", "john", "password", "hunter2", "pasword", "x")));

        assertThat(failure.kind()).isEqualTo(FailureKind.INVALID_INPUT);
        assertThat(failure.observed()).contains("pasword");
        assertThat(surface.acted).isEmpty();
    }

    // ── failures ─────────────────────────────────────────────────────────────

    @Test
    void aControlThatIsMissingIsReportedWithTheStepAndMaskedEvidence() {
        FakeSurface surface = happySurface();
        surface.cannotFind.add("textbox Password");

        var failure = failed(replay(surface, recipe(), INPUTS));

        assertThat(failure.kind()).isEqualTo(FailureKind.TARGET_NOT_FOUND);
        assertThat(failure.stepId()).isEqualTo("step-3-type");
        assertThat(failure.evidence()).isNotNull();
        assertThat(surface.maskedInEvidence).containsExactly(PASSWORD_BOX);
    }

    @Test
    void aStepExpectationThatNeverComesTrueIsReportedAtThatStep() {
        FakeSurface surface = happySurface();   // the page text never shows "Accounts Overview"

        var failure = failed(replay(surface,
                recipe(new Condition.TextVisible("Accounts Overview"),
                        List.of(new Capability.Output("balance", ValueType.STRING, "balance", false))),
                INPUTS));

        assertThat(failure.kind()).isEqualTo(FailureKind.EXPECTATION_NOT_MET);
        assertThat(failure.stepId()).isEqualTo("step-4-click");
    }

    @Test
    void endingOnTheWrongPageFailsTheSuccessCondition() {
        FakeSurface surface = happySurface();
        surface.clickGoesTo.put("button Log In", BASE + "/parabank/index.htm");

        var failure = failed(replay(surface, recipe(), INPUTS));

        assertThat(failure.kind()).isEqualTo(FailureKind.EXPECTATION_NOT_MET);
        assertThat(failure.expected()).contains("/parabank/overview.htm");
    }

    @Test
    void aPromisedOutputThatNoStepReadIsAFailure() {
        FakeSurface surface = happySurface();
        var outputs = List.of(new Capability.Output("balance", ValueType.STRING, "balance", false),
                new Capability.Output("accountNumber", ValueType.STRING, "number", false));

        var failure = failed(replay(surface, recipe(null, outputs), INPUTS));

        assertThat(failure.kind()).isEqualTo(FailureKind.EXPECTATION_NOT_MET);
        assertThat(failure.expected()).contains("accountNumber");
    }

    @Test
    void aSurfaceBreakdownIsReportedAndNeverShowsTheSecret() {
        FakeSurface surface = happySurface();
        surface.breakWith = new SurfaceException("could not type hunter2");

        var failure = failed(replay(surface, recipe(), INPUTS));

        assertThat(failure.kind()).isEqualTo(FailureKind.SURFACE_ERROR);
        assertThat(failure.observed()).doesNotContain("hunter2").contains("[REDACTED:password]");
    }

    // ── policy ───────────────────────────────────────────────────────────────

    @Test
    void anAddressOutsideThePolicyIsRefusedBeforeItIsOpened() {
        FakeSurface surface = happySurface();

        var failure = failed(new ReplayEngine(surface, policy(), new Redactor(), "https://evil.example")
                .run(recipe(), INPUTS, "run-1"));

        assertThat(failure.kind()).isEqualTo(FailureKind.POLICY_DENIED);
        assertThat(failure.stepId()).isEqualTo("step-1-navigate");
        assertThat(surface.acted).isEmpty();
    }

    @Test
    void aClickThatLeavesTheAllowedAreaStopsTheRun() {
        FakeSurface surface = happySurface();
        surface.clickGoesTo.put("button Log In", "https://evil.example/steal");

        var failure = failed(replay(surface, recipe(), INPUTS));

        assertThat(failure.kind()).isEqualTo(FailureKind.POLICY_DENIED);
        assertThat(failure.stepId()).isEqualTo("step-4-click");
        assertThat(surface.acted).noneMatch(action -> action instanceof Action.Read);
    }
}
