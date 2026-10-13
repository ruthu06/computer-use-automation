package com.computeruse.automation.smoke;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;

/** Sets up customers in the live ParaBank, with a plain browser and no AI. */
final class ParaBankUsers {

    /**
     * Registers a brand-new customer through the Register form.
     *
     * @param baseUrl e.g. "http://localhost:8080"
     * @return the new username; the password is the one passed in
     */
    static String register(String baseUrl, String password) {
        String username = "user" + Long.toString(System.nanoTime(), 36);
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch();
             BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.setDefaultTimeout(10_000);

            page.navigate(baseUrl + "/parabank/index.htm");
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Register")).click();
            page.locator("[id='customer.firstName']").fill("Replay");
            page.locator("[id='customer.lastName']").fill("Test");
            page.locator("[id='customer.address.street']").fill("1 Test Street");
            page.locator("[id='customer.address.city']").fill("Testville");
            page.locator("[id='customer.address.state']").fill("CA");
            page.locator("[id='customer.address.zipCode']").fill("90000");
            page.locator("[id='customer.phoneNumber']").fill("555-0100");
            page.locator("[id='customer.ssn']").fill("000-00-0000");
            page.locator("[id='customer.username']").fill(username);
            page.locator("[id='customer.password']").fill(password);
            page.locator("#repeatedPassword").fill(password);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Register")).click();

            assertThat(page.getByText("Your account was created successfully. You are now logged in."))
                    .isVisible();
        }
        return username;
    }

    private ParaBankUsers() {}
}
