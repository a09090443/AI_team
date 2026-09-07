package com.zipe.aiteam.task;

import com.fasterxml.jackson.databind.JsonNode;

/** 單一階段的產出。sessionId 供重做時 --resume 續接。 */
public record StageOutcome(
        String role,
        String sessionId,
        JsonNode result,
        Double costUsd,
        long elapsedSec
) {}
