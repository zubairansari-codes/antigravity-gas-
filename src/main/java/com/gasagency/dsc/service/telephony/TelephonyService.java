package com.gasagency.dsc.service.telephony;

import com.gasagency.dsc.dto.CallResult;

/**
 * Abstract telephony interface — all providers (Twilio, Exotel, etc.) implement this.
 * Enables per-agency provider switching via TelephonyProviderFactory.
 */
public interface TelephonyService {

    /**
     * Initiate an outbound call to the given number using the specified ElevenLabs agent.
     *
     * @param toNumber   The recipient phone number (E.164 format)
     * @param agentId    The ElevenLabs Conversational AI agent ID
     * @return CallResult with provider-specific call ID and conversation ID
     */
    CallResult makeCall(String toNumber, String agentId);

    /**
     * @return The provider name (e.g., "TWILIO", "EXOTEL")
     */
    String getProviderName();

    /**
     * @return true if this provider is properly configured and available for use
     */
    boolean isAvailable();
}
