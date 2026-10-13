package com.computeruse.automation.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.computeruse.automation.artifact.Capability;
import com.computeruse.automation.artifact.CapabilityJson;
import com.computeruse.automation.artifact.CapabilityValidator;
import com.computeruse.automation.artifact.Condition;
import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Risk;
import com.computeruse.automation.artifact.Step;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.artifact.ValueType;
import com.computeruse.automation.discovery.DiscoveryLoop.RecordedStep;
import com.computeruse.automation.surface.ActionType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilityCompilerTest {

    private static final Target USERNAME_BOX =
            new Target("textbox Username", List.of(new Locator.Label("Username", "textbox")));
    private static final Target BALANCE_CELL =
            new Target("cell $515.50", List.of(new Locator.Structure("td:nth-of-type(2)")));

    private static final List<RecordedStep> LOGIN_AND_READ = List.of(
            new RecordedStep(ActionType.NAVIGATE, null, "http://localhost:8080/parabank/index.htm", null, "open the application"),
            new RecordedStep(ActionType.TYPE, USERNAME_BOX, "{{username}}", null, "  type the username  "),
            new RecordedStep(ActionType.READ, BALANCE_CELL, null, "balance", "read the balance"));

    private static final Brain.Task TASK =
            new Brain.Task("Log in and read the account", List.of("username", "password"), List.of("accountNumber", "balance"));

    // ── steps ────────────────────────────────────────────────────────────────

    @Test
    void anAddressBecomesAPathSoTheRecipeWorksOnAnyHost() {
        List<Step> steps = CapabilityCompiler.steps(LOGIN_AND_READ);

        assertThat(steps.get(0).action()).isEqualTo(ActionType.NAVIGATE);
        assertThat(steps.get(0).value()).isEqualTo("/parabank/index.htm");
    }

    @Test
    void aQueryStringIsKeptWithThePath() {
        var recorded = List.of(new RecordedStep(
                ActionType.NAVIGATE, null, "http://localhost:8080/parabank/activity.htm?id=1", null, "open"));

        assertThat(CapabilityCompiler.steps(recorded).get(0).value()).isEqualTo("/parabank/activity.htm?id=1");
    }

    @Test
    void stepsGetUniqueIdsInOrder() {
        assertThat(CapabilityCompiler.steps(LOGIN_AND_READ)).extracting(Step::id)
                .containsExactly("step-1-navigate", "step-2-type", "step-3-read");
    }

    @Test
    void theBrainsReasonBecomesTheDescription() {
        assertThat(CapabilityCompiler.steps(LOGIN_AND_READ).get(1).description()).isEqualTo("type the username");
    }

    @Test
    void aBlankReasonFallsBackToTheActionAndControlName() {
        var recorded = List.of(new RecordedStep(ActionType.CLICK, USERNAME_BOX, null, null, " "));

        assertThat(CapabilityCompiler.steps(recorded).get(0).description()).isEqualTo("CLICK textbox Username");
    }

    @Test
    void placeholdersTargetsAndOutputsSurviveUntouched() {
        List<Step> steps = CapabilityCompiler.steps(LOGIN_AND_READ);

        assertThat(steps.get(1).target()).isEqualTo(USERNAME_BOX);
        assertThat(steps.get(1).value()).isEqualTo("{{username}}");
        assertThat(steps.get(2).output()).isEqualTo("balance");
        assertThat(steps).extracting(Step::risk).containsOnly(Risk.SAFE);
    }

    @Test
    void whatAReadStepReadIsNotLeftInTheRecipeAsALocator() {
        var cell = new Target("cell $515.50", List.of(
                new Locator.Role("cell", "$515.50"),
                new Locator.Structure("[id='accountTable'] > tbody > tr > td:nth-of-type(2)")));
        var recorded = List.of(new RecordedStep(ActionType.READ, cell, null, "balance", "read the balance"));

        Step step = CapabilityCompiler.steps(recorded, Map.of("balance", "$515.50")).get(0);

        assertThat(step.target().locators()).containsExactly(
                new Locator.Structure("[id='accountTable'] > tbody > tr > td:nth-of-type(2)"));
        assertThat(step.target().name()).isEqualTo("cell [balance]");
    }

    @Test
    void aLocatorThatHappensToDifferFromTheReadValueIsKept() {
        var cell = new Target("cell Balance", List.of(
                new Locator.Role("cell", "Balance"), new Locator.Structure("td:nth-of-type(2)")));
        var recorded = List.of(new RecordedStep(ActionType.READ, cell, null, "balance", "read the balance"));

        Step step = CapabilityCompiler.steps(recorded, Map.of("balance", "$515.50")).get(0);

        assertThat(step.target().locators()).hasSize(2);
        assertThat(step.target().name()).isEqualTo("cell Balance");
    }

    @Test
    void clicksAndTypingAreNeverStrippedEvenIfTheirNameMatchesAReadValue() {
        var link = new Target("link 21891", List.of(new Locator.Role("link", "21891"), new Locator.Structure("a")));
        var recorded = List.of(new RecordedStep(ActionType.CLICK, link, null, null, "open it"));

        Step step = CapabilityCompiler.steps(recorded, Map.of("accountNumber", "21891")).get(0);

        assertThat(step.target().locators()).hasSize(2);
    }

    @Test
    void aStepThatNeededApprovalStaysRiskyInTheRecipe() {
        var recorded = List.of(
                new RecordedStep(ActionType.CLICK, USERNAME_BOX, null, null, "open the account", Risk.RISKY),
                new RecordedStep(ActionType.CLICK, USERNAME_BOX, null, null, "look around"));

        assertThat(CapabilityCompiler.steps(recorded)).extracting(Step::risk)
                .containsExactly(Risk.RISKY, Risk.SAFE);
    }

    @Test
    void aWaitBecomesItsCondition() {
        var recorded = List.of(new RecordedStep(ActionType.WAIT, null, "Accounts Overview", null, "page loads"));

        Step step = CapabilityCompiler.steps(recorded).get(0);

        assertThat(step.expect()).isEqualTo(new Condition.TextVisible("Accounts Overview"));
        assertThat(step.value()).isNull();
    }

    @Test
    void theCompiledPartsAreAcceptedByTheRecipeValidator() {
        var recipe = new Capability(
                Capability.SCHEMA_VERSION, "parabank.read-account", "1.0.0", "Read account", "Reads the balance",
                new Capability.App("ParaBank", "2.1.0", "web", "/parabank/index.htm"),
                CapabilityCompiler.inputs(TASK, Set.of("password")),
                CapabilityCompiler.outputs(TASK),
                CapabilityCompiler.steps(LOGIN_AND_READ),
                List.of(),
                new Condition.LocationContains("/overview.htm"),
                null);

        assertThatCode(() -> CapabilityValidator.validate(recipe)).doesNotThrowAnyException();
    }

    // ── the whole recipe ─────────────────────────────────────────────────────

    private static final CapabilityCompiler.Identity IDENTITY = new CapabilityCompiler.Identity(
            "parabank.read-account", "1.0.0", "Read account", "Logs in and reads the first account",
            "ParaBank", "2.1.0");

    private static final Capability.Provenance PROVENANCE = new Capability.Provenance(
            Capability.Provenance.Source.DISCOVERY, "run-1", "claude-opus-5-5", "2026-10-13T09:00:00Z");

    private static final Capability.Outcome BAD_LOGIN = new Capability.Outcome(
            "INVALID_CREDENTIALS", "The bank rejected the login",
            new Condition.TextVisible("The username and password could not be verified."));

    private static DiscoveryLoop.Result finished(DiscoveryLoop.Status status, String location) {
        return new DiscoveryLoop.Result(status, "done", LOGIN_AND_READ, java.util.Map.of(), location);
    }

    private static Capability compile(DiscoveryLoop.Result result) {
        return CapabilityCompiler.compile(IDENTITY, TASK, Set.of("password"), result, List.of(BAD_LOGIN), PROVENANCE);
    }

    @Test
    void aCompletedRunBecomesAWholeRecipe() {
        Capability recipe = compile(finished(DiscoveryLoop.Status.COMPLETED,
                "http://localhost:8080/parabank/overview.htm"));

        assertThat(recipe.id()).isEqualTo("parabank.read-account");
        assertThat(recipe.app().entryPath()).isEqualTo("/parabank/index.htm");
        assertThat(recipe.app().surface()).isEqualTo("web");
        assertThat(recipe.steps()).hasSize(3);
        assertThat(recipe.inputs()).extracting(Capability.Input::name).containsExactly("username", "password");
        assertThat(recipe.outputs()).extracting(Capability.Output::name).containsExactly("accountNumber", "balance");
        assertThat(recipe.outcomes()).containsExactly(BAD_LOGIN);
        assertThat(recipe.successCondition()).isEqualTo(new Condition.LocationContains("/parabank/overview.htm"));
        assertThat(recipe.provenance()).isEqualTo(PROVENANCE);
    }

    @Test
    void sessionIdsAndQueriesAreLeftOutOfTheSuccessCondition() {
        assertThat(CapabilityCompiler.successCondition("http://x/parabank/overview.htm;jsessionid=ABC123?a=1"))
                .isEqualTo(new Condition.LocationContains("/parabank/overview.htm"));
    }

    @Test
    void aRunThatNeededAHumanIsNotCompiled() {
        assertThatThrownBy(() -> compile(finished(DiscoveryLoop.Status.NEEDS_HUMAN,
                "http://localhost:8080/parabank/openaccount.htm")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NEEDS_HUMAN");
    }

    @Test
    void aRunWithNoEndingLocationIsNotCompiled() {
        assertThatThrownBy(() -> compile(finished(DiscoveryLoop.Status.COMPLETED, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRecipeSurvivesTheTripToJsonAndBackWithoutAnySecret() throws Exception {
        Capability recipe = compile(finished(DiscoveryLoop.Status.COMPLETED,
                "http://localhost:8080/parabank/overview.htm"));
        java.nio.file.Path file = java.nio.file.Files.createTempFile("recipe", ".json");

        CapabilityJson.save(recipe, file);
        String text = java.nio.file.Files.readString(file);

        assertThat(CapabilityJson.load(file)).isEqualTo(recipe);
        assertThat(text).contains("{{username}}").doesNotContain("hunter2");
        java.nio.file.Files.delete(file);
    }

    // ── inputs and outputs ───────────────────────────────────────────────────

    @Test
    void everyTaskInputBecomesARequiredTextInputInTheSameOrder() {
        List<Capability.Input> inputs = CapabilityCompiler.inputs(TASK, Set.of());

        assertThat(inputs).extracting(Capability.Input::name).containsExactly("username", "password");
        assertThat(inputs).allSatisfy(input -> {
            assertThat(input.type()).isEqualTo(ValueType.STRING);
            assertThat(input.required()).isTrue();
            assertThat(input.description()).isNotBlank();
            assertThat(input.allowedValues()).isEmpty();
        });
    }

    @Test
    void onlyTheNamedSecretsAreMarkedSensitive() {
        assertThat(CapabilityCompiler.inputs(TASK, Set.of("password")))
                .filteredOn(Capability.Input::sensitive)
                .extracting(Capability.Input::name).containsExactly("password");
    }

    @Test
    void aSecretThatIsNotATaskInputIsRejected() {
        assertThatThrownBy(() -> CapabilityCompiler.inputs(TASK, Set.of("pin")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pin");
    }

    @Test
    void everyTaskOutputBecomesATextOutputInTheSameOrder() {
        List<Capability.Output> outputs = CapabilityCompiler.outputs(TASK);

        assertThat(outputs).extracting(Capability.Output::name).containsExactly("accountNumber", "balance");
        assertThat(outputs).allSatisfy(output -> {
            assertThat(output.type()).isEqualTo(ValueType.STRING);
            assertThat(output.description()).isNotBlank();
            assertThat(output.sensitive()).isFalse();
        });
    }

    @Test
    void aTaskWithNoInputsOrOutputsGivesEmptyLists() {
        var bare = new Brain.Task("look around", null, null);

        assertThat(CapabilityCompiler.inputs(bare, Set.of())).isEmpty();
        assertThat(CapabilityCompiler.outputs(bare)).isEmpty();
    }
}
