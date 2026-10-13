package com.computeruse.automation.smoke;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.computeruse.automation.artifact.Condition;
import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.redaction.Redactor;
import com.computeruse.automation.surface.Action;
import java.nio.file.Files;
import com.computeruse.automation.surface.ActionResult;
import com.computeruse.automation.surface.Conditions;
import com.computeruse.automation.surface.Evidence;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import com.computeruse.automation.surface.Observation;
import com.computeruse.automation.surface.Surface;
import com.computeruse.automation.surface.SurfaceException;
import com.computeruse.automation.surface.TargetNotFoundException;
import com.computeruse.automation.surface.UiElement;
import com.computeruse.automation.surface.WaitTimedOutException;
import com.computeruse.automation.surface.web.PlaywrightSurface;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the real browser through the Surface interface only.
 * Run with {@code ./mvnw test -Psmoke}; pass {@code -Dheaded=true} to watch.
 */
@Tag("smoke")
class PlaywrightSurfaceSmokeTest {

    private static final String BASE_URL =
            System.getProperty("parabank.baseUrl", "http://localhost:8080");

    @TempDir
    Path evidenceDir;

    private Surface open() {
        return new PlaywrightSurface(new PlaywrightSurface.Options(
                evidenceDir, !Boolean.getBoolean("headed"), redactor::redact));
    }

    private final Redactor redactor = new Redactor();

    @Test
    void everyControlComesWithDirectionsThatWork() {
        try (Surface surface = open()) {
            surface.act(new Action.Navigate(BASE_URL + "/parabank/index.htm"));
            Observation seen = surface.observe();

            // The nameless password box is found by the word written before it.
            UiElement passwordBox = seen.elements().stream()
                    .filter(el -> el.target().locators().contains(new Locator.Label("Password", "textbox")))
                    .findFirst().orElseThrow();
            assertEquals("textbox Password", passwordBox.target().name());

            // Directions taken from one look still work on the next action.
            ActionResult typed = surface.act(new Action.Type(passwordBox.target(), "anything"));
            assertEquals(new Locator.Label("Password", "textbox"), typed.matchedBy());

            UiElement register = seen.elements().stream()
                    .filter(el -> el.role().equals("link") && el.name().equals("Register"))
                    .findFirst().orElseThrow();
            surface.act(new Action.Click(register.target()));
            assertTrue(surface.observe().location().contains("/parabank/register.htm"));
        }
    }

    @Test
    void snapshotDescribesThePageWithSecretsRemoved() throws IOException {
        redactor.register("username", "secret-user-77");
        try (Surface surface = open()) {
            surface.act(new Action.Navigate(BASE_URL + "/parabank/index.htm"));
            surface.act(new Action.Type(USERNAME_BOX, "secret-user-77"));

            Evidence evidence = surface.captureEvidence("login", List.of());
            String snapshot = Files.readString(Path.of(evidence.snapshot()));

            assertTrue(snapshot.contains("button \"Log In\""), snapshot);
            assertTrue(snapshot.contains("Customer Login"), snapshot);
            assertTrue(snapshot.contains("[REDACTED:username]"), snapshot);
            assertTrue(!snapshot.contains("secret-user-77"), snapshot);
        }
    }

    @Test
    void clicksALinkAndLandsOnTheNextPage() {
        try (Surface surface = open()) {
            surface.act(new Action.Navigate(BASE_URL + "/parabank/index.htm"));
            Target register = new Target("register-link", List.of(
                    new Locator.Structure("a.does-not-exist"),
                    new Locator.Role("link", "Register")));

            ActionResult result = surface.act(new Action.Click(register));

            assertEquals(new Locator.Role("link", "Register"), result.matchedBy());
            assertTrue(surface.observe().location().contains("/parabank/register.htm"));
        }
    }

    @Test
    void aMissingControlIsReportedAsNotFound() {
        try (Surface surface = open()) {
            surface.act(new Action.Navigate(BASE_URL + "/parabank/index.htm"));
            Target missing = new Target("no-such-button", List.of(
                    new Locator.Role("button", "Launch Rocket")));

            assertThrows(TargetNotFoundException.class, () -> surface.act(new Action.Click(missing)));
        }
    }

