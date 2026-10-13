package com.computeruse.automation.surface;

import com.computeruse.automation.artifact.Condition;

/** A WAIT action ran out of time before its condition became true. */
public class WaitTimedOutException extends SurfaceException {

    private final Condition condition;

    public WaitTimedOutException(Condition condition) {
        super("timed out waiting until " + Conditions.describe(condition));
        this.condition = condition;
    }

    public Condition condition() { return condition; }
}
