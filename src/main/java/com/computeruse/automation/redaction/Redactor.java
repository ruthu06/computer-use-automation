package com.computeruse.automation.redaction;

import com.computeruse.automation.surface.Observation;
import com.computeruse.automation.surface.UiElement;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Removes secrets from text before it is logged, saved, or sent to Claude.
 *
 * Two kinds of secret:
 *
 *   Registered values — known for this run: passwords, tokens, sensitive outputs.
 *                       Added by calling register() before the run starts.
 *
 *   Shape patterns    — sensitive wherever they appear, regardless of value:
 *                       SSNs, card numbers, session tokens, API keys.
 *                       Built in — no registration needed.
 *
 * One Redactor instance belongs to one run.
 * Thread-safe: register() and redact() are both synchronized.
 */
public final class Redactor {

    // Built-in patterns — always active
    private static final Map<String, Pattern> SHAPES = new LinkedHashMap<>();
    static {
        SHAPES.put("ssn",     Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"));
        SHAPES.put("card",    Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b"));
        SHAPES.put("session", Pattern.compile("(?i)(?<=jsessionid=)[0-9a-z._-]+"));
        SHAPES.put("apiKey",  Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}"));
    }

    // Registered secrets for this run: secret value → label for the marker
    private final Map<String, String> secrets = new LinkedHashMap<>();

    /**
     * Register a value that must never appear in logs, artifacts, or Claude prompts.
     * Blank values are ignored — no point registering an empty password.
     *
     * @param label short name used in the redaction marker, e.g. "password"
     * @param secret the actual secret value
     */
    public synchronized void register(String label, String secret) {
        if (secret != null && !secret.isBlank()) {
            secrets.put(secret, label);
        }
    }

    /**
     * Returns a copy of the text with all secrets replaced by markers.
     * Null input returns null — safe to call on any field without a null check.
     */
    public synchronized String redact(String text) {
        if (text == null || text.isEmpty()) return text;

        String result = text;

        // Remove registered secrets — longest first.
        // Why longest first: if "abc1234" and "1234" are both secrets,
        // removing "1234" first would leave "abc[REDACTED]" which exposes "abc".
        // Removing "abc1234" first leaves the marker intact.
        List<String> longestFirst = secrets.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String secret : longestFirst) {
            result = result.replace(secret, marker(secrets.get(secret)));
        }

        // Apply built-in shape patterns
        for (Map.Entry<String, Pattern> shape : SHAPES.entrySet()) {
            result = shape.getValue().matcher(result).replaceAll(marker(shape.getKey()));
        }

        return result;
    }

    /**
     * Returns a safe copy of the observation — every text field scrubbed.
     *
     * The screenshot is passed through unchanged because pixel-level masking
     * is handled earlier, before the PNG is written, using Playwright's native
     * mask option in Surface.captureEvidence(). By the time a screenshot exists
     * on disk, sensitive fields are already replaced with solid rectangles.
     *
     * This method only scrubs text — names, values, URLs, visible text.
     */
    public Observation redact(Observation observation) {
        List<UiElement> safeElements = observation.elements().stream()
                .map(e -> new UiElement(
                        e.ref(),
                        e.role(),
                        redact(e.name()),
                        redact(e.value()),
                        e.enabled(),
                        e.target()))
                .toList();

        return new Observation(
                redact(observation.location()),
                redact(observation.title()),
                redact(observation.text()),
                safeElements,
                observation.screenshot()  // already masked at capture time
        );
    }

    private static String marker(String label) {
        return "[REDACTED:" + label + "]";
    }
}