    private static final String PASSWORD = "smoke-pass-1";

    private static final Target USERNAME_BOX = new Target("username-field",
            List.of(new Locator.Label("Username", "textbox")));
    private static final Target PASSWORD_BOX = new Target("password-field",
            List.of(new Locator.Label("Password", "textbox")));
    private static final Target LOGIN_BUTTON = new Target("login-button",
            List.of(new Locator.Role("button", "Log In")));

    private static Target css(String path) {
        return new Target(path, List.of(new Locator.Structure(path)));
    }

    private static Target link(String name) {
        return new Target(name, List.of(new Locator.Role("link", name)));
    }

    /** Registers a fresh synthetic customer through the surface, logs out, and returns the username. */
    private static String registerSyntheticUser(Surface surface) {
        String username = "smoke" + Long.toString(System.nanoTime(), 36);
        surface.act(new Action.Navigate(BASE_URL + "/parabank/register.htm"));
        surface.act(new Action.Type(css("[id='customer.firstName']"), "Smoke"));
        surface.act(new Action.Type(css("[id='customer.lastName']"), "Test"));
        surface.act(new Action.Type(css("[id='customer.address.street']"), "1 Test Street"));
        surface.act(new Action.Type(css("[id='customer.address.city']"), "Testville"));
        surface.act(new Action.Type(css("[id='customer.address.state']"), "CA"));
        surface.act(new Action.Type(css("[id='customer.address.zipCode']"), "90000"));
        surface.act(new Action.Type(css("[id='customer.phoneNumber']"), "555-0100"));
        surface.act(new Action.Type(css("[id='customer.ssn']"), "000-00-0000"));
        surface.act(new Action.Type(css("[id='customer.username']"), username));
        surface.act(new Action.Type(css("[id='customer.password']"), PASSWORD));
        surface.act(new Action.Type(css("#repeatedPassword"), PASSWORD));
        surface.act(new Action.Click(new Target("register-button",
                List.of(new Locator.Role("button", "Register")))));
        surface.act(new Action.Wait(
                new Condition.TextVisible("Your account was created successfully"), 10_000));
        surface.act(new Action.Click(link("Log Out")));
        return username;
    }

    @Test
    void logsInThenReadsAndSelects() {
        try (Surface surface = open()) {
            String username = registerSyntheticUser(surface);

            // TYPE into the two boxes that have no name of their own, then CLICK.
            ActionResult typed = surface.act(new Action.Type(USERNAME_BOX, username));
            surface.act(new Action.Type(PASSWORD_BOX, PASSWORD));
            surface.act(new Action.Click(LOGIN_BUTTON));
            assertEquals(new Locator.Label("Username", "textbox"), typed.matchedBy());

            // WAIT until the overview page is really showing.
            surface.act(new Action.Wait(new Condition.AllOf(List.of(
                    new Condition.LocationContains("/overview.htm"),
                    new Condition.ElementVisible("heading", "Accounts Overview"))), 10_000));

            // READ the account number, which the page draws a moment after it loads.
            ActionResult account = surface.act(new Action.Read(css("#accountTable a")));
            assertTrue(account.value().matches("\\d+"), "expected an account number, got: " + account.value());

            // SELECT an option in a dropdown.
            surface.act(new Action.Click(link("Open New Account")));
            surface.act(new Action.Select(css("#type"), "SAVINGS"));
            assertTrue(surface.observe().elements().stream().anyMatch(el ->
                    el.role().equals("combobox") && "SAVINGS".equals(el.value())));
        }
    }

