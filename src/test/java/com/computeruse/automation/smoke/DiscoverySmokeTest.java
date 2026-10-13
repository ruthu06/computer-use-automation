package com.computeruse.automation.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.computeruse.automation.artifact.Capability;
import com.computeruse.automation.artifact.CapabilityJson;
import com.computeruse.automation.artifact.Condition;
import com.computeruse.automation.discovery.Brain;
import com.computeruse.automation.discovery.CapabilityCompiler;
import com.computeruse.automation.discovery.ClaudeBrain;
import com.computeruse.automation.discovery.DiscoveryLoop;
import com.computeruse.automation.evidence.RunLog;
import com.computeruse.automation.policy.PolicyEngine;
import com.computeruse.automation.policy.PolicyLoader;
import com.computeruse.automation.redaction.Redactor;
import com.computeruse.automation.surface.Surface;
import com.computeruse.automation.surface.web.PlaywrightSurface;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The first real run: Claude looks at the live ParaBank and works out the way in.
 *
 * It spends real money, so it only runs when you ask for it:
 * {@code ./mvnw test -Psmoke -Dtest=DiscoverySmokeTest -Dlive=true}
 * with ANTHROPIC_API_KEY set and {@code docker compose up -d --wait} running.
 * Add {@code -Dheaded=true} to watch the browser.
 */
@Tag("smoke")
class DiscoverySmokeTest {

    private static final String BASE_URL =
            System.getProperty("parabank.baseUrl", "http://localhost:8080");
    private static final String PASSWORD = "discover-pass-1";
    private static final int MAX_TURNS = 20;

    /** Runs go to runs/ (git-ignored). -Devidence.root=evidence keeps them where a reviewer can read them. */
    private static final Path EVIDENCE_ROOT = Path.of(System.getProperty("evidence.root", "runs"));

    /** Safe goal: only looks. Nothing on the way is on the policy's risky list. */
    @Test
    void claudeLogsInAndReadsTheAccountDetails() throws IOException {
        Brain.Task task = new Brain.Task(
                "Log in to the bank. When the accounts overview is showing, read the account number "
                        + "of the first account and its balance. Do not change anything.",
                List.of("username", "password"),
                List.of("accountNumber", "balance"));

        DiscoveryLoop.Result result = discover(task);

        assertThat(result.status()).isEqualTo(DiscoveryLoop.Status.COMPLETED);
        assertThat(result.outputs()).containsKeys("accountNumber", "balance");
        assertThat(result.outputs().get("accountNumber")).matches("\\d+");
        assertThat(result.outputs().get("balance")).matches("\\$[\\d,]+\\.\\d{2}");
        assertThat(result.steps()).anyMatch(step -> "{{password}}".equals(step.value()));
        assertThat(result.steps()).noneMatch(step -> PASSWORD.equals(step.value()));

        // Step 5: keep what was found as a reusable recipe.
        Capability recipe = CapabilityCompiler.compile(
                new CapabilityCompiler.Identity("parabank.read-account", "1.0.0", "Read account details",
                        "Logs in to ParaBank and returns the first account's number and balance.",
                        "ParaBank", "2.1.0"),
                task,
                Set.of("password"),
                result,
                // A run that went right never saw this ending, so a person supplies it.
                List.of(new Capability.Outcome("INVALID_CREDENTIALS", "The bank rejected the login",
                        new Condition.TextVisible("The username and password could not be verified."))),
                new Capability.Provenance(Capability.Provenance.Source.DISCOVERY, UUID.randomUUID().toString(),
                        System.getProperty("model", ClaudeBrain.DEFAULT_MODEL), Instant.now().toString()));

        Path file = Path.of("artifacts", recipe.id() + ".json");
        CapabilityJson.save(recipe, file);
        System.out.println("recipe:  " + file.toAbsolutePath());

        assertThat(Files.readString(file)).contains("{{password}}").doesNotContain(PASSWORD);
        assertThat(CapabilityJson.load(file)).isEqualTo(recipe);
    }

