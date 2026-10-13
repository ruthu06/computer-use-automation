package com.computeruse.automation.redaction;

import com.computeruse.automation.surface.Observation;
import com.computeruse.automation.surface.UiElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    private Redactor redactor;

    @BeforeEach
    void setup() {
        redactor = new Redactor();
    }

    // ── registered secrets ───────────────────────────────────────────────────

    @Test
    void redactsRegisteredSecret() {
        redactor.register("password", "s3cr3t!");
        assertThat(redactor.redact("User typed s3cr3t! into the field"))
                .isEqualTo("User typed [REDACTED:password] into the field");
    }

    @Test
    void redactsLongestSecretFirst() {
        redactor.register("short", "abc");
        redactor.register("long",  "abc123");
        // "abc123" must be removed as one unit, not "abc" + "123"
        assertThat(redactor.redact("value=abc123")).isEqualTo("value=[REDACTED:long]");
    }

    @Test
    void ignoresBlankSecret() {
        redactor.register("empty", "");
        redactor.register("blank", "   ");
        assertThat(redactor.redact("no change here")).isEqualTo("no change here");
    }

    @Test
    void nullInputReturnsNull() {
        assertThat(redactor.redact((String) null)).isNull();
    }

    // ── built-in shape patterns ──────────────────────────────────────────────

    @Test
    void redactsSsn() {
        assertThat(redactor.redact("SSN: 123-45-6789"))
                .isEqualTo("SSN: [REDACTED:ssn]");
    }

    @Test
    void redactsCreditCard() {
        assertThat(redactor.redact("Card: 4111111111111111"))
                .isEqualTo("Card: [REDACTED:card]");
    }

    @Test
    void redactsJsessionid() {
        assertThat(redactor.redact("http://localhost/page;jsessionid=ABCDEF123456"))
                .isEqualTo("http://localhost/page;jsessionid=[REDACTED:session]");
    }

    // ── observation redaction ────────────────────────────────────────────────

    @Test
    void redactsObservationTextFields() {
        redactor.register("password", "hunter2");
        var element = new UiElement("ref1", "textbox", "Password", "hunter2", true, null);
        var obs = new Observation(
                "http://localhost/page;jsessionid=XYZ",
                "Login page",
                "Enter your hunter2 here",
                List.of(element),
                null
        );

        var safe = redactor.redact(obs);

        assertThat(safe.location()).contains("[REDACTED:session]");
        assertThat(safe.text()).doesNotContain("hunter2");
        assertThat(safe.elements().get(0).value()).isEqualTo("[REDACTED:password]");
        assertThat(safe.screenshot()).isNull(); // passed through unchanged
    }
}
