package com.computeruse.automation.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.computeruse.automation.artifact.Capability;
import com.computeruse.automation.artifact.CapabilityJson;
import com.computeruse.automation.api.SessionManager;
import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.handoff.ApprovalGate;
import com.computeruse.automation.handoff.Session;
import com.computeruse.automation.surface.Action;
import java.time.Duration;
import com.computeruse.automation.artifact.Step;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.evidence.RunLog;
import com.computeruse.automation.policy.PolicyEngine;
import com.computeruse.automation.policy.PolicyLoader;
import com.computeruse.automation.redaction.Redactor;
import com.computeruse.automation.replay.ReplayEngine;
import com.computeruse.automation.result.ReplayResult;
import com.computeruse.automation.surface.web.PlaywrightSurface;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Replays the recipe that discovery saved, with no AI at all, and checks every way a run can end.
 *
 * Needs the bank running ({@code docker compose up -d --wait}) and a saved recipe
 * (run the discovery smoke test first). Needs NO Anthropic key: this class never touches Claude.
 * Run: {@code ./mvnw test -Psmoke -Dtest=ReplaySmokeTest}. Add {@code -Dheaded=true} to watch.
 *
 * Each run is written to evidence/&lt;run&gt;/replay.json.
 */
@Tag("smoke")
class ReplaySmokeTest {

    private static final String BASE_URL =
            System.getProperty("parabank.baseUrl", "http://localhost:8080");
    private static final Path RECIPE = Path.of("artifacts", "parabank.read-account.json");
    /** Runs go to runs/ (git-ignored). -Devidence.root=evidence keeps them where a reviewer can read them. */
    private static final Path EVIDENCE_ROOT = Path.of(System.getProperty("evidence.root", "runs"));

    /** Where one replay left its record, and what the replay returned. */
    private record Replayed(ReplayResult result, RunLog.Run run) {}

    // ── the three ways a run can end ─────────────────────────────────────────

    @Test
    void successReadsTheAccountOfACustomerItHasNeverSeen() throws IOException {
        String password = "replay-pass-1";
        String username = ParaBankUsers.register(BASE_URL, password);

        Replayed replayed = replay(loadRecipe(), username, password, surface -> { });

        assertThat(replayed.result()).isInstanceOf(ReplayResult.Success.class);
        Map<String, Object> outputs = ((ReplayResult.Success) replayed.result()).outputs();
        assertThat(outputs.get("accountNumber").toString()).matches("\\d+");
        assertThat(outputs.get("balance").toString()).matches("\\$[\\d,]+\\.\\d{2}");
        assertThat(replayed.result().recoveries()).isEmpty();
    }

