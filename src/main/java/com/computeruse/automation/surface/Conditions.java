package com.computeruse.automation.surface;

import com.computeruse.automation.artifact.Condition;
import java.util.Locale;

/**
 * Evaluates a Condition against an Observation. Pure logic — no browser access.
 *
 * Used by replay to check step expectations and the success condition,
 * and by the discovery loop to detect known business outcomes.
 */
public final class Conditions {

    private Conditions() {}

    /** Returns true if the condition holds for this observation. */
    public static boolean holds(Condition condition, Observation observation) {
        return switch (condition) {
            case Condition.LocationContains c ->
                    observation.location() != null
                    && observation.location().contains(c.value());

            case Condition.TextVisible c ->
                    normalize(observation.text()).contains(normalize(c.text()));

            case Condition.ElementVisible c ->
                    observation.elements().stream().anyMatch(el ->
                            c.role().equalsIgnoreCase(el.role())
                            && normalize(c.name()).equals(normalize(el.name())));

            case Condition.AllOf c ->
                    c.conditions().stream().allMatch(each -> holds(each, observation));
        };
    }

    /**
     * A one-line description used in failure messages and logs.
     * Example: heading "Accounts Overview" is visible
     */
    public static String describe(Condition condition) {
        return switch (condition) {
            case Condition.LocationContains c -> "location contains \"" + c.value() + "\"";
            case Condition.TextVisible c      -> "text \"" + c.text() + "\" is visible";
            case Condition.ElementVisible c   -> c.role() + " \"" + c.name() + "\" is visible";
            case Condition.AllOf c            -> String.join(" and ",
                    c.conditions().stream().map(Conditions::describe).toList());
        };
    }

    /**
     * Trims, collapses whitespace and lowercases, so conditions survive cosmetic
     * differences in spacing and capitalisation.
     */
    private static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
