package com.autoinvoice.worker.jobs;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.jobs.BackgroundJob;
import com.autoinvoice.platform.jobs.BackgroundJobService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class PersistentJobRunner implements AutoCloseable {
    private final BackgroundJobService jobService;
    private final Map<String, JobHandler> handlers;
    private final String workerId;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService heartbeatExecutor;

    @Autowired
    public PersistentJobRunner(BackgroundJobService jobService, List<JobHandler> handlers,
                               @Value("${auto-invoice.worker.id:${spring.application.name}:local}") String workerId,
                               @Value("${auto-invoice.worker.job-types:}") String enabledJobTypes,
                               @Value("${auto-invoice.worker.lease-duration:2m}") Duration leaseDuration,
                               @Value("${auto-invoice.worker.lease-heartbeat-interval:30s}") Duration heartbeatInterval) {
        this(jobService, handlers, workerId, enabledJobTypes, leaseDuration, heartbeatInterval,
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon(true).name("job-lease-heartbeat-", 0).factory()));
    }

    private PersistentJobRunner(BackgroundJobService jobService, List<JobHandler> handlers, String workerId,
                                String enabledJobTypes, Duration leaseDuration, Duration heartbeatInterval,
                                ScheduledExecutorService heartbeatExecutor) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Worker lease duration must be positive");
        }
        if (heartbeatInterval == null || heartbeatInterval.isZero() || heartbeatInterval.isNegative()
                || heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("Worker heartbeat interval must be positive and shorter than the lease");
        }
        this.jobService = jobService;
        this.handlers = new LinkedHashMap<>();
        Set<String> available = handlers.stream().map(JobHandler::type)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> enabled = enabledJobTypes == null || enabledJobTypes.isBlank()
                ? null
                : java.util.Arrays.stream(enabledJobTypes.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (enabled != null && !available.containsAll(enabled)) {
            Set<String> unknown = new LinkedHashSet<>(enabled);
            unknown.removeAll(available);
            throw new IllegalArgumentException("Unknown worker job types: " + unknown);
        }
        handlers.stream()
                .filter(handler -> enabled == null || enabled.contains(handler.type()))
                .forEach(handler -> this.handlers.put(handler.type(), handler));
        this.workerId = workerId;
        this.leaseDuration = leaseDuration;
        this.heartbeatInterval = heartbeatInterval;
        this.heartbeatExecutor = heartbeatExecutor;
    }

    public int drain(int maximumJobs) {
        int processed = 0;
        String[] supportedTypes = handlers.keySet().toArray(String[]::new);
        while (processed < maximumJobs) {
            var claimed = jobService.claimNext(workerId, leaseDuration, supportedTypes);
            if (claimed.isEmpty()) {
                break;
            }
            run(claimed.get());
            processed++;
        }
        return processed;
    }

    private void run(BackgroundJob job) {
        JobHandler handler = handlers.get(job.type());
        LeaseHeartbeat heartbeat = startHeartbeat(job);
        try {
            JsonNode result = handler.handle(job);
            heartbeat.close();
            heartbeat.assertHealthy();
            jobService.complete(job.id(), workerId, result);
        } catch (Exception exception) {
            heartbeat.close();
            if (heartbeat.failed() || leaseLost(exception)) {
                return;
            }
            String code = exception.getClass().getSimpleName().toUpperCase();
            String message = exception.getMessage() == null ? "Worker handler failed" : exception.getMessage();
            long delaySeconds = Math.min(300, 5L << Math.min(job.attemptCount(), 6));
            try {
                jobService.fail(job.id(), workerId, code, truncate(message, 4000), Duration.ofSeconds(delaySeconds));
            } catch (DomainException leaseException) {
                if (!"JOB_LEASE_LOST".equals(leaseException.code())) {
                    throw leaseException;
                }
            }
        }
    }

    private LeaseHeartbeat startHeartbeat(BackgroundJob job) {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        long intervalMillis = Math.max(1, heartbeatInterval.toMillis());
        ScheduledFuture<?> future = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            if (failure.get() != null) {
                return;
            }
            try {
                jobService.renewLease(job.id(), workerId, leaseDuration);
            } catch (RuntimeException exception) {
                failure.compareAndSet(null, exception);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return new LeaseHeartbeat(future, failure);
    }

    private boolean leaseLost(Exception exception) {
        return exception instanceof DomainException domain && "JOB_LEASE_LOST".equals(domain.code());
    }

    private String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    @Override
    @PreDestroy
    public void close() {
        heartbeatExecutor.shutdownNow();
    }

    private record LeaseHeartbeat(ScheduledFuture<?> future, AtomicReference<RuntimeException> failure)
            implements AutoCloseable {
        @Override
        public void close() {
            future.cancel(false);
        }

        private boolean failed() {
            return failure.get() != null;
        }

        private void assertHealthy() {
            RuntimeException exception = failure.get();
            if (exception != null) {
                throw exception;
            }
        }
    }
}
