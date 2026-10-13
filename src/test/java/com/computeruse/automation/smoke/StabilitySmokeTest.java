package com.computeruse.automation.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.computeruse.automation.artifact.Capability;
import com.computeruse.automation.artifact.CapabilityJson;
import com.computeruse.automation.artifact.Json;
import com.computeruse.automation.discovery.Brain;
import com.computeruse.automation.discovery.CapabilityCompiler;
import com.computeruse.automation.discovery.ClaudeBrain;
import com.computeruse.automation.discovery.DiscoveryLoop;
import com.computeruse.automation.handoff.ApprovalGate;
import com.computeruse.automation.policy.PolicyEngine;
import com.computeruse.automation.policy.PolicyLoader;
import com.computeruse.automation.redaction.Redactor;
import com.computeruse.automation.replay.ReplayEngine;
import com.computeruse.automation.result.ReplayResult;
import com.computeruse.automation.surface.web.PlaywrightSurface;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How often does it work? The same thing, many times, with the results counted.
 *
 * Replay is free. Discovery spends money, so it only runs with -Dlive=true.
 *   -Drepeats=N   how many trials (default 10 for replay, 3 for discovery)
 *   -Dmodel=ID    which Claude model drives discovery
 *   -Devidence.root=evidence   where the summary is written (default: runs, which is git-ignored)
 *
 * A discovery trial is the whole chain: Claude finds the way, the run is compiled into a recipe,
 * and that recipe is replayed, with no Claude, for a customer it has never seen.
 */
@Tag("smoke")
class StabilitySmokeTest {

    private static final String BASE_URL =
            System.getProperty("parabank.baseUrl", "http://localhost:8080");
    private static final String PASSWORD = "stability-pass-1";
    private static final Path RECIPE = Path.of("artifacts", "parabank.read-account.json");
    private static final Path EVIDENCE_ROOT = Path.of(System.getProperty("evidence.root", "runs"));

    private static final Brain.Task READ_TASK = new Brain.Task(
            "Log in to the bank. When the accounts overview is showing, read the account number "
                    + "of the first account and its balance. Do not change anything.",
            List.of("username", "password"), List.of("accountNumber", "balance"));

    private static final Brain.Task OPEN_TASK = new Brain.Task(
            "Log in to the bank. Then open a new SAVINGS account: use the Open New Account page, choose the "
                    + "SAVINGS type, and press the Open New Account button. When the confirmation shows, "
                    + "read the new account number.",
            List.of("username", "password"), List.of("newAccountNumber"));

    @TempDir
    Path scratch;

    /** One trial. stage says how far it got: discovery, compile, or replay. */
    private record Trial(boolean ok, String stage, String detail, double seconds, int calls, long tokensIn, long tokensOut) {}

    // ── replay, many times ───────────────────────────────────────────────────

    @Test
    void replayingTheSavedRecipeRepeatedlyForNewCustomers() throws IOException {
        assumeTrue(Files.exists(RECIPE), "no saved recipe yet: run the discovery smoke test first");
        int repeats = Integer.getInteger("repeats", 10);
        Capability recipe = CapabilityJson.load(RECIPE);

        List<Trial> trials = new ArrayList<>();
        for (int i = 0; i < repeats; i++) {
            String username = ParaBankUsers.register(BASE_URL, PASSWORD);
            long started = System.nanoTime();
            ReplayResult result = replay(recipe, username, ApprovalGate.NEVER);
            double seconds = seconds(started);
            boolean ok = result instanceof ReplayResult.Success success
                    && success.outputs().get("accountNumber").toString().matches("\\d+")
                    && success.outputs().get("balance").toString().matches("\\$[\\d,]+\\.\\d{2}");
            trials.add(new Trial(ok, "replay", describe(result), seconds, 0, 0, 0));
        }

        summarise("replay-" + repeats, "replay of " + recipe.id(), null, trials);
        assertThat(trials).allMatch(Trial::ok);
    }

    // ── discovery, many times, then replay what it found ─────────────────────