    /** Risky goal: shows the policy stopping a real action that Claude chose to take. */
    @Test
    void claudeFindsTheWayToTheRiskyButtonAndStopsThere() throws IOException {
        DiscoveryLoop.Result result = discover(new Brain.Task(
                "Log in to the bank. Then open a new SAVINGS account: go to the Open New Account page, "
                        + "choose the SAVINGS type, and press the Open New Account button. "
                        + "Afterwards read the new account number and its balance.",
                List.of("username", "password"),
                List.of("accountNumber", "balance")));

        // The one click that changes the bank's records must wait for a person.
        assertThat(result.status()).isEqualTo(DiscoveryLoop.Status.NEEDS_HUMAN);
        assertThat(result.message()).contains("cannot be undone");
        // The recipe holds the placeholder, never the real password.
        assertThat(result.steps()).noneMatch(step -> PASSWORD.equals(step.value()));
        assertThat(result.steps()).anyMatch(step -> "{{password}}".equals(step.value()));
    }

    /** Registers a customer, lets Claude pursue the task on the live bank, prints and returns what happened. */
    private DiscoveryLoop.Result discover(Brain.Task task) throws IOException {
        assumeTrue(Boolean.getBoolean("live"), "pass -Dlive=true to spend money on a real Claude run");
        String key = System.getenv("ANTHROPIC_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "ANTHROPIC_API_KEY is not set");

        String username = ParaBankUsers.register(BASE_URL, PASSWORD);

        Redactor redactor = new Redactor();
        // -Dmodel=claude-sonnet-5-5 tries another model on the same goal.
        ClaudeBrain brain = new ClaudeBrain(AnthropicOkHttpClient.fromEnv(),
                System.getProperty("model", ClaudeBrain.DEFAULT_MODEL));

        RunLog log = new RunLog(EVIDENCE_ROOT);
        RunLog.Run run = log.start();

        DiscoveryLoop.Result result;
        long started = System.nanoTime();
        try (Surface surface = new PlaywrightSurface(new PlaywrightSurface.Options(
                run.dir(), !Boolean.getBoolean("headed"), redactor::redact))) {

            DiscoveryLoop loop = new DiscoveryLoop(surface, brain,
                    new PolicyEngine(PolicyLoader.fromResource("policy/parabank.json")), redactor, MAX_TURNS);

            result = loop.run(new DiscoveryLoop.Request(
                    task,
                    BASE_URL + "/parabank/index.htm",
                    Map.of("username", username, "password", PASSWORD),
                    Set.of("password")));

            // What the screen looked like when the run ended.
            surface.captureEvidence("final-page", List.of());
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        ClaudeBrain.Usage usage = brain.usage();

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("runId", run.id());
        record.put("model", brain.model());
        record.put("goal", task.goal());
        record.put("status", result.status());
        record.put("message", result.message());
        record.put("endedOn", result.location());
        record.put("steps", result.steps());
        record.put("outputs", result.outputs());
        record.put("claudeCalls", usage.calls());
        record.put("inputTokens", usage.inputTokens());
        record.put("outputTokens", usage.outputTokens());
        record.put("seconds", Math.round(seconds * 10) / 10.0);
        Path file = log.write(run, "discovery.json", record, redactor::redact);

        System.out.println("record:  " + file.toAbsolutePath());
        System.out.printf("model:   %s%n", brain.model());
        System.out.printf("usage:   %d calls, %d tokens in, %d tokens out, %.1f s%n",
                usage.calls(), usage.inputTokens(), usage.outputTokens(), seconds);
        System.out.println("status:  " + result.status());
        System.out.println("message: " + result.message());
        result.steps().forEach(step -> System.out.println("  " + step));
        System.out.println("outputs: " + result.outputs());
        return result;
    }
}
