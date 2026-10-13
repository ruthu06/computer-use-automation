package com.computeruse.automation.evidence;

import com.computeruse.automation.artifact.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Keeps what happened in a run, so it can be looked at afterwards.
 *
 * Every run gets its own folder under the root, named by when it started, e.g.
 * runs/20261013T093012-1a2b3c4d/. Screenshots and snapshots taken during the run go in
 * the same folder as the run's own record, so one folder tells the whole story.
 */
public final class RunLog {

    private static final DateTimeFormatter STAMP =
            // The T matters: 14 digits in a row look like a card number, and the redactor would mask the id.
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    /** id — also the folder name; dir — where this run's files belong */
    public record Run(String id, Path dir) {}

    private final Path root;

    public RunLog(Path root) {
        this.root = root;
    }

    /** Opens a new, empty folder for one run. */
    public Run start() throws IOException {
        String id = STAMP.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path dir = root.resolve(id);
        Files.createDirectories(dir);
        return new Run(id, dir);
    }

    /**
     * Writes any record as JSON into the run's folder.
     *
     * @param scrub applied to the finished text, so a secret that slipped into a record
     *              is still masked before it reaches the disk
     * @return the file written
     */
    public Path write(Run run, String fileName, Object record, UnaryOperator<String> scrub) throws IOException {
        Path file = run.dir().resolve(fileName);
        Files.writeString(file, scrub.apply(Json.MAPPER.writeValueAsString(record)));
        return file;
    }
}
