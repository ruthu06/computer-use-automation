package com.computeruse.automation.artifact;

import tools.jackson.databind.exc.MismatchedInputException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads and writes Capability artifacts to/from JSON files.
 *
 * Always validates after reading — you can't load a broken artifact.
 * Always validates before writing — you can't save a broken artifact.
 *
 * Why a separate class rather than methods on Capability:
 * Capability is a pure data record. IO and validation are concerns that
 * belong outside it so the record stays easy to construct in tests.
 */
public final class CapabilityJson {

    /**
     * Load and validate a capability from a JSON file.
     *
     * @throws InvalidCapabilityException if validation fails
     * @throws IOException                if the file can't be read or parsed
     */
    public static Capability load(Path file) throws IOException {
        Capability cap;
        try {
            cap = Json.MAPPER.readValue(file.toFile(), Capability.class);
        } catch (MismatchedInputException e) {
            throw new InvalidCapabilityException(
                    List.of("JSON does not map to a Capability: " + e.getMessage()));
        }
        CapabilityValidator.validate(cap);
        return cap;
    }

    /**
     * Validate and write a capability to a JSON file.
     * The file is created or overwritten.
     *
     * @throws InvalidCapabilityException if validation fails
     * @throws IOException                if the file can't be written
     */
    public static void save(Capability cap, Path file) throws IOException {
        CapabilityValidator.validate(cap);
        file.getParent().toFile().mkdirs();
        Json.MAPPER.writeValue(file.toFile(), cap);
    }

    private CapabilityJson() {}
}
