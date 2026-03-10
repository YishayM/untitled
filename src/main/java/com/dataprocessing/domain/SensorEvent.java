package com.dataprocessing.domain;

import java.util.Map;

public record SensorEvent(
        String date,
        String source,
        String destination,
        Map<String, String> values
) {}
