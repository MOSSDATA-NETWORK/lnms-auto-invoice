package com.autoinvoice.worker.outbox;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

@Component
@DisallowConcurrentExecution
public class OutboxPoller implements Job {
    private final OutboxPublisher publisher;

    public OutboxPoller(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void execute(JobExecutionContext context) {
        publisher.drain(20);
    }
}
