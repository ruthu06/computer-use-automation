package com.computeruse.automation.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Risk;
import com.computeruse.automation.handoff.ApprovalGate;
import com.computeruse.automation.handoff.Intervention;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.discovery.DiscoveryLoop.Status;
import com.computeruse.automation.policy.Policy;
import com.computeruse.automation.policy.PolicyEngine;
import com.computeruse.automation.redaction.Redactor;
import com.computeruse.automation.surface.Action;
import com.computeruse.automation.surface.ActionResult;
import com.computeruse.automation.surface.ActionType;
import com.computeruse.automation.surface.Evidence;
import com.computeruse.automation.surface.Observation;
import com.computeruse.automation.surface.Surface;
import com.computeruse.automation.surface.SurfaceException;
import com.computeruse.automation.surface.UiElement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiscoveryLoopTest {

    private static final String ORIGIN = "http://localhost:8080";
    private static final String LOGIN_URL = ORIGIN + "/parabank/login.htm";
    private static final String OPEN_ACCOUNT_URL = ORIGIN + "/parabank/openaccount.htm";

    private static final Target PASSWORD_BOX =
            new Target("textbox Password", List.of(new Locator.Label("Password", "textbox")));
    private static final Target LOGIN_BUTTON =
            new Target("button Log In", List.of(new Locator.Role("button", "Log In")));
    private static final Target OPEN_BUTTON =
            new Target("button Open New Account", List.of(new Locator.Role("button", "Open New Account")));

    private static final Observation LOGIN_SCREEN = new Observation(LOGIN_URL, "Login", "Customer Login", List.of(
            new UiElement("e1", "textbox", "", null, true, PASSWORD_BOX),
            new UiElement("e2", "button", "Log In", null, true, LOGIN_BUTTON)), null);

    private static final Observation OPEN_ACCOUNT_SCREEN = new Observation(OPEN_ACCOUNT_URL, "Open", "", List.of(
            new UiElement("e1", "button", "Open New Account", null, true, OPEN_BUTTON)), null);

    private static final Observation ELSEWHERE_SCREEN =
            new Observation("https://evil.example/", "Elsewhere", "", List.of(), null);

    private static final Brain.Task TASK = new Brain.Task("log in", List.of("password"), List.of("accountNumber"));
    private static final Map<String, Object> INPUTS = Map.of("password", "hunter2");

    // ── test doubles ─────────────────────────────────────────────────────────

    /** A surface that always shows the same screen and remembers what it was asked to do. */
    private static final class FakeSurface implements Surface {
        private final Observation screen;
        final List<Action> acted = new ArrayList<>();
        String readValue = null;
        boolean failEveryAction = false;
        String failureMessage = "boom";

        FakeSurface(Observation screen) { this.screen = screen; }

        @Override public Observation observe() { return screen; }

        @Override public ActionResult act(Action action) {
            if (failEveryAction && !(action instanceof Action.Navigate)) {
                throw new SurfaceException(failureMessage);
            }
            acted.add(action);
            return new ActionResult(action instanceof Action.Read ? readValue : null, null);
        }

        @Override public Evidence captureEvidence(String label, List<Target> sensitiveTargets) { return null; }

        @Override public void close() {}
    }

    /** A brain that plays back a fixed list of moves and keeps what it was shown. */
    private static final class ScriptedBrain implements Brain {
        private final Deque<Move> script = new ArrayDeque<>();
        private final Move repeatWhenEmpty;
        final List<Observation> seen = new ArrayList<>();
        final List<List<Turn>> histories = new ArrayList<>();

        ScriptedBrain(Move... moves) { this(null, List.of(moves)); }

        private ScriptedBrain(Move repeatWhenEmpty, List<Move> moves) {
            this.repeatWhenEmpty = repeatWhenEmpty;
            script.addAll(moves);
        }

        /** A brain that gives the same move every turn, for runs that must hit the turn limit. */
        static ScriptedBrain repeating(Move move) { return new ScriptedBrain(move, List.of()); }

        @Override public Move next(Task task, Observation screen, List<Turn> history) {
            seen.add(screen);
            histories.add(new ArrayList<>(history));  // the loop keeps changing the original
            return script.isEmpty() ? repeatWhenEmpty : script.poll();
        }
    }

    private static PolicyEngine policy(Set<ActionType> allowed) {
        return new PolicyEngine(new Policy(
                List.of(ORIGIN),
                List.of("/parabank/login.htm", "/parabank/openaccount.htm"),
                allowed,
                List.of(new Policy.RiskRule(ActionType.CLICK, "/parabank/openaccount.htm",
                        "button", "(?i)^open new account$", "opens a real account"))));
    }

    private static final Set<ActionType> ALL_ACTIONS = Set.of(ActionType.values());

    private static DiscoveryLoop.Result run(FakeSurface surface, Brain brain, int maxTurns) {
        return run(surface, brain, new Redactor(), policy(ALL_ACTIONS), LOGIN_URL, maxTurns);
    }

    private static DiscoveryLoop.Result run(FakeSurface surface, Brain brain, Redactor redactor,
                                            PolicyEngine policy, String startUrl, int maxTurns) {
        return new DiscoveryLoop(surface, brain, policy, redactor, maxTurns)
                .run(new DiscoveryLoop.Request(TASK, startUrl, INPUTS, Set.of()));
    }

    /** A run in which the named inputs are secrets. */
    private static DiscoveryLoop.Result runWithSecrets(FakeSurface surface, Brain brain, Set<String> secrets) {
        return new DiscoveryLoop(surface, brain, policy(ALL_ACTIONS), new Redactor(), 10)
                .run(new DiscoveryLoop.Request(TASK, LOGIN_URL, INPUTS, secrets));
    }

    // ── how a run ends ───────────────────────────────────────────────────────

    @Test
    void completesWhenTheBrainSaysTheGoalIsReached() {
        var surface = new FakeSurface(LOGIN_SCREEN);
        var brain = new ScriptedBrain(new Move.Click("e2", "submit"), new Move.Complete("logged in"));

        var result = run(surface, brain, 10);

        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(result.message()).isEqualTo("logged in");
        assertThat(result.steps()).extracting(DiscoveryLoop.RecordedStep::action)
                .containsExactly(ActionType.NAVIGATE, ActionType.CLICK);
        assertThat(surface.acted).containsExactly(new Action.Navigate(LOGIN_URL), new Action.Click(LOGIN_BUTTON));
    }

    @Test
    void needsAHumanWhenTheBrainAsksForOne() {
        var result = run(new FakeSurface(LOGIN_SCREEN), new ScriptedBrain(new Move.AskHuman("captcha")), 10);

        assertThat(result.status()).isEqualTo(Status.NEEDS_HUMAN);
        assertThat(result.message()).isEqualTo("captcha");
    }

    @Test
    void stopsWhenOutOfTurns() {
        var brain = ScriptedBrain.repeating(new Move.Click("e2", "again"));

        var result = run(new FakeSurface(LOGIN_SCREEN), brain, 3);

        assertThat(result.status()).isEqualTo(Status.STOPPED);
        assertThat(result.message()).contains("3 turns");
        assertThat(brain.seen).hasSize(3);
    }

    @Test
    void stopsWhenThePageIsOutsideTheAllowedArea() {
        var brain = new ScriptedBrain(new Move.Complete("never asked"));

        var result = run(new FakeSurface(ELSEWHERE_SCREEN), brain, 10);

        assertThat(result.status()).isEqualTo(Status.STOPPED);
        assertThat(result.message()).contains("left the allowed area");
        assertThat(brain.seen).isEmpty();
    }

    @Test
    void refusesAStartAddressOutsideThePolicy() {
        var surface = new FakeSurface(LOGIN_SCREEN);

        var result = run(surface, new ScriptedBrain(), new Redactor(), policy(ALL_ACTIONS), "https://evil.example/", 10);

        assertThat(result.status()).isEqualTo(Status.STOPPED);
        assertThat(result.message()).contains("start address refused");
        assertThat(surface.acted).isEmpty();
    }

    // ── policy ───────────────────────────────────────────────────────────────

    @Test
    void theRiskyButtonIsNeverClickedAndNeedsAHuman() {
        var surface = new FakeSurface(OPEN_ACCOUNT_SCREEN);
        var brain = new ScriptedBrain(new Move.Click("e1", "open the account"));

        var result = run(surface, brain, 10);

        assertThat(result.status()).isEqualTo(Status.NEEDS_HUMAN);
        assertThat(result.message()).isEqualTo("opens a real account");
        assertThat(surface.acted).containsExactly(new Action.Navigate(LOGIN_URL));
    }

    private static DiscoveryLoop.Result runWithGate(FakeSurface surface, Brain brain, ApprovalGate gate) {
        return new DiscoveryLoop(surface, brain, policy(ALL_ACTIONS), new Redactor(), 10, gate)
                .run(new DiscoveryLoop.Request(TASK, LOGIN_URL, INPUTS, Set.of()));
    }

    @Test
    void anApprovedRiskyClickIsDoneAndRecordedAsRiskyForReplay() {
        var surface = new FakeSurface(OPEN_ACCOUNT_SCREEN);
        var brain = new ScriptedBrain(new Move.Click("e1", "open the account"), new Move.Complete("opened"));
        var asked = new ArrayList<Intervention>();

        var result = runWithGate(surface, brain, question -> {
            asked.add(question);
            return ApprovalGate.Answer.APPROVE;
        });

        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(surface.acted).contains(new Action.Click(OPEN_BUTTON));
        assertThat(result.steps().get(result.steps().size() - 1).risk()).isEqualTo(Risk.RISKY);
        assertThat(asked).singleElement().satisfies(question -> {
            assertThat(question.stepId()).isEqualTo("turn-1");
            assertThat(question.reason()).isEqualTo("opens a real account");
        });
    }

    @Test
    void aRiskyStepThePersonDidThemselvesIsNotRepeatedButIsStillRecorded() {
        var surface = new FakeSurface(OPEN_ACCOUNT_SCREEN);
        var brain = new ScriptedBrain(new Move.Click("e1", "open the account"), new Move.Complete("opened"));

        var result = runWithGate(surface, brain, question -> ApprovalGate.Answer.DONE_BY_PERSON);

        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(surface.acted).noneMatch(action -> action instanceof Action.Click);
        assertThat(result.steps().get(result.steps().size() - 1).risk()).isEqualTo(Risk.RISKY);
        assertThat(brain.histories.get(1)).singleElement()
                .satisfies(turn -> assertThat(turn.outcome()).isEqualTo("a person did this step"));
    }

    @Test
    void aPersonWhoWentElsewhereEndsDiscoveryForAPersonAndNothingIsRecorded() {
        var surface = new FakeSurface(OPEN_ACCOUNT_SCREEN);
        var brain = new ScriptedBrain(new Move.Click("e1", "open the account"));

        var result = runWithGate(surface, brain, question -> ApprovalGate.Answer.OFF_SCRIPT);

        assertThat(result.status()).isEqualTo(Status.NEEDS_HUMAN);
        assertThat(result.message()).contains("another page or tab");
        assertThat(surface.acted).noneMatch(action -> action instanceof Action.Click);
        assertThat(result.steps()).noneMatch(step -> step.risk() == Risk.RISKY);
    }

    @Test
    void aDeclinedRiskyClickEndsTheRunForAPerson() {
        var surface = new FakeSurface(OPEN_ACCOUNT_SCREEN);
        var brain = new ScriptedBrain(new Move.Click("e1", "open the account"));

        var result = runWithGate(surface, brain, question -> ApprovalGate.Answer.REJECT);

        assertThat(result.status()).isEqualTo(Status.NEEDS_HUMAN);
        assertThat(surface.acted).noneMatch(action -> action instanceof Action.Click);
    }

    @Test
    void aDeniedActionIsNotRunAndTheBrainIsToldWhy() {
        var surface = new FakeSurface(LOGIN_SCREEN);
        var noSelect = policy(Set.of(ActionType.NAVIGATE, ActionType.CLICK, ActionType.TYPE));
        var brain = new ScriptedBrain(new Move.Select("e1", "SAVINGS", "pick"), new Move.Complete("gave up"));

        var result = run(surface, brain, new Redactor(), noSelect, LOGIN_URL, 10);

        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(surface.acted).containsExactly(new Action.Navigate(LOGIN_URL));
        assertThat(brain.histories.get(1)).singleElement()
                .satisfies(turn -> assertThat(turn.outcome()).startsWith("not allowed"));
    }

    // ── feedback to the brain ────────────────────────────────────────────────

    @Test
    void aHandleThatDoesNotExistIsFedBackNotRun() {
        var surface = new FakeSurface(LOGIN_SCREEN);
        var brain = new ScriptedBrain(new Move.Click("e99", "?"), new Move.Complete("done"));

        run(surface, brain, 10);

        assertThat(surface.acted).containsExactly(new Action.Navigate(LOGIN_URL));
        assertThat(brain.histories.get(1)).singleElement().satisfies(turn -> {
            assertThat(turn.outcome()).startsWith("rejected");
            assertThat(turn.outcome()).contains("e99");
        });
    }

    @Test
    void aSurfaceFailureIsFedBackAndTheStepIsNotRecorded() {
        var surface = new FakeSurface(LOGIN_SCREEN);
        surface.failEveryAction = true;
        var brain = new ScriptedBrain(new Move.Click("e2", "submit"), new Move.Complete("done"));

        var result = run(surface, brain, 10);

        assertThat(result.steps()).extracting(DiscoveryLoop.RecordedStep::action).containsExactly(ActionType.NAVIGATE);
        assertThat(brain.histories.get(1)).singleElement()
                .satisfies(turn -> assertThat(turn.outcome()).isEqualTo("failed: boom"));
    }

    // ── what is kept ─────────────────────────────────────────────────────────

    @Test
    void theRealPasswordIsTypedButOnlyThePlaceholderIsRecorded() {
        var surface = new FakeSurface(LOGIN_SCREEN);
        var brain = new ScriptedBrain(new Move.Type("e1", "{{password}}", "fill"), new Move.Complete("done"));

        var result = run(surface, brain, 10);

        assertThat(surface.acted).contains(new Action.Type(PASSWORD_BOX, "hunter2"));
        assertThat(result.steps().get(1).value()).isEqualTo("{{password}}");
    }

    @Test
    void aReadFillsTheNamedOutput() {
        var surface = new FakeSurface(LOGIN_SCREEN);
        surface.readValue = "14121";
        var brain = new ScriptedBrain(new Move.Read("e2", "accountNumber", "read it"), new Move.Complete("done"));

        var result = run(surface, brain, 10);

        assertThat(result.outputs()).containsExactly(Map.entry("accountNumber", "14121"));
        assertThat(result.steps().get(1).output()).isEqualTo("accountNumber");
    }

    // ── secrets ──────────────────────────────────────────────────────────────

    @Test
    void aSensitiveInputIsHiddenFromTheBrainByTheRunItself() {
        var leaky = new Observation(LOGIN_URL, "Login", "your password is hunter2", List.of(), null);

        var unprotected = new ScriptedBrain(new Move.Complete("done"));
        runWithSecrets(new FakeSurface(leaky), unprotected, Set.of());
        assertThat(unprotected.seen.get(0).text()).contains("hunter2");   // proves the leak is real without it

        var protectedBrain = new ScriptedBrain(new Move.Complete("done"));
        runWithSecrets(new FakeSurface(leaky), protectedBrain, Set.of("password"));
        assertThat(protectedBrain.seen.get(0).text())
                .doesNotContain("hunter2")
                .contains("[REDACTED:password]");
    }

    @Test
    void aSecretInsideAnErrorMessageNeverReachesTheHistory() {
        var surface = new FakeSurface(LOGIN_SCREEN);
        surface.failEveryAction = true;
        surface.failureMessage = "could not type hunter2";
        var brain = new ScriptedBrain(new Move.Click("e2", "submit"), new Move.Complete("done"));

        runWithSecrets(surface, brain, Set.of("password"));

        assertThat(brain.histories.get(1)).singleElement().satisfies(turn ->
                assertThat(turn.outcome()).doesNotContain("hunter2").contains("[REDACTED:password]"));
    }

    @Test
    void aSensitiveNameWithNoValueIsRejectedInsteadOfLeftUnprotected() {
        assertThatThrownBy(() -> new DiscoveryLoop.Request(TASK, LOGIN_URL, INPUTS, Set.of("pin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pin");
    }
}
