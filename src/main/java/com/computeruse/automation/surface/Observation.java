package com.computeruse.automation.surface;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * Everything the surface can perceive at one moment in time.
 *
 * This is what gets sent to Claude during discovery (after redaction).
 * It is also what Conditions are evaluated against during replay.
 *
 * location   — where we are: the URL on the web, the window title on desktop
 * title      — the page or window title
 * text       — all visible text joined into one string (used by TextVisible conditions)
 * elements   — every interactive control and landmark the surface can see
 * screenshot — raw PNG bytes; @JsonIgnore means it is NEVER written to JSON automatically.
 *              Screenshots go to the evidence folder on disk only, never into logs.
 */
public record Observation(
        String location,
        String title,
        String text,
        List<UiElement> elements,
        @JsonIgnore byte[] screenshot
) {
    public Observation {
        elements = elements == null ? List.of() : List.copyOf(elements);
    }

    /** The screen written out as plain text: what the brain is shown, and what is saved as evidence. */
    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append("location: ").append(location).append('\n');
        out.append("title: ").append(title).append("\n\ncontrols:\n");
        for (UiElement el : elements) {
            out.append(el.ref()).append(' ').append(el.role()).append(" \"").append(el.name()).append('"');
            if (el.value() != null && !el.value().isEmpty()) out.append(" value=\"").append(el.value()).append('"');
            if (!el.enabled()) out.append(" (disabled)");
            out.append('\n');
        }
        out.append("\nvisible text:\n").append(text).append('\n');
        return out.toString();
    }
}
