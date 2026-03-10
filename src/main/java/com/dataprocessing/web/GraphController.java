package com.dataprocessing.web;

import com.dataprocessing.domain.GraphEdge;
import com.dataprocessing.domain.SensitiveClassification;
import com.dataprocessing.repository.SeenTriplesGraphRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Serves the service data-flow graph for a single account.
 *
 * <p>GET /graph returns a vis.js-compatible payload: distinct service nodes and
 * directed edges annotated with the sensitive data classifications observed on each flow.
 *
 * <p>Account scope is enforced by the {@code X-Account-ID} header (validated upstream
 * by {@link AccountIdInterceptor}).
 */
@RestController
public class GraphController {

    private static final Logger log = LoggerFactory.getLogger(GraphController.class);

    private final SeenTriplesGraphRepository graphRepository;

    public GraphController(SeenTriplesGraphRepository graphRepository) {
        this.graphRepository = graphRepository;
    }

    @GetMapping("/graph")
    public ResponseEntity<GraphResponse> getGraph(HttpServletRequest request) {
        String accountId = (String) request.getAttribute("accountId");
        if (accountId == null) {
            log.warn("GET /graph reached controller without accountId attribute");
            return ResponseEntity.badRequest().build();
        }

        List<GraphEdge> edges = graphRepository.findEdgesByAccount(accountId);
        GraphResponse response = buildResponse(edges);
        log.debug("GET /graph account={} nodes={} edges={}",
                accountId, response.nodes().size(), response.edges().size());
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private GraphResponse buildResponse(List<GraphEdge> edges) {
        // Collect unique service names in encounter order for deterministic output.
        Set<String> serviceNames = new LinkedHashSet<>();
        for (GraphEdge edge : edges) {
            serviceNames.add(edge.source());
            serviceNames.add(edge.destination());
        }

        List<GraphResponse.VisNode> nodes = new ArrayList<>(serviceNames.size());
        for (String name : serviceNames) {
            nodes.add(new GraphResponse.VisNode(name, name));
        }

        List<GraphResponse.VisEdge> visEdges = new ArrayList<>(edges.size());
        for (GraphEdge edge : edges) {
            String label = edge.classifications().stream()
                    .map(SensitiveClassification::name)
                    .sorted()
                    .collect(Collectors.joining(","));
            visEdges.add(new GraphResponse.VisEdge(edge.source(), edge.destination(), label));
        }

        return new GraphResponse(nodes, visEdges);
    }
}
