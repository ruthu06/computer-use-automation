package com.computeruse.automation.result;

import com.computeruse.automation.surface.Evidence;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/**
 * What a replay hands back to its caller.
 *
 * Every run ends in exactly one of three shapes:
 *
 *   SUCCESS         — the goal was achieved; outputs are filled
 *   BUSINESS_OUTCOME — a legitimate answer that is not success, e.g. "wrong password"
 *   FAILURE         — something broke and the run could not continue
 *
 * Recoverable hiccups (e.g. a slow page that eventually loaded) are NOT a fourth shape.
 * They were handled, so the run still ends in one of the three above.
 * What was handled is listed in `recoveries` on whichever shape won.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "outcome")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ReplayResult.Success.class,         name = "success"),
        @JsonSubTypes.Type(value = ReplayResult.BusinessOutcome.class, name = "businessOutcome"),
        @JsonSubTypes.Type(value = ReplayResult.Failure.class,         name = "failure")
})
public sealed interface ReplayResult {

    String runId();
    String capabilityId();
    String capabilityVersion();

    /** Conditions that were handled on the way without stopping the run. */
    List<Recovery> recoveries();

    // ── the three shapes ─────────────────────────────────────────────────────

    /**
     * The success condition held. outputs contains every declared output.
     *
     * Example: accountNumber="14121", balance="$100.00"
     */
    record Success(
            String runId,
            String capabilityId,
            String capabilityVersion,
            List<Recovery> recoveries,
            Map<String, Object> outputs
    ) implements ReplayResult {
        public Success {
            recoveries = recoveries == null ? List.of() : List.copyOf(recoveries);
            outputs    = outputs    == null ? Map.of()  : Map.copyOf(outputs);
        }
    }

    /**
     * The run reached a known outcome that is NOT success but is NOT a crash either.
     *
     * This is a legitimate answer the caller should act on.
     * Example: code="INVALID_CREDENTIALS" — the bank said "wrong password".
     *
     * The most common design mistake is treating this as a failure. It is not.
     * The caller asked "can this customer log in?" and got a real answer: no.
     *
     * stepId — the step that was running when the outcome was detected
     */
    record BusinessOutcome(
            String runId,
            String capabilityId,
            String capabilityVersion,
            List<Recovery> recoveries,
            String code,
            String message,
            String stepId
    ) implements ReplayResult {
        public BusinessOutcome {
            recoveries = recoveries == null ? List.of() : List.copyOf(recoveries);
        }
    }

    /**
     * The run stopped and could not continue.
     *
     * kind     — what category of failure this is (see FailureKind)
     * stepId   — which step was running when it failed (null if it failed before step 1)
     * expected — what replay needed to see, in plain English
     * observed — what it actually saw, already redacted
     * evidence — paths to the screenshot and snapshot saved for debugging
     */
    record Failure(
            String runId,
            String capabilityId,
            String capabilityVersion,
            List<Recovery> recoveries,
            FailureKind kind,
            String stepId,
            String expected,
            String observed,
            Evidence evidence
    ) implements ReplayResult {
        public Failure {
            recoveries = recoveries == null ? List.of() : List.copyOf(recoveries);
        }
    }

    // ── supporting types ─────────────────────────────────────────────────────

    /**
     * What category of failure stopped the run.
     * The caller can branch on this without parsing the message.
     */
    enum FailureKind {
        /** The caller passed inputs that don't match the capability's declared inputs. */
        INVALID_INPUT,
        /** Policy refused an action — the agent tried to go somewhere it wasn't allowed. */
        POLICY_DENIED,
        /** No locator found the control a step needed. */
        TARGET_NOT_FOUND,
        /** A step's expect condition or the success condition never became true in time. */
        EXPECTATION_NOT_MET,
        /** The browser or driver itself broke. */
        SURFACE_ERROR,
        /**
         * A person was handed the window and also went somewhere else (another page or tab).
         * What they did there is unknown, so the run stopped instead of carrying on.
         */
        HUMAN_OFF_SCRIPT,
        /** The run asked for a person and nobody answered in the time allowed. Nothing was done. */
        NO_HUMAN_RESPONSE,
        /** A person handed the window back, but the result of the step they said they did was not on the page. */
        HUMAN_STEP_NOT_DONE,
        /** A person handed the window back, and the recipe has nothing to check their step against. */
        HUMAN_STEP_UNVERIFIED
    }

    /**
     * A runtime hiccup that replay noticed and handled without stopping.
     *
     * condition — what happened, e.g. "page took 4s to show the expected heading"
     * response  — what replay did, e.g. "kept waiting, condition met after 4.1s"
     */
    record Recovery(String stepId, String condition, String response) {}
}
