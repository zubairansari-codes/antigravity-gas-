package com.gasagency.dsc.service.telephony;

import com.gasagency.dsc.dto.CallResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Exotel telephony provider — uses Exotel's Make-a-Call API v1 + Voicebot Applet (WebSocket).
 *
 * Integration flow:
 *   1. Make-a-Call API triggers outbound call via ExoPhone
 *   2. When answered, Exotel's call flow routes to the Voicebot Applet
 *   3. Voicebot Applet opens WebSocket to Bridge Server
 *   4. Bridge Server maintains bidirectional audio stream to ElevenLabs Agent
 *
 * API Docs: https://developer.exotel.com/api/make-a-call
 */
@Slf4j
@Service
public class ExotelTelephonyService implements TelephonyService {

    private final WebClient exotelClient;
    private final String exotelSid;
    private final String exotelPhoneNumber;
    private final String bridgeServerUrl;
    private final ObjectMapper objectMapper;

    public ExotelTelephonyService(
            @Value("${exotel.sid:}") String exotelSid,
            @Value("${exotel.api.key:}") String exotelApiKey,
            @Value("${exotel.api.token:}") String exotelApiToken,
            @Value("${exotel.phone.number:}") String exotelPhoneNumber,
            @Value("${exotel.bridge.server.url:}") String bridgeServerUrl,
            ObjectMapper objectMapper) {
        this.exotelSid = exotelSid;
        this.exotelPhoneNumber = exotelPhoneNumber;
        this.bridgeServerUrl = bridgeServerUrl;
        this.objectMapper = objectMapper;

        // Build authenticated WebClient for Exotel API
        String credentials = exotelApiKey + ":" + exotelApiToken;
        String basicAuth = Base64.getEncoder().encodeToString(credentials.getBytes());

        this.exotelClient = WebClient.builder()
                .baseUrl("https://api.exotel.com/v1/Accounts/" + exotelSid)
                .defaultHeader("Authorization", "Basic " + basicAuth)
                .build();
    }

    @Override
    public CallResult makeCall(String toNumber, String agentId) {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Exotel not fully configured. Need: exotel.sid, exotel.api.key, exotel.bridge.server.url");
        }

        // Exotel Make-a-Call API uses form-urlencoded POST
        // The Url parameter points to the call flow that includes the Voicebot Applet
        // The Voicebot Applet's WebSocket URL is configured in the Exotel dashboard
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("From", exotelPhoneNumber);
        formData.add("To", toNumber);
        formData.add("CallerId", exotelPhoneNumber);
        // Url points to the ExoML flow with the Voicebot Applet
        // The agent_id is passed as a query param so the bridge server knows which ElevenLabs agent to connect
        formData.add("Url", "https://my.exotel.com/" + exotelSid + "/exoml/start_voice/1230142");
        formData.add("CallType", "trans");  // Transactional call (not promotional)

        try {
            String response = exotelClient.post()
                    .uri("/Calls/connect.json")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(formData))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("Exotel outbound call dispatched to {}: {}", toNumber, response);

            // Parse the response to extract the call SID
            String callSid = parseCallSid(response);

            return new CallResult(
                    "exotel_" + callSid,
                    null,
                    "QUEUED",
                    "EXOTEL",
                    LocalDateTime.now()
            );
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.error("Exotel Make-a-Call failed for {}: {}", toNumber, e.getResponseBodyAsString());
            throw new RuntimeException("Exotel call failed: " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * Parse the Exotel Call SID from the JSON response.
     */
    private String parseCallSid(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode call = root.path("Call");
            if (!call.isMissingNode()) {
                return call.path("Sid").asText("unknown");
            }
            return "unknown_" + System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("Could not parse Exotel response: {}", e.getMessage());
            return "unknown_" + System.currentTimeMillis();
        }
    }

    @Override
    public String getProviderName() {
        return "EXOTEL";
    }

    @Override
    public boolean isAvailable() {
        return exotelSid != null && !exotelSid.isBlank()
                && bridgeServerUrl != null && !bridgeServerUrl.isBlank();
    }
}
