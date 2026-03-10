package com.dataprocessing.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public record SensorEvent(
        @JsonProperty("date") String eventTimestamp,
        String source,
        String destination,
        Map<String, String> values
) {
    public SensorEvent {
        values = Objects.requireNonNullElse(values, Collections.emptyMap());
    }
}
