package com.computeruse.automation.surface.web;

import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Target;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Translates a recipe Locator, which knows nothing about browsers,
 * into a Playwright locator that can find the control on the page.
 */
final class TargetResolver {

    /** Which HTML controls count as each role, written as an XPath test. */
    private static final Map<String, String> HTML_FOR_ROLE = Map.of(
            "textbox",  "self::textarea or self::input[not(@type='hidden' or @type='submit' or @type='button'"
                      + " or @type='reset' or @type='image' or @type='checkbox' or @type='radio')]",
            "button",   "self::button or self::input[@type='submit' or @type='button' or @type='reset' or @type='image']",
            "combobox", "self::select",
            "checkbox", "self::input[@type='checkbox']",
            "radio",    "self::input[@type='radio']",
            "link",     "self::a[@href]");

    /** The control that was found, and which locator found it. */
    record Match(com.microsoft.playwright.Locator control, Locator matchedBy) {}

    /**
     * Tries the target's locators in order. The first one that points at
     * exactly one control wins. Two or more matches is a guess, so it is skipped.
     */
    static Optional<Match> find(Page page, Target target) {
        for (Locator locator : target.locators()) {
            Optional<com.microsoft.playwright.Locator> control = toPlaywright(page, locator);
            if (control.isPresent() && control.get().count() == 1) {
                return Optional.of(new Match(control.get(), locator));
            }
        }
        return Optional.empty();
    }

    /**
     * Empty when the locator has no browser equivalent: a pixel position is not
     * a control, and a role the browser does not know cannot be searched for.
     */
    static Optional<com.microsoft.playwright.Locator> toPlaywright(Page page, Locator locator) {
        return switch (locator) {
            case Locator.Role r        -> byRole(page, r);
            case Locator.Label l       -> afterCaption(page, l);
            case Locator.Structure s   -> Optional.of(page.locator(s.path()));
            case Locator.Coordinates c -> Optional.empty();
        };
    }

    private static Optional<com.microsoft.playwright.Locator> byRole(Page page, Locator.Role locator) {
        AriaRole role;
        try {
            role = AriaRole.valueOf(locator.role().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
        Page.GetByRoleOptions options = new Page.GetByRoleOptions();
        if (locator.name() != null) {
            options.setName(wholeText(locator.name()));
        }
        return Optional.of(page.getByRole(role, options));
    }

    /** The first control of the wanted role that comes after the caption text. */
    private static Optional<com.microsoft.playwright.Locator> afterCaption(Page page, Locator.Label locator) {
        String html = HTML_FOR_ROLE.get(locator.role() == null ? "" : locator.role().toLowerCase(Locale.ROOT));
        if (html == null || locator.text() == null) {
            return Optional.empty();
        }
        return Optional.of(page.getByText(wholeText(locator.text())).first()
                .locator("xpath=following::*[" + html + "]").first());
    }

    /** Matches the whole text, ignoring capitalisation: "Log In" must not match "Log In Help". */
    private static Pattern wholeText(String text) {
        // Escaped by hand because the pattern runs in the browser, and
        // JavaScript does not understand Java's \Q...\E quoting.
        String escaped = text.strip().replaceAll("[.*+?^${}()|\\[\\]\\\\]", "\\\\$0");
        return Pattern.compile("^\\s*" + escaped + "\\s*$", Pattern.CASE_INSENSITIVE);
    }

    private TargetResolver() {}
}
