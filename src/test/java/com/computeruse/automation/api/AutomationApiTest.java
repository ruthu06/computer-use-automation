package com.computeruse.automation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.computeruse.automation.artifact.Capability;
import com.computeruse.automation.artifact.Condition;
import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Risk;
import com.computeruse.automation.artifact.Step;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.handoff.Session;
import com.computeruse.automation.result.ReplayResult;
import com.computeruse.automation.surface.Action;
import com.computeruse.automation.surface.ActionResult;
import com.computeruse.automation.surface.ActionType;
import com.computeruse.automation.surface.Conditions;
import com.computeruse.automation.surface.Evidence;
import com.computeruse.automation.surface.Observation;
import com.computeruse.automation.surface.Surface;
import com.computeruse.automation.surface.WaitTimedOutException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

/**
 * The REST door, called as plain Java with a pretend browser behind it.
 * No web server and no real browser: what is tested is the hand-off between a person and a run.
 */
class AutomationApiTest {

    private static final Target OPEN_BUTTON =
            new Target("button Open", List.of(new Locator.Structure("#open")));

    @TempDir
    Path folder;

    private AutomationApi api;
    private final List<Action> acted = new ArrayList<>();

    /** A browser that follows navigation, notes what it was asked to do, and takes real "screenshots". */
    private final class FakeSurface implements Surface {
        private final Path evidenceDir;
        private String location = "about:blank";

        FakeSurface(Path evidenceDir) { this.evidenceDir = evidenceDir; }

        @Override public Observation observe() { return new Observation(location, "t", "", List.of(), null); }

        @Override public String location() { return location; }

        @Override public ActionResult act(Action action) {
            acted.add(action);
            if (action instanceof Action.Navigate nav) {
                location = nav.url();
            }
            if (action instanceof Action.Wait wait && !Conditions.holds(wait.until(), observe())) {
                throw new WaitTimedOutException(wait.until());
            }
            return new ActionResult(null, null);
        }

        @Override public Evidence captureEvidence(String label, List<Target> sensitiveTargets) {
            try {
                Files.createDirectories(evidenceDir);
                Path shot = Files.write(evidenceDir.resolve(label + ".png"), new byte[] {1, 2, 3});
                return new Evidence(shot.toString(), null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override public void beginHandoff() { }

        @Override public List<String> endHandoff() { return List.of("clicked button#open"); }

        @Override public void close() { }
    }

    @BeforeEach
    void setUp() {
        AutomationProperties properties = new AutomationProperties(
                "http://localhost:8080", "ParaBank", "2.1.0", "/parabank/index.htm",
                folder.resolve("artifacts").toString(), folder.resolve("evidence").toString(),
                "policy/parabank.json", true, "claude-opus-5-5", 5, 10, 300);

        CapabilityStore store = new CapabilityStore(folder.resolve("artifacts"));
        store.save(riskyRecipe());

        api = new AutomationApi(properties, store, new SessionManager(
                (evidenceDir, scrub) -> new FakeSurface(evidenceDir)));
    }

    /** Open the home page, then do one thing the recipe says must be approved. */
    private static Capability riskyRecipe() {
        return new Capability(Capability.SCHEMA_VERSION, "bank.open-thing", "1.0.0", "Open a thing", "Does a risky thing",
                new Capability.App("ParaBank", "2.1.0", "web", "/parabank/index.htm"),
                List.of(), List.of(),
                List.of(new Step("step-1-navigate", "open the home page", ActionType.NAVIGATE, null,
                                "/parabank/index.htm", null, Risk.SAFE, null),
                        // What must be true afterwards. Without it, a person's word could not be checked.
                        new Step("step-2-click", "press the button", ActionType.CLICK, OPEN_BUTTON, null, null,
                                Risk.RISKY, new Condition.LocationContains("/parabank/index.htm"))),
                List.of(),
                new Condition.LocationContains("/parabank/index.htm"),
                null);
    }

    private static int statusOf(ResponseStatusException e) {
        return e.getStatusCode().value();
    }

    private boolean buttonWasPressed() {
        return acted.stream().anyMatch(action -> action instanceof Action.Click);
    }

    // ── recipes ──────────────────────────────────────────────────────────────

    @Test
    void theSavedRecipesAreListedWithWhetherTheyNeedApproval() {
        var summaries = api.capabilities();

        assertThat(summaries).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo("bank.open-thing");
            assertThat(summary.needsApproval()).isTrue();
        });
        assertThat(api.capability("bank.open-thing").name()).isEqualTo("Open a thing");
    }

    @Test
    void anUnknownRecipeIsNotFound() {
        assertThatThrownBy(() -> api.capability("nope")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(statusOf(e)).isEqualTo(404));
        assertThatThrownBy(() -> api.replay("nope", new AutomationApi.ReplayRequest(Map.of())))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(statusOf(e)).isEqualTo(404));
    }

    // ── the approval pause ───────────────────────────────────────────────────

