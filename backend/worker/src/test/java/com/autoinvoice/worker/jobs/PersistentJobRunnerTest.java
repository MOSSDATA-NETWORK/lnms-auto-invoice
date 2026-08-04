package com.autoinvoice.worker.jobs;

import com.autoinvoice.platform.jobs.BackgroundJob;
import com.autoinvoice.platform.jobs.BackgroundJobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentJobRunnerTest {
    @Test
    void trimsConfiguredTypesAndFailsFastForUnknownHandlers() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        JobHandler render = new StubHandler("RENDER_INVOICE_PDF", Duration.ZERO);

        try (PersistentJobRunner ignored = runner(jobs, List.of(render), "  RENDER_INVOICE_PDF  ")) {
            assertThat(ignored).isNotNull();
        }

        assertThatThrownBy(() -> runner(jobs, List.of(render), "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    void renewsTheLeaseWhileAHandlerIsRunning() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        BackgroundJob job = new BackgroundJob(
                UUID.randomUUID(), UUID.randomUUID(), "RENDER_INVOICE_PDF", "render-1",
                JsonNodeFactory.instance.objectNode(), "LEASED", 1, 10, Instant.now(), Instant.now().plusSeconds(1));
        when(jobs.claimNext(anyString(), any(Duration.class), any(String[].class)))
                .thenReturn(Optional.of(job));

        try (PersistentJobRunner runner = new PersistentJobRunner(jobs,
                List.of(new StubHandler("RENDER_INVOICE_PDF", Duration.ofMillis(80))),
                "worker-1", "", Duration.ofMillis(250), Duration.ofMillis(10))) {
            assertThat(runner.drain(1)).isEqualTo(1);
        }

        verify(jobs, atLeastOnce()).renewLease(job.id(), "worker-1", Duration.ofMillis(250));
        verify(jobs).complete(job.id(), "worker-1", JsonNodeFactory.instance.objectNode());
    }

    @Test
    void rejectsAHeartbeatThatCannotRenewBeforeExpiry() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        assertThatThrownBy(() -> new PersistentJobRunner(jobs, List.of(), "worker-1", "",
                Duration.ofSeconds(30), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter than the lease");
    }

    private PersistentJobRunner runner(BackgroundJobService jobs, List<JobHandler> handlers, String enabled) {
        return new PersistentJobRunner(jobs, handlers, "worker-1", enabled,
                Duration.ofMinutes(2), Duration.ofSeconds(30));
    }

    private record StubHandler(String type, Duration delay) implements JobHandler {
        @Override
        public JsonNode handle(BackgroundJob job) throws InterruptedException {
            if (!delay.isZero()) {
                Thread.sleep(delay);
            }
            return JsonNodeFactory.instance.objectNode();
        }
    }
}
