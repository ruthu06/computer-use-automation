package com.computeruse.automation.policy;

/**
 * The policy engine's answer for one action.
 * reason is always filled — it goes into logs and is shown to the human when approval is needed.
 */
public record Decision(Verdict verdict, String reason) {

    public enum Verdict {
        ALLOW,
        REQUIRE_APPROVAL,  // risky action — pause and ask a human
        DENY
    }

    public boolean allowed() {
        return verdict == Verdict.ALLOW;
    }
}
