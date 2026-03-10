package com.dataprocessing.repository;

import com.dataprocessing.domain.GraphEdge;

import java.util.List;

/**
 * Read-only view of {@code seen_triples} for graph construction.
 */
public interface SeenTriplesGraphRepository {

    /**
     * Returns all distinct (source, destination) edges observed for the given account,
     * with the set of classifications seen on each flow.
     */
    List<GraphEdge> findEdgesByAccount(String accountId);
}
