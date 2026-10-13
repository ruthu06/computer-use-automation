package com.computeruse.automation.surface;

import com.computeruse.automation.artifact.Target;

/**
 * One control the surface can currently see on screen.
 *
 * ref     — a short handle valid only for this observation (e.g. "e3").
 *           The model uses it to point at a control without repeating its full description.
 *
 * role    — what kind of control it is: "button", "textbox", "heading", "link", ...
 * name    — what it is called (its accessible name). Empty when the control has no name.
 * value   — the current text inside an input. Always null for password fields — the
 *           surface must never read a password out of the application.
 * enabled — false when the control is greyed out and cannot be acted on.
 * target  — how to find this control again; this is what gets written into a recipe step.
 */
public record UiElement(
        String ref,
        String role,
        String name,
        String value,
        boolean enabled,
        Target target
) {}
