package com.jobscheduler.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jobscheduler.common.exception.InvalidRequestException;

/** Payload rules shared by one-off and recurring jobs. */
public final class JobPayloads {

    private JobPayloads() {
    }

    /** A missing payload becomes {@code {}}; anything other than a JSON object is rejected. */
    public static JsonNode normalise(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (!payload.isObject()) {
            throw new InvalidRequestException("payload must be a JSON object");
        }
        return payload;
    }
}
