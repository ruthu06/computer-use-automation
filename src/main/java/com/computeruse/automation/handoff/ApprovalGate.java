package com.computeruse.automation.handoff;

/**
 * Where a run stops and waits for a person.
 *
 * Replay and discovery call ask() when the next action needs a human's say.
 * The call does not return until the person has answered.
 */
public interface ApprovalGate {

    enum Answer {
        /** Go ahead and do the action. */
        APPROVE,
        /** Do not do it. The run stops. */
        REJECT,
        /** The person did it themselves, in the live window. Carry on with the step after it. */
        DONE_BY_PERSON,
        /**
         * The person had the window, but went somewhere else: another page, or another tab.
         * What they did there is unknown, so the run must not carry on as if all were well.
         */
        OFF_SCRIPT,
        /** Nobody answered in the time allowed. The run must stop; nothing was approved. */
        NO_ANSWER
    }

    Answer ask(Intervention intervention);

    /** The gate used when nobody is there to answer: nothing risky is ever approved. */
    ApprovalGate NEVER = intervention -> Answer.REJECT;
}
