package com.computeruse.automation.artifact;

import com.computeruse.automation.surface.ActionType;

/**
 * One recorded step in a capability.
 *
 * Not every field applies to every action:
 *   - TYPE and SELECT need a value
 *   - CLICK, TYPE, SELECT, READ need a target
 *   - READ needs an output name
 *   - any step can have an expect condition (wait for this before moving on)
 *
 * The validator enforces these rules; unused fields are left null.
 */
public record Step(
        /** Short id used in logs and error messages, e.g. "submit-login" */
        String id,

        /** Why this step exists, written for a human reviewer */
        String description,

        /** What to do */
        ActionType action,

        /** Which control to act on, with fallback locators (null for NAVIGATE and WAIT) */
        Target target,

        /** Text to type or option to select; may contain {{placeholders}} (null for CLICK and READ) */
        String value,

        /** For READ steps: which declared output to store the text in */
        String output,

        /** SAFE for reversible actions; RISKY for anything that changes records */
        Risk risk,

        /** If set, replay waits for this to be true on screen before moving to the next step */
        Condition expect
) {
}
