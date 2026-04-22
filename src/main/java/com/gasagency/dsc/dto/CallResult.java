package com.gasagency.dsc.dto;

import java.time.LocalDateTime;

/**
 * Unified result from any telephony provider after initiating a call.
 */
public record CallResult(
        String callId,
        String conversationId,
        String status,
        String providerName,
        LocalDateTime initiatedAt
) {}
