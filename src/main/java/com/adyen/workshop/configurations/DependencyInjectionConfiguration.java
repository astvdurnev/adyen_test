package com.adyen.workshop.configurations;

import com.adyen.Client;
import com.adyen.Config;
import com.adyen.enums.Environment;
import com.adyen.service.checkout.ModificationsApi;
import com.adyen.service.checkout.PaymentsApi;
import com.adyen.service.checkout.RecurringApi;
import com.adyen.util.HMACValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DependencyInjectionConfiguration — Spring beans for the Adyen SDK.
 * Workshop step(s): Step 4
 * Adyen docs:       https://github.com/Adyen/adyen-java-api-library
 * What & Why:       Builds ONE Adyen Client + PaymentsApi for the whole app and exposes
 *                   them as Spring beans, so controllers can inject them instead of
 *                   re-creating HTTP clients (and re-doing TLS handshakes) per request.
 */
@Configuration
public class DependencyInjectionConfiguration {

    // Holds the values that Spring read from .env / application.properties
    // (ADYEN_API_KEY, ADYEN_CLIENT_KEY, ADYEN_MERCHANT_ACCOUNT, ADYEN_HMAC_KEY).
    // We only need the *API key* here; merchant account and HMAC key are used elsewhere.
    private final ApplicationConfiguration applicationConfiguration;

    public DependencyInjectionConfiguration(ApplicationConfiguration applicationConfiguration) {
        this.applicationConfiguration = applicationConfiguration;
    }

    /**
     * The shared Adyen HTTP client. Every API call (paymentMethods, payments,
     * payments/details, ...) goes through this client.
     * Docs: https://github.com/Adyen/adyen-java-api-library?tab=readme-ov-file#using-the-library
     */
    @Bean
    Client client() {
        // Config is the Adyen SDK's settings holder (API key, environment, HTTP timeouts,
        // proxy, etc.). We only set the bare minimum required for a TEST integration.
        var config = new Config();

        // SECURITY: the API key authenticates *us* against Adyen. It MUST stay server-side
        // (never shipped to the browser). The value is loaded from the ADYEN_API_KEY env
        // var via ApplicationConfiguration, so it isn't hard-coded in source control.
        // Docs: https://docs.adyen.com/development-resources/api-credentials/
        config.setApiKey(applicationConfiguration.getAdyenApiKey());

        // Environment.TEST → requests are sent to checkout-test.adyen.com.
        // CRITICAL: switching to Environment.LIVE also requires a different API key AND
        // a "live URL prefix" — never assume the same code works on LIVE without changes.
        // Docs: https://docs.adyen.com/development-resources/live-endpoints/
        config.setEnvironment(Environment.TEST);

        // Construct the actual client. From now on we never touch raw HTTP — we'll use
        // typed service classes (PaymentsApi, etc.) that take this Client.
        return new Client(config);
    }

    /**
     * Typed wrapper around the Checkout API (/paymentMethods, /payments, /payments/details).
     * This is what controllers will actually call.
     * Docs: https://docs.adyen.com/api-explorer/Checkout/latest/overview
     */
    @Bean
    PaymentsApi paymentsApi() {
        // Re-uses the Client bean above. Spring caches @Bean return values by default,
        // so client() is only executed once — both beans share the same HTTP client.
        return new PaymentsApi(client());
    }

    /**
     * Typed wrapper around the Checkout Recurring API
     * (GET / DELETE /storedPaymentMethods). Used in Module 2 / Phase 10 to delete
     * a stored token from the Adyen Vault when a subscription is cancelled.
     * Adyen docs: https://docs.adyen.com/api-explorer/Checkout/latest/delete/storedPaymentMethods/(recurringId)
     */
    @Bean
    RecurringApi recurringApi() {
        // Shares the same Client bean as PaymentsApi — the SDK is fine with
        // multiple service classes pointing at one HTTP client.
        return new RecurringApi(client());
    }

    /**
     * Typed wrapper around the Checkout Modifications API
     * (POST /payments/{pspRef}/captures, /cancels, /refunds, /amountUpdates).
     * Used in Module 3 from Phase 11b onwards. Adyen docs:
     * https://docs.adyen.com/online-payments/modify-payments
     */
    @Bean
    ModificationsApi modificationsApi() {
        return new ModificationsApi(client());
    }

    /**
     * HMAC validator used by the webhooks endpoint to verify that incoming notifications
     * really come from Adyen (and not someone forging the URL).
     * Used in Step 16; declared here so the bean exists from app start.
     * Docs: https://docs.adyen.com/development-resources/webhooks/verify-hmac-signatures/
     */
    @Bean
    HMACValidator hmacValidator() {
        return new HMACValidator();
    }
}
