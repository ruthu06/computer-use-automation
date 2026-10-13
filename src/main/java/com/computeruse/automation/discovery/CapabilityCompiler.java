package com.computeruse.automation.discovery;

import com.computeruse.automation.artifact.Capability;
import com.computeruse.automation.artifact.CapabilityValidator;
import com.computeruse.automation.artifact.Condition;
import com.computeruse.automation.artifact.InvalidCapabilityException;
import com.computeruse.automation.artifact.Locator;
import com.computeruse.automation.artifact.Target;
import com.computeruse.automation.artifact.Risk;
import com.computeruse.automation.artifact.Step;
import com.computeruse.automation.artifact.ValueType;
import com.computeruse.automation.discovery.DiscoveryLoop.RecordedStep;
import com.computeruse.automation.surface.ActionType;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns what discovery found into the parts of a saved recipe.
 *
 * Discovery notes are rough: full addresses, the brain's own words, no ids, only
 * the names of values. A recipe is tidy and stands on its own.
 */
public final class CapabilityCompiler {

    /**
     * Who the recipe is. Discovery cannot know this, so the caller says.
     *
     * id/version — how the recipe is referred to, e.g. "parabank.read-account" and "1.0.0"
     * product    — the application it was recorded on, e.g. "ParaBank" and its version
     */
    public record Identity(String id, String version, String name, String description,
                           String product, String productVersion) {}

    // ── the whole recipe ─────────────────────────────────────────────────────

    /**
     * Assembles a finished recipe from a discovery run that reached its goal.
     *
     * @param outcomes known non-success endings, e.g. a rejected login. A run that went
     *                 right never saw them, so a person supplies them.
     * @throws IllegalArgumentException   if the run did not complete or did not record where it ended
     * @throws InvalidCapabilityException if what was assembled is not a legal recipe
     */
    public static Capability compile(Identity identity, Brain.Task task, Set<String> sensitiveInputs,
                                     DiscoveryLoop.Result discovered, List<Capability.Outcome> outcomes,
                                     Capability.Provenance provenance) {
        if (discovered.status() != DiscoveryLoop.Status.COMPLETED) {
            // A half-finished run would become a recipe that quietly stops half way.
            throw new IllegalArgumentException(
                    "only a completed discovery can be compiled, this one is " + discovered.status());
        }
        if (discovered.location() == null) {
            throw new IllegalArgumentException("discovery did not record where it ended");
        }

        List<Step> steps = steps(discovered.steps(), discovered.outputs());
        Step first = steps.isEmpty() ? null : steps.get(0);
        if (first == null || first.action() != ActionType.NAVIGATE) {
            throw new IllegalArgumentException("discovery did not start by opening a page");
        }

        Capability recipe = new Capability(
                Capability.SCHEMA_VERSION,
                identity.id(), identity.version(), identity.name(), identity.description(),
                new Capability.App(identity.product(), identity.productVersion(), "web", first.value()),
                inputs(task, sensitiveInputs),
                outputs(task),
                steps,
                outcomes,
                successCondition(discovered.location()),
                provenance);

        CapabilityValidator.validate(recipe);
        return recipe;
    }

    /**
     * The run succeeded when the page is the one discovery ended on.
     * Session ids such as ";jsessionid=ABC" and query strings change every run, so they are dropped.
     */
    static Condition successCondition(String location) {
        String path = URI.create(location).getPath();
        int matrix = path.indexOf(';');
        return new Condition.LocationContains(matrix < 0 ? path : path.substring(0, matrix));
    }

    // ── steps ────────────────────────────────────────────────────────────────

    static List<Step> steps(List<RecordedStep> recorded) {
        return steps(recorded, Map.of());
    }

    /**
     * @param readValues what each READ step actually read, by output name. Those texts are data
     *                   from one customer's run, so they must not end up in the recipe.
     */
    static List<Step> steps(List<RecordedStep> recorded, Map<String, String> readValues) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < recorded.size(); i++) {
            steps.add(step(withoutReadValue(recorded.get(i), readValues), i + 1));
        }
        return steps;
    }

    /**
     * A control being read is found by its own text, e.g. a cell named "$515.50". On another
     * customer's screen that text is different, so a locator made of it only finds this run's
     * data. Dropped here; the position-based locators that remain are what replay uses.
     */
    private static RecordedStep withoutReadValue(RecordedStep recorded, Map<String, String> readValues) {
        String value = recorded.action() == ActionType.READ && recorded.output() != null
                ? readValues.get(recorded.output()) : null;
        if (value == null || value.isBlank() || recorded.target() == null) {
            return recorded;
        }
        String data = value.strip();

        List<Locator> kept = recorded.target().locators().stream()
                .filter(locator -> !(locator instanceof Locator.Role role && data.equalsIgnoreCase(trimmed(role.name()))))
                .filter(locator -> !(locator instanceof Locator.Label label && data.equalsIgnoreCase(trimmed(label.text()))))
                .toList();
        if (kept.isEmpty()) {
            return recorded;   // nothing durable to fall back on; keep what we have rather than a target that finds nothing
        }
        String name = recorded.target().name().replace(data, "[" + recorded.output() + "]");
        return new RecordedStep(recorded.action(), new Target(name, kept), recorded.value(), recorded.output(),
                recorded.reason(), recorded.risk());
    }

    private static String trimmed(String text) {
        return text == null ? "" : text.strip();
    }

    private static Step step(RecordedStep recorded, int number) {
        ActionType action = recorded.action();
        String id = "step-" + number + "-" + action.name().toLowerCase(Locale.ROOT);

        String description = recorded.reason() == null || recorded.reason().isBlank()
                ? action + (recorded.target() == null ? "" : " " + recorded.target().name())
                : recorded.reason().strip();

        return switch (action) {
            // The recipe must work wherever the app is deployed, so keep the path, drop the host.
            case NAVIGATE -> new Step(id, description, action, null, pathOf(recorded.value()),
                    null, Risk.SAFE, null);

            // A wait is nothing but its condition.
            case WAIT -> new Step(id, description, action, null, null,
                    null, Risk.SAFE, new Condition.TextVisible(recorded.value()));

            // A step that needed a person's approval during discovery asks again on every replay.
            case CLICK, TYPE, SELECT, READ -> new Step(id, description, action, recorded.target(),
                    recorded.value(), recorded.output(), recorded.risk(), null);
        };
    }

    /** "http://localhost:8080/parabank/index.htm?x=1" becomes "/parabank/index.htm?x=1". */
    private static String pathOf(String address) {
        URI uri = URI.create(address);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    // ── inputs and outputs ───────────────────────────────────────────────────

    /**
     * Discovery only knows the NAMES of values, so each is declared as text.
     *
     * @param sensitive names of inputs that are secrets; each must be one of the task's inputs
     */
    static List<Capability.Input> inputs(Brain.Task task, Set<String> sensitive) {
        for (String name : sensitive) {
            // Quietly dropping the flag would put a secret into a file as ordinary data.
            if (!task.inputs().contains(name)) {
                throw new IllegalArgumentException("sensitive input '" + name + "' is not an input of the task");
            }
        }
        return task.inputs().stream()
                .map(name -> new Capability.Input(
                        name, ValueType.STRING, "The caller's " + name, true, sensitive.contains(name), List.of()))
                .toList();
    }

    static List<Capability.Output> outputs(Brain.Task task) {
        return task.outputs().stream()
                .map(name -> new Capability.Output(name, ValueType.STRING, "Read from the screen: " + name, false))
                .toList();
    }

    private CapabilityCompiler() {}
}
