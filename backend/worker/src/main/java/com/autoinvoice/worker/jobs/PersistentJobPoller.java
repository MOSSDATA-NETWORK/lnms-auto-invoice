package com.autoinvoice.worker.jobs;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

@Component
@DisallowConcurrentExecution
public class PersistentJobPoller implements Job {
    private final PersistentJobRunner runner;

    public PersistentJobPoller(PersistentJobRunner runner) {
        this.runner = runner;
    }

    @Override
    public void execute(JobExecutionContext context) {
        runner.drain(10);
    }
}
