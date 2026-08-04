package com.autoinvoice.worker.jobs;

import com.autoinvoice.platform.jobs.BackgroundJob;
import com.fasterxml.jackson.databind.JsonNode;

public interface JobHandler {
    String type();

    JsonNode handle(BackgroundJob job) throws Exception;
}
