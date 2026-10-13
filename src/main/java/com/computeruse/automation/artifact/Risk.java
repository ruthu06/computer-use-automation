package com.computeruse.automation.artifact;

public enum Risk {
    /** Reversible or read-only. Safe to run without asking anyone. */
    SAFE,
    /** Changes the institution's records and cannot be undone from the UI. Needs a human to approve. */
    RISKY
}
