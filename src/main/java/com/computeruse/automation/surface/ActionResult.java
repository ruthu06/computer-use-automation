package com.computeruse.automation.surface;

import com.computeruse.automation.artifact.Locator;

/**
 * What the surface returns after carrying out an action.
 *
 * value     — the text that was read, for READ actions; null for everything else
 * matchedBy — which locator strategy actually found the control.
 *             Logged so you can see when a fallback was used instead of the primary.
 */
public record ActionResult(String value, Locator matchedBy) {}
