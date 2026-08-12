package com.autoinvoice.worker.invoice;

import com.autoinvoice.platform.jobs.BackgroundJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class AutomaticPreviewScheduler {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final BackgroundJobService jobs;

    public AutomaticPreviewScheduler(JdbcClient jdbc, ObjectMapper objectMapper, BackgroundJobService jobs) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.jobs = jobs;
    }

    public int enqueueDueProfiles() {
        List<ProfileSchedule> schedules = jdbc.sql("""
                        SELECT profile.tenant_id, profile.id AS profile_id, profile.timezone,
                               COALESCE(profile.billing_day, 1) AS billing_day,
                               settings.system_user_id
                        FROM invoice_profiles profile
                        JOIN tenant_operational_settings settings ON settings.tenant_id = profile.tenant_id
                        WHERE profile.status = 'ACTIVE' AND profile.auto_generate = true
                          AND profile.billing_cycle = 'MONTHLY'
                          AND settings.auto_generation_enabled = true
                          AND settings.emergency_stop = false
                          AND settings.system_user_id IS NOT NULL
                        ORDER BY profile.tenant_id, profile.id
                        """)
                .query((rs, row) -> new ProfileSchedule(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("profile_id", UUID.class), rs.getString("timezone"),
                        rs.getInt("billing_day"), rs.getObject("system_user_id", UUID.class))).list();
        Instant now = Instant.now();
        int enqueued = 0;
        for (ProfileSchedule schedule : schedules) {
            ZoneId zone = ZoneId.of(schedule.timezone());
            LocalDate localToday = now.atZone(zone).toLocalDate();
            if (localToday.getDayOfMonth() < schedule.billingDay()) {
                continue;
            }
            LocalDate periodEndDate = localToday.withDayOfMonth(1);
            LocalDate periodStartDate = periodEndDate.minusMonths(1);
            OffsetDateTime periodStart = periodStartDate.atStartOfDay(zone).toOffsetDateTime();
            OffsetDateTime periodEnd = periodEndDate.atStartOfDay(zone).toOffsetDateTime();
            ObjectNode payload = objectMapper.createObjectNode()
                    .put("invoice_profile_id", schedule.profileId().toString())
                    .put("period_start", periodStart.toString())
                    .put("period_end", periodEnd.toString())
                    .put("requested_by", schedule.systemUserId().toString())
                    .put("force_usage_sync", true)
                    .put("trigger_type", "SCHEDULED");
            jobs.enqueue(schedule.tenantId(), GenerateInvoicePreviewHandler.TYPE,
                    "preview:" + schedule.profileId() + ":" + periodStart + ":" + periodEnd, payload);
            enqueued++;
        }
        return enqueued;
    }

    private record ProfileSchedule(UUID tenantId, UUID profileId, String timezone,
                                   int billingDay, UUID systemUserId) {
    }
}
