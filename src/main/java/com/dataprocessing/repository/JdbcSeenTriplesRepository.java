package com.dataprocessing.repository;

import com.dataprocessing.domain.TripleKey;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSeenTriplesRepository implements SeenTriplesRepository {

    private static final String INSERT_IF_ABSENT_SQL = """
            INSERT INTO seen_triples (account_id, source, destination, classification)
            VALUES (:accountId, :source, :destination, :classification)
            ON CONFLICT (account_id, source, destination, classification) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSeenTriplesRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean recordIfAbsent(TripleKey triple) {
        var params = new MapSqlParameterSource()
                .addValue("accountId",     triple.accountId())
                .addValue("source",        triple.source())
                .addValue("destination",   triple.destination())
                .addValue("classification", triple.classification().name());
        return jdbc.update(INSERT_IF_ABSENT_SQL, params) == 1;
    }
}
