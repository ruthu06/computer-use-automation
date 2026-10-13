package com.computeruse.automation.discovery;

/**
 * The one thing the brain chooses to do next. These seven are the whole menu:
 * the brain cannot answer with anything else.
 *
 * ref    — which control, using the short handle from the latest look, e.g. "e12"
 * reason — why, in the brain's own words; kept for the log and the recipe
 */
public sealed interface Move {

    String reason();

    record Click(String ref, String reason) implements Move {}

    /** value may hold a placeholder such as {{password}}, so the brain never sees the secret itself. */
    record Type(String ref, String value, String reason) implements Move {}

    record Select(String ref, String option, String reason) implements Move {}

    /** Read a control's text and keep it as the named output, e.g. "accountNumber". */
    record Read(String ref, String output, String reason) implements Move {}

    /** Wait until this text shows on screen. */
    record WaitFor(String text, String reason) implements Move {}

    /** The goal has been reached. */
    record Complete(String reason) implements Move {}

    /** The brain cannot or should not continue; a person is needed. */
    record AskHuman(String reason) implements Move {}
}
