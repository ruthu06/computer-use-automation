package com.computeruse.automation.replay;

import com.computeruse.automation.artifact.Capability;
import com.computeruse.automation.artifact.Condition;
import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Risk;
import com.computeruse.automation.artifact.Step;
import com.computeruse.automation.handoff.ApprovalGate;
import com.computeruse.automation.handoff.Intervention;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.artifact.Templates;
import com.computeruse.automation.policy.Decision;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Follows a saved recipe, step by step, with no AI involved.
 *
 * There is no brain and no Anthropic client in this class, so it cannot call Claude.
 * Each step: fill in the real values, ask the policy, act, then wait for what the step expects.
 * One engine belongs to one run and one surface.
 */
public final class ReplayEngine {

    /** How long a step's expectation, or the final success condition, may take to become true. */
    private static final long EXPECT_TIMEOUT_MS = 10_000;

    /** A step that takes longer than this is noted as a slow page that was waited out. */
    public static final long DEFAULT_SLOW_STEP_MS = 3_000;

    private final Surface surface;
    private final PolicyEngine policy;
    private final Redactor redactor;
    private final String baseUrl;
    private final long slowStepMs;
    private final ApprovalGate gate;

    /** Hiccups of this run that were waited out. One engine is one run, so this lives here. */
    private final List<ReplayResult.Recovery> recoveries = new ArrayList<>();

    /**
     * @param baseUrl where the application lives now, e.g. "http://localhost:8080".
     *                A recipe holds only paths, so the same recipe works wherever the app runs.
     */
    public ReplayEngine(Surface surface, PolicyEngine policy, Redactor redactor, String baseUrl) {
        this(surface, policy, redactor, baseUrl, DEFAULT_SLOW_STEP_MS);
    }

    /** @param slowStepMs a step slower than this is recorded as a recovery */
    public ReplayEngine(Surface surface, PolicyEngine policy, Redactor redactor, String baseUrl, long slowStepMs) {
        this(surface, policy, redactor, baseUrl, slowStepMs, ApprovalGate.NEVER);
    }

    /**
     * @param gate asked before any step that needs a person. Without one, such a step is never done.
     */
    public ReplayEngine(Surface surface, PolicyEngine policy, Redactor redactor, String baseUrl,
                        long slowStepMs, ApprovalGate gate) {
        this.surface = surface;
        this.policy = policy;
        this.redactor = redactor;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.slowStepMs = slowStepMs;
        this.gate = gate;
    }

