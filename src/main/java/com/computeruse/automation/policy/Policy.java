package com.computeruse.automation.policy;

import com.computeruse.automation.surface.ActionType;
import java.util.List;
import java.util.Set;

/**
 * The rules for one application. Loaded from a JSON config file, not hardcoded.
 * A different bank gets a different policy file.
 *
 * allowedOrigins  — permitted scheme+host+port, e.g. "http://localhost:8080"
 * allowedPaths    — permitted page paths, supports * (one segment) and ** (any depth)
 * allowedActions  — which ActionTypes may be used at all
 * riskyActions    — rules that flag specific controls as needing human approval
 */
public record Policy(
        List<String> allowedOrigins,
        List<String> allowedPaths,
        Set<ActionType> allowedActions,
        List<RiskRule> riskyActions
) {
    public Policy {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        allowedPaths   = allowedPaths   == null ? List.of() : List.copyOf(allowedPaths);
        allowedActions = allowedActions == null ? Set.of()  : Set.copyOf(allowedActions);
        riskyActions   = riskyActions   == null ? List.of() : List.copyOf(riskyActions);
    }

    /**
     * Marks one specific control as risky.
     *
     * action — which ActionType triggers this rule (usually CLICK)
     * path   — page path pattern where the control lives
     * role   — accessible role to match, or null to match any role
     * name   — regex matched against the control's accessible name
     * reason — shown to the human being asked to approve
     */
    public record RiskRule(
            ActionType action,
            String path,
            String role,
            String name,
            String reason
    ) {}
}
