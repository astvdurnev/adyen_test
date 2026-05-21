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

    /**
     * POST /api/payments — submits a payment authorisation to Adyen for the chosen
     * payment method. Called by the Drop-in's onSubmit handler.
     * Workshop step(s): Step 9 (request shape) + Step 11 (idempotency)
     * Adyen docs:       https://docs.adyen.com/api-explorer/Checkout/latest/post/payments
     * What & Why:       Translates the Drop-in's collected state (card encrypted blob,
     *                   Klarna data, ...) into the full /payments request the Adyen
     *                   Checkout API expects, then forwards the result back to the SDK
     *                   so it can decide whether to show 3DS2, success page, etc.
     */
    @PostMapping("/api/payments")
    public ResponseEntity<PaymentResponse> payments(@RequestBody PaymentRequest body) throws IOException, ApiException {
        // We build a *fresh* PaymentRequest server-side instead of trusting `body` whole.
        // SECURITY: the browser could otherwise pick its own amount / merchantAccount /
        // returnUrl. We only copy the fields that genuinely come from the shopper
        // (the encrypted payment method blob, see further down).
        var paymentRequest = new PaymentRequest();

        // === Amount ===
        // EUR 99.98 — minor units (cents) per Adyen API convention.
        // Demo basket: 2 items × €49.99. In a real store this would come from the
        // server-side cart, NEVER from the browser.
        // Docs: https://docs.adyen.com/development-resources/currency-codes/
        var amount = new Amount()
                .currency("EUR")
                .value(9998L);
        paymentRequest.setAmount(amount);

        // Same env-driven value used in /paymentMethods — keeps a single source of truth.
        paymentRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());

        // Channel tells Adyen the integration surface. WEB → Drop-in/Components in a browser;
        // alternatives are IOS, ANDROID. Affects which payment methods are returned and how
        // 3DS2 challenges are rendered.
        // Docs: https://docs.adyen.com/api-explorer/Checkout/latest/post/payments#request-channel
        paymentRequest.setChannel(PaymentRequest.ChannelEnum.WEB);

        // The only field we *copy* from the browser body: the encrypted payment method
        // (e.g. encryptedCardNumber / encryptedExpiryMonth / encryptedSecurityCode for
        // cards, or `klarna_paynow` selector for Klarna). The Drop-in builds it via
        // Adyen.Web's client-side encryption — we never see the raw PAN.
        // Docs: https://docs.adyen.com/online-payments/build-your-integration/advanced-flow/?platform=Web&integration=Drop-in#step-4-make-a-payment
        paymentRequest.setPaymentMethod(body.getPaymentMethod());

        // Merchant reference is OUR id for the order. It appears in the Customer Area,
        // in webhooks, and in reconciliation files. Must be unique per attempt.
        // For the workshop a random UUID is fine; in production this would be the order id.
        var orderRef = UUID.randomUUID().toString();
        paymentRequest.setReference(orderRef);

        // returnUrl is where Adyen redirects the shopper after an off-site step
        // (3DS2 challenge, bank redirect for iDeal, Klarna confirmation page, ...).
        // We handle that landing in GET /handleShopperRedirect (Phase 4 / Step 14).
        // CRITICAL: the domain of this URL must be in the Client Key "Allowed origins"
        // list in the Customer Area, otherwise Adyen will refuse the redirect.
        paymentRequest.setReturnUrl("http://localhost:8080/handleShopperRedirect");

        // === Step 11: Idempotency key =================================================
        // If the network blips or the shopper double-clicks "Pay", we may end up retrying
        // this exact /payments call. Without an idempotency key, Adyen would happily
        // create a SECOND authorisation and charge the card twice. With the key, Adyen
        // returns the original result for any retry within ~24h.
        // We tie the key to the merchant reference so two LOGICAL orders get distinct
        // keys, but two retries of the SAME order share one.
        // Docs: https://docs.adyen.com/development-resources/api-idempotency/
        var requestOptions = new RequestOptions();
        requestOptions.setIdempotencyKey(UUID.randomUUID().toString());

        // NOTE: we deliberately do NOT log `paymentRequest` in full to avoid leaking the
        // encrypted card blob into log aggregators. We log the reference instead — enough
        // to correlate with the Adyen response on the same line.
        log.info("Sending /payments to Adyen for reference {}", orderRef);

        // The actual HTTPS call. The library performs auth, JSON (de)serialization, and
        // wraps non-2xx HTTP statuses into ApiException with the parsed Adyen error body.
        var response = paymentsApi.payments(paymentRequest, requestOptions);

        log.info("/payments response: reference={} resultCode={} pspReference={}",
                orderRef, response.getResultCode(), response.getPspReference());

        // The full response body (including any `action` such as a 3DS2 challenge) flows
        // back to the Drop-in. The SDK reads `resultCode` + `action` and decides what to
        // render next: success, refused, redirect, native 3DS2 challenge, ...
        // Docs: https://docs.adyen.com/development-resources/overview-response-handling/#result-codes
        return ResponseEntity.ok().body(response);
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