    @Test
    void discoveringAReadOnlyGoalRepeatedly() throws IOException {
        paidRun();
        List<Trial> trials = new ArrayList<>();
        int repeats = Integer.getInteger("repeats", 3);
        for (int i = 0; i < repeats; i++) {
            trials.add(trial(READ_TASK, "parabank.stability-read", ApprovalGate.NEVER,
                    outputs -> outputs.get("accountNumber") != null && outputs.get("accountNumber").matches("\\d+")
                            && outputs.get("balance") != null && outputs.get("balance").matches("\\$[\\d,]+\\.\\d{2}")));
        }
        summarise("discover-read-" + modelName() + "-" + repeats, "discovery of a read-only goal",
                modelName(), trials);
        assertThat(trials).allMatch(Trial::ok);
    }

    @Test
    void discoveringTheRiskyGoalWithApprovalRepeatedly() throws IOException {
        paidRun();
        List<Trial> trials = new ArrayList<>();
        int repeats = Integer.getInteger("repeats", 3);
        for (int i = 0; i < repeats; i++) {
            // The test stands in for the person: it approves the one risky click, in both phases.
            trials.add(trial(OPEN_TASK, "parabank.stability-open", question -> ApprovalGate.Answer.APPROVE,
                    outputs -> outputs.get("newAccountNumber") != null
                            && outputs.get("newAccountNumber").matches("\\d+")));
        }
        summarise("discover-open-" + modelName() + "-" + repeats, "discovery of a goal with a risky click",
                modelName(), trials);
        assertThat(trials).allMatch(Trial::ok);
    }

    // ── one full trial ───────────────────────────────────────────────────────

    private Trial trial(Brain.Task task, String id, ApprovalGate gate, Predicate<Map<String, String>> outputsOk) {
        String discoverer = ParaBankUsers.register(BASE_URL, PASSWORD);
        Redactor redactor = new Redactor();
        ClaudeBrain brain = new ClaudeBrain(AnthropicOkHttpClient.fromEnv(), modelName());
        long started = System.nanoTime();
        DiscoveryLoop.Result found;
        try {
            try (PlaywrightSurface surface = new PlaywrightSurface(new PlaywrightSurface.Options(
                    scratch, true, redactor::redact))) {
                found = new DiscoveryLoop(surface, brain, policy(), redactor, 25, gate).run(
                        new DiscoveryLoop.Request(task, BASE_URL + "/parabank/index.htm",
                                Map.of("username", discoverer, "password", PASSWORD), Set.of("password")));
            }
        } catch (RuntimeException e) {
            return failed("discovery", "crashed: " + e.getClass().getSimpleName(), started, brain);
        }
        ClaudeBrain.Usage used = brain.usage();

        if (found.status() != DiscoveryLoop.Status.COMPLETED) {
            return failed("discovery", found.status() + ": " + found.message(), started, brain);
        }
        if (!outputsOk.test(found.outputs())) {
            return failed("discovery", "completed with unusable outputs " + found.outputs(), started, brain);
        }

        Capability recipe;
        try {
            recipe = CapabilityCompiler.compile(
                    new CapabilityCompiler.Identity(id, "1.0.0", id, "stability trial", "ParaBank", "2.1.0"),
                    task, Set.of("password"), found, List.of(),
                    new Capability.Provenance(Capability.Provenance.Source.DISCOVERY, UUID.randomUUID().toString(),
                            modelName(), Instant.now().toString()));
        } catch (RuntimeException e) {
            return failed("compile", e.getClass().getSimpleName() + ": " + e.getMessage(), started, brain);
        }

        // The point of the whole system: what Claude found must work without Claude, for someone else.
        String replayer = ParaBankUsers.register(BASE_URL, PASSWORD);
        ReplayResult replayed;
        try {
            replayed = replay(recipe, replayer, gate);
        } catch (RuntimeException e) {
            return failed("replay", "crashed: " + e.getClass().getSimpleName(), started, brain);
        }
        boolean ok = replayed instanceof ReplayResult.Success;
        return new Trial(ok, ok ? "replay" : "replay", ok ? "ok" : describe(replayed), seconds(started),
                used.calls(), used.inputTokens(), used.outputTokens());
    }

