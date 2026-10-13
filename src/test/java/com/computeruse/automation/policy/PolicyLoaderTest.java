package com.computeruse.automation.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.surface.Action;
import com.computeruse.automation.surface.ActionType;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyLoaderTest {

    private static final String HOME = "http://localhost:8080/parabank/index.htm";
    private static final String OPEN_ACCOUNT = "http://localhost:8080/parabank/openaccount.htm";

    @Test
    void readsEveryPartOfTheParaBankFile() throws IOException {
        Policy policy = PolicyLoader.fromResource("policy/parabank.json");

        assertThat(policy.allowedOrigins()).containsExactly("http://localhost:8080");
        assertThat(policy.allowedPaths()).contains("/parabank/login.htm", "/parabank/openaccount.htm");
        assertThat(policy.allowedActions()).containsExactlyInAnyOrder(ActionType.values());
        assertThat(policy.riskyActions()).singleElement().satisfies(rule -> {
            assertThat(rule.action()).isEqualTo(ActionType.CLICK);
            assertThat(rule.path()).isEqualTo("/parabank/openaccount.htm");
            assertThat(rule.role()).isEqualTo("button");
        });
    }

    @Test
    void theLoadedFileMakesTheEngineGuardTheOpenAccountButton() throws IOException {
        var engine = new PolicyEngine(PolicyLoader.fromResource("policy/parabank.json"));
        var openButton = new Target("button Open New Account",
                List.of(new Locator.Role("button", "Open New Account")));

        assertThat(engine.evaluate(new Action.Navigate(HOME), HOME).allowed()).isTrue();
        assertThat(engine.evaluate(new Action.Click(openButton), OPEN_ACCOUNT).verdict())
                .isEqualTo(Decision.Verdict.REQUIRE_APPROVAL);
    }

    @Test
    void aMissingFileIsReportedByName() {
        assertThatThrownBy(() -> PolicyLoader.fromResource("policy/nope.json"))
                .isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("policy/nope.json");
    }
}
