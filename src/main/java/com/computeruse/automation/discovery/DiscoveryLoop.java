package com.computeruse.automation.discovery;

import com.computeruse.automation.artifact.Risk;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.handoff.ApprovalGate;
import com.computeruse.automation.handoff.Intervention;
import com.computeruse.automation.policy.Decision;
import com.computeruse.automation.policy.PolicyEngine;
import com.computeruse.automation.redaction.Redactor;
import com.computeruse.automation.surface.Action;
import com.computeruse.automation.surface.ActionResult;
import com.computeruse.automation.surface.ActionType;
import com.computeruse.automation.surface.Evidence;
import com.computeruse.automation.surface.Observation;
import com.computeruse.automation.surface.Surface;
import com.computeruse.automation.surface.SurfaceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lets a brain work out how to reach a goal on a screen it has never seen.
 *
 * Each turn: look, ask the brain, check the rules, act, remember.
 * The steps that worked are what later becomes the saved recipe.
 */
public final class DiscoveryLoop {

    public enum Status {
        /** The brain said the goal was reached. */
        COMPLETED,
        /** The brain asked for a person, or the next action needs a person's approval. */
        NEEDS_HUMAN,
        /** The run was cut short: out of turns, or it left the allowed area. */
        STOPPED
    }

    /**
     * startUrl — the full address to open first
     * inputs   — the real values behind the task's input names
     * sensitiveInputs — names of inputs that are secrets, e.g. "password". Their values
     *            are hidden wherever they show up: on screen, in history, in saved files.
     */
    public record Request(Brain.Task task, String startUrl, Map<String, Object> inputs, Set<String> sensitiveInputs) {
        public Request {
            sensitiveInputs = sensitiveInputs == null ? Set.of() : Set.copyOf(sensitiveInputs);
            for (String name : sensitiveInputs) {
                // A typo here would leave a secret unprotected, so fail loudly instead.
                if (!inputs.containsKey(name)) {
                    throw new IllegalArgumentException("sensitive input '" + name + "' has no value in inputs");
                }
            }
        }
    }

    /**
     * One action that worked. value keeps the placeholder, never the real secret.
     * risk is RISKY for a step that needed a person's approval, so replay asks again.
     */
    public record RecordedStep(ActionType action, Target target, String value, String output, String reason,
                               Risk risk) {
        public RecordedStep(ActionType action, Target target, String value, String output, String reason) {
            this(action, target, value, output, reason, Risk.SAFE);
        }
    }

    /** location — the page the run ended on, or null if it never got as far as opening one */
    public record Result(Status status, String message, List<RecordedStep> steps, Map<String, String> outputs,
                         String location) {}

    private final Surface surface;
    private final Brain brain;
    private final PolicyEngine policy;
    private final Redactor redactor;
    private final int maxTurns;
    private final ApprovalGate gate;

    /** Without a gate, an action that needs approval ends the run and is never taken. */
    public DiscoveryLoop(Surface surface, Brain brain, PolicyEngine policy, Redactor redactor, int maxTurns) {
        this(surface, brain, policy, redactor, maxTurns, ApprovalGate.NEVER);
    }

    public DiscoveryLoop(Surface surface, Brain brain, PolicyEngine policy, Redactor redactor, int maxTurns,
                         ApprovalGate gate) {
        this.surface = surface;
        this.brain = brain;
        this.policy = policy;
        this.redactor = redactor;
        this.maxTurns = maxTurns;
        this.gate = gate;
    }

