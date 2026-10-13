package com.computeruse.automation.surface;

import com.computeruse.automation.artifact.Target;
import java.util.List;

/**
 * The seam between "how we perceive and act on an application" and everything else.
 *
 * Discovery, replay, and the human handoff only ever talk to this interface.
 * Playwright for web is one implementation. A desktop driver would be another.
 * Neither the recipe nor the replay engine knows which one is in use.
 *
 * Three verbs:
 *   observe()         — what does the screen look like right now?
 *   act(action)       — do this one thing
 *   captureEvidence() — save a screenshot and accessibility snapshot to disk
 */
public interface Surface extends AutoCloseable {

    /**
     * Read the current state of the screen.
     * Called before every action during discovery (to send to Claude)
     * and after every action during replay (to check step expectations).
     */
    Observation observe();

    /**
     * Where the surface is right now: the URL on the web.
     *
     * Cheaper than observe(), which lists every control. Replay asks this before
     * every action to apply the policy, so a full look each time would be slow.
     */
    default String location() {
        return observe().location();
    }

    /**
     * A person is about to use the live window. Start noting what they do.
     * Surfaces that cannot be handed to a person may leave this empty.
     */
    default void beginHandoff() {}

    /**
     * The person is done. What they did, in plain words, oldest first.
     * Never includes what was typed, only that something was.
     */
    default List<String> endHandoff() {
        return List.of();
    }

    /**
     * Every page the person's activity reached during the hand-over, in any tab or window,
     * as addresses without their query (see {@link Addresses#page}). A second tab or window
     * is reported as {@link Addresses#ANOTHER_TAB}. Call after endHandoff().
     *
     * The run paused on one page. Anything here that is not that page means the person went elsewhere.
     */
    default List<String> visitedDuringHandoff() {
        return List.of();
    }

    /**
     * Carry out one action.
     *
     * @throws TargetNotFoundException if no locator in the action's target matched
     * @throws SurfaceException        if the action could not be carried out
     */
    ActionResult act(Action action);

    /**
     * Save a screenshot and accessibility snapshot right now.
     * Called on failure so there is something to debug with.
     *
     * sensitiveTargets are masked in the screenshot before the PNG is written —
     * their pixels are replaced with a solid rectangle by the Playwright layer.
     * This keeps screenshots useful for debugging while preventing PII from
     * ever existing in the file.
     *
     * @param label            short name for the files, e.g. "step-3-failure"
     * @param sensitiveTargets controls whose pixels must be masked, e.g. password fields
     */
    Evidence captureEvidence(String label, List<Target> sensitiveTargets);

    /** Always called at the end of a run, even if it crashed. Closes the browser. */
    @Override
    void close();
}