    @Test
    void businessOutcomeAWrongPasswordIsTheBanksAnswerNotACrash() throws IOException {
        String username = ParaBankUsers.register(BASE_URL, "the-real-password-1");

        Replayed replayed = replay(loadRecipe(), username, "not-the-password", surface -> { });

        assertThat(replayed.result()).isInstanceOf(ReplayResult.BusinessOutcome.class);
        assertThat(((ReplayResult.BusinessOutcome) replayed.result()).code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void recoverableASlowPageIsWaitedOutAndNotedNotFailed() throws IOException {
        String password = "replay-pass-1";
        String username = ParaBankUsers.register(BASE_URL, password);

        // The bank itself is untouched: the browser holds back the account data the overview
        // page asks for, so the table appears 3.5 s late.
        Replayed replayed = replay(loadRecipe(), username, password,
                surface -> surface.injectDelay("**/services_proxy/**", 3_500));

        assertThat(replayed.result()).isInstanceOf(ReplayResult.Success.class);
        assertThat(replayed.result().recoveries()).isNotEmpty();
        // Which step felt the delay is the app's business: here the page load waits for the data.
        ReplayResult.Recovery recovery = replayed.result().recoveries().get(0);
        assertThat(recovery.stepId()).startsWith("step-");
        assertThat(recovery.condition()).contains("took");
        assertThat(recovery.response()).contains("waited");
    }

    @Test
    void driftARelabelledControlStillWorksByItsFallbackAndTheChangeIsReported() throws IOException {
        String password = "replay-pass-1";
        String username = ParaBankUsers.register(BASE_URL, password);

        // As if the bank had renamed the Log In button: the recipe's preferred locator no longer matches.
        Replayed replayed = replay(withTheLoginButtonRelabelled(loadRecipe()), username, password, surface -> { });

        assertThat(replayed.result()).isInstanceOf(ReplayResult.Success.class);   // it still worked
        assertThat(replayed.result().recoveries()).singleElement().satisfies(note -> {
            assertThat(note.stepId()).isEqualTo("step-4-click");
            assertThat(note.condition()).contains("preferred locator").contains("did not match");
            assertThat(note.response()).contains("fallback");
        });
    }

    @Test
    void hardFailureAControlThatIsGoneStopsTheRunWithAScreenshot() throws IOException {
        String password = "replay-pass-1";
        String username = ParaBankUsers.register(BASE_URL, password);

        Replayed replayed = replay(withoutTheBalanceControl(loadRecipe()), username, password, surface -> { });

        assertThat(replayed.result()).isInstanceOf(ReplayResult.Failure.class);
        var failure = (ReplayResult.Failure) replayed.result();
        assertThat(failure.kind()).isEqualTo(ReplayResult.FailureKind.TARGET_NOT_FOUND);
        assertThat(failure.stepId()).isNotNull();
        assertThat(failure.expected()).contains("Balance (removed)");
        assertThat(failure.evidence()).isNotNull();
        assertThat(Path.of(failure.evidence().screenshot())).exists();
        assertThat(Path.of(failure.evidence().snapshot())).exists();
        assertThat(Path.of(failure.evidence().screenshot()).getParent()).isEqualTo(replayed.run().dir());
    }

    // ── a person's word is not proof ─────────────────────────────────────────

    private static final Path OPEN_RECIPE = Path.of("artifacts", "parabank.open-savings-account.json");

    private static final Target PAGE_HEADING =
            new Target("heading Open New Account", List.of(new Locator.Role("heading", "Open New Account")));
    private static final Target OPEN_BUTTON =
            new Target("button Open New Account", List.of(new Locator.Role("button", "Open New Account")));

    @Test
    void aPersonWhoClickedTheWrongThingOnTheSamePageIsNotCountedAsSuccess() throws IOException {
        assumeTrue(Files.exists(OPEN_RECIPE), "no saved recipe: run the discovery over HTTP first");
        String username = ParaBankUsers.register(BASE_URL, "wrong-click-1");

        // The "person" presses a harmless control on the same page, then says they are done.
        Replayed replayed = replayWithGate(CapabilityJson.load(OPEN_RECIPE), username, "wrong-click-1", surface -> question -> {
            surface.act(new Action.Click(PAGE_HEADING));
            return ApprovalGate.Answer.DONE_BY_PERSON;
        });

        assertThat(replayed.result()).isInstanceOf(ReplayResult.Failure.class);
        var failure = (ReplayResult.Failure) replayed.result();
        assertThat(failure.kind()).isEqualTo(ReplayResult.FailureKind.HUMAN_STEP_NOT_DONE);
        assertThat(failure.stepId()).isEqualTo("step-7-click");
        assertThat(failure.evidence()).isNotNull();
    }

    @Test
    void aPersonWhoReallyDidTheStepIsCountedAsSuccessBecauseItsResultIsSeen() throws IOException {
        assumeTrue(Files.exists(OPEN_RECIPE), "no saved recipe: run the discovery over HTTP first");
        String username = ParaBankUsers.register(BASE_URL, "did-it-1");

        Replayed replayed = replayWithGate(CapabilityJson.load(OPEN_RECIPE), username, "did-it-1", surface -> question -> {
            surface.act(new Action.Click(OPEN_BUTTON));   // what the person does in the window
            return ApprovalGate.Answer.DONE_BY_PERSON;
        });

        assertThat(replayed.result()).isInstanceOf(ReplayResult.Success.class);
        assertThat(((ReplayResult.Success) replayed.result()).outputs().get("newAccountNumber").toString())
                .matches("\\d+");
    }

    @Test
    void whenNobodyAnswersTheRealStackStopsAndLeavesARecord() throws Exception {
        assumeTrue(Files.exists(OPEN_RECIPE), "no saved recipe: run the discovery over HTTP first");
        String username = ParaBankUsers.register(BASE_URL, "nobody-1");
        Capability recipe = CapabilityJson.load(OPEN_RECIPE);

        Redactor redactor = new Redactor();
        RunLog log = new RunLog(EVIDENCE_ROOT);
        RunLog.Run run = log.start();
        PolicyEngine policy = new PolicyEngine(PolicyLoader.fromResource("policy/parabank.json"));
        SessionManager sessions = new SessionManager(
                (dir, scrub) -> new PlaywrightSurface(new PlaywrightSurface.Options(dir, true, scrub)),
                Duration.ofSeconds(3));   // 3 seconds here; the real default is 5 minutes

        Session session = sessions.start(run.id(), run.dir(), redactor, (surface, gate) -> {
            ReplayResult result = new ReplayEngine(surface, policy, redactor, BASE_URL,
                    ReplayEngine.DEFAULT_SLOW_STEP_MS, gate)
                    .run(recipe, Map.of("username", username, "password", "nobody-1"), run.id());
            log.write(run, "replay.json", result, redactor::redact);
            return result;
        });

        assertThat(session.awaitRest(Duration.ofSeconds(60)).state()).isEqualTo(Session.State.PAUSED);
        Session.View over = waitUntilFinished(session);   // nobody sends anything

        assertThat(over.result()).isInstanceOfSatisfying(ReplayResult.Failure.class, failure -> {
            assertThat(failure.kind()).isEqualTo(ReplayResult.FailureKind.NO_HUMAN_RESPONSE);
            assertThat(failure.observed()).contains("no person answered");
        });
        assertThat(run.dir().resolve("session.json")).exists();
        assertThat(Files.readString(run.dir().resolve("session.json"))).contains("PAUSED").contains("FINISHED");
    }

    private static Session.View waitUntilFinished(Session session) throws InterruptedException {
        long give = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (session.view().state() != Session.State.FINISHED && System.nanoTime() < give) {
            Thread.sleep(100);
        }
        return session.view();
    }

    /** One replay where the gate is built from the live surface, so a test can act as the person. */
    private static Replayed replayWithGate(Capability recipe, String username, String password,
                                           java.util.function.Function<PlaywrightSurface, ApprovalGate> gateFor)
            throws IOException {
        Redactor redactor = new Redactor();
        RunLog log = new RunLog(EVIDENCE_ROOT);
        RunLog.Run run = log.start();

        ReplayResult result;
        try (PlaywrightSurface surface = new PlaywrightSurface(new PlaywrightSurface.Options(
                run.dir(), !Boolean.getBoolean("headed"), redactor::redact))) {
            ReplayEngine engine = new ReplayEngine(surface,
                    new PolicyEngine(PolicyLoader.fromResource("policy/parabank.json")), redactor, BASE_URL,
                    ReplayEngine.DEFAULT_SLOW_STEP_MS, gateFor.apply(surface));
            result = engine.run(recipe, Map.of("username", username, "password", password), run.id());
        }
        log.write(run, "replay.json", result, redactor::redact);
        System.out.println("result:  " + result);
        return new Replayed(result, run);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Capability loadRecipe() throws IOException {
        assumeTrue(Files.exists(RECIPE), "no saved recipe yet: run the discovery smoke test first");
        return CapabilityJson.load(RECIPE);
    }

    /**
     * One replay in a fresh browser, written to its own evidence folder and printed.
     *
     * @param beforeRun lets a test tamper with the browser first, e.g. to make a page slow
     */
    private static Replayed replay(Capability recipe, String username, String password,
                                   Consumer<PlaywrightSurface> beforeRun) throws IOException {
        Redactor redactor = new Redactor();
        RunLog log = new RunLog(EVIDENCE_ROOT);
        RunLog.Run run = log.start();

        ReplayResult result;
        long started = System.nanoTime();
        try (PlaywrightSurface surface = new PlaywrightSurface(new PlaywrightSurface.Options(
                run.dir(), !Boolean.getBoolean("headed"), redactor::redact))) {

            beforeRun.accept(surface);
            ReplayEngine engine = new ReplayEngine(surface,
                    new PolicyEngine(PolicyLoader.fromResource("policy/parabank.json")), redactor, BASE_URL);
            result = engine.run(recipe, Map.of("username", username, "password", password), run.id());
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        Path record = log.write(run, "replay.json", result, redactor::redact);
        System.out.printf("replay:  %s in %.1f s%n", result.getClass().getSimpleName(), seconds);
        System.out.println("result:  " + result);
        System.out.println("record:  " + record.toAbsolutePath());
        return new Replayed(result, run);
    }

    /** The same recipe, with a locator for a renamed button put ahead of the real ones. */
    private static Capability withTheLoginButtonRelabelled(Capability recipe) {
        List<Step> steps = recipe.steps().stream().map(step -> {
            if (!step.id().equals("step-4-click")) return step;
            List<Locator> locators = new java.util.ArrayList<>();
            locators.add(new Locator.Role("button", "Sign On"));   // the bank's new wording; not on the page
            locators.addAll(step.target().locators());
            return new Step(step.id(), step.description(), step.action(),
                    new Target(step.target().name(), locators), step.value(), step.output(), step.risk(),
                    step.expect());
        }).toList();
        return new Capability(recipe.schemaVersion(), recipe.id(), recipe.version(), recipe.name(),
                recipe.description(), recipe.app(), recipe.inputs(), recipe.outputs(), steps,
                recipe.outcomes(), recipe.successCondition(), recipe.provenance());
    }

    /** The same recipe, as if the bank had removed the balance from the page. */
    private static Capability withoutTheBalanceControl(Capability recipe) {
        Target gone = new Target("cell Balance (removed)",
                List.of(new Locator.Role("cell", "No Such Balance"), new Locator.Structure("#no-such-balance")));
        List<Step> steps = recipe.steps().stream()
                .map(step -> "balance".equals(step.output())
                        ? new Step(step.id(), step.description(), step.action(), gone, step.value(),
                                step.output(), step.risk(), step.expect())
                        : step)
                .toList();
        return new Capability(recipe.schemaVersion(), recipe.id(), recipe.version(), recipe.name(),
                recipe.description(), recipe.app(), recipe.inputs(), recipe.outputs(), steps,
                recipe.outcomes(), recipe.successCondition(), recipe.provenance());
    }
}
