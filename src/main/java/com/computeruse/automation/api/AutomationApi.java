package com.computeruse.automation.api;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.computeruse.automation.artifact.Capability;
import com.computeruse.automation.artifact.Condition;
import com.computeruse.automation.artifact.Risk;
import com.computeruse.automation.discovery.Brain;
import com.computeruse.automation.discovery.CapabilityCompiler;
import com.computeruse.automation.discovery.ClaudeBrain;
import com.computeruse.automation.discovery.DiscoveryLoop;
import com.computeruse.automation.evidence.RunLog;
import com.computeruse.automation.handoff.Intervention;
import com.computeruse.automation.handoff.Session;
import com.computeruse.automation.policy.PolicyEngine;
import com.computeruse.automation.policy.PolicyLoader;
import com.computeruse.automation.redaction.Redactor;
import com.computeruse.automation.replay.ReplayEngine;
import com.computeruse.automation.result.ReplayResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The outside door: find a recipe, run it, and let a person step in.
 *
 * Calls are synchronous. A call that starts or continues a run returns when the run next
 * needs a person, or is over, or after waitSeconds, whichever comes first. The answer says
 * which: look at "state".
 */
@RestController
public class AutomationApi {

    // ── what comes in and goes out ───────────────────────────────────────────

    public record CapabilitySummary(String id, String version, String name, String description,
                                    List<Capability.Input> inputs, List<Capability.Output> outputs,
                                    boolean needsApproval) {}

    public record ReplayRequest(Map<String, Object> inputs) {}

    /** A reason a person is needed that is already known to the caller, written up as a recipe outcome. */
    public record KnownOutcome(String code, String description, String text) {}

    /**
     * goal            — what to achieve, in plain words
     * inputs          — the real values; sensitiveInputs names which of them are secrets
     * outputs         — names of the values to read back
     * capabilityId... — who the saved recipe will be
     */
    public record DiscoverRequest(String goal, Map<String, Object> inputs, Set<String> sensitiveInputs,
                                  List<String> outputs, String capabilityId, String name, String description,
                                  String startPath, List<KnownOutcome> knownOutcomes) {}

    public record DiscoveryOutcome(String status, String message, Map<String, String> outputs,
                                   String savedAs, ClaudeBrain.Usage usage, double seconds) {}

    /** approve — the answer to a pause for approval. Not needed when handing control back from a person. */
    public record ResumeRequest(Boolean approve, String note) {}

    private final AutomationProperties properties;
    private final CapabilityStore store;
    private final SessionManager sessions;

    public AutomationApi(AutomationProperties properties, CapabilityStore store, SessionManager sessions) {
        this.properties = properties;
        this.store = store;
        this.sessions = sessions;
    }

    // ── recipes ──────────────────────────────────────────────────────────────

    @GetMapping("/capabilities")
    public List<CapabilitySummary> capabilities() {
        return store.list().stream()
                .map(c -> new CapabilitySummary(c.id(), c.version(), c.name(), c.description(), c.inputs(),
                        c.outputs(), c.steps().stream().anyMatch(s -> s.risk() == Risk.RISKY)))
                .toList();
    }

    @GetMapping("/capabilities/{id}")
    public Capability capability(@PathVariable String id) {
        return store.find(id).orElseThrow(() -> notFound("no capability '" + id + "'"));
    }

    // ── runs ─────────────────────────────────────────────────────────────────

    @PostMapping("/capabilities/{id}/replay")
    public Session.View replay(@PathVariable String id, @RequestBody ReplayRequest body) {
        Capability recipe = store.find(id).orElseThrow(() -> notFound("no capability '" + id + "'"));
        Map<String, Object> inputs = body == null || body.inputs() == null ? Map.of() : body.inputs();

        Redactor redactor = new Redactor();
        RunLog log = new RunLog(Path.of(properties.evidenceDir()));
        RunLog.Run run = openRun(log);
        PolicyEngine policy = openPolicy();

        Session session = sessions.start(run.id(), run.dir(), redactor, (surface, gate) -> {
            ReplayResult result = new ReplayEngine(surface, policy, redactor, properties.targetBaseUrl(),
                    ReplayEngine.DEFAULT_SLOW_STEP_MS, gate).run(recipe, inputs, run.id());
            log.write(run, "replay.json", result, redactor::redact);
            return result;
        });
        return session.awaitRest(patience());
    }

