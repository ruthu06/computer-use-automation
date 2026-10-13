package com.computeruse.automation.artifact;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityJsonTest {

    private Path sampleArtifact() {
        URL url = getClass().getClassLoader().getResource("artifacts/parabank.login.json");
        assertThat(url).as("sample artifact not found on classpath").isNotNull();
        return Path.of(url.getPath());
    }

    // ── load round-trip ──────────────────────────────────────────────────────

    @Test
    void loadsAndValidatesSampleArtifact() throws IOException {
        Capability cap = CapabilityJson.load(sampleArtifact());

        assertThat(cap.id()).isEqualTo("parabank.login");
        assertThat(cap.version()).isEqualTo("1.0.0");
        assertThat(cap.steps()).hasSize(4);
        assertThat(cap.inputs()).hasSize(2);
        assertThat(cap.outcomes()).hasSize(1);
        assertThat(cap.successCondition()).isInstanceOf(Condition.AllOf.class);
    }

    @Test
    void stepLocatorsDeserialise() throws IOException {
        Capability cap = CapabilityJson.load(sampleArtifact());

        Step fillUsername = cap.steps().get(1);
        assertThat(fillUsername.id()).isEqualTo("fill-username");
        assertThat(fillUsername.target().locators()).hasSize(2);
        assertThat(fillUsername.target().locators().get(0)).isInstanceOf(Locator.Label.class);
        assertThat(fillUsername.target().locators().get(1)).isInstanceOf(Locator.Structure.class);
    }

    @Test
    void successConditionIsAllOf() throws IOException {
        Capability cap = CapabilityJson.load(sampleArtifact());

        var allOf = (Condition.AllOf) cap.successCondition();
        assertThat(allOf.conditions()).hasSize(2);
        assertThat(allOf.conditions().get(0)).isInstanceOf(Condition.LocationContains.class);
        assertThat(allOf.conditions().get(1)).isInstanceOf(Condition.ElementVisible.class);
    }

    // ── serialise → parse round-trip ─────────────────────────────────────────

    @Test
    void roundTrip() throws IOException {
        Capability original = CapabilityJson.load(sampleArtifact());
        String json = Json.MAPPER.writeValueAsString(original);
        Capability reparsed = Json.MAPPER.readValue(json, Capability.class);

        assertThat(reparsed.id()).isEqualTo(original.id());
        assertThat(reparsed.steps()).hasSize(original.steps().size());
    }

    // ── validator ────────────────────────────────────────────────────────────

    @Test
    void validatorRejectsEmptyId() {
        var cap = new Capability(
                "1.0", "", "1.0.0", "Name", "Desc",
                new Capability.App("ParaBank", "2.0", "web", "/index.htm"),
                List.of(), List.of(),
                List.of(new Step("s1", "desc", com.computeruse.automation.surface.ActionType.NAVIGATE,
                        null, "/index.htm", null, Risk.SAFE, null)),
                List.of(),
                new Condition.LocationContains("/overview.htm"),
                null
        );
        assertThatThrownBy(() -> CapabilityValidator.validate(cap))
                .isInstanceOf(InvalidCapabilityException.class)
                .hasMessageContaining("id is required");
    }

    @Test
    void validatorRejectsDuplicateStepIds() {
        var step = new Step("same-id", "desc", com.computeruse.automation.surface.ActionType.NAVIGATE,
                null, "/index.htm", null, Risk.SAFE, null);
        var cap = new Capability(
                "1.0", "test", "1.0.0", "Name", "Desc",
                new Capability.App("ParaBank", "2.0", "web", "/index.htm"),
                List.of(), List.of(),
                List.of(step, step),
                List.of(),
                new Condition.LocationContains("/overview.htm"),
                null
        );
        assertThatThrownBy(() -> CapabilityValidator.validate(cap))
                .isInstanceOf(InvalidCapabilityException.class)
                .hasMessageContaining("duplicate id");
    }

    @Test
    void validatorRejectsUndeclaredInputReference() {
        var step = new Step("s1", "fill it", com.computeruse.automation.surface.ActionType.TYPE,
                new Target("field", List.of(new Locator.Structure("input[name=x]"))),
                "{{undeclaredKey}}", null, Risk.SAFE, null);
        var cap = new Capability(
                "1.0", "test", "1.0.0", "Name", "Desc",
                new Capability.App("ParaBank", "2.0", "web", "/index.htm"),
                List.of(), List.of(),
                List.of(step),
                List.of(),
                new Condition.LocationContains("/overview.htm"),
                null
        );
        assertThatThrownBy(() -> CapabilityValidator.validate(cap))
                .isInstanceOf(InvalidCapabilityException.class)
                .hasMessageContaining("undeclaredKey");
    }

    // ── validator: per-action rules ──────────────────────────────────────────

    private static Capability capWith(List<Capability.Output> outputs, Step step) {
        return new Capability(
                "1.0", "test", "1.0.0", "Name", "Desc",
                new Capability.App("ParaBank", "2.0", "web", "/index.htm"),
                List.of(), outputs,
                List.of(step),
                List.of(),
                new Condition.LocationContains("/overview.htm"),
                null
        );
    }

    private static final Target FIELD =
            new Target("field", List.of(new Locator.Structure("input[name=x]")));

    private static void assertRejected(Step step, String expectedMessage) {
        assertThatThrownBy(() -> CapabilityValidator.validate(capWith(List.of(), step)))
                .isInstanceOf(InvalidCapabilityException.class)
                .hasMessageContaining(expectedMessage);
    }

    @Test
    void validatorRejectsClickWithoutTarget() {
        assertRejected(new Step("s1", "click", com.computeruse.automation.surface.ActionType.CLICK,
                null, null, null, Risk.SAFE, null), "CLICK requires a target");
    }

    @Test
    void validatorRejectsTargetWithNoLocators() {
        assertRejected(new Step("s1", "click", com.computeruse.automation.surface.ActionType.CLICK,
                new Target("empty", List.of()), null, null, Risk.SAFE, null), "CLICK requires a target");
    }

    @Test
    void validatorRejectsTypeWithoutValue() {
        assertRejected(new Step("s1", "type", com.computeruse.automation.surface.ActionType.TYPE,
                FIELD, null, null, Risk.SAFE, null), "TYPE requires a value");
    }

    @Test
    void validatorRejectsNavigateWithoutValue() {
        assertRejected(new Step("s1", "go", com.computeruse.automation.surface.ActionType.NAVIGATE,
                null, null, null, Risk.SAFE, null), "NAVIGATE requires a value");
    }

    @Test
    void validatorRejectsReadIntoUndeclaredOutput() {
        assertRejected(new Step("s1", "read", com.computeruse.automation.surface.ActionType.READ,
                FIELD, null, "accountNumber", Risk.SAFE, null), "undeclared output 'accountNumber'");
    }

    @Test
    void validatorRejectsWaitWithoutExpect() {
        assertRejected(new Step("s1", "wait", com.computeruse.automation.surface.ActionType.WAIT,
                null, null, null, Risk.SAFE, null), "WAIT requires an expect");
    }

    @Test
    void validatorAcceptsReadIntoDeclaredOutput() {
        var step = new Step("s1", "read", com.computeruse.automation.surface.ActionType.READ,
                FIELD, null, "accountNumber", Risk.SAFE, null);
        var outputs = List.of(new Capability.Output("accountNumber", ValueType.STRING, "new account", false));
        CapabilityValidator.validate(capWith(outputs, step));
    }

    // ── Templates ────────────────────────────────────────────────────────────

    @Test
    void templatesResolvesPlaceholders() {
        String result = Templates.resolve("Hello {{name}}, your code is {{code}}.",
                Map.of("name", "Alice", "code", "42"));
        assertThat(result).isEqualTo("Hello Alice, your code is 42.");
    }

    @Test
    void templatesThrowsOnMissingKey() {
        assertThatThrownBy(() -> Templates.resolve("{{missing}}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void templatesNullIsPassedThrough() {
        assertThat(Templates.resolve(null, Map.of())).isNull();
    }
}
