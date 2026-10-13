package com.computeruse.automation.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.computeruse.automation.artifact.Condition;
import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.surface.Action;
import com.computeruse.automation.surface.Observation;
import com.computeruse.automation.surface.UiElement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MoveTranslatorTest {

    private static final Target PASSWORD_BOX =
            new Target("textbox Password", List.of(new Locator.Label("Password", "textbox")));
    private static final Target LOGIN_BUTTON =
            new Target("button Log In", List.of(new Locator.Role("button", "Log In")));

    private static final Observation SCREEN = new Observation("http://x/", "Login", "", List.of(
            new UiElement("e11", "textbox", "", null, true, PASSWORD_BOX),
            new UiElement("e12", "button", "Log In", null, true, LOGIN_BUTTON)), null);

    private static final Map<String, Object> INPUTS = Map.of("password", "hunter2");

    @Test
    void clickUsesTheDirectionsOfTheNamedControl() {
        Action action = MoveTranslator.toAction(new Move.Click("e12", "submit"), SCREEN, INPUTS);

        assertThat(action).isEqualTo(new Action.Click(LOGIN_BUTTON));
    }

    @Test
    void typeSwapsThePlaceholderForTheRealValue() {
        Action action = MoveTranslator.toAction(new Move.Type("e11", "{{password}}", "fill"), SCREEN, INPUTS);

        assertThat(action).isEqualTo(new Action.Type(PASSWORD_BOX, "hunter2"));
    }

    @Test
    void waitForBecomesAWaitUntilTheTextShows() {
        Action action = MoveTranslator.toAction(new Move.WaitFor("Accounts Overview", "page loads"), SCREEN, INPUTS);

        assertThat(action).isInstanceOf(Action.Wait.class);
        assertThat(((Action.Wait) action).until()).isEqualTo(new Condition.TextVisible("Accounts Overview"));
    }

    @Test
    void aHandleThatIsNotOnTheScreenIsRejected() {
        assertThatThrownBy(() -> MoveTranslator.toAction(new Move.Click("e99", "?"), SCREEN, INPUTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("e99");
    }

    @Test
    void aPlaceholderThatWasNotProvidedIsRejected() {
        assertThatThrownBy(() -> MoveTranslator.toAction(new Move.Type("e11", "{{pin}}", "?"), SCREEN, INPUTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pin");
    }
}
