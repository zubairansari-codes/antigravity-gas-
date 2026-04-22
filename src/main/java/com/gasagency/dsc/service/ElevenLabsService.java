package com.gasagency.dsc.service;

import com.gasagency.dsc.dto.AgencySetupRequest;
import com.gasagency.dsc.entity.Agency;
import com.gasagency.dsc.entity.Call;
import com.gasagency.dsc.entity.Campaign;
import com.gasagency.dsc.enums.CallStatus;
import com.gasagency.dsc.repository.CallRepository;
import com.gasagency.dsc.repository.CampaignRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import com.twilio.Twilio;
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ElevenLabsService {

    private final WebClient webClient;
    private final CallRepository callRepository;
    private final CampaignRepository campaignRepository;
    private final ObjectMapper objectMapper;
    private final String twilioAccountSid;
    private final String twilioAuthToken;
    private final String twilioPhoneNumber;
    private final String publicUrl;
    private final String elevenlabsAgentPhoneNumberId;

    public ElevenLabsService(
            @Value("${elevenlabs.api.key:}") String apiKey,
            @Value("${elevenlabs.api.base-url:https://api.elevenlabs.io/v1}") String baseUrl,
            @Value("${twilio.account.sid:}") String twilioAccountSid,
            @Value("${twilio.auth.token:}") String twilioAuthToken,
            @Value("${twilio.phone.number:}") String twilioPhoneNumber,
            @Value("${app.public.url:}") String publicUrl,
            @Value("${elevenlabs.agent.phone.number.id:}") String elevenlabsAgentPhoneNumberId,
            CallRepository callRepository,
            CampaignRepository campaignRepository,
            ObjectMapper objectMapper) {
        this.twilioAccountSid = twilioAccountSid;
        this.twilioAuthToken = twilioAuthToken;
        this.twilioPhoneNumber = twilioPhoneNumber;
        this.publicUrl = publicUrl;
        this.elevenlabsAgentPhoneNumberId = elevenlabsAgentPhoneNumberId;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("xi-api-key", apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.callRepository = callRepository;
        this.campaignRepository = campaignRepository;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (twilioAccountSid != null && !twilioAccountSid.isBlank()) {
            Twilio.init(twilioAccountSid, twilioAuthToken);
            log.info("Twilio SDK initialized natively");
        }
    }

    /**
     * Create a conversational AI agent for an agency on ElevenLabs.
     */
    public String createAgent(Agency agency, AgencySetupRequest setup) {
        if (agency.getElevenLabsAgentId() != null && !agency.getElevenLabsAgentId().isBlank()) {
            log.info("Agency {} already has ElevenLabs agent: {}. Skipping creation.", agency.getId(), agency.getElevenLabsAgentId());
            return agency.getElevenLabsAgentId();
        }
        String systemPrompt = buildAgentPrompt(
                setup.agentName(),
                agency.getName(),
                agency.getCity() != null ? agency.getCity() : "India",
                setup.transferNumber(),
                setup.emergencyNumber() != null ? setup.emergencyNumber() : "101"
        );

        // Build agent sub-config with disable_first_message_interruptions
        Map<String, Object> agentBlock = new java.util.HashMap<>();
        agentBlock.put("prompt", Map.of(
                "prompt", systemPrompt,
                // qwen3-30b-a3b: ElevenLabs native model — ultra-low latency (~100ms TTFB), no external API overhead
                "llm", "qwen3-30b-a3b",
                "temperature", 0.2,
                "tools", List.of(
                        Map.of(
                                "type", "webhook",
                                "name", "submit_dsc",
                                "description", "Call this tool ONLY after the customer verbally confirms their 4-digit DSC code. Pass status as 'dsc_collected' and the exact 4-digit number as dscNumber.",
                                "api_schema", Map.of(
                                        "url", publicUrl + "/webhook/elevenlabs",
                                        "method", "POST",
                                        "request_body_schema", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "status", Map.of("type", "string", "description", "Must be 'dsc_collected'"),
                                                        "dscNumber", Map.of("type", "string", "description", "The EXACT 4-digit DSC code confirmed by the customer")
                                                ),
                                                "required", List.of("status", "dscNumber")
                                        )
                                )
                        ),
                        Map.of(
                                "type", "system",
                                "name", "end_call",
                                "description", "IMMEDIATELY hang up and disconnect the call. Call this tool RIGHT AFTER submit_dsc succeeds. Also call if customer refuses to share DSC or hangs up. After calling submit_dsc, you MUST call end_call — do NOT say anything else after 'नमस्ते!'."
                        )
                )
        ));
        agentBlock.put("first_message", String.format(
                "नमस्ते जी! मैं %s बोल रही हूँ, %s गैस एजेंसी की तरफ से। आपके यहाँ हाल ही में एक सिलेंडर डिलीवर हुआ था — क्या आप डिलीवरी का DSC नंबर बता सकते हैं?",
                setup.agentName(), agency.getName()
        ));
        agentBlock.put("language", "hi");
        agentBlock.put("disable_first_message_interruptions", true);

        var conversationConfig = new java.util.LinkedHashMap<String, Object>();
        conversationConfig.put("agent", agentBlock);
        conversationConfig.put("stt", Map.of(
                // scribe_v2 confirmed valid — better Hindi accuracy, lower latency
                "model", "scribe_v2",
                "language", "hi"
        ));
        conversationConfig.put("tts", Map.of(
                // eleven_flash_v2_5 required for non-English (Hindi) agents
                "model_id", "eleven_flash_v2_5",
                // ✅ Indian women voice — already in the ElevenLabs account
                "voice_id", "3cedsbr7ryRZwBvoUmQZ"
        ));
        conversationConfig.put("turn", Map.of(
                "turn_timeout", 6,
                "turn_eagerness", "normal"
        ));
        conversationConfig.put("vad", Map.of(
                "background_voice_detection", true
        ));
        conversationConfig.put("conversation", Map.of(
                "max_duration_seconds", 300
        ));

        Map<String, Object> agentConfig = Map.of(
                "name", agency.getName() + " - DSC Agent",
                "conversation_config", conversationConfig
        );

        try {
            String response = webClient.post()
                    .uri("/convai/agents/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(agentConfig)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            var root = objectMapper.readTree(response);
            String agentId = root.path("agent_id").asText();
            log.info("ElevenLabs agent created: {}", agentId);
            return agentId;
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.error("Failed to create ElevenLabs agent: {} - Body: {}", e.getMessage(), e.getResponseBodyAsString());
            throw new RuntimeException("ElevenLabs agent creation failed", e);
        } catch (Exception e) {
            log.error("Failed to create ElevenLabs agent: {}", e.getMessage());
            throw new RuntimeException("ElevenLabs agent creation failed", e);
        }
    }

    /**
     * Trigger batch calls for a campaign.
     */
    public void startBatchCalls(Agency agency, Campaign campaign, List<Call> calls) {
        if (agency.getElevenLabsAgentId() == null) {
            log.error("No ElevenLabs agent configured for agency {}", agency.getId());
            throw new IllegalStateException("ElevenLabs agent not configured. Complete setup first.");
        }
        if (elevenlabsAgentPhoneNumberId == null || elevenlabsAgentPhoneNumberId.isBlank()) {
             log.error("No ElevenLabs agent phone number ID configured (elevenlabs.agent.phone.number.id). Native calls blocked.");
             throw new IllegalStateException("Missing native phone routing integration");
        }

        log.info("Batch calls executing natively via ElevenLabs for campaign {}: {} calls", campaign.getId(), calls.size());
        
        calls.forEach(call -> {
            try {
                String toPhone = call.getCustomer().getPhone();
                Map<String, Object> reqBody = Map.of(
                        "agent_id", agency.getElevenLabsAgentId(),
                        "agent_phone_number_id", elevenlabsAgentPhoneNumberId,
                        "to_number", toPhone
                );

                String response = webClient.post()
                        .uri("/convai/twilio/outbound-call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(reqBody)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                
                log.info("Native Twilio Outbound Dispatch Success: {}", response);
                call.setTwilioCallSid("elevenlabs_native_" + System.currentTimeMillis() + "_" + call.getId());
                call.setStatus(CallStatus.QUEUED);
                call.setCalledAt(LocalDateTime.now());
                callRepository.save(call);
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
                log.error("Native ElevenLabs out call failed for {}: Body: {}", call.getCustomer().getPhone(), e.getResponseBodyAsString());
                call.setStatus(CallStatus.FAILED);
                call.setNotes("ElevenLabs API failed: " + e.getResponseBodyAsString());
                callRepository.save(call);
            } catch (Exception e) {
                log.error("Native ElevenLabs out call failed for {}: {}", call.getCustomer().getPhone(), e.getMessage());
                call.setStatus(CallStatus.FAILED);
                call.setNotes("Dispatch failed: " + e.getMessage());
                callRepository.save(call);
            }
        });
    }

    /**
     * Process an incoming webhook with call results from ElevenLabs.
     */
    @Transactional
    public void processWebhook(String callSid, java.util.Map<String, Object> body) {
        log.info("Webhook map received: callSid={}", callSid);
        try {
            log.info("RAW Webhook Payload: {}", objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            log.error("Could not serialize payload", e);
        }

        Call call = null;
        if (callSid != null && !callSid.isBlank()) {
            call = callRepository.findByTwilioCallSid(callSid).orElse(null);
        }
        
        if (call == null) {
            log.warn("Missing/Invalid callSid in webhook. Using fallback for demo environment.");
            call = callRepository.findFirstByStatusInOrderByCalledAtDesc(List.of(CallStatus.QUEUED, CallStatus.IN_PROGRESS)).orElse(null);
        }

        if (call == null) {
            log.error("CRITICAL: No fallback active call found to map ElevenLabs webhook! Data lost.");
            return;
        }

        // ElevenLabs sends the exact JSON matching request_body_schema directly at the root level!
        String status = null;
        if (body.get("status") instanceof String) {
            status = (String) body.get("status");
        }
        
        String dscNumber = null;
        if (body.get("dscNumber") instanceof String) {
            dscNumber = (String) body.get("dscNumber");
        }

        if ("dsc_collected".equals(status) && dscNumber != null && !dscNumber.isBlank()) {
            call.setStatus(CallStatus.DSC_COLLECTED);
            call.setDscNumber(dscNumber);
            log.info("Data extracted successfully directly from tool call: DSC={}", dscNumber);
        } else if ("failed".equals(status)) {
            call.setStatus(CallStatus.FAILED);
        } else if (body.containsKey("parameters")) {
            // Fallback for wrapped format just in case ElevenLabs changes backend routing
            Object paramsObj = body.get("parameters");
            if (paramsObj instanceof java.util.Map) {
                java.util.Map<?, ?> params = (java.util.Map<?, ?>) paramsObj;
                String innerStatus = (String) params.get("status");
                String innerDsc = (String) params.get("dscNumber");
                if ("dsc_collected".equals(innerStatus) && innerDsc != null) {
                    call.setStatus(CallStatus.DSC_COLLECTED);
                    call.setDscNumber(innerDsc);
                    log.info("Data extracted successfully via wrapped params: DSC={}", innerDsc);
                }
            }
        }

        call.setCompletedAt(LocalDateTime.now());
        callRepository.save(call);

        // Update campaign stats
        updateCampaignStats(call.getCampaign());
    }

    private void updateCampaignStats(Campaign campaign) {
        long completed = callRepository.countByCampaignIdAndStatusIn(campaign.getId(),
                List.of(CallStatus.DSC_COLLECTED, CallStatus.TRANSFERRED, CallStatus.NO_ANSWER,
                        CallStatus.VOICEMAIL, CallStatus.FAILED, CallStatus.BUSY));
        long successful = callRepository.countByCampaignIdAndStatus(campaign.getId(), CallStatus.DSC_COLLECTED);
        long failed = callRepository.countByCampaignIdAndStatusIn(campaign.getId(),
                List.of(CallStatus.NO_ANSWER, CallStatus.VOICEMAIL, CallStatus.FAILED, CallStatus.BUSY));
        long transferred = callRepository.countByCampaignIdAndStatus(campaign.getId(), CallStatus.TRANSFERRED);

        campaign.setCompletedCalls((int) completed);
        campaign.setSuccessfulCalls((int) successful);
        campaign.setFailedCalls((int) failed);
        campaign.setTransferredCalls((int) transferred);

        // Check if campaign is complete
        if (completed >= campaign.getTotalCustomers()) {
            campaign.setStatus(com.gasagency.dsc.enums.CampaignStatus.COMPLETED);
            campaign.setCompletedAt(LocalDateTime.now());
        }

        campaignRepository.save(campaign);
    }

    /**
     * Trigger a single test call to the agency's transfer number so the owner can hear the agent.
     */
    public void triggerTestCall(Agency agency) {
        if (agency.getElevenLabsAgentId() == null) {
            throw new IllegalStateException("No ElevenLabs agent configured for agency " + agency.getId());
        }
        if (elevenlabsAgentPhoneNumberId == null || elevenlabsAgentPhoneNumberId.isBlank()) {
            throw new IllegalStateException("ElevenLabs phone number not configured");
        }

        try {
            Map<String, Object> reqBody = Map.of(
                    "agent_id", agency.getElevenLabsAgentId(),
                    "agent_phone_number_id", elevenlabsAgentPhoneNumberId,
                    "to_number", agency.getTransferNumber()
            );

            String response = webClient.post()
                    .uri("/convai/twilio/outbound-call")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(reqBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("Test call initiated for agency {} via native ElevenLabs: {}", agency.getId(), response);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.error("Test call failed for agency {}: {}", agency.getId(), e.getResponseBodyAsString());
            throw new RuntimeException("Test call failed: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("Test call failed for agency {}: {}", agency.getId(), e.getMessage());
            throw new RuntimeException("Failed to trigger test call", e);
        }
    }

    /**
     * Build the system prompt for the DSC collection agent.
     * Instructions in English (low token count), example responses in Devanagari Hindi
     * for native TTS pronunciation via ElevenLabs eleven_flash_v2_5.
     */
    private String buildAgentPrompt(String agentName, String agencyName, String city,
                                     String transferNumber, String emergencyNumber) {
        return """
                # Role
                You are %s, an AI assistant calling from %s Gas Agency in %s.
                Your sole goal is to collect the 4-digit Delivery Status Confirmation (DSC) code from customers for their recent gas cylinder delivery.
                YOU MUST ALWAYS REPLY IN HINDI (Devanagari script). Never reply in English or Romanized Hindi.

                # Tone & Style
                - Polite, respectful, professional. Use जी, ठीक है naturally.
                - Keep responses extremely short (1-2 sentences max).
                - Ask only ONE question at a time.

                # Conversation Flow
                1. You have already greeted the user. Listen to their response.
                2. If they provide a DSC, verify it has exactly 4 digits.
                   - If not 4 digits, say: "जी, DSC कोड सिर्फ 4 नंबर का होता है, एक बार SMS देख लीजिए।"
                3. If 4 digits are provided, confirm by reading back: "ठीक है, [1]-[2]-[3]-[4]... सही है ना जी?"
                4. Once confirmed, IMMEDIATELY invoke the `submit_dsc` tool.
                5. After `submit_dsc` succeeds, say EXACTLY: "धन्यवाद जी! नमस्ते।" and IMMEDIATELY invoke `end_call`.

                🚨 CRITICAL RULES:
                - DO NOT say anything after calling submit_dsc except "धन्यवाद जी! नमस्ते।"
                - Phrases like "मैं नोट कर लेती हूँ" or "ठीक है" after submission are STRICTLY PROHIBITED.
                - Disconnecting the call via `end_call` is your MANDATORY LAST ACTION.

                # Edge Cases
                - Delivery received but DSC lost: Say "ठीक है जी, कोई बात नहीं।" → invoke submit_dsc with status='no_dsc' → invoke end_call.
                - Delivery NOT received: Say "ठीक है जी, मैं नोट कर लेती हूँ। किसी समस्या के लिए हमारे %s नंबर पर संपर्क करें।" → invoke end_call.
                - Gas Leak / Emergency: Say "जी तुरंत घर से बाहर निकलिए! आपातकालीन नंबर %s पर अभी call करें!" → invoke end_call.

                # Guardrails
                - Only discuss the DSC code.
                - If asked about billing, booking, or complaints: "इसके लिए आप हमारे %s नंबर पर बात करें।"
                - NEVER ask for Aadhaar, Bank Details, or OTPs.
                """.formatted(agentName, agencyName, city, transferNumber, emergencyNumber, transferNumber);
    }
}
