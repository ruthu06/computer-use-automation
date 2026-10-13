package com.computeruse.automation.surface;

import com.computeruse.automation.artifact.Target;

/**
 * None of a target's locators matched a control on the current screen.
 * Carries the target so the failure message can say exactly what was tried.
 */
public class TargetNotFoundException extends SurfaceException {

    private final Target target;

    public TargetNotFoundException(Target target) {
        super("No locator matched \"" + target.name() + "\" (tried " + target.locators().size() + ")");
        this.target = target;
    }

    public Target target() { return target; }
}