    @Test
    void aTypedPasswordNeverShowsUpInWhatWeSee() {
        try (Surface surface = open()) {
            surface.act(new Action.Navigate(BASE_URL + "/parabank/index.htm"));
            surface.act(new Action.Type(USERNAME_BOX, "someone"));
            surface.act(new Action.Type(PASSWORD_BOX, "hunter2-secret"));

            Observation seen = surface.observe();

            assertTrue(seen.elements().stream().anyMatch(el -> "someone".equals(el.value())),
                    "the username should be readable");
            assertTrue(seen.elements().stream().noneMatch(el -> "hunter2-secret".equals(el.value())),
                    "the password must not be readable");
            assertTrue(!seen.text().contains("hunter2-secret"));
        }
    }

    /** How many pixels of the cover colour (bright magenta) are in the picture. */
    private static long coveredPixels(String pngPath) throws IOException {
        BufferedImage image = ImageIO.read(new File(pngPath));
        long count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == 0xFF00FF) count++;
            }
        }
        return count;
    }

    @Test
    void screenshotCoversSensitiveControls() throws IOException {
        try (Surface surface = open()) {
            surface.act(new Action.Navigate(BASE_URL + "/parabank/index.htm"));
            surface.act(new Action.Type(PASSWORD_BOX, "hunter2-secret"));

            Evidence plain   = surface.captureEvidence("plain", List.of());
            Evidence covered = surface.captureEvidence("step 3/failure", List.of(PASSWORD_BOX));

            assertEquals(0, coveredPixels(plain.screenshot()), "nothing is sensitive, so nothing is covered");
            assertTrue(coveredPixels(covered.screenshot()) > 500, "the password box should be covered");
            assertTrue(covered.screenshot().endsWith("step_3_failure.png"), covered.screenshot());
        }
    }

    @Test
    void waitingForSomethingThatNeverHappensTimesOut() {
        try (Surface surface = open()) {
            surface.act(new Action.Navigate(BASE_URL + "/parabank/index.htm"));

            assertThrows(WaitTimedOutException.class, () -> surface.act(
                    new Action.Wait(new Condition.TextVisible("Accounts Overview"), 500)));
        }
    }

    @Test
    void startsAndClosesTheBrowser() {
        Surface surface = open();
        surface.close();
    }

    @Test
    void navigatesToTheBank() {
        try (Surface surface = open()) {
            assertDoesNotThrow(() -> surface.act(new Action.Navigate(BASE_URL + "/parabank/index.htm")));
        }
    }

    @Test
    void observesTheLoginPage() {
        try (Surface surface = open()) {
            surface.act(new Action.Navigate(BASE_URL + "/parabank/index.htm"));

            Observation seen = surface.observe();

            assertTrue(seen.location().contains("/parabank/index.htm"), seen.location());
            assertTrue(seen.title().contains("ParaBank"), seen.title());
            assertTrue(Conditions.holds(new Condition.TextVisible("Customer Login"), seen),
                    "expected the login panel heading in: " + seen.text());
        }
    }

    @Test
    void observesTheControlsOnTheLoginPage() {
        try (Surface surface = open()) {
            surface.act(new Action.Navigate(BASE_URL + "/parabank/index.htm"));

            Observation seen = surface.observe();

            assertTrue(Conditions.holds(new Condition.ElementVisible("button", "Log In"), seen));
            assertTrue(Conditions.holds(new Condition.ElementVisible("link", "Register"), seen));
            assertTrue(Conditions.holds(new Condition.ElementVisible("heading", "Customer Login"), seen));

            // ParaBank's login boxes have no name of their own: the reason the Label locator exists.
            long unnamedTextboxes = seen.elements().stream()
                    .filter(el -> el.role().equals("textbox") && el.name().isEmpty())
                    .count();
            assertTrue(unnamedTextboxes >= 2, "expected the two unnamed login boxes");
        }
    }

    @Test
    void anUnreachableAddressIsASurfaceError() {
        try (Surface surface = open()) {
            assertThrows(SurfaceException.class,
                    () -> surface.act(new Action.Navigate("http://localhost:1/nothing-here")));
        }
    }

    @Test
    void closingTwiceIsHarmless() {
        Surface surface = open();
        surface.close();
        assertDoesNotThrow(surface::close);
    }
}
