package com.dataprocessing.domain;

import java.util.Set;

/**
 * A directed edge in the service graph: source → destination, with the set of
 * sensitive classifications observed on that flow.
 *
 * <p>Corresponds to one vis.js edge in the GET /graph response.
 */
public record GraphEdge(String source, String destination, Set<SensitiveClassification> classifications) {

    public GraphEdge {
        classifications = Set.copyOf(classifications);
    }
}
