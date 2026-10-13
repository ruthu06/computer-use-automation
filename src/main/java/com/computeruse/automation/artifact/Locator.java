package com.computeruse.automation.artifact;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One strategy for finding a control on screen.
 *
 * A Target holds several of these, tried in order. The first one that
 * finds a real control wins. This fallback chain is what makes replay
 * survive legacy UIs that have no stable IDs.
 *
 * The four kinds, from most durable to least:
 *
 *   Role        — what the control IS and what it's called  (works on web and desktop)
 *   Label       — the control after a visible caption        (covers legacy, no-name inputs)
 *   Structure   — a CSS path in the page's HTML             (web only, breaks if HTML changes)
 *   Coordinates — a pixel position on screen                (last resort, breaks if window resizes)
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "by")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Locator.Role.class,        name = "role"),
        @JsonSubTypes.Type(value = Locator.Label.class,       name = "label"),
        @JsonSubTypes.Type(value = Locator.Structure.class,   name = "structure"),
        @JsonSubTypes.Type(value = Locator.Coordinates.class, name = "coordinates")
})
public sealed interface Locator {

    /**
     * Find by accessible role + accessible name.
     * Example: button named "Log In".
     * This is what screen readers use, so it works on any surface with an accessibility tree.
     */
    record Role(String role, String name) implements Locator {}

    /**
     * Find the first control of the given role that follows a visible text caption.
     * Example: the textbox after the word "Password".
     * Covers the common legacy pattern where an input has no name of its own,
     * just a bold label sitting next to it in the HTML.
     */
    record Label(String text, String role) implements Locator {}

    /**
     * A CSS selector or structural path.
     * Example: input[name='password']
     * Web-only. Faster than the above but breaks if the HTML is reorganised.
     */
    record Structure(String path) implements Locator {}

    /**
     * A pixel coordinate recorded at a known viewport size.
     * Example: x=410, y=322, recorded at 1280×720.
     * The very last resort — used when no other strategy works,
     * e.g. a canvas-based desktop app with no accessibility tree at all.
     */
    record Coordinates(int x, int y, int viewportWidth, int viewportHeight) implements Locator {}
}
