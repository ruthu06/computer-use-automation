package com.computeruse.automation.artifact;

import java.util.List;

/**
 * A saved recipe — everything needed to replay a recorded flow.
 *
 * Think of it as a function an AI agent can call:
 *   - it has a name and description so the agent knows what it does
 *   - it declares its inputs (what the caller must supply)
 *   - it declares its outputs (what the caller gets back)
 *   - it lists the steps to carry out
 *   - it knows what a successful ending looks like
 */
public record Capability(

        /** Version of this file format. If the format changes, old files are still recognisable. */
        String schemaVersion,

        /** Stable identifier, e.g. "parabank.open-account" */
        String id,

        /** Semantic version of this recipe, e.g. "1.0.0" */
        String version,

        /** Human-readable name */
        String name,

        /** What this capability does — what an AI agent reads to decide whether to use it */
        String description,

        /** Which application this was recorded on */
        App app,

        /** Values the caller must supply on each invocation, e.g. username, password */
        List<Input> inputs,

        /** Values the caller gets back, e.g. accountNumber, balance */
        List<Output> outputs,

        /** The steps to carry out, in order */
        List<Step> steps,

        /** Known non-success endings that are legitimate answers, not failures */
        List<Outcome> outcomes,

        /** What must be true on screen once the last step has run */
        Condition successCondition,

        /** Where this recipe came from */
        Provenance provenance

) {

    public static final String SCHEMA_VERSION = "1.0";

    /** Defensive copy: null lists become empty, lists become unmodifiable */
    public Capability {
        inputs    = inputs    == null ? List.of() : List.copyOf(inputs);
        outputs   = outputs   == null ? List.of() : List.copyOf(outputs);
        steps     = steps     == null ? List.of() : List.copyOf(steps);
        outcomes  = outcomes  == null ? List.of() : List.copyOf(outcomes);
    }

    // ── sub-records ──────────────────────────────────────────────────────────

    /**
     * The application this capability was recorded on.
     *
     * entryPath is relative (e.g. "/index.htm"), not absolute.
     * That way one recipe works for any bank running the same product,
     * regardless of the address they've deployed it on.
     */
    public record App(
            String product,
            String productVersion,
            String surface,
            String entryPath
    ) {}

    /**
     * A value the caller supplies per invocation.
     *
     * sensitive = true means the value is never written to logs or saved files.
     * allowedValues limits what the caller can pass (empty = anything goes).
     */
    public record Input(
            String name,
            ValueType type,
            String description,
            boolean required,
            boolean sensitive,
            List<String> allowedValues
    ) {
        public Input {
            allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        }
    }

    /** A value the caller gets back after a successful run. */
    public record Output(
            String name,
            ValueType type,
            String description,
            boolean sensitive
    ) {}

    /**
     * A known ending that is NOT success but is NOT a failure either.
     *
     * Example: INVALID_CREDENTIALS — the bank said "wrong password".
     * That is a legitimate answer the caller should act on, not a crash.
     * The when field describes what appears on screen when this has happened.
     */
    public record Outcome(
            String code,
            String description,
            Condition when
    ) {}

    /** Where this recipe came from and what produced it. */
    public record Provenance(
            Source source,
            String runId,
            String model,
            String recordedAt
    ) {
        public enum Source { DISCOVERY, HAND_WRITTEN }
    }
}
