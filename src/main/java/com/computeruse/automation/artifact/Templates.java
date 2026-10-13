package com.computeruse.automation.artifact;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {{placeholder}} tokens in step values.
 *
 * A capability stores steps with values like "{{username}}" and "{{password}}".
 * Before replay executes each step, it calls resolve() with the caller's inputs
 * to swap the token for the real value.
 *
 * Rules:
 *   - Tokens look like {{name}} — double curly braces, no spaces inside.
 *   - resolve() throws if a token has no matching key in the inputs map.
 *   - Null text is returned as null — safe to call on any nullable field.
 */
public final class Templates {

    /** Shared with the validator, so both agree on what a placeholder looks like. */
    static final Pattern TOKEN = Pattern.compile("\\{\\{([^}]+)}}");

    /**
     * Replace every {{key}} in text with inputs.get(key).
     *
     * @throws IllegalArgumentException if any token has no matching key
     */
    public static String resolve(String text, Map<String, Object> inputs) {
        if (text == null) return null;

        Matcher m = TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            if (!inputs.containsKey(key)) {
                throw new IllegalArgumentException(
                        "step value references input '" + key + "' which was not provided");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(inputs.get(key))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Templates() {}
}
