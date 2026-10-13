package com.computeruse.automation.api;

import com.computeruse.automation.artifact.Json;
import com.computeruse.automation.handoff.Session;
import com.computeruse.automation.redaction.Redactor;
import com.computeruse.automation.surface.Surface;
import com.computeruse.automation.surface.web.PlaywrightSurface;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Keeps the live sessions, so a later request can find the one it is about. */
@Component
public class SessionManager {

    private static final Logger LOG = LoggerFactory.getLogger(SessionManager.class);

    /** Opens the browser for one session. Called on the session's own thread. */
    @FunctionalInterface
    public interface SurfaceFactory {
        Surface open(Path evidenceDir, UnaryOperator<String> scrub);
    }

    private final SurfaceFactory surfaces;
    private final Duration humanWait;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Autowired
    public SessionManager(AutomationProperties properties) {
        this((evidenceDir, scrub) ->
                        new PlaywrightSurface(new PlaywrightSurface.Options(evidenceDir, properties.headless(), scrub)),
                Duration.ofSeconds(properties.humanWaitSeconds()));
    }

    public SessionManager(SurfaceFactory surfaces) {
        this(surfaces, Session.DEFAULT_HUMAN_WAIT);
    }

    public SessionManager(SurfaceFactory surfaces, Duration humanWait) {
        this.surfaces = surfaces;
        this.humanWait = humanWait;
    }

    /**
     * Starts a run in its own session and returns at once.
     * When the session ends, its whole story (every state, what each person did, the result or
     * the error) is written to session.json in the run's evidence folder.
     *
     * @param redactor the same one the job uses, so errors and evidence are scrubbed alike
     */
    public Session start(String id, Path evidenceDir, Redactor redactor, Session.Job job) {
        Session session = new Session(id, redactor::redact, humanWait);
        sessions.put(id, session);
        session.onFinish(view -> writeRecord(evidenceDir, view, redactor));
        session.start(() -> surfaces.open(evidenceDir, redactor::redact), job);
        return session;
    }

    public Optional<Session> find(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    private static void writeRecord(Path evidenceDir, Session.View view, Redactor redactor) {
        try {
            Files.createDirectories(evidenceDir);
            Files.writeString(evidenceDir.resolve("session.json"),
                    redactor.redact(Json.MAPPER.writeValueAsString(view)));
        } catch (IOException e) {
            LOG.warn("could not write session.json for {}: {}", view.id(), e.getMessage());
        }
    }

    /** On shutdown, no browser is left open waiting for a person who is not coming. */
    @PreDestroy
    void closeAll() {
        sessions.values().forEach(Session::close);
    }
}