    /** @param runId names this run in its result and in any evidence file, so the caller can find them */
    public ReplayResult run(Capability recipe, Map<String, Object> inputs, String runId) {

        // 1. The caller's inputs must match what the recipe declares, before a browser moves.
        String problem = checkInputs(recipe, inputs);
        if (problem != null) {
            return new ReplayResult.Failure(runId, recipe.id(), recipe.version(), List.of(),
                    FailureKind.INVALID_INPUT, null, "inputs that match the recipe", problem, null);
        }

        // 2. Hide this run's secrets from everything that is logged or saved.
        for (Capability.Input input : recipe.inputs()) {
            if (input.sensitive() && inputs.containsKey(input.name())) {
                redactor.register(input.name(), String.valueOf(inputs.get(input.name())));
            }
        }
        List<Target> secretTargets = secretTargets(recipe);

        // 3. The steps, in order.
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (Step step : recipe.steps()) {
            Action action = toAction(step, inputs);

            Decision decision = policy.evaluate(action, surface.location());
            if (decision.verdict() == Decision.Verdict.DENY) {
                return failure(recipe, runId, FailureKind.POLICY_DENIED, step.id(),
                        "an action the policy allows", decision.reason(), secretTargets);
            }

            // Either the policy or the recipe itself says this step needs a person's say.
            boolean donePersonally = false;
            boolean policyAsks = decision.verdict() == Decision.Verdict.REQUIRE_APPROVAL;
            if (policyAsks || step.risk() == Risk.RISKY) {
                String reason = policyAsks ? decision.reason() : "the recipe marks this step as risky";
                Intervention request = new Intervention(step.id(), reason, surface.location(),
                        quietEvidence("approval-" + runId + "-" + step.id(), secretTargets));

                switch (gate.ask(request)) {
                    case REJECT -> {
                        return failure(recipe, runId, FailureKind.POLICY_DENIED, step.id(),
                                "a person's approval", "not approved: " + reason, secretTargets);
                    }
                    case OFF_SCRIPT -> {
                        return failure(recipe, runId, FailureKind.HUMAN_OFF_SCRIPT, step.id(),
                                "the person to stay on the page the run paused on",
                                "while a person had the window, the browser also went to another page or tab",
                                secretTargets);
                    }
                    case NO_ANSWER -> {
                        return failure(recipe, runId, FailureKind.NO_HUMAN_RESPONSE, step.id(),
                                "a person to answer", "no person answered in time, so the run was stopped. "
                                        + "Nothing was done on this step: " + reason, secretTargets);
                    }
                    case DONE_BY_PERSON -> donePersonally = true;
                    case APPROVE -> { }
                }
            }

            long began = System.nanoTime();
            try {
                if (!donePersonally) {
                    ActionResult done = surface.act(action);
                    noteIfAFallbackWasUsed(step, done);
                    if (step.action() == ActionType.READ) {
                        outputs.put(step.output(), done.value());
                    }
                }

                // A click can carry the browser somewhere it should never be.
                Decision after = policy.evaluateLocation(surface.location());
                if (!after.allowed()) {
                    return failure(recipe, runId, FailureKind.POLICY_DENIED, step.id(),
                            "to stay inside the allowed area", after.reason(), secretTargets);
                }

                // A submit can end in a known answer such as "wrong password". Say so now,
                // instead of failing on a later step that was never going to work.
                if (step.action() == ActionType.CLICK) {
                    ReplayResult known = knownOutcome(recipe, runId, step.id());
                    if (known != null) {
                        return known;
                    }
                }

                // A person said they did it. That is a claim, not a result: look for the proof.
                if (donePersonally) {
                    ReplayResult unproven = proveHandledByPerson(recipe, step, runId, secretTargets);
                    if (unproven != null) {
                        return unproven;
                    }
                }

                if (step.expect() != null && step.action() != ActionType.WAIT) {
                    surface.act(new Action.Wait(step.expect(), EXPECT_TIMEOUT_MS));
                }

                // It worked, but only after waiting. That is worth knowing about, not worth failing for.
                long tookMs = (System.nanoTime() - began) / 1_000_000;
                if (tookMs > slowStepMs) {
                    recoveries.add(new ReplayResult.Recovery(step.id(),
                            "the step took " + tookMs + " ms, more than the " + slowStepMs + " ms normally allowed",
                            "waited for the page to respond, then carried on"));
                }
            } catch (TargetNotFoundException e) {
                return failure(recipe, runId, FailureKind.TARGET_NOT_FOUND, step.id(),
                        "to find " + e.target().name(), e.getMessage(), secretTargets);
            } catch (WaitTimedOutException e) {
                return failure(recipe, runId, FailureKind.EXPECTATION_NOT_MET, step.id(),
                        Conditions.describe(e.condition()), e.getMessage(), secretTargets);
            } catch (SurfaceException e) {
                return failure(recipe, runId, FailureKind.SURFACE_ERROR, step.id(),
                        step.description(), e.getMessage(), secretTargets);
            }
        }

        // 4. Did it end where the recipe says success looks like?
        String lastStep = recipe.steps().get(recipe.steps().size() - 1).id();
        try {
            surface.act(new Action.Wait(recipe.successCondition(), EXPECT_TIMEOUT_MS));
        } catch (WaitTimedOutException e) {
            return failure(recipe, runId, FailureKind.EXPECTATION_NOT_MET, lastStep,
                    Conditions.describe(e.condition()), e.getMessage(), secretTargets);
        } catch (SurfaceException e) {
            return failure(recipe, runId, FailureKind.SURFACE_ERROR, lastStep,
                    "the success condition to be checked", e.getMessage(), secretTargets);
        }

        // 5. Every promised output must have been read.
        for (Capability.Output declared : recipe.outputs()) {
            if (!outputs.containsKey(declared.name())) {
                return failure(recipe, runId, FailureKind.EXPECTATION_NOT_MET, lastStep,
                        "output '" + declared.name() + "' to be read", "no step produced it", secretTargets);
            }
        }

        return new ReplayResult.Success(runId, recipe.id(), recipe.version(), List.copyOf(recoveries), outputs);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Returns what is wrong with the inputs, or null when they are fine. */
    private static String checkInputs(Capability recipe, Map<String, Object> inputs) {
        Set<String> declared = new HashSet<>();
        for (Capability.Input input : recipe.inputs()) {
            declared.add(input.name());
            Object value = inputs.get(input.name());
            if (input.required() && value == null) {
                return "required input '" + input.name() + "' was not provided";
            }
            if (value != null && !input.allowedValues().isEmpty()
                    && !input.allowedValues().contains(String.valueOf(value))) {
                return "input '" + input.name() + "' is not one of its allowed values";
            }
        }
        for (String name : inputs.keySet()) {
            // Most often a typo, and a typo on a secret would otherwise go unnoticed.
            if (!declared.contains(name)) {
                return "input '" + name + "' is not declared by the recipe";
            }
        }
        return null;
    }

    private Action toAction(Step step, Map<String, Object> inputs) {
        return switch (step.action()) {
            case NAVIGATE -> new Action.Navigate(baseUrl + Templates.resolve(step.value(), inputs));
            case CLICK    -> new Action.Click(step.target());
            case TYPE     -> new Action.Type(step.target(), Templates.resolve(step.value(), inputs));
            case SELECT   -> new Action.Select(step.target(), Templates.resolve(step.value(), inputs));
            case READ     -> new Action.Read(step.target());
            case WAIT     -> new Action.Wait(step.expect(), EXPECT_TIMEOUT_MS);
        };
    }

    /**
     * The preferred locator is the first one a step lists. If the control was found by a later one, the
     * page no longer looks the way it did when the recipe was recorded. The run still worked, but this is
     * the earliest sign of drift, so it is reported rather than silently absorbed.
     */
    private void noteIfAFallbackWasUsed(Step step, ActionResult done) {
        if (step.target() == null || step.target().locators().isEmpty() || done.matchedBy() == null) {
            return;
        }
        List<Locator> listed = step.target().locators();
        Locator preferred = listed.get(0);
        if (!done.matchedBy().equals(preferred)) {
            recoveries.add(new ReplayResult.Recovery(step.id(),
                    "the preferred locator (" + kindOf(preferred) + ") did not match; the control was found by "
                            + "locator " + (listed.indexOf(done.matchedBy()) + 1) + " of " + listed.size()
                            + " (" + kindOf(done.matchedBy()) + ")",
                    "used the fallback locator and carried on; the page may have changed since recording"));
        }
    }

    /** Only the kind, never the text: a locator's name can be data. */
    private static String kindOf(Locator locator) {
        return switch (locator) {
            case Locator.Role r        -> "role and name";
            case Locator.Label l       -> "caption";
            case Locator.Structure s   -> "structure path";
            case Locator.Coordinates c -> "screen position";
        };
    }

    /**
     * What on the page shows this step really happened: the step's own expectation, or failing that the
     * next checkpoint the recipe waits for (a wait, or any later step with an expectation). Looking stops
     * at the next risky step. Null when the recipe offers nothing to check against.
     */
    private static Condition proofOf(Capability recipe, Step step) {
        if (step.expect() != null) {
            return step.expect();
        }
        List<Step> steps = recipe.steps();
        for (int i = steps.indexOf(step) + 1; i < steps.size(); i++) {
            Step later = steps.get(i);
            if (later.expect() != null) {
                return later.expect();
            }
            if (later.risk() == Risk.RISKY) {
                break;
            }
        }
        return null;
    }

    /**
     * Returns null when the proof was seen; otherwise the failure to report. Success is never assumed
     * because a person handed the window back.
     */
    private ReplayResult proveHandledByPerson(Capability recipe, Step step, String runId,
                                              List<Target> secretTargets) {
        Condition proof = proofOf(recipe, step);
        if (proof == null) {
            return failure(recipe, runId, FailureKind.HUMAN_STEP_UNVERIFIED, step.id(),
                    "something on the page that shows this step was done",
                    "a person handed the window back, but the recipe has nothing to check this step against, "
                            + "so it cannot be told whether it was done. Stopped rather than assume.",
                    secretTargets);
        }
        try {
            surface.act(new Action.Wait(proof, EXPECT_TIMEOUT_MS));
            return null;
        } catch (WaitTimedOutException e) {
            return failure(recipe, runId, FailureKind.HUMAN_STEP_NOT_DONE, step.id(),
                    Conditions.describe(proof),
                    "a person handed the window back, but the result of this step was not seen on the page. "
                            + "Stopped rather than assume it was done.",
                    secretTargets);
        }
    }

    /** A screenshot for the person who is asked to decide. Missing one must not stop the question. */
    private Evidence quietEvidence(String label, List<Target> secretTargets) {
        try {
            return surface.captureEvidence(label, secretTargets);
        } catch (SurfaceException e) {
            return null;
        }
    }

    /** Controls that receive a secret, so a failure screenshot can black them out. */
    private static List<Target> secretTargets(Capability recipe) {
        List<Target> targets = new ArrayList<>();
        for (Step step : recipe.steps()) {
            if (step.target() == null || step.value() == null) continue;
            for (Capability.Input input : recipe.inputs()) {
                if (input.sensitive() && step.value().contains("{{" + input.name() + "}}")) {
                    targets.add(step.target());
                }
            }
        }
        return targets;
    }

    /**
     * If the screen shows one of the recipe's known non-success endings, that ending is the answer.
     * Returns null when none applies.
     */
    private ReplayResult knownOutcome(Capability recipe, String runId, String stepId) {
        if (recipe.outcomes().isEmpty()) {
            return null;
        }
        Observation seen;
        try {
            seen = surface.observe();
        } catch (SurfaceException e) {
            return null;   // the page is not readable right now; let the caller report what it was doing
        }
        for (Capability.Outcome outcome : recipe.outcomes()) {
            if (Conditions.holds(outcome.when(), seen)) {
                return new ReplayResult.BusinessOutcome(runId, recipe.id(), recipe.version(), List.copyOf(recoveries),
                        outcome.code(), outcome.description(), stepId);
            }
        }
        return null;
    }

    private ReplayResult failure(Capability recipe, String runId, FailureKind kind, String stepId,
                                 String expected, String observed, List<Target> secretTargets) {
        // A missing control or an unmet expectation may simply be a known answer in disguise.
        // A policy refusal never is: the guard's word is final.
        if (kind == FailureKind.TARGET_NOT_FOUND || kind == FailureKind.EXPECTATION_NOT_MET) {
            ReplayResult known = knownOutcome(recipe, runId, stepId);
            if (known != null) {
                return known;
            }
        }

        Evidence evidence = null;
        String where = "unknown";
        try {
            where = surface.location();
            evidence = surface.captureEvidence("replay-" + runId + "-" + stepId, secretTargets);
        } catch (SurfaceException ignored) {
            // The failure itself matters more than a missing screenshot.
        }
        return new ReplayResult.Failure(runId, recipe.id(), recipe.version(), List.copyOf(recoveries), kind, stepId,
                redactor.redact(expected),
                redactor.redact(observed + " (page: " + where + ")"),
                evidence);
    }
}
