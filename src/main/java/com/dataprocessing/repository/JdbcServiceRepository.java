package com.dataprocessing.repository;

import com.dataprocessing.domain.ServiceRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcServiceRepository implements ServiceRepository {

    private final JdbcTemplate jdbc;

    public JdbcServiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(String accountId, String serviceName, boolean isPublic) {
        jdbc.update("""
                INSERT INTO services (account_id, service_name, is_public, updated_at)
                VALUES (?, ?, ?, NOW())
                ON CONFLICT (account_id, service_name)
                DO UPDATE SET is_public = EXCLUDED.is_public, updated_at = EXCLUDED.updated_at
                """,
                accountId, serviceName, isPublic);
    }

    @Override
    public List<ServiceRecord> findAll() {
        return jdbc.query(
                "SELECT account_id, service_name, is_public FROM services",
                (rs, rowNum) -> new ServiceRecord(
                        rs.getString("account_id"),
                        rs.getString("service_name"),
                        rs.getBoolean("is_public")
                )
        );
    }
}
