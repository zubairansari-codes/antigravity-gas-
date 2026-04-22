package com.gasagency.dsc.service.telephony;

import com.gasagency.dsc.dto.CallResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Twilio telephony provider — uses ElevenLabs' native Twilio outbound-call endpoint.
 * This is the current production provider.
 */
@Slf4j
@Service
public class TwilioTelephonyService implements TelephonyService {

    private final WebClient elevenLabsClient;
    private final String agentPhoneNumberId;

    public TwilioTelephonyService(
            @Value("${elevenlabs.api.key:}") String apiKey,
            @Value("${elevenlabs.api.base-url:https://api.elevenlabs.io/v1}") String baseUrl,
            @Value("${elevenlabs.agent.phone.number.id:}") String agentPhoneNumberId) {
        this.agentPhoneNumberId = agentPhoneNumberId;
        this.elevenLabsClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("xi-api-key", apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public CallResult makeCall(String toNumber, String agentId) {
        if (agentPhoneNumberId == null || agentPhoneNumberId.isBlank()) {
            throw new IllegalStateException("ElevenLabs phone number ID not configured for Twilio provider");
        }

        Map<String, Object> reqBody = Map.of(
                "agent_id", agentId,
                "agent_phone_number_id", agentPhoneNumberId,
                "to_number", toNumber
        );

        String response = elevenLabsClient.post()
                .uri("/convai/twilio/outbound-call")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(reqBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        log.info("Twilio outbound call dispatched to {}: {}", toNumber, response);

        // ElevenLabs returns { success, message, conversation_id, callSid }
        // For now, use the response string as the call ID
        return new CallResult(
                "twilio_" + System.currentTimeMillis(),
                null,
                "QUEUED",
                "TWILIO",
                LocalDateTime.now()
        );
    }

    @Override
    public String getProviderName() {
        return "TWILIO";
    }

    @Override
    public boolean isAvailable() {
        return agentPhoneNumberId != null && !agentPhoneNumberId.isBlank();
    }
}
