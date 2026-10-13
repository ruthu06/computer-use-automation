package com.computeruse.automation.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.surface.Action;
import com.computeruse.automation.surface.Addresses;
import com.computeruse.automation.surface.Surface;
import com.computeruse.automation.surface.web.PlaywrightSurface;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks the note-taking that runs while a person has the live window.
 * Needs the bank: {@code docker compose up -d --wait}. No AI, no API key.
 *
 * The browser cannot tell a person's click from a scripted one, so scripted actions stand in for the person.
 */
@Tag("smoke")
class HandoffSmokeTest {

    private static final String BASE_URL =
            System.getProperty("parabank.baseUrl", "http://localhost:8080");

    private static final Target USERNAME_BOX =
            new Target("textbox Username", List.of(new Locator.Structure("input[name='username']")));
    private static final Target PASSWORD_BOX =
            new Target("textbox Password", List.of(new Locator.Structure("input[name='password']")));
    private static final Target LOG_IN = new Target("button Log In", List.of(new Locator.Role("button", "Log In")));

    @TempDir
    Path evidenceDir;

    private static final String HOME = BASE_URL + "/parabank/index.htm";
    private static final String ABOUT = BASE_URL + "/parabank/about.htm";

    @Test
    void aPersonWhoStaysOnThePageReachedNothingElse() {
        try (PlaywrightSurface surface = new PlaywrightSurface(
                new PlaywrightSurface.Options(evidenceDir, true, text -> text))) {
            surface.act(new Action.Navigate(HOME));

            surface.beginHandoff();
            surface.act(new Action.Type(USERNAME_BOX, "someone"));   // activity, but on the same page
            surface.endHandoff();

            assertThat(surface.visitedDuringHandoff()).containsExactly(Addresses.page(HOME));
        }
    }

    @Test
    void anotherPageInTheSameTabIsSeen() {
        try (PlaywrightSurface surface = new PlaywrightSurface(
                new PlaywrightSurface.Options(evidenceDir, true, text -> text))) {
            surface.act(new Action.Navigate(HOME));

            surface.beginHandoff();
            surface.act(new Action.Navigate(ABOUT));   // as if a person followed a link
            surface.endHandoff();

            assertThat(surface.visitedDuringHandoff()).contains(Addresses.page(ABOUT));
        }
    }

    @Test
    void aSecondTabIsSeenEvenThoughTheRunsOwnTabNeverMoved() {
        try (PlaywrightSurface surface = new PlaywrightSurface(
                new PlaywrightSurface.Options(evidenceDir, true, text -> text))) {
            surface.act(new Action.Navigate(HOME));

            surface.beginHandoff();
            surface.openSecondTab(ABOUT);                       // what happened in the real trial
            surface.endHandoff();

            // The run's own tab is exactly where it was. Only the browser-wide view shows the other tab.
            assertThat(surface.location()).isEqualTo(HOME);
            assertThat(surface.visitedDuringHandoff())
                    .contains(Addresses.ANOTHER_TAB, Addresses.page(ABOUT));
        }
    }

    @Test
    void whatAPersonDoesIsNotedAcrossAPageChangeAndWhatTheyTypedIsNot() {
        try (Surface surface = new PlaywrightSurface(
                new PlaywrightSurface.Options(evidenceDir, true, text -> text))) {

            surface.act(new Action.Navigate(BASE_URL + "/parabank/index.htm"));
            // Before the hand-over: this is the automation's own doing and must not be noted.
            surface.act(new Action.Type(PASSWORD_BOX, "typed-by-the-robot"));

            surface.beginHandoff();
            surface.act(new Action.Type(USERNAME_BOX, "super-secret-name"));
            surface.act(new Action.Click(LOG_IN));      // submits, so the page changes
            List<String> notes = surface.endHandoff();

            System.out.println("notes:   " + notes);

            assertThat(notes).anyMatch(note -> note.startsWith("typed 17 characters into input#username"));
            assertThat(notes).anyMatch(note -> note.equals("clicked input \"Log In\""));
            // Only how much was typed and where. Never the text itself.
            assertThat(notes).noneMatch(note -> note.contains("super-secret-name"));
            assertThat(notes).noneMatch(note -> note.contains("typed-by-the-robot"));
            // The robot's typing, from before the hand-over, is not the person's.
            assertThat(notes).noneMatch(note -> note.contains("input#password"));

            // After the hand-back nothing more is noted.
            surface.beginHandoff();
            assertThat(surface.endHandoff()).isEmpty();
        }
    }
}
