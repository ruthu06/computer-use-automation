package com.computeruse.automation.surface;

/**
 * File paths of what was captured at one moment during a run.
 * Each is the full path of a file inside the run's evidence folder.
 *
 * screenshot — a PNG of the screen at the moment of capture
 * snapshot   — the screen written out as text at that moment
 */
public record Evidence(String screenshot, String snapshot) {}
