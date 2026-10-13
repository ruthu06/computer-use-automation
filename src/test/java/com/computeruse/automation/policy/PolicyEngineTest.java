package com.computeruse.automation.policy;

import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.surface.Action;
import com.computeruse.automation.surface.ActionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyEngineTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:8080";
    private static final String HOME           = ALLOWED_ORIGIN + "/parabank/index.htm";

    private PolicyEngine engine;

    @BeforeEach
    void setup() {
        var policy = new Policy(
                List.of(ALLOWED_ORIGIN),
                List.of("/parabank/index.htm", "/parabank/login.htm", "/parabank/overview.htm",
                        "/parabank/openaccount.htm"),
                Set.of(ActionType.NAVIGATE, ActionType.CLICK, ActionType.TYPE,
                        ActionType.SELECT, ActionType.READ, ActionType.WAIT),
                List.of(new Policy.RiskRule(
                        ActionType.CLICK,
                        "/parabank/openaccount.htm",
                        "button",
                        "(?i)^open new account$",
                        "opening a new account requires human approval"
                ))
        );
        engine = new PolicyEngine(policy);
    }

    // ── action type allowlist ────────────────────────────────────────────────

    @Test
    void allowsNavigateToAllowedPage() {
        var result = engine.evaluate(new Action.Navigate(ALLOWED_ORIGIN + "/parabank/login.htm"), HOME);
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void deniesNavigateToUnknownOrigin() {
        var result = engine.evaluate(new Action.Navigate("https://evil.example/steal"), HOME);
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("origin");
    }

    @Test
    void deniesNavigateToUnlistedPath() {
        var result = engine.evaluate(new Action.Navigate(ALLOWED_ORIGIN + "/parabank/admin.htm"), HOME);
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("path");
    }

    // ── risky control check ──────────────────────────────────────────────────

    @Test
    void requiresApprovalForOpenAccountButton() {
        var target = new Target("open-account-button", List.of(
                new Locator.Role("button", "Open New Account")
        ));
        var result = engine.evaluate(
                new Action.Click(target),
                ALLOWED_ORIGIN + "/parabank/openaccount.htm"
        );
        assertThat(result.verdict()).isEqualTo(Decision.Verdict.REQUIRE_APPROVAL);
    }

    @Test
    void allowsRegularClickOnRiskyPage() {
        var target = new Target("submit-button", List.of(
                new Locator.Role("button", "Submit")
        ));
        var result = engine.evaluate(
                new Action.Click(target),
                ALLOWED_ORIGIN + "/parabank/openaccount.htm"
        );
        assertThat(result.allowed()).isTrue();
    }

    // ── jsessionid stripping ─────────────────────────────────────────────────

    @Test
    void allowsPageWithJsessionid() {
        var result = engine.evaluate(
                new Action.Navigate(ALLOWED_ORIGIN + "/parabank/overview.htm;jsessionid=ABC123"),
                HOME
        );
        assertThat(result.allowed()).isTrue();
    }

    // ── evaluateLocation ─────────────────────────────────────────────────────

    @Test
    void evaluateLocation_allowsKnownPage() {
        assertThat(engine.evaluateLocation(ALLOWED_ORIGIN + "/parabank/index.htm").allowed())
                .isTrue();
    }

    @Test
    void evaluateLocation_deniesUnknownOrigin() {
        assertThat(engine.evaluateLocation("http://attacker.com/parabank/index.htm").allowed())
                .isFalse();
    }
}
