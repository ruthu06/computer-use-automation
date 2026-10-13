package com.computeruse.automation.artifact;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * Something that is either true or false about what is currently on screen.
 *
 * Used in two places in a recipe:
 *   - Step.expect  : "don't move to the next step until THIS is true"
 *   - Capability.successCondition : "the whole run succeeded when THIS is true"
 *   - Outcome.when : "this known non-success ending has happened when THIS is true"
 *
 * Conditions only describe what can be PERCEIVED — the URL, visible text,
 * and accessible roles/names. They never look inside the HTML. That means the
 * same condition works whether the surface is a browser or a desktop app.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Condition.LocationContains.class, name = "locationContains"),
        @JsonSubTypes.Type(value = Condition.TextVisible.class,      name = "textVisible"),
        @JsonSubTypes.Type(value = Condition.ElementVisible.class,   name = "elementVisible"),
        @JsonSubTypes.Type(value = Condition.AllOf.class,            name = "allOf")
})
public sealed interface Condition {

    /**
     * The current URL contains this string.
     * Example: value="/activity.htm"
     * Use it to confirm you landed on the right page.
     */
    record LocationContains(String value) implements Condition {}

    /**
     * This exact text is visible somewhere on screen.
     * Example: text="The username and password could not be verified."
     * Use it to detect error messages or business outcomes.
     */
    record TextVisible(String text) implements Condition {}

    /**
     * A control with this role and this name is visible.
     * Example: role="heading", name="Accounts Overview"
     * More precise than TextVisible — checks both what kind of thing it is
     * and what it says, so a heading and a paragraph with the same words
     * don't get confused.
     */
    record ElementVisible(String role, String name) implements Condition {}

    /**
     * Every condition in the list must be true.
     * Example: the URL contains "/activity.htm" AND the "Account Details" heading is visible.
     * Used as the overall success condition when one signal isn't enough on its own.
     */
    record AllOf(List<Condition> conditions) implements Condition {
        public AllOf {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }
    }
}
