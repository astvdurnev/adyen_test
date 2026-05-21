package com.adyen.workshop.configurations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfiguration {
    @Value("${server.port}")
    private int serverPort;

    @Value("${ADYEN_API_KEY:#{null}}") // Don't edit @Value(...)
    private String adyenApiKey;

    @Value("${ADYEN_MERCHANT_ACCOUNT:#{null}}") // Don't edit @Value(...)
    private String adyenMerchantAccount;

    @Value("${ADYEN_CLIENT_KEY:#{null}}") // Don't edit @Value(...)
    private String adyenClientKey;

    @Value("${ADYEN_HMAC_KEY:#{null}}") // Don't edit @Value(...)
    private String adyenHmacKey; // We'll cover this in step 16.

    /**
     * Stable customer identifier used for shopper-bound features:
     *   - Module 2 / Phase 8 — subscription tokenisation
     *   - Module 3 / Phase 11d — "Save card" on the /preauthorisation page
     * Workshop default: {@code workshop-shopper-001}. Override with the
     * environment variable {@code ADYEN_SHOPPER_REFERENCE} if you want to
     * isolate tokens between flows or multiple workshop runs.
     *
     * IMPORTANT: tokens in TokenStore are keyed by shopperReference, so if
     * you save a card via /preauthorisation while the subscription flow
     * also uses the default value, the second save will overwrite the
     * first locally (Adyen Vault still stores both, but TokenStore only
     * tracks the latest one).
     */
    @Value("${ADYEN_SHOPPER_REFERENCE:workshop-shopper-001}")
    private String adyenShopperReference;

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public String getAdyenApiKey() {
        return adyenApiKey;
    }

    public void setAdyenApiKey(String adyenApiKey) {
        this.adyenApiKey = adyenApiKey;
    }

    public String getAdyenMerchantAccount() {
        return adyenMerchantAccount;
    }

    public void setAdyenMerchantAccount(String adyenMerchantAccount) {
        this.adyenMerchantAccount = adyenMerchantAccount;
    }

    public String getAdyenClientKey() {
        return adyenClientKey;
    }

    public void setAdyenClientKey(String adyenClientKey) {
        this.adyenClientKey = adyenClientKey;
    }

    public String getAdyenHmacKey() {
        return adyenHmacKey;
    }

    public void setAdyenHmacKey(String adyenHmacKey) {
        this.adyenHmacKey = adyenHmacKey;
    }

    public String getAdyenShopperReference() {
        return adyenShopperReference;
    }

    public void setAdyenShopperReference(String adyenShopperReference) {
        this.adyenShopperReference = adyenShopperReference;
    }
}