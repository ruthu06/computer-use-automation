package com.computeruse.automation.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.computeruse.automation.redaction.Redactor;
import com.computeruse.automation.result.ReplayResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLogTest {

    @TempDir
    Path root;

    @Test
    void everyRunGetsItsOwnFolderNamedByItsId() throws IOException {
        RunLog log = new RunLog(root);

        RunLog.Run first = log.start();
        RunLog.Run second = log.start();

        assertThat(first.id()).matches("\\d{8}T\\d{6}-[0-9a-f]{8}");
        assertThat(first.dir()).isEqualTo(root.resolve(first.id())).isDirectory();
        assertThat(second.dir()).isDirectory().isNotEqualTo(first.dir());
    }

    @Test
    void aResultIsWrittenAsReadableJsonInTheRunsFolder() throws IOException {
        RunLog.Run run = new RunLog(root).start();
        var result = new ReplayResult.Success(run.id(), "parabank.read-account", "1.0.0", List.of(),
                Map.of("balance", "$515.50"));

        Path file = new RunLog(root).write(run, "replay.json", result, text -> text);

        assertThat(file).isEqualTo(run.dir().resolve("replay.json"));
        assertThat(Files.readString(file))
                .contains("\"outcome\"")
                .contains("success")
                .contains("$515.50")
                .contains(run.id());
    }

    @Test
    void aRunIdSurvivesTheRedactorUntouched() throws IOException {
        RunLog.Run run = new RunLog(root).start();

        assertThat(new Redactor().redact(run.id())).isEqualTo(run.id());
    }

    @Test
    void aSecretThatSlippedIntoARecordIsMaskedBeforeItReachesTheDisk() throws IOException {
        Redactor redactor = new Redactor();
        redactor.register("password", "hunter2");
        RunLog log = new RunLog(root);
        RunLog.Run run = log.start();

        Path file = log.write(run, "note.json", Map.of("note", "typed hunter2 by mistake"), redactor::redact);

        assertThat(Files.readString(file)).doesNotContain("hunter2").contains("[REDACTED:password]");
    }
}