    private static Trial failed(String stage, String detail, long started, ClaudeBrain brain) {
        ClaudeBrain.Usage used = brain.usage();
        return new Trial(false, stage, detail, seconds(started), used.calls(), used.inputTokens(), used.outputTokens());
    }

    private ReplayResult replay(Capability recipe, String username, ApprovalGate gate) {
        Redactor redactor = new Redactor();
        try (PlaywrightSurface surface = new PlaywrightSurface(new PlaywrightSurface.Options(
                scratch, true, redactor::redact))) {
            return new ReplayEngine(surface, policy(), redactor, BASE_URL, ReplayEngine.DEFAULT_SLOW_STEP_MS, gate)
                    .run(recipe, Map.of("username", username, "password", PASSWORD), "stability-" + UUID.randomUUID());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static PolicyEngine policy() {
        try {
            return new PolicyEngine(PolicyLoader.fromResource("policy/parabank.json"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String modelName() {
        return System.getProperty("model", ClaudeBrain.DEFAULT_MODEL);
    }

    private static void paidRun() {
        assumeTrue(Boolean.getBoolean("live"), "pass -Dlive=true to spend money on real Claude runs");
        String key = System.getenv("ANTHROPIC_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "ANTHROPIC_API_KEY is not set");
    }

    private static double seconds(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 1e8) / 10.0;
    }

    private static String describe(ReplayResult result) {
        return switch (result) {
            case ReplayResult.Success s -> "ok";
            case ReplayResult.BusinessOutcome b -> "business outcome " + b.code();
            case ReplayResult.Failure f -> f.kind() + " at " + f.stepId();
        };
    }

    /** Prints the counts, and keeps them as JSON next to the other evidence. */
    private static void summarise(String name, String what, String model, List<Trial> trials) throws IOException {
        long ok = trials.stream().filter(Trial::ok).count();
        List<Double> times = trials.stream().map(Trial::seconds).sorted(Comparator.naturalOrder()).toList();
        double calls = trials.stream().mapToInt(Trial::calls).average().orElse(0);
        double tokensIn = trials.stream().mapToLong(Trial::tokensIn).average().orElse(0);
        double tokensOut = trials.stream().mapToLong(Trial::tokensOut).average().orElse(0);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("what", what);
        summary.put("model", model);
        summary.put("trials", trials.size());
        summary.put("succeeded", ok);
        summary.put("secondsMin", times.get(0));
        summary.put("secondsMedian", times.get(times.size() / 2));
        summary.put("secondsMax", times.get(times.size() - 1));
        summary.put("avgModelCalls", Math.round(calls * 10) / 10.0);
        summary.put("avgTokensIn", Math.round(tokensIn));
        summary.put("avgTokensOut", Math.round(tokensOut));
        summary.put("failures", trials.stream().filter(t -> !t.ok())
                .map(t -> t.stage() + ": " + t.detail()).toList());

        Files.createDirectories(EVIDENCE_ROOT);
        Path file = EVIDENCE_ROOT.resolve("stability-" + name + ".json");
        Files.writeString(file, Json.MAPPER.writeValueAsString(summary));

        System.out.println("stability: " + what + (model == null ? "" : " [" + model + "]"));
        System.out.printf("  %d of %d succeeded | seconds min/median/max %.1f / %.1f / %.1f%n",
                ok, trials.size(), times.get(0), times.get(times.size() / 2), times.get(times.size() - 1));
        if (model != null) {
            System.out.printf("  avg %.1f model calls, %.0f tokens in, %.0f out per trial%n", calls, tokensIn, tokensOut);
        }
        summary.get("failures");
        ((List<?>) summary.get("failures")).forEach(f -> System.out.println("  FAILED " + f));
        System.out.println("  record: " + file.toAbsolutePath());
    }
}
