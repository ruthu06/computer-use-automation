package com.computeruse.automation.api;

import com.computeruse.automation.artifact.Capability;
import com.computeruse.automation.artifact.CapabilityJson;
import com.computeruse.automation.artifact.InvalidCapabilityException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** The saved recipes: plain JSON files in one folder. */
@Component
public class CapabilityStore {

    private static final Logger LOG = LoggerFactory.getLogger(CapabilityStore.class);

    private final Path dir;

    @Autowired
    public CapabilityStore(AutomationProperties properties) {
        this(Path.of(properties.artifactsDir()));
    }

    public CapabilityStore(Path dir) {
        this.dir = dir;
    }

    /** Every valid recipe in the folder. A file that is not a legal recipe is skipped, and logged. */
    public List<Capability> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Capability> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    found.add(CapabilityJson.load(file));
                } catch (InvalidCapabilityException | IOException e) {
                    LOG.warn("skipping {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    public Optional<Capability> find(String id) {
        return list().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /** Saves under the recipe's own id, replacing an older version of it. */
    public Path save(Capability recipe) {
        // The id becomes a file name, so it must not be able to point somewhere else.
        if (!recipe.id().matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("capability id may only use letters, digits, '.', '_' and '-'");
        }
        Path file = dir.resolve(recipe.id() + ".json");
        try {
            CapabilityJson.save(recipe, file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
