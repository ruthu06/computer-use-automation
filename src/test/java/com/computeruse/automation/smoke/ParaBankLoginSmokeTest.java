package com.computeruse.automation.smoke;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Hand-written, non-AI check that the pinned ParaBank container and Playwright work together.
 * Needs the target running: {@code docker compose up -d --wait}, then {@code ./mvnw test -Psmoke}.
 * Pass {@code -Dheaded=true} to watch the browser.
 */
@Tag("smoke")
class ParaBankLoginSmokeTest {

    private static final String BASE_URL =
            System.getProperty("parabank.baseUrl", "http://localhost:8080");
    private static final String PASSWORD = "smoke-pass-1";

    private static Playwright playwright;
    private static Browser browser;

    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(!Boolean.getBoolean("headed")));
    }

    @AfterAll
    static void closeBrowser() {
        browser.close();
        playwright.close();
    }

    @BeforeEach
    void openPage() {
        context = browser.newContext();
        page = context.newPage();
        page.setDefaultTimeout(10_000);
    }

    @AfterEach
    void closePage() {
        context.close();
    }

    @Test
    void registeredUserCanLogIn() {
        String username = ParaBankUsers.register(BASE_URL, PASSWORD);

        logIn(username, PASSWORD);

        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Accounts Overview")))
                .isVisible();
        // The overview table is filled in by script after the page loads.
        assertThat(page.locator("#accountTable a").first()).isVisible();
        assertTrue(page.locator("#accountTable a").first().innerText().matches("\\d+"),
                "expected an account number link in the overview table");
    }

    @Test
    void wrongPasswordIsRejectedWithAMessage() {
        String username = ParaBankUsers.register(BASE_URL, PASSWORD);

        logIn(username, "not-the-password");

        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Error!"))).isVisible();
        assertThat(page.getByText("The username and password could not be verified.")).isVisible();
    }

    private void logIn(String username, String password) {
        page.navigate(BASE_URL + "/parabank/index.htm");
        page.locator("input[name='username']").fill(username);
        page.locator("input[name='password']").fill(password);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log In")).click();
    }
}
