package com.computeruse.automation.handoff;

import com.computeruse.automation.surface.Evidence;

/**
 * A request for a person: what the run is stuck on and why.
 *
 * stepId   — the step that needs a decision
 * reason   — why a person is needed, in plain words
 * location — the page the run is on
 * evidence — a screenshot and page snapshot taken at this moment (may be null)
 */
public record Intervention(String stepId, String reason, String location, Evidence evidence) {}
