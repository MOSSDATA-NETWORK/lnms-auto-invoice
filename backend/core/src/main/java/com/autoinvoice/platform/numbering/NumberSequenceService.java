package com.autoinvoice.platform.numbering;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NumberSequenceService {
    private final JdbcClient jdbc;

    public NumberSequenceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long next(UUID tenantId, String sequenceKey, String periodKey, int padding) {
        if (padding < 1 || padding > 18) {
            throw new IllegalArgumentException("Sequence padding must be between 1 and 18");
        }
        return jdbc.sql("""
                        INSERT INTO number_sequences(id, tenant_id, sequence_key, period_key, next_value, padding)
                        VALUES (:id, :tenantId, :sequenceKey, :periodKey, 2, :padding)
                        ON CONFLICT (tenant_id, sequence_key, period_key)
                        DO UPDATE SET next_value = number_sequences.next_value + 1, updated_at = now()
                        RETURNING next_value - 1
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("sequenceKey", sequenceKey)
                .param("periodKey", periodKey)
                .param("padding", padding)
                .query(Long.class)
                .single();
    }
}
