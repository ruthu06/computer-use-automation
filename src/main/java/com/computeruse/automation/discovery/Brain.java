package com.computeruse.automation.discovery;

import com.computeruse.automation.surface.Observation;
import java.util.List;

/**
 * Whatever decides the next move during discovery.
 *
 * Claude is one implementation. Tests use a scripted one, so the discovery
 * loop can be tested without calling an AI at all.
 */
public interface Brain {

    /**
     * @param task    what we are trying to achieve
     * @param screen  what is on screen right now, already scrubbed of secrets
     * @param history what has been done so far in this run, oldest first
     */
    Move next(Task task, Observation screen, List<Turn> history);

    /**
     * goal    — in plain words, e.g. "Log in and open a new savings account"
     * inputs  — the NAMES of values the brain may type, e.g. "username", "password".
     *           Only names: the real values never reach the brain.
     * outputs — the names of values to read back, e.g. "accountNumber"
     */
    record Task(String goal, List<String> inputs, List<String> outputs) {
        public Task {
            inputs  = inputs  == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
        }
    }

    /** One finished turn: the move that was chosen, and what happened. */
    record Turn(Move move, String outcome) {}
}
