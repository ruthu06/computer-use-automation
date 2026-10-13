package com.computeruse.automation.handoff;

import com.computeruse.automation.surface.Addresses;
import com.computeruse.automation.surface.Surface;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One run, one live browser, and a way for a person to step in.
 *
 * A browser may only be used from the thread that opened it, so each session has its own
 * thread that owns the browser for its whole life. Everyone else (the web layer, a person
 * clicking "approve") talks to that thread by sending it commands and waiting for the state
 * to change. The thread, in turn, only ever waits at one place: the approval gate.
 *
 * States:
 *   AUTOMATION  the run is working on its own
 *   PAUSED      the run asked for a person and is waiting
 *   HUMAN       a person has taken the live window
 *   RESUMING    the person handed control back; the run is picking up again
 *   FINISHED    the run is over (see result or error)
 */
public final class Session {

    public enum State { AUTOMATION, PAUSED, HUMAN, RESUMING, FINISHED }

    /** The work a session carries out on its browser. */
    @FunctionalInterface
    public interface Job {
        Object run(Surface surface, ApprovalGate gate) throws Exception;
    }

    /** What the person asked for. */
    public enum Command { APPROVE, REJECT, TAKEOVER, RESUME }

    private record Message(Command command, String note) {}

    /**
     * A look at the session at one moment.
     * history    — every state the session has been in, oldest first
     * resultType — what kind of thing result is, e.g. "Success", "Failure", "BusinessOutcome",
     *              because a plain JSON object does not say
     */
    public record View(String id, State state, Intervention intervention, List<HumanTurn> humanTurns,
                       Object result, String resultType, String error, List<State> history) {}

    private static final Logger LOG = LoggerFactory.getLogger(Session.class);

    /** How long a paused run waits for a person before it gives up. */
    public static final Duration DEFAULT_HUMAN_WAIT = Duration.ofMinutes(5);

    private final String id;
    private final UnaryOperator<String> scrub;
    private final Duration humanWait;

    /** Called once, on the session's thread, when the session is over. */
    private volatile Consumer<View> onFinish = view -> { };

    private final Object lock = new Object();
    private final BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();

    // Guarded by lock.
    private State state = State.AUTOMATION;
    private long version;
    private Intervention pending;
    private Object result;
    private String error;
    private final List<State> history = new ArrayList<>(List.of(State.AUTOMATION));
    private final List<HumanTurn> humanTurns = new ArrayList<>();

    // Used only by the session's own thread.
    private Surface surface;
    private Thread thread;

    /** @param scrub applied to any error text before anyone can read it */
    public Session(String id, UnaryOperator<String> scrub) {
        this(id, scrub, DEFAULT_HUMAN_WAIT);
    }

    /**
     * @param humanWait how long a run that has paused waits for someone to answer or take over.
     *                  When it passes, the run is stopped and nothing is done. Zero or less waits forever.
     */
    public Session(String id, UnaryOperator<String> scrub, Duration humanWait) {
        this.id = id;
        this.scrub = scrub;
        this.humanWait = humanWait;
    }

    /** Asks to be told, with the final view, when the session is over. Set before start(). */
    public void onFinish(Consumer<View> listener) {
        this.onFinish = listener;
    }

    public String id() {
        return id;
    }

    // ── starting ─────────────────────────────────────────────────────────────

    /**
     * Opens the browser and runs the job on the session's own thread.
     * Returns at once; use awaitRest() to wait until the run needs a person or is over.
     */
    public void start(Supplier<Surface> openSurface, Job job) {
        thread = new Thread(() -> {
            try (Surface opened = openSurface.get()) {
                surface = opened;
                finish(job.run(opened, this::ask), null);
            } catch (Throwable problem) {
                finish(null, scrub.apply(problem.getClass().getSimpleName() + ": " + problem.getMessage()));
            }
        }, "session-" + id);
        thread.setDaemon(true);
        thread.start();
    }

    // ── what the web layer calls ─────────────────────────────────────────────

    public View view() {
        synchronized (lock) {
            return snapshot();
        }
    }

    /** Waits until the session needs a person, has one, or is over, or until the time is up. */
    public View awaitRest(Duration most) {
        synchronized (lock) {
            waitFor(most, -1);
            return snapshot();
        }
    }

