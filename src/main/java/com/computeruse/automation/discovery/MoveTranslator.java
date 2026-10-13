package com.computeruse.automation.discovery;

import com.computeruse.automation.artifact.Condition;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.artifact.Templates;
import com.computeruse.automation.surface.Action;
import com.computeruse.automation.surface.Observation;
import java.util.Map;

/**
 * Turns the brain's move into an action the surface can carry out.
 *
 * The brain speaks in handles and placeholders: "type {{password}} into e11".
 * The surface needs the real control and the real text.
 */
final class MoveTranslator {

    /** How long a WaitFor move may wait before it gives up. */
    private static final long WAIT_MS = 10_000;

    /**
     * @param screen the same look the brain was shown, so its handles mean the same controls
     * @param inputs the real values behind the placeholders
     * @throws IllegalArgumentException if the move names a handle or an input that does not exist
     */
    static Action toAction(Move move, Observation screen, Map<String, Object> inputs) {
        return switch (move) {
            case Move.Click m   -> new Action.Click(targetOf(m.ref(), screen));
            case Move.Type m    -> new Action.Type(targetOf(m.ref(), screen), Templates.resolve(m.value(), inputs));
            case Move.Select m  -> new Action.Select(targetOf(m.ref(), screen), Templates.resolve(m.option(), inputs));
            case Move.Read m    -> new Action.Read(targetOf(m.ref(), screen));
            case Move.WaitFor m -> new Action.Wait(new Condition.TextVisible(m.text()), WAIT_MS);
            case Move.Complete m -> throw new IllegalArgumentException("Complete ends the run; it is not an action");
            case Move.AskHuman m -> throw new IllegalArgumentException("AskHuman ends the run; it is not an action");
        };
    }

    /** Looks the handle up in the list of controls and returns that control's directions. */
    private static Target targetOf(String ref, Observation screen) {
        return screen.elements().stream()
                .filter(el -> el.ref().equals(ref))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("there is no control " + ref + " on this screen"))
                .target();
    }

    private MoveTranslator() {}
}
