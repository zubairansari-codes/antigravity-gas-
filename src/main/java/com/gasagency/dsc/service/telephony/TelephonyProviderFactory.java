package com.gasagency.dsc.service.telephony;

import com.gasagency.dsc.entity.Agency;
import com.gasagency.dsc.enums.TelephonyProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Factory for selecting the appropriate TelephonyService based on agency configuration.
 * Falls back to Twilio if the requested provider is not available.
 */
@Slf4j
@Service
public class TelephonyProviderFactory {

    private final Map<String, TelephonyService> providers;

    public TelephonyProviderFactory(List<TelephonyService> telephonyServices) {
        this.providers = telephonyServices.stream()
                .collect(Collectors.toMap(
                        TelephonyService::getProviderName,
                        Function.identity()
                ));
        log.info("Registered telephony providers: {}", providers.keySet());
    }

    /**
     * Get the telephony provider configured for the given agency.
     * Falls back to TWILIO if the agency's provider is not available.
     */
    public TelephonyService getProvider(Agency agency) {
        TelephonyProvider providerEnum = agency.getTelephonyProvider();
        String providerName = (providerEnum != null) ? providerEnum.name() : "TWILIO";

        TelephonyService provider = providers.get(providerName);
        if (provider == null || !provider.isAvailable()) {
            log.warn("Provider {} not available for agency {}, falling back to TWILIO",
                    providerName, agency.getId());
            provider = providers.get("TWILIO");
        }

        return provider;
    }

    /**
     * Get a specific provider by name, with TWILIO as fallback.
     */
    public TelephonyService getProvider(String providerName) {
        TelephonyService provider = providers.get(providerName);
        if (provider == null || !provider.isAvailable()) {
            return providers.get("TWILIO");
        }
        return provider;
    }
}
