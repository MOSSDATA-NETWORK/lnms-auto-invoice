package com.autoinvoice.worker.invoice;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

@Component
@DisallowConcurrentExecution
public class AutomaticPreviewScheduleJob implements Job {
    private final AutomaticPreviewScheduler scheduler;

    public AutomaticPreviewScheduleJob(AutomaticPreviewScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void execute(JobExecutionContext context) {
        scheduler.enqueueDueProfiles();
    }
}
