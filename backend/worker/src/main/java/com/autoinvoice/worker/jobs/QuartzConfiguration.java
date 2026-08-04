package com.autoinvoice.worker.jobs;

import com.autoinvoice.worker.invoice.AutomaticPreviewScheduleJob;
import com.autoinvoice.worker.outbox.OutboxPoller;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
public class QuartzConfiguration {
    @Bean
    JobDetail persistentJobPollerDetail() {
        return JobBuilder.newJob(PersistentJobPoller.class)
                .withIdentity("persistent-job-poller")
                .storeDurably()
                .build();
    }

    @Bean
    Trigger persistentJobPollerTrigger(JobDetail persistentJobPollerDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(persistentJobPollerDetail)
                .withIdentity("persistent-job-poller-trigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(2)
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "auto-invoice.scheduler.enabled", havingValue = "true")
    JobDetail automaticPreviewScheduleDetail() {
        return JobBuilder.newJob(AutomaticPreviewScheduleJob.class)
                .withIdentity("automatic-preview-scheduler").storeDurably().build();
    }

    @Bean
    @ConditionalOnProperty(name = "auto-invoice.scheduler.enabled", havingValue = "true")
    Trigger automaticPreviewScheduleTrigger(JobDetail automaticPreviewScheduleDetail) {
        return TriggerBuilder.newTrigger().forJob(automaticPreviewScheduleDetail)
                .withIdentity("automatic-preview-scheduler-trigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInMinutes(5).repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "auto-invoice.outbox.enabled", havingValue = "true")
    JobDetail outboxPollerDetail() {
        return JobBuilder.newJob(OutboxPoller.class)
                .withIdentity("outbox-poller").storeDurably().build();
    }

    @Bean
    @ConditionalOnProperty(name = "auto-invoice.outbox.enabled", havingValue = "true")
    Trigger outboxPollerTrigger(JobDetail outboxPollerDetail) {
        return TriggerBuilder.newTrigger().forJob(outboxPollerDetail)
                .withIdentity("outbox-poller-trigger")
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(2).repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }
}