    public Result run(Request request) {
        // Before anything is read, sent or saved: teach the redactor this run's secrets.
        for (String name : request.sensitiveInputs()) {
            redactor.register(name, String.valueOf(request.inputs().get(name)));
        }

        List<RecordedStep> steps = new ArrayList<>();
        Map<String, String> outputs = new LinkedHashMap<>();
        List<Brain.Turn> history = new ArrayList<>();

        Action open = new Action.Navigate(request.startUrl());
        Decision mayOpen = policy.evaluate(open, request.startUrl());
        if (!mayOpen.allowed()) {
            return new Result(Status.STOPPED, "start address refused: " + mayOpen.reason(), steps, outputs, null);
        }
        surface.act(open);
        steps.add(new RecordedStep(ActionType.NAVIGATE, null, request.startUrl(), null, "open the application"));

        String lastLocation = request.startUrl();
        for (int turn = 1; turn <= maxTurns; turn++) {

            // 1. Look.
            Observation screen = surface.observe();
            lastLocation = screen.location();
            Decision here = policy.evaluateLocation(screen.location());
            if (!here.allowed()) {
                return new Result(Status.STOPPED, "left the allowed area: " + here.reason(), steps, outputs, screen.location());
            }

            // 2. Ask the brain. It only ever sees the scrubbed screen.
            Move move = brain.next(request.task(), redactor.redact(screen), history);

            if (move instanceof Move.Complete) {
                return new Result(Status.COMPLETED, move.reason(), steps, outputs, screen.location());
            }
            if (move instanceof Move.AskHuman) {
                return new Result(Status.NEEDS_HUMAN, move.reason(), steps, outputs, screen.location());
            }

            // 3. Turn the move into a real action.
            Action action;
            try {
                action = MoveTranslator.toAction(move, screen, request.inputs());
            } catch (IllegalArgumentException e) {
                history.add(new Brain.Turn(move, "rejected: " + e.getMessage()));
                continue;
            }

            // 4. Check the rules.
            Decision decision = policy.evaluate(action, screen.location());
            boolean risky = false;
            boolean donePersonally = false;
            if (decision.verdict() == Decision.Verdict.REQUIRE_APPROVAL) {
                // Stop and wait for a person. The brain is not asked again until they have answered.
                Intervention question = new Intervention("turn-" + turn, decision.reason(), screen.location(),
                        quietEvidence("approval-turn-" + turn));
                ApprovalGate.Answer answer = gate.ask(question);
                if (answer == ApprovalGate.Answer.REJECT) {
                    return new Result(Status.NEEDS_HUMAN, decision.reason(), steps, outputs, screen.location());
                }
                if (answer == ApprovalGate.Answer.NO_ANSWER) {
                    return new Result(Status.NEEDS_HUMAN,
                            "no person answered in the time allowed, so the run was stopped",
                            steps, outputs, screen.location());
                }
                if (answer == ApprovalGate.Answer.OFF_SCRIPT) {
                    return new Result(Status.NEEDS_HUMAN,
                            "a person had the window and also went to another page or tab", steps, outputs,
                            screen.location());
                }
                risky = true;
                donePersonally = answer == ApprovalGate.Answer.DONE_BY_PERSON;
            } else if (!decision.allowed()) {
                history.add(new Brain.Turn(move, "not allowed: " + decision.reason()));
                continue;
            }

            // 5. Act, and remember what worked.
            try {
                ActionResult done = donePersonally ? new ActionResult(null, null) : surface.act(action);
                steps.add(record(move, action, risky ? Risk.RISKY : Risk.SAFE));
                String outcome = donePersonally ? "a person did this step" : "done";
                if (move instanceof Move.Read read) {
                    outputs.put(read.output(), done.value());
                    outcome = "read: " + done.value();
                }
                history.add(new Brain.Turn(move, redactor.redact(outcome)));
            } catch (SurfaceException e) {
                history.add(new Brain.Turn(move, "failed: " + redactor.redact(e.getMessage())));
            }
        }

        return new Result(Status.STOPPED, "goal not reached after " + maxTurns + " turns", steps, outputs, lastLocation);
    }

    /** A screenshot for the person who is asked to decide. Missing one must not stop the question. */
    private Evidence quietEvidence(String label) {
        try {
            return surface.captureEvidence(label, List.of());
        } catch (SurfaceException e) {
            return null;
        }
    }

    private static RecordedStep record(Move move, Action action, Risk risk) {
        Target target = switch (action) {
            case Action.Click a  -> a.target();
            case Action.Type a   -> a.target();
            case Action.Select a -> a.target();
            case Action.Read a   -> a.target();
            case Action.Navigate a -> null;
            case Action.Wait a     -> null;
        };
        // Taken from the move, not the action: the move still holds {{password}}, the action holds the real one.
        String value = switch (move) {
            case Move.Type m    -> m.value();
            case Move.Select m  -> m.option();
            case Move.WaitFor m -> m.text();
            default -> null;
        };
        String output = move instanceof Move.Read read ? read.output() : null;
        return new RecordedStep(action.type(), target, value, output, move.reason(), risk);
    }
}
