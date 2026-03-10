package com.dataprocessing.web;

import java.util.List;

/**
 * vis.js-compatible graph payload for GET /graph.
 *
 * <pre>
 * {
 *   "nodes": [{"id": "users", "label": "users"}, ...],
 *   "edges": [{"from": "users", "to": "payment",
 *              "label": "CREDIT_CARD_NUMBER,SOCIAL_SECURITY_NUMBER"}, ...]
 * }
 * </pre>
 */
public record GraphResponse(List<VisNode> nodes, List<VisEdge> edges) {

    public record VisNode(String id, String label) {}

    public record VisEdge(String from, String to, String label) {}
}
