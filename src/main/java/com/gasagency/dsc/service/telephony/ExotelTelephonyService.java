package com.gasagency.dsc.service.telephony;

import com.gasagency.dsc.dto.CallResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Exotel telephony provider — uses Exotel's Make-a-Call API + Voicebot Applet (WebSocket).
 *
 * Integration flow:
 *   Exotel Make-a-Call API → ExoPhone → Voicebot Applet → WebSocket → Bridge Server → ElevenLabs Agent
 *
 * Prerequisites:
 *   1. Active Exotel account with Voicebot applet enabled
 *   2. Bridge server deployed (AWS/GCP) with ElevenLabs agent configured
 *   3. ExoPhone call flow configured with Voicebot applet pointing to bridge server
 */
@Slf4j
@Service
public class ExotelTelephonyService implements TelephonyService {

    private final String exotelSid;
    private final String exotelApiKey;
    private final String exotelApiToken;
    private final String exotelPhoneNumber;
    private final String bridgeServerUrl;

    public ExotelTelephonyService(
            @Value("${exotel.sid:}") String exotelSid,
            @Value("${exotel.api.key:}") String exotelApiKey,
            @Value("${exotel.api.token:}") String exotelApiToken,
            @Value("${exotel.phone.number:}") String exotelPhoneNumber,
            @Value("${exotel.bridge.server.url:}") String bridgeServerUrl) {
        this.exotelSid = exotelSid;
        this.exotelApiKey = exotelApiKey;
        this.exotelApiToken = exotelApiToken;
        this.exotelPhoneNumber = exotelPhoneNumber;
        this.bridgeServerUrl = bridgeServerUrl;
    }

    @Override
    public CallResult makeCall(String toNumber, String agentId) {
        // TODO: Implement Exotel Make-a-Call API integration
        // POST https://api.exotel.com/v1/Accounts/{sid}/Calls/connect.json
        // with From={exotelPhoneNumber}, To={toNumber}, Url={bridgeServerUrl}/call?agent_id={agentId}
        throw new UnsupportedOperationException(
                "Exotel integration not yet implemented. Complete KYC and Voicebot Applet setup first.");
    }

    @Override
    public String getProviderName() {
        return "EXOTEL";
    }

    @Override
    public boolean isAvailable() {
        return exotelSid != null && !exotelSid.isBlank()
                && exotelApiKey != null && !exotelApiKey.isBlank()
                && bridgeServerUrl != null && !bridgeServerUrl.isBlank();
    }
}
