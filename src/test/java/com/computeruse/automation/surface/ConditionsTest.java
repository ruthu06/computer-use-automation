package com.computeruse.automation.surface;

import com.computeruse.automation.artifact.Condition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionsTest {

    private static Observation obs(String location, String text, List<UiElement> elements) {
        return new Observation(location, "Page Title", text, elements, null);
    }

    private static UiElement el(String role, String name) {
        return new UiElement("ref1", role, name, null, true, null);
    }

    // ── LocationContains ─────────────────────────────────────────────────────

    @Test
    void locationContains_matchesSubstring() {
        var obs = obs("http://localhost:8080/parabank/overview.htm", "", List.of());
        assertThat(Conditions.holds(new Condition.LocationContains("/overview.htm"), obs)).isTrue();
    }

    @Test
    void locationContains_doesNotMatchMissingSubstring() {
        var obs = obs("http://localhost:8080/parabank/index.htm", "", List.of());
        assertThat(Conditions.holds(new Condition.LocationContains("/overview.htm"), obs)).isFalse();
    }

    // ── TextVisible ──────────────────────────────────────────────────────────

    @Test
    void textVisible_matchesExactText() {
        var obs = obs("http://x/", "The username and password could not be verified.", List.of());
        assertThat(Conditions.holds(
                new Condition.TextVisible("The username and password could not be verified."), obs))
                .isTrue();
    }

    @Test
    void textVisible_matchesSubstringInsideLargerText() {
        var obs = obs("http://x/", "Some prefix. Error occurred. Some suffix.", List.of());
        assertThat(Conditions.holds(new Condition.TextVisible("Error occurred."), obs)).isTrue();
    }

    @Test
    void textVisible_isCaseInsensitive() {
        var obs = obs("http://x/", "Welcome, JOHN", List.of());
        assertThat(Conditions.holds(new Condition.TextVisible("welcome, john"), obs)).isTrue();
    }

    @Test
    void textVisible_collapseWhitespace() {
        var obs = obs("http://x/", "Accounts   Overview", List.of());
        assertThat(Conditions.holds(new Condition.TextVisible("Accounts Overview"), obs)).isTrue();
    }

    // ── ElementVisible ───────────────────────────────────────────────────────

    @Test
    void elementVisible_matchesRoleAndName() {
        var obs = obs("http://x/", "", List.of(el("heading", "Accounts Overview")));
        assertThat(Conditions.holds(new Condition.ElementVisible("heading", "Accounts Overview"), obs))
                .isTrue();
    }

    @Test
    void elementVisible_failsWhenNameMismatch() {
        var obs = obs("http://x/", "", List.of(el("heading", "Something Else")));
        assertThat(Conditions.holds(new Condition.ElementVisible("heading", "Accounts Overview"), obs))
                .isFalse();
    }

    @Test
    void elementVisible_nameIsCaseInsensitive() {
        var obs = obs("http://x/", "", List.of(el("heading", "ACCOUNTS OVERVIEW")));
        assertThat(Conditions.holds(new Condition.ElementVisible("heading", "Accounts Overview"), obs))
                .isTrue();
    }

    @Test
    void elementVisible_failsWhenRoleMismatch() {
        var obs = obs("http://x/", "", List.of(el("button", "Accounts Overview")));
        assertThat(Conditions.holds(new Condition.ElementVisible("heading", "Accounts Overview"), obs))
                .isFalse();
    }

    // ── AllOf ────────────────────────────────────────────────────────────────

    @Test
    void allOf_trueWhenAllChildrenTrue() {
        var obs = obs("http://x/overview.htm", "Accounts Overview",
                List.of(el("heading", "Accounts Overview")));
        var cond = new Condition.AllOf(List.of(
                new Condition.LocationContains("/overview.htm"),
                new Condition.ElementVisible("heading", "Accounts Overview")
        ));
        assertThat(Conditions.holds(cond, obs)).isTrue();
    }

    @Test
    void allOf_falseWhenOneChildFalse() {
        var obs = obs("http://x/other.htm", "Accounts Overview",
                List.of(el("heading", "Accounts Overview")));
        var cond = new Condition.AllOf(List.of(
                new Condition.LocationContains("/overview.htm"),
                new Condition.ElementVisible("heading", "Accounts Overview")
        ));
        assertThat(Conditions.holds(cond, obs)).isFalse();
    }

    @Test
    void allOf_emptyListIsTrue() {
        var obs = obs("http://x/", "", List.of());
        assertThat(Conditions.holds(new Condition.AllOf(List.of()), obs)).isTrue();
    }
}
