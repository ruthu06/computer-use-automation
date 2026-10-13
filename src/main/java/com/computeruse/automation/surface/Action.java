package com.computeruse.automation.surface;

import com.computeruse.automation.artifact.Condition;
import com.computeruse.automation.artifact.Target;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One concrete thing to do on a surface, with all its data filled in.
 *
 * Claude produces these during discovery.
 * Replay produces them from artifact steps with placeholders resolved.
 * Both go through the policy engine before being handed to the Surface.
 *
 * Sealed so the compiler forces every handler to cover all 6 cases.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "do")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Action.Navigate.class, name = "navigate"),
        @JsonSubTypes.Type(value = Action.Click.class,    name = "click"),
        @JsonSubTypes.Type(value = Action.Type.class,     name = "type"),
        @JsonSubTypes.Type(value = Action.Select.class,   name = "select"),
        @JsonSubTypes.Type(value = Action.Read.class,     name = "read"),
        @JsonSubTypes.Type(value = Action.Wait.class,     name = "wait")
})
public sealed interface Action {

    ActionType type();

    /** Go to this URL. Policy checks the destination, not the current page. */
    record Navigate(String url) implements Action {
        public ActionType type() { return ActionType.NAVIGATE; }
    }

    /** Click a control. */
    record Click(Target target) implements Action {
        public ActionType type() { return ActionType.CLICK; }
    }

    /** Type text into a field. The text has already had {{placeholders}} resolved. */
    record Type(Target target, String text) implements Action {
        public ActionType type() { return ActionType.TYPE; }
    }

    /** Pick an option from a dropdown. */
    record Select(Target target, String option) implements Action {
        public ActionType type() { return ActionType.SELECT; }
    }

    /** Read the visible text of a control and return it. Used to capture outputs. */
    record Read(Target target) implements Action {
        public ActionType type() { return ActionType.READ; }
    }

    /** Pause until the condition is true, or fail after timeoutMs milliseconds. */
    record Wait(Condition until, long timeoutMs) implements Action {
        public ActionType type() { return ActionType.WAIT; }
    }
}