    @Test
    void aRiskyReplayPausesBeforeTheRiskyStepAndShowsAScreenshot() throws IOException {
        Session.View view = api.replay("bank.open-thing", new AutomationApi.ReplayRequest(Map.of()));

        assertThat(view.state()).isEqualTo(Session.State.PAUSED);
        assertThat(view.intervention().stepId()).isEqualTo("step-2-click");
        assertThat(view.intervention().reason()).contains("risky");
        assertThat(buttonWasPressed()).isFalse();
        assertThat(api.screenshot(view.id()).getBody()).containsExactly(1, 2, 3);
    }

    @Test
    void approvingDoesTheStepAndFinishesWithARecordOnDisk() {
        String id = api.replay("bank.open-thing", new AutomationApi.ReplayRequest(Map.of())).id();

        Session.View view = api.resume(id, new AutomationApi.ResumeRequest(true, null));

        assertThat(view.state()).isEqualTo(Session.State.FINISHED);
        assertThat(view.result()).isInstanceOf(ReplayResult.Success.class);
        assertThat(view.resultType()).isEqualTo("Success");
        assertThat(buttonWasPressed()).isTrue();
        assertThat(folder.resolve("evidence").resolve(id).resolve("replay.json")).exists();
    }

    @Test
    void decliningStopsTheRunAndNothingIsPressed() {
        String id = api.replay("bank.open-thing", new AutomationApi.ReplayRequest(Map.of())).id();

        Session.View view = api.resume(id, new AutomationApi.ResumeRequest(false, "not today"));

        assertThat(view.state()).isEqualTo(Session.State.FINISHED);
        assertThat(view.result()).isInstanceOfSatisfying(ReplayResult.Failure.class,
                failure -> assertThat(failure.kind()).isEqualTo(ReplayResult.FailureKind.POLICY_DENIED));
        assertThat(buttonWasPressed()).isFalse();
    }

    @Test
    void aPausedSessionDoesNotGuessTheAnswer() {
        String id = api.replay("bank.open-thing", new AutomationApi.ReplayRequest(Map.of())).id();

        assertThatThrownBy(() -> api.resume(id, null)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(statusOf(e)).isEqualTo(400));
        assertThat(api.session(id).state()).isEqualTo(Session.State.PAUSED);
    }

    // ── a person takes over ──────────────────────────────────────────────────

    @Test
    void aPersonCanTakeOverDoTheStepThemselvesAndHandBack() {
        String id = api.replay("bank.open-thing", new AutomationApi.ReplayRequest(Map.of())).id();

        Session.View taken = api.takeover(id);
        assertThat(taken.state()).isEqualTo(Session.State.HUMAN);

        Session.View done = api.resume(id, new AutomationApi.ResumeRequest(null, "pressed it myself"));

        assertThat(done.state()).isEqualTo(Session.State.FINISHED);
        assertThat(done.result()).isInstanceOf(ReplayResult.Success.class);
        assertThat(buttonWasPressed()).isFalse();   // the person did it, so the run did not
        assertThat(done.humanTurns()).singleElement().satisfies(turn -> {
            assertThat(turn.note()).isEqualTo("pressed it myself");
            assertThat(turn.events()).containsExactly("clicked button#open");
        });
        assertThat(done.history()).containsExactly(Session.State.AUTOMATION, Session.State.PAUSED,
                Session.State.HUMAN, Session.State.RESUMING, Session.State.AUTOMATION, Session.State.FINISHED);
    }

    @Test
    void aCommandThatDoesNotFitTheStateIsAConflict() {
        String id = api.replay("bank.open-thing", new AutomationApi.ReplayRequest(Map.of())).id();
        api.takeover(id);

        assertThatThrownBy(() -> api.takeover(id)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(statusOf(e)).isEqualTo(409));

        api.resume(id, new AutomationApi.ResumeRequest(null, null));   // finishes the run

        assertThatThrownBy(() -> api.resume(id, new AutomationApi.ResumeRequest(null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(statusOf(e)).isEqualTo(409));
    }

    @Test
    void anUnknownSessionIsNotFound() {
        assertThatThrownBy(() -> api.session("nope")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(statusOf(e)).isEqualTo(404));
    }

    // ── discovery request checks (no browser needed) ─────────────────────────

    @Test
    void aDiscoveryRequestMissingItsGoalIsRejectedBeforeAnythingOpens() {
        var request = new AutomationApi.DiscoverRequest(" ", Map.of(), Set.of(), List.of(),
                "x.y", "X", null, null, null);

        assertThatThrownBy(() -> api.discover(request)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(statusOf(e)).isEqualTo(400));
        assertThat(acted).isEmpty();
    }

    @Test
    void aSecretThatHasNoValueIsRejectedInsteadOfLeftUnprotected() {
        var request = new AutomationApi.DiscoverRequest("do it", Map.of("username", "john"), Set.of("password"),
                List.of(), "x.y", "X", null, null, null);

        assertThatThrownBy(() -> api.discover(request)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> {
                    assertThat(statusOf(e)).isEqualTo(400);
                    assertThat(e.getReason()).contains("password");
                });
        assertThat(acted).isEmpty();
    }
}
