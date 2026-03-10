package com.dataprocessing.repository;

import com.dataprocessing.domain.GraphEdge;
import com.dataprocessing.domain.SensitiveClassification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Queries {@code seen_triples} to build the service graph for one account.
 *
 * <p>Rows are grouped in Java (not SQL) to keep the SQL readable and avoid
 * array-aggregation dialect differences. The result set is bounded by
 * {@code seen_triples} which has a composite PK, so cardinality is
 * (unique source/destination pairs) × (4 classification values) — small in practice.
 */
@Repository
public class JdbcSeenTriplesGraphRepository implements SeenTriplesGraphRepository {

    // -------------------------------------------------------------------------
    // SQL
    // -------------------------------------------------------------------------

    private static final String FIND_EDGES_SQL = """
            SELECT source, destination, classification
            FROM   seen_triples
            WHERE  account_id = :accountId
            ORDER  BY source, destination, classification
            """;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSeenTriplesGraphRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // SeenTriplesGraphRepository
    // -------------------------------------------------------------------------

    @Override
    public List<GraphEdge> findEdgesByAccount(String accountId) {
        var params = new MapSqlParameterSource("accountId", accountId);

        // Accumulate classifications per (source, destination) pair.
        // LinkedHashMap preserves insertion order for deterministic response body.
        Map<String, EnumSet<SensitiveClassification>> byPair = new LinkedHashMap<>();

        jdbc.query(FIND_EDGES_SQL, params, rs -> {
            String key = rs.getString("source") + "\0" + rs.getString("destination");
            SensitiveClassification classification =
                    SensitiveClassification.valueOf(rs.getString("classification"));
            byPair.computeIfAbsent(key, k -> EnumSet.noneOf(SensitiveClassification.class))
                  .add(classification);
        });

        List<GraphEdge> edges = new ArrayList<>(byPair.size());
        for (var entry : byPair.entrySet()) {
            String[] parts = entry.getKey().split("\0", 2);
            edges.add(new GraphEdge(parts[0], parts[1], entry.getValue()));
        }
        return edges;
    }
}
