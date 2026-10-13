package com.computeruse.automation.artifact;

import java.util.List;

/**
 * The control a step should act on.
 *
 * name     — what a reviewer would call it ("Log In button"). Never used to find
 *            the control — it's only for humans reading the recipe.
 *
 * locators — the strategies for actually finding it, in priority order.
 *            Replay tries them one by one and uses the first that matches.
 *            Recording more than one is what makes replay robust: if the
 *            primary locator ever stops working, the next one takes over.
 */
public record Target(String name, List<Locator> locators) {

    public Target {
        locators = locators == null ? List.of() : List.copyOf(locators);
    }
}
