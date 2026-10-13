package com.computeruse.automation.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.handoff.Session.State;
import com.computeruse.automation.surface.Action;
import com.computeruse.automation.surface.Addresses;
import com.computeruse.automation.surface.ActionResult;
import com.computeruse.automation.surface.Evidence;
import com.computeruse.automation.surface.Observation;
import com.computeruse.automation.surface.Surface;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SessionTest {

    private static final Duration PATIENCE = Duration.ofSeconds(5);
    private static final Intervention QUESTION =
            new Intervention("step-4-click", "opens a real account", "http://localhost:8080/parabank/openaccount.htm", null);

    /** A pretend browser that remembers whether it was handed to a person. */
    private static final class FakeSurface implements Surface {
        final AtomicBoolean handedOver = new AtomicBoolean();
        final AtomicBoolean handedBack = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile String location = "http://localhost:8080/parabank/openaccount.htm";
        volatile List<String> visited = List.of();   // pages the person reached, in any tab

        @Override public List<String> visitedDuringHandoff() { return visited; }

        @Override public Observation observe() { return new Observation(location, "t", "", List.of(), null); }
        @Override public String location() { return location; }
        @Override public ActionResult act(Action action) { return new ActionResult(null, null); }
        @Override public Evidence captureEvidence(String label, List<Target> sensitiveTargets) { return null; }

        @Override public void beginHandoff() { handedOver.set(true); }

        @Override public List<String> endHandoff() {
            handedBack.set(true);
            location = "http://localhost:8080/parabank/overview.htm";   // the person moved the page on
            return List.of("chose \"SAVINGS\" in select#type", "clicked input#openAccount");
        }

        @Override public void close() { closed.set(true); }
    }

    /** A job that does one risky thing, asks about it, and returns the answer it got. */
    private static final Session.Job ASK_ONCE = (surface, gate) -> gate.ask(QUESTION);

    private static Session started(FakeSurface surface, Session.Job job) {
        Session session = new Session("s1", text -> text.replace("hunter2", "[REDACTED]"));
        session.start(() -> surface, job);
        return session;
    }

    @Test
    void aRunThatNeedsAPersonPausesAndShowsWhy() {
        Session session = started(new FakeSurface(), ASK_ONCE);

        Session.View view = session.awaitRest(PATIENCE);

        assertThat(view.state()).isEqualTo(State.PAUSED);
        assertThat(view.intervention()).isEqualTo(QUESTION);
        assertThat(view.result()).isNull();
    }

    @Test
    void approvingLetsTheRunDoTheActionAndFinish() {
        FakeSurface surface = new FakeSurface();
        Session session = started(surface, ASK_ONCE);
        session.awaitRest(PATIENCE);

        Session.View view = session.send(Session.Command.APPROVE, null, PATIENCE);

        assertThat(view.state()).isEqualTo(State.FINISHED);
        assertThat(view.result()).isEqualTo(ApprovalGate.Answer.APPROVE);
        assertThat(view.history()).containsExactly(State.AUTOMATION, State.PAUSED, State.RESUMING,
                State.AUTOMATION, State.FINISHED);
        assertThat(surface.handedOver).isFalse();
    }

    @Test
    void rejectingTellsTheRunNotToDoIt() {
        Session session = started(new FakeSurface(), ASK_ONCE);
        session.awaitRest(PATIENCE);

        Session.View view = session.send(Session.Command.REJECT, null, PATIENCE);

        assertThat(view.result()).isEqualTo(ApprovalGate.Answer.REJECT);
    }

    @Test
    void aPersonCanTakeTheWindowAndHandItBackWithWhatTheyDidRecorded() {
        FakeSurface surface = new FakeSurface();
        Session session = started(surface, ASK_ONCE);
        session.awaitRest(PATIENCE);

        Session.View taken = session.send(Session.Command.TAKEOVER, null, PATIENCE);
        assertThat(taken.state()).isEqualTo(State.HUMAN);
        assertThat(surface.handedOver).isTrue();

        Session.View done = session.send(Session.Command.RESUME, "opened it myself", PATIENCE);

        assertThat(done.state()).isEqualTo(State.FINISHED);
        assertThat(done.result()).isEqualTo(ApprovalGate.Answer.DONE_BY_PERSON);
        assertThat(done.history()).containsExactly(State.AUTOMATION, State.PAUSED, State.HUMAN,
                State.RESUMING, State.AUTOMATION, State.FINISHED);
        assertThat(done.humanTurns()).singleElement().satisfies(turn -> {
            assertThat(turn.note()).isEqualTo("opened it myself");
            assertThat(turn.locationBefore()).endsWith("/openaccount.htm");
            assertThat(turn.locationAfter()).endsWith("/overview.htm");
            assertThat(turn.events()).containsExactly("chose \"SAVINGS\" in select#type", "clicked input#openAccount");
            assertThat(turn.startedAt()).isBeforeOrEqualTo(turn.endedAt());
        });
    }

    // ── nobody turns up ──────────────────────────────────────────────────────

    private static Session shortPatience(FakeSurface surface, Duration humanWait) {
        Session session = new Session("s1", text -> text, humanWait);
        session.start(() -> surface, ASK_ONCE);
        return session;
    }

    @Test
    void ifNobodyAnswersInTimeTheRunIsStoppedAndNothingIsApproved() {
        FakeSurface surface = new FakeSurface();
        Session session = shortPatience(surface, Duration.ofMillis(150));

        Session.View paused = session.awaitRest(PATIENCE);
        assertThat(paused.state()).isEqualTo(State.PAUSED);

        Session.View over = session.awaitRest(PATIENCE);   // returns when the time has run out
        // awaitRest returns at once for PAUSED, so wait for the end explicitly
        long give = System.nanoTime() + PATIENCE.toNanos();
        while (over.state() != State.FINISHED && System.nanoTime() < give) {
            over = session.view();
            Thread.onSpinWait();
        }

        assertThat(over.state()).isEqualTo(State.FINISHED);
        assertThat(over.result()).isEqualTo(ApprovalGate.Answer.NO_ANSWER);
        assertThat(surface.handedOver).isFalse();
        assertThat(surface.closed).isTrue();
    }

    @Test
    void oncePersonHasTheWindowThereIsNoTimeLimit() throws Exception {
        FakeSurface surface = new FakeSurface();
        Session session = shortPatience(surface, Duration.ofMillis(150));
        session.awaitRest(PATIENCE);
        session.send(Session.Command.TAKEOVER, null, PATIENCE);

        Thread.sleep(500);   // far longer than the limit

        assertThat(session.view().state()).isEqualTo(State.HUMAN);
        Session.View done = session.send(Session.Command.RESUME, "took my time", PATIENCE);
        assertThat(done.result()).isEqualTo(ApprovalGate.Answer.DONE_BY_PERSON);
    }

    @Test
    void theWholeStoryIsHandedToTheFinishHookForTheRecord() {
        FakeSurface surface = new FakeSurface();
        List<Session.View> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        Session session = new Session("s1", text -> text, Duration.ofMillis(100));
        session.onFinish(seen::add);
        session.start(() -> surface, ASK_ONCE);

        long give = System.nanoTime() + PATIENCE.toNanos();
        while (seen.isEmpty() && System.nanoTime() < give) {
            Thread.onSpinWait();
        }

        assertThat(seen).singleElement().satisfies(view -> {
            assertThat(view.state()).isEqualTo(State.FINISHED);
            assertThat(view.result()).isEqualTo(ApprovalGate.Answer.NO_ANSWER);
            assertThat(view.history()).containsExactly(State.AUTOMATION, State.PAUSED, State.RESUMING,
                    State.AUTOMATION, State.FINISHED);
        });
    }

    // ── a person who goes somewhere else ─────────────────────────────────────

    private Session.View takeOverAndHandBack(FakeSurface surface) {
        Session session = started(surface, ASK_ONCE);
        session.awaitRest(PATIENCE);
        session.send(Session.Command.TAKEOVER, null, PATIENCE);
        return session.send(Session.Command.RESUME, "back", PATIENCE);
    }

    @Test
    void aPersonWhoStaysOnThePausedPageIsStillDoneByPerson() {
        var surface = new FakeSurface();
        // The same page, reloaded with a query and a session id: still the same page.
        surface.visited = List.of("http://localhost:8080/parabank/openaccount.htm");

        Session.View view = takeOverAndHandBack(surface);

        assertThat(view.result()).isEqualTo(ApprovalGate.Answer.DONE_BY_PERSON);
        assertThat(view.humanTurns().get(0).events()).noneMatch(event -> event.startsWith("went to"));
    }

    @Test
    void aPersonWhoVisitsAnotherPageIsOffScriptAndSaidSo() {
        var surface = new FakeSurface();
        surface.visited = List.of("http://localhost:8080/parabank/openaccount.htm",
                "http://localhost:8080/parabank/billpay.htm");

        Session.View view = takeOverAndHandBack(surface);

        assertThat(view.result()).isEqualTo(ApprovalGate.Answer.OFF_SCRIPT);
        assertThat(view.humanTurns().get(0).events())
                .contains("went to http://localhost:8080/parabank/billpay.htm")
                .doesNotContain("went to http://localhost:8080/parabank/openaccount.htm");
    }

    @Test
    void aSecondTabIsOffScriptEvenIfItNeverLeftTheSameAddress() {
        var surface = new FakeSurface();
        surface.visited = List.of(Addresses.ANOTHER_TAB);

        Session.View view = takeOverAndHandBack(surface);

        assertThat(view.result()).isEqualTo(ApprovalGate.Answer.OFF_SCRIPT);
        assertThat(view.humanTurns().get(0).events()).contains("went to another tab or window");
    }

    @Test
    void aCommandThatDoesNotFitTheStateIsRefused() {
        Session session = started(new FakeSurface(), ASK_ONCE);
        session.awaitRest(PATIENCE);

        assertThatThrownBy(() -> session.send(Session.Command.RESUME, null, PATIENCE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PAUSED");

        session.send(Session.Command.TAKEOVER, null, PATIENCE);

        assertThatThrownBy(() -> session.send(Session.Command.APPROVE, null, PATIENCE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HUMAN");
    }

    @Test
    void aRunThatNeverAsksJustFinishesAndClosesItsBrowser() {
        FakeSurface surface = new FakeSurface();
        Session session = started(surface, (s, gate) -> "all done");

        Session.View view = session.awaitRest(PATIENCE);

        assertThat(view.state()).isEqualTo(State.FINISHED);
        assertThat(view.result()).isEqualTo("all done");
        assertThat(view.history()).containsExactly(State.AUTOMATION, State.FINISHED);
        assertThat(surface.closed).isTrue();
    }

    @Test
    void aRunThatCrashesReportsAnErrorWithTheSecretScrubbed() {
        Session session = started(new FakeSurface(), (s, gate) -> {
            throw new IllegalStateException("could not type hunter2");
        });

        Session.View view = session.awaitRest(PATIENCE);

        assertThat(view.state()).isEqualTo(State.FINISHED);
        assertThat(view.error()).contains("could not type [REDACTED]").doesNotContain("hunter2");
        assertThat(view.result()).isNull();
    }

    @Test
    void waitingGivesUpAtTheTimeLimitAndTheRunKeepsGoing() throws Exception {
        Session session = started(new FakeSurface(), (s, gate) -> {
            Thread.sleep(300);
            return "slow but done";
        });

        assertThat(session.awaitRest(Duration.ofMillis(30)).state()).isEqualTo(State.AUTOMATION);
        assertThat(session.awaitRest(PATIENCE).result()).isEqualTo("slow but done");
    }
}
