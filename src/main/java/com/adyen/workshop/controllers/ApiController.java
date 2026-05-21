package com.adyen.workshop.controllers;

import com.adyen.model.RequestOptions;
import com.adyen.model.checkout.*;
import com.adyen.workshop.configurations.ApplicationConfiguration;
import com.adyen.service.checkout.PaymentsApi;
import com.adyen.service.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for using the Adyen payments API.
 */
@RestController
public class ApiController {
    private final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final ApplicationConfiguration applicationConfiguration;
    private final PaymentsApi paymentsApi;

    public ApiController(ApplicationConfiguration applicationConfiguration, PaymentsApi paymentsApi) {
        this.applicationConfiguration = applicationConfiguration;
        this.paymentsApi = paymentsApi;
    }

    // Step 0
    @GetMapping("/hello-world")
    public ResponseEntity<String> helloWorld() throws Exception {
        return ResponseEntity.ok().body("This is the 'Hello World' from the workshop - You've successfully finished step 0!");
    }

    /**
     * POST /api/paymentMethods — proxy endpoint that asks Adyen which payment methods
     * are available for the current merchant account (e.g. card, iDeal, Klarna, ...).
     * Workshop step(s): Step 7
     * Adyen docs:       https://docs.adyen.com/api-explorer/Checkout/latest/post/paymentMethods
     * What & Why:       The browser must never call Adyen directly because that would
     *                   require shipping the API key to the client. Instead the Drop-in
     *                   calls this server endpoint, which forwards the request signed
     *                   with our server-side API key.
     */
    @PostMapping("/api/paymentMethods")
    public ResponseEntity<PaymentMethodsResponse> paymentMethods() throws IOException, ApiException {
        // Body of the /paymentMethods call. For the minimum viable request we only need
        // the merchant account; richer requests can also include amount, country,
        // shopperLocale, channel, etc., which Adyen uses to filter the returned list
        // (e.g. it won't return iDeal for a non-EUR amount).
        // Docs: https://docs.adyen.com/api-explorer/Checkout/latest/post/paymentMethods#request
        var paymentMethodsRequest = new PaymentMethodsRequest();

        // The merchant account name (e.g. "YourCompanyECOM"). Loaded from ADYEN_MERCHANT_ACCOUNT
        // env var via ApplicationConfiguration — keeps the value out of source control and
        // lets us switch accounts (test vs. live, sandbox A vs. sandbox B) without code changes.
        paymentMethodsRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());

        // NOTE: logging the *request* is fine (no card data, no secrets); logging the
        // response is also safe at this stage — it only contains the list of methods,
        // not shopper PAN data. Once we move to /payments we'll be more careful about
        // what we log.
        log.info("Retrieving available Payment Methods from Adyen {}", paymentMethodsRequest);

        // Synchronous HTTPS call to Adyen Checkout API (https://checkout-test.adyen.com).
        // The Adyen Java library handles auth (x-API-key header), serialization,
        // and error decoding into ApiException for us.
        var response = paymentsApi.paymentMethods(paymentMethodsRequest);

        log.info("Payment Methods response from Adyen {}", response);

        // Forward the response as-is to the Drop-in. The shape of `response` matches
        // what AdyenCheckout's `paymentMethodsResponse` config field expects, so we
        // don't need any DTO mapping here.
        return ResponseEntity.ok().body(response);
    }

    // Step 9 - Implement the /payments call to Adyen.
    @PostMapping("/api/payments")
    public ResponseEntity<PaymentResponse> payments(@RequestBody PaymentRequest body) throws IOException, ApiException {

        return ResponseEntity.ok().body(null);
    }

    // Step 13 - Handle details call (triggered after Native 3DS2 flow)
    @PostMapping("/api/payments/details")
    public ResponseEntity<PaymentDetailsResponse> paymentsDetails(@RequestBody PaymentDetailsRequest detailsRequest) throws IOException, ApiException
    {

        return ResponseEntity.ok().body(null);
    }

    // Step 14 - Handle Redirect 3DS2 during payment.
    @GetMapping("/handleShopperRedirect")
    public RedirectView redirect(@RequestParam(required = false) String payload, @RequestParam(required = false) String redirectResult) throws IOException, ApiException {

        return null;
    }
}
