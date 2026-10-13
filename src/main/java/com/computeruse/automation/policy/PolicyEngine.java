package com.computeruse.automation.policy;

import com.computeruse.automation.policy.Decision.Verdict;
import com.computeruse.automation.policy.Policy.RiskRule;
import com.computeruse.automation.surface.Action;
import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Target;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether an action may run.
 *
 * Called before every action during both discovery and replay —
 * so Claude and a recorded recipe are held to exactly the same rules.
 *
 * Three questions, asked in order:
 *   1. Is this type of action on the allowlist?
 *   2. Is the current page (or destination) on the allowlist?
 *   3. Is this the risky control that needs human approval?
 */
public final class PolicyEngine {

    // Pre-compiled at construction time — these checks run before every action
    private record CompiledRule(RiskRule rule, Pattern path, Pattern name) {}

    private final Policy policy;
    private final List<String> origins;
    private final List<Pattern> paths;
    private final List<CompiledRule> rules;

    public PolicyEngine(Policy policy) {
        this.policy  = policy;
        this.origins = policy.allowedOrigins().stream()
                .map(o -> o.toLowerCase(Locale.ROOT)).toList();
        this.paths   = policy.allowedPaths().stream()
                .map(PolicyEngine::glob).toList();
        this.rules   = policy.riskyActions().stream()
                .map(r -> new CompiledRule(r, glob(r.path()), Pattern.compile(r.name())))
                .toList();
    }

    /**
     * The main check. Called before every action.
     *
     * @param currentLocation where the surface is right now
     */
    public Decision evaluate(Action action, String currentLocation) {

        // Question 1 — is this action type allowed at all?
        if (!policy.allowedActions().contains(action.type())) {
            return new Decision(Verdict.DENY,
                    "action type " + action.type() + " is not on the allowlist");
        }

        // Question 2 — Navigate is judged by where it's going, not where we are.
        if (action instanceof Action.Navigate nav) {
            return evaluateLocation(nav.url());
        }

        // Question 2 — everything else is judged by the current page.
        Decision here = evaluateLocation(currentLocation);
        if (!here.allowed()) {
            return here;
        }

        // Wait has no target and no risky-action rules apply to it.
        if (action instanceof Action.Wait) {
            return here;
        }

        // Question 3 — does this action target the risky control?
        Target target = switch (action) {
            case Action.Click   a -> a.target();
            case Action.Type    a -> a.target();
            case Action.Select  a -> a.target();
            case Action.Read    a -> a.target();
            default -> throw new IllegalStateException("unhandled: " + action.type());
        };

        String path = pathOf(URI.create(currentLocation));
        for (CompiledRule compiled : rules) {

            // Does this rule apply to the current page and action type?
            if (compiled.rule().action() != action.type()
                    || !compiled.path().matcher(path).matches()) {
                continue;
            }

            // Extract the role+name locators — they're the only ones that identify a control by what it IS.
            List<Locator.Role> identities = target.locators().stream()
                    .filter(Locator.Role.class::isInstance)
                    .map(Locator.Role.class::cast)
                    .toList();

            // If the target has no role+name locator, we can't confirm it's safe.
            // Require approval rather than guess.
            if (identities.isEmpty()) {
                return new Decision(Verdict.REQUIRE_APPROVAL,
                        "cannot confirm the target is safe on a page with a risky control: "
                        + compiled.rule().reason());
            }

            // Does any role+name locator match the risky-control rule?
            boolean matches = identities.stream().anyMatch(id ->
                    (compiled.rule().role() == null
                            || compiled.rule().role().equalsIgnoreCase(id.role()))
                    && id.name() != null
                    && compiled.name().matcher(id.name()).find());

            if (matches) {
                return new Decision(Verdict.REQUIRE_APPROVAL, compiled.rule().reason());
            }
        }

        return here;
    }

    /**
     * Checks an address in isolation.
     * Called for Navigate destinations and also after every action,
     * because a click can navigate to a new page.
     */
    public Decision evaluateLocation(String location) {
        URI uri;
        try {
            uri = URI.create(location);
        } catch (IllegalArgumentException | NullPointerException e) {
            return new Decision(Verdict.DENY, "not a valid address");
        }

        // Check the origin (scheme + host + port)
        String origin = originOf(uri);
        if (origin == null || !origins.contains(origin)) {
            return new Decision(Verdict.DENY,
                    "origin " + (origin == null ? uri.getScheme() : origin)
                    + " is not on the allowlist");
        }

        // Check the path
        String path = pathOf(uri);
        if (paths.stream().noneMatch(p -> p.matcher(path).matches())) {
            return new Decision(Verdict.DENY,
                    "path " + path + " is not on the allowlist");
        }

        return new Decision(Verdict.ALLOW, "allowed");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String originOf(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) return null;
        String origin = uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        return origin.toLowerCase(Locale.ROOT);
    }

    /**
     * Strips matrix parameters like ;jsessionid=ABC from the path before checking.
     * ParaBank puts session tokens in the URL — without this, every path check would fail.
     */
    private static String pathOf(URI uri) {
        String path = uri.normalize().getPath();
        if (path == null || path.isEmpty()) return "/";
        int semicolon = path.indexOf(';');
        return semicolon < 0 ? path : path.substring(0, semicolon);
    }

    /**
     * Converts a simple wildcard pattern to a regex.
     *   *  matches within one path segment (no slashes)
     *   ** matches across segments
     */
    private static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (String part : glob.split("((?<=\\*\\*)|(?=\\*\\*))")) {
            if (part.equals("**")) {
                regex.append(".*");
            } else {
                String[] literals = part.split("\\*", -1);
                for (int i = 0; i < literals.length; i++) {
                    if (i > 0) regex.append("[^/]*");
                    regex.append(Pattern.quote(literals[i]));
                }
            }
        }
        return Pattern.compile(regex.toString());
    }
}
