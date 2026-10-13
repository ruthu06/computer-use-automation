package com.computeruse.automation.policy;

import com.computeruse.automation.artifact.Json;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a Policy from a JSON file that ships with the application.
 *
 * The rules live in a file, not in code, so a different bank gets a different
 * file and no recompile.
 */
public final class PolicyLoader {

    /**
     * @param resource path on the classpath, e.g. "policy/parabank.json"
     * @throws FileNotFoundException if no such file is on the classpath
     * @throws IOException           if the file cannot be read
     */
    public static Policy fromResource(String resource) throws IOException {
        try (InputStream in = PolicyLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new FileNotFoundException("policy file not found on the classpath: " + resource);
            }
            return Json.MAPPER.readValue(in, Policy.class);
        }
    }

    private PolicyLoader() {}
}
