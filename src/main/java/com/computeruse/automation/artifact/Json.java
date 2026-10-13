package com.computeruse.automation.artifact;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single, shared Jackson mapper for the whole application.
 *
 * Why a dedicated class: ObjectMapper is expensive to create and must be shared.
 * All serialization goes through MAPPER so config stays in one place.
 *
 * Config choices:
 *   INDENT_OUTPUT  — artifacts on disk are human-readable
 *   FAIL_ON_UNKNOWN_PROPERTIES disabled — forward-compatible: ignore new fields
 *   findAndAddModules — picks up the Java-time module automatically
 */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .findAndAddModules()
            .build();

    private Json() {}
}
