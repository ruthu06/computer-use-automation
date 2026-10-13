package com.computeruse.automation.handoff;

import java.time.Instant;
import java.util.List;

/**
 * What a person did while they had the live window.
 *
 * events — each click and each change, in order. Typed text is never recorded,
 *          only how many characters went into which field, so a secret cannot end up here.
 */
public record HumanTurn(String note, String locationBefore, String locationAfter,
                        List<String> events, Instant startedAt, Instant endedAt) {
    public HumanTurn {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
