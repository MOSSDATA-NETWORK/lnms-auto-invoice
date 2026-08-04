package com.autoinvoice.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditServiceHashTest {
    private final AuditService service = new AuditService(null, new ObjectMapper());

    @Test
    void canonicalJsonIsStableAcrossMapInsertionOrder() throws Exception {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", Map.of("b", 2, "a", 1));
        first.put("a", 3);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 3);
        second.put("z", Map.of("a", 1, "b", 2));

        assertThat(service.canonicalJson(first)).isEqualTo(service.canonicalJson(second));
    }

    @Test
    void actorRequestAndMetadataAreCoveredByTheHash() {
        AuditService.HashMaterial original = material("Alice", "req-1", "{\"reason\":\"approved\"}");
        String hash = AuditService.computeEventHash(original);

        assertThat(AuditService.computeEventHash(material("Mallory", "req-1", "{\"reason\":\"approved\"}")))
                .isNotEqualTo(hash);
        assertThat(AuditService.computeEventHash(material("Alice", "req-2", "{\"reason\":\"approved\"}")))
                .isNotEqualTo(hash);
        assertThat(AuditService.computeEventHash(material("Alice", "req-1", "{\"reason\":\"changed\"}")))
                .isNotEqualTo(hash);
    }

    private AuditService.HashMaterial material(String actorDisplay, String requestId, String metadata) {
        return new AuditService.HashMaterial(
                "0".repeat(64),
                UUID.fromString("01900000-0000-7000-8000-000000000001"),
                UUID.fromString("01900000-0000-7000-8000-000000000002"),
                "USER",
                UUID.fromString("01900000-0000-7000-8000-000000000003"),
                actorDisplay,
                "invoice.finalized",
                "invoice",
                UUID.fromString("01900000-0000-7000-8000-000000000004"),
                "corr-1",
                requestId,
                "{\"before\":1}",
                "{\"after\":2}",
                metadata,
                "203.0.113.10",
                "test-agent",
                Instant.parse("2026-07-31T00:00:00Z"));
    }
}
