package com.computeruse.automation.artifact;

import java.util.List;

/**
 * Thrown when a capability JSON fails structural validation.
 *
 * violations — every problem found, not just the first one, so the caller
 *              can fix them all in one go rather than playing whack-a-mole.
 */
public final class InvalidCapabilityException extends RuntimeException {

    private final List<String> violations;

    public InvalidCapabilityException(List<String> violations) {
        super("invalid capability: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