    @PostMapping("/discover")
    public Session.View discover(@RequestBody DiscoverRequest body) {
        if (blank(body.goal()) || blank(body.capabilityId()) || blank(body.name()) || body.inputs() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "goal, capabilityId, name and inputs are required");
        }
        Set<String> secrets = body.sensitiveInputs() == null ? Set.of() : body.sensitiveInputs();
        List<String> outputs = body.outputs() == null ? List.of() : body.outputs();
        String startPath = blank(body.startPath()) ? properties.startPath() : body.startPath();
        List<Capability.Outcome> outcomes = body.knownOutcomes() == null ? List.of()
                : body.knownOutcomes().stream()
                        .map(k -> new Capability.Outcome(k.code(), k.description(), new Condition.TextVisible(k.text())))
                        .toList();

        // Fails with 400 now, before a browser opens, if a secret is not among the inputs.
        DiscoveryLoop.Request request;
        Brain.Task task = new Brain.Task(body.goal(), List.copyOf(body.inputs().keySet()), outputs);
        try {
            request = new DiscoveryLoop.Request(task, properties.targetBaseUrl() + startPath, body.inputs(), secrets);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        Redactor redactor = new Redactor();
        RunLog log = new RunLog(Path.of(properties.evidenceDir()));
        RunLog.Run run = openRun(log);
        PolicyEngine policy = openPolicy();

        Session session = sessions.start(run.id(), run.dir(), redactor, (surface, gate) -> {
            long started = System.nanoTime();
            ClaudeBrain brain = new ClaudeBrain(AnthropicOkHttpClient.fromEnv(), properties.model());
            DiscoveryLoop.Result found = new DiscoveryLoop(surface, brain, policy, redactor,
                    properties.maxTurns(), gate).run(request);

            String savedAs = null;
            String message = found.message();
            if (found.status() == DiscoveryLoop.Status.COMPLETED) {
                try {
                    Capability recipe = CapabilityCompiler.compile(
                            new CapabilityCompiler.Identity(body.capabilityId(), "1.0.0", body.name(),
                                    body.description(), properties.product(), properties.productVersion()),
                            task, secrets, found, outcomes,
                            new Capability.Provenance(Capability.Provenance.Source.DISCOVERY, run.id(),
                                    properties.model(), Instant.now().toString()));
                    store.save(recipe);
                    savedAs = recipe.id();
                } catch (RuntimeException e) {
                    message = "the goal was reached, but the recipe was not saved: " + e.getMessage();
                }
            }

            DiscoveryOutcome outcome = new DiscoveryOutcome(found.status().name(), message, found.outputs(),
                    savedAs, brain.usage(), Math.round((System.nanoTime() - started) / 1e8) / 10.0);
            log.write(run, "discovery.json", outcome, redactor::redact);
            return outcome;
        });
        return session.awaitRest(patience());
    }

    // ── a person steps in ────────────────────────────────────────────────────

    @GetMapping("/sessions/{id}")
    public Session.View session(@PathVariable String id) {
        return find(id).view();
    }

    /** The screenshot taken when the run asked for a person. */
    @GetMapping(value = "/sessions/{id}/screenshot", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> screenshot(@PathVariable String id) throws IOException {
        Intervention waiting = find(id).view().intervention();
        if (waiting == null || waiting.evidence() == null || waiting.evidence().screenshot() == null) {
            throw notFound("session '" + id + "' has no screenshot waiting");
        }
        Path file = Path.of(waiting.evidence().screenshot());
        if (!Files.isRegularFile(file)) {
            throw notFound("the screenshot is no longer on disk");
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(Files.readAllBytes(file));
    }

    /** Hands the live window to a person. Only possible while the run is paused for approval. */
    @PostMapping("/sessions/{id}/takeover")
    public Session.View takeover(@PathVariable String id) {
        return send(id, Session.Command.TAKEOVER, null);
    }

    /**
     * Two meanings, by what the session is waiting for:
     * paused for approval  — approve: true does the action, approve: false stops the run
     * a person has control — hands control back; the run carries on after the step they handled
     */
    @PostMapping("/sessions/{id}/resume")
    public Session.View resume(@PathVariable String id, @RequestBody(required = false) ResumeRequest body) {
        String note = body == null ? null : body.note();
        if (find(id).view().state() == Session.State.PAUSED) {
            if (body == null || body.approve() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "this session is waiting for a decision: send {\"approve\": true} or {\"approve\": false}");
            }
            return send(id, body.approve() ? Session.Command.APPROVE : Session.Command.REJECT, note);
        }
        return send(id, Session.Command.RESUME, note);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Session find(String id) {
        return sessions.find(id).orElseThrow(() -> notFound("no session '" + id + "'"));
    }

    private Session.View send(String id, Session.Command command, String note) {
        try {
            return find(id).send(command, note, patience());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private Duration patience() {
        return Duration.ofSeconds(properties.waitSeconds());
    }

    private static RunLog.Run openRun(RunLog log) {
        try {
            return log.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private PolicyEngine openPolicy() {
        try {
            return new PolicyEngine(PolicyLoader.fromResource(properties.policyResource()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
