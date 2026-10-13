package com.computeruse.automation.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.surface.Observation;
import com.computeruse.automation.surface.UiElement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClaudeBrainTest {

    private static final Brain.Task TASK =
            new Brain.Task("Log in", List.of("username", "password"), List.of("accountNumber"));

    private static final Target ANY = new Target("x", List.of(new Locator.Role("button", "Log In")));

    private static final Observation SCREEN = new Observation(
            "http://localhost:8080/parabank/index.htm", "ParaBank", "Customer Login",
            List.of(new UiElement("e1", "textbox", "Username", "", true, ANY),
                    new UiElement("e2", "button", "Log In", null, false, ANY)),
            null);

    // ── what Claude is shown ─────────────────────────────────────────────────

    @Test
    void theQuestionHoldsTheGoalTheNamesTheControlsAndTheText() {
        String question = ClaudeBrain.describe(TASK, SCREEN, List.of());

        assertThat(question)
                .contains("Goal: Log in")
                .contains("username, password")
                .contains("accountNumber")
                .contains("location: http://localhost:8080/parabank/index.htm")
                .contains("e1 textbox \"Username\"")
                .contains("e2 button \"Log In\" (disabled)")
                .contains("Customer Login")
                .contains("(nothing yet)");
    }

    @Test
    void earlierTurnsAreListedWithWhatHappened() {
        var history = List.of(new Brain.Turn(new Move.Click("e2", "submit"), "failed: boom"));

        assertThat(ClaudeBrain.describe(TASK, SCREEN, history))
                .contains("Click[ref=e2, reason=submit] -> failed: boom")
                .doesNotContain("(nothing yet)");
    }

    // ── what Claude answers with ─────────────────────────────────────────────

    @Test
    void eachMenuChoiceBecomesItsMove() {
        assertThat(ClaudeBrain.toMove(Map.of("action", "click", "ref", "e2", "reason", "go")))
                .isEqualTo(new Move.Click("e2", "go"));
        assertThat(ClaudeBrain.toMove(Map.of("action", "type", "ref", "e1", "text", "{{username}}", "reason", "fill")))
                .isEqualTo(new Move.Type("e1", "{{username}}", "fill"));
        assertThat(ClaudeBrain.toMove(Map.of("action", "select", "ref", "e3", "text", "SAVINGS", "reason", "pick")))
                .isEqualTo(new Move.Select("e3", "SAVINGS", "pick"));
        assertThat(ClaudeBrain.toMove(Map.of("action", "read", "ref", "e4", "output", "accountNumber", "reason", "grab")))
                .isEqualTo(new Move.Read("e4", "accountNumber", "grab"));
        assertThat(ClaudeBrain.toMove(Map.of("action", "wait_for", "text", "Overview", "reason", "loads")))
                .isEqualTo(new Move.WaitFor("Overview", "loads"));
        assertThat(ClaudeBrain.toMove(Map.of("action", "complete", "reason", "done")))
                .isEqualTo(new Move.Complete("done"));
        assertThat(ClaudeBrain.toMove(Map.of("action", "ask_human", "reason", "stuck")))
                .isEqualTo(new Move.AskHuman("stuck"));
    }

    @Test
    void fieldsAMoveDoesNotUseAreIgnored() {
        var fields = Map.of("action", "click", "ref", "e2", "text", "", "output", "", "reason", "go");

        assertThat(ClaudeBrain.toMove(fields)).isEqualTo(new Move.Click("e2", "go"));
    }

    @Test
    void aMoveMissingWhatItNeedsBecomesAskHuman() {
        assertThat(ClaudeBrain.toMove(Map.of("action", "click", "ref", "", "reason", "go")))
                .isInstanceOf(Move.AskHuman.class);
        assertThat(ClaudeBrain.toMove(Map.of("action", "read", "ref", "e4", "output", "", "reason", "grab")))
                .isInstanceOf(Move.AskHuman.class);
    }

    @Test
    void anUnknownActionBecomesAskHumanNotAGuess() {
        Move move = ClaudeBrain.toMove(Map.of("action", "delete_everything", "reason", "?"));

        assertThat(move).isInstanceOf(Move.AskHuman.class);
        assertThat(move.reason()).contains("delete_everything");
    }
}
