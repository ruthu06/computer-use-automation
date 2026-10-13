package com.computeruse.automation.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings, read from application.properties under "automation.".
 *
 * targetBaseUrl — where the application being automated lives now
 * headless      — false opens a visible window, which a person needs in order to take over
 * waitSeconds   — how long a request waits for a run to need a person or finish
 * humanWaitSeconds — how long a paused run waits for someone to answer before it stops itself
 */
@ConfigurationProperties(prefix = "automation")
public record AutomationProperties(
        String targetBaseUrl,
        String product,
        String productVersion,
        String startPath,
        String artifactsDir,
        String evidenceDir,
        String policyResource,
        boolean headless,
        String model,
        int maxTurns,
        long waitSeconds,
        long humanWaitSeconds) {}