    /**
     * Sends a person's decision and waits until the session settles again.
     *
     * @throws IllegalStateException if the session is not in a state that can take this command
     */
    public View send(Command command, String note, Duration most) {
        synchronized (lock) {
            State needed = command == Command.RESUME ? State.HUMAN : State.PAUSED;
            if (state != needed) {
                throw new IllegalStateException(
                        "cannot " + command.name().toLowerCase() + " while the session is " + state);
            }
            long before = version;
            inbox.add(new Message(command, note));
            waitFor(most, before);
            return snapshot();
        }
    }

    /** Stops a session that is no longer wanted. Its browser is closed by its own thread. */
    public void close() {
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
    }

    // ── what the session's own thread does ───────────────────────────────────

    /** The one place a run waits. Called by the job, on the session's thread. */
    private ApprovalGate.Answer ask(Intervention intervention) {
        move(State.PAUSED, intervention);

        String locationBefore = null;
        Instant startedAt = null;
        boolean personHasWindow = false;
        while (true) {
            Message message;
            try {
                // Waiting for someone to turn up has a limit. Once a person holds the window it does not:
                // they may need a long time, and taking the window back from them would be worse.
                boolean limited = !personHasWindow && !humanWait.isZero() && !humanWait.isNegative();
                message = limited ? inbox.poll(humanWait.toMillis(), TimeUnit.MILLISECONDS) : inbox.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                settle();
                return ApprovalGate.Answer.REJECT;
            }

            if (message == null) {
                LOG.warn("session {} stopped: paused at {} ({}) and nobody answered within {}",
                        id, intervention.stepId(), scrub.apply(String.valueOf(intervention.reason())), humanWait);
                settle();
                return ApprovalGate.Answer.NO_ANSWER;
            }

            switch (message.command()) {
                case APPROVE -> {
                    settle();
                    return ApprovalGate.Answer.APPROVE;
                }
                case REJECT -> {
                    settle();
                    return ApprovalGate.Answer.REJECT;
                }
                case TAKEOVER -> {
                    surface.beginHandoff();
                    locationBefore = surface.location();
                    startedAt = Instant.now();
                    personHasWindow = true;
                    move(State.HUMAN, intervention);
                }
                case RESUME -> {
                    List<String> events = new ArrayList<>(surface.endHandoff());

                    // The run paused on one page. Any other page, in any tab, means the person went elsewhere.
                    String home = Addresses.page(locationBefore);
                    List<String> elsewhere = surface.visitedDuringHandoff().stream()
                            .filter(reached -> !reached.equals(home))
                            .toList();
                    elsewhere.forEach(reached -> events.add("went to " + reached));
                    if (!elsewhere.isEmpty()) {
                        LOG.warn("session {}: a person went elsewhere while holding the window: {}", id, elsewhere);
                    }

                    synchronized (lock) {
                        humanTurns.add(new HumanTurn(message.note(), locationBefore, surface.location(),
                                events, startedAt, Instant.now()));
                    }
                    settle();
                    return elsewhere.isEmpty()
                            ? ApprovalGate.Answer.DONE_BY_PERSON
                            : ApprovalGate.Answer.OFF_SCRIPT;
                }
            }
        }
    }

    private void settle() {
        move(State.RESUMING, null);
        move(State.AUTOMATION, null);
    }

    private void move(State next, Intervention waitingOn) {
        synchronized (lock) {
            state = next;
            pending = waitingOn;
            history.add(next);
            version++;
            lock.notifyAll();
        }
    }

    private void finish(Object outcome, String problem) {
        View last;
        synchronized (lock) {
            result = outcome;
            error = problem;
            state = State.FINISHED;
            pending = null;
            history.add(State.FINISHED);
            version++;
            last = snapshot();
        }
        // Written before anyone is told the session is over, so a caller who sees FINISHED can find the record.
        try {
            onFinish.accept(last);
        } catch (RuntimeException e) {
            LOG.warn("session {}: could not write the final record: {}", id, e.getMessage());
        }
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    // ── helpers (callers hold the lock) ──────────────────────────────────────

    /** Waits for a state a person can act on, or the end, that came after the given version. */
    private void waitFor(Duration most, long afterVersion) {
        long deadline = System.nanoTime() + most.toNanos();
        while (!(version > afterVersion && isRest(state))) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return;
            }
            try {
                lock.wait(Math.max(1, left / 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean isRest(State state) {
        return state == State.PAUSED || state == State.HUMAN || state == State.FINISHED;
    }

    private View snapshot() {
        return new View(id, state, pending, List.copyOf(humanTurns), result,
                result == null ? null : result.getClass().getSimpleName(), error, List.copyOf(history));
    }
}
