package com.dataprocessing.repository;

import com.dataprocessing.domain.ServiceRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JDBC DAO for the services table.
 *
 * Design choices:
 * - NamedParameterJdbcTemplate over JdbcTemplate: named params are self-documenting
 *   and immune to positional-argument reordering bugs.
 * - SQL as named constants: centralised, greppable, and easy to test in isolation.
 * - RowMapper as a named static field: reusable, readable, and not buried in a call site.
 */
@Repository
public class JdbcServiceRepository implements ServiceRepository {

    // -------------------------------------------------------------------------
    // SQL
    // -------------------------------------------------------------------------

    private static final String UPSERT_SQL = """
            INSERT INTO services (account_id, service_name, is_public, updated_at)
            VALUES (:accountId, :serviceName, :isPublic, NOW())
            ON CONFLICT (account_id, service_name)
            DO UPDATE SET
                is_public  = EXCLUDED.is_public,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String FIND_ALL_SQL =
            "SELECT account_id, service_name, is_public FROM services";

    // -------------------------------------------------------------------------
    // RowMapper
    // -------------------------------------------------------------------------

    private static final RowMapper<ServiceRecord> SERVICE_ROW_MAPPER = (rs, rowNum) ->
            new ServiceRecord(
                    rs.getString("account_id"),
                    rs.getString("service_name"),
                    rs.getBoolean("is_public")
            );

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcServiceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // ServiceRepository
    // -------------------------------------------------------------------------

    @Override
    public void upsert(String accountId, String serviceName, boolean isPublic) {
        var params = new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("serviceName", serviceName)
                .addValue("isPublic", isPublic);
        jdbc.update(UPSERT_SQL, params);
    }

    @Override
    public List<ServiceRecord> findAll() {
        return jdbc.query(FIND_ALL_SQL, EmptySqlParameterSource.INSTANCE, SERVICE_ROW_MAPPER);
    }
}
