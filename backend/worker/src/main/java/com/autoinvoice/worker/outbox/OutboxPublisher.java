package com.autoinvoice.worker.outbox;

import com.autoinvoice.notification.NotificationService;
import com.autoinvoice.platform.outbox.OutboxService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class OutboxPublisher {
    private final OutboxService outbox;
    private final NotificationService notifications;
    private final String publisherId;

    public OutboxPublisher(OutboxService outbox, NotificationService notifications,
                           @Value("${auto-invoice.outbox.publisher-id:${spring.application.name}:local}")
                           String publisherId) {
        this.outbox = outbox;
        this.notifications = notifications;
        this.publisherId = publisherId;
    }

    public int drain(int maximumEvents) {
        int processed = 0;
        while (processed < maximumEvents) {
            var claimed = outbox.claimNext(publisherId, Duration.ofMinutes(2));
            if (claimed.isEmpty()) {
                break;
            }
            publish(claimed.get());
            processed++;
        }
        return processed;
    }

    private void publish(OutboxService.OutboxEvent event) {
        try {
            if ("invoice.confirmed".equals(event.eventType())) {
                notifications.queueAutomaticInvoice(event.tenantId(), event.aggregateId());
            }
            outbox.complete(event.id(), publisherId);
        } catch (Exception exception) {
            long delaySeconds = Math.min(300, 5L << Math.min(event.attemptCount(), 6));
            outbox.fail(event.id(), publisherId,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(),
                    Duration.ofSeconds(delaySeconds));
        }
    }
}
