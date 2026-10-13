package com.computeruse.automation.artifact;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * Validates a Capability's structure before a run is allowed to start.
 *
 * The validator is intentionally separate from Capability so that validation
 * logic can grow without cluttering the data class.
 *
 * What it checks:
 *   - Required string fields are present and non-blank
 *   - Every step has an id, description, action, and target (except Navigate/Wait)
 *   - Step ids are unique within the capability
 *   - Every {{placeholder}} in step values matches a declared input key
 *   - The success condition is present
 */
public final class CapabilityValidator {

    /**
     * @throws InvalidCapabilityException if any violation is found
     */
    public static void validate(Capability cap) {
        List<String> v = new ArrayList<>();

        if (blank(cap.id()))          v.add("id is required");
        if (blank(cap.name()))        v.add("name is required");
        if (cap.app() == null)        v.add("app is required");
        if (cap.successCondition() == null) v.add("successCondition is required");

        if (cap.app() != null) {
            if (blank(cap.app().product()))    v.add("app.product is required");
            if (blank(cap.app().entryPath()))  v.add("app.entryPath is required");
        }

        if (cap.steps().isEmpty()) {
            v.add("at least one step is required");
        } else {
            Set<String> seenIds = new HashSet<>();
            Set<String> inputKeys = cap.inputs().stream()
                    .map(Capability.Input::name).collect(Collectors.toSet());
            Set<String> outputKeys = cap.outputs().stream()
                    .map(Capability.Output::name).collect(Collectors.toSet());

            for (int i = 0; i < cap.steps().size(); i++) {
                validateStep(cap.steps().get(i), i, seenIds, inputKeys, outputKeys, v);
            }
        }

        if (!v.isEmpty()) throw new InvalidCapabilityException(v);
    }

    private static void validateStep(
            Step step, int idx,
            Set<String> seenIds, Set<String> inputKeys, Set<String> outputKeys,
            List<String> v) {

        String prefix = "step[" + idx + "]";

        if (blank(step.id())) {
            v.add(prefix + ".id is required");
        } else if (!seenIds.add(step.id())) {
            v.add(prefix + " duplicate id '" + step.id() + "'");
        }

        if (blank(step.description())) v.add(prefix + ".description is required");
        if (step.action() == null) {
            v.add(prefix + ".action is required");
        } else {
            validateActionFields(step, prefix, outputKeys, v);
        }

        // Every {{placeholder}} in the value must name a declared input.
        if (step.value() != null) {
            Matcher m = Templates.TOKEN.matcher(step.value());
            while (m.find()) {
                String key = m.group(1);
                if (!inputKeys.contains(key)) {
                    v.add(prefix + ".value references undeclared input '" + key + "'");
                }
            }
        }
    }

    /**
     * Each action type needs different fields. Catching a missing one here means
     * a bad recipe is rejected before the browser opens, instead of crashing mid-run.
     */
    private static void validateActionFields(
            Step step, String prefix, Set<String> outputKeys, List<String> v) {

        boolean hasTarget = step.target() != null && !step.target().locators().isEmpty();
        String action = step.action().name();

        switch (step.action()) {
            case NAVIGATE -> {
                if (blank(step.value())) v.add(prefix + " NAVIGATE requires a value (the path to open)");
            }
            case CLICK -> {
                if (!hasTarget) v.add(prefix + " CLICK requires a target with at least one locator");
            }
            case TYPE, SELECT -> {
                if (!hasTarget) v.add(prefix + " " + action + " requires a target with at least one locator");
                if (step.value() == null) v.add(prefix + " " + action + " requires a value");
            }
            case READ -> {
                if (!hasTarget) v.add(prefix + " READ requires a target with at least one locator");
                if (blank(step.output())) {
                    v.add(prefix + " READ requires an output name");
                } else if (!outputKeys.contains(step.output())) {
                    v.add(prefix + " READ stores into undeclared output '" + step.output() + "'");
                }
            }
            case WAIT -> {
                if (step.expect() == null) v.add(prefix + " WAIT requires an expect condition");
            }
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private CapabilityValidator() {}
}
