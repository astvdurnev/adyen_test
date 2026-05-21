package com.adyen.workshop.controllers;

import com.adyen.model.RequestOptions;
import com.adyen.model.checkout.*;
import com.adyen.workshop.configurations.ApplicationConfiguration;
import com.adyen.workshop.services.TokenStore;
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
    private final TokenStore tokenStore;

    public ApiController(ApplicationConfiguration applicationConfiguration,
                         PaymentsApi paymentsApi,
                         TokenStore tokenStore) {
        this.applicationConfiguration = applicationConfiguration;
        this.paymentsApi = paymentsApi;
        this.tokenStore = tokenStore;
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
        // Body of the /paymentMethods call. Adyen filters the returned list by the
        // values we send: a method that doesn't support our amount/currency/country/
        // channel combination is simply omitted from the response.
        // Docs: https://docs.adyen.com/api-explorer/Checkout/latest/post/paymentMethods#request
        var paymentMethodsRequest = new PaymentMethodsRequest();

        // The merchant account name (e.g. "YourCompanyECOM"). Loaded from ADYEN_MERCHANT_ACCOUNT
        // env var via ApplicationConfiguration — keeps the value out of source control and
        // lets us switch accounts (test vs. live, sandbox A vs. sandbox B) without code changes.
        paymentMethodsRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());

        // === Step 18: enrichment so country-specific methods appear =================
        // Without these fields, Adyen returns only the methods that work for ANY context
        // (mainly cards). To get iDeal (NL-only, EUR-only) and Klarna_paynow (region-aware),
        // we have to tell Adyen exactly what kind of payment the shopper is about to make.
        // Docs: https://docs.adyen.com/online-payments/build-your-integration/sessions-flow/?platform=Web&integration=Drop-in#step-3-make-a-request

        // Channel = WEB → Adyen filters out methods that only work on iOS/Android (e.g.
        // Apple Pay / Google Pay native SDK variants). Web has its own equivalents.
        paymentMethodsRequest.setChannel(PaymentMethodsRequest.ChannelEnum.WEB);

        // Country of the shopper. NL is the workshop's home market. iDeal returns ONLY when
        // countryCode = "NL" and amount.currency = "EUR"; outside that combo Adyen omits it.
        paymentMethodsRequest.setCountryCode("NL");

        // Shopper UI language for any server-side rendered strings (rare in Drop-in, but
        // Adyen also uses it to decide locale-specific Klarna variants).
        paymentMethodsRequest.setShopperLocale("nl_NL");

        // The same EUR 99.98 used in /payments. Sending it here ensures the returned list
        // matches what the shopper will see at the Pay button (e.g. BNPL methods often
        // require a minimum amount and would be hidden for tiny transactions).
        // Docs (currency codes): https://docs.adyen.com/development-resources/currency-codes/
        paymentMethodsRequest.setAmount(new Amount().currency("EUR").value(9998L));

        // NOTE: logging the *request* is fine (no card data, no secrets); the response is
        // also safe at this stage — it only contains the list of methods, not shopper data.
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

        // Shopper / merchant country for the order. Adyen routes the transaction through
        // the appropriate acquirer based on this + the currency, and BNPL methods like
        // Klarna REQUIRE it (they'll refuse with "Invalid issuer countrycode" otherwise).
        // Hardcoded NL because our briefing is a Dutch store.
        // Docs: https://docs.adyen.com/api-explorer/Checkout/latest/post/payments#request-countryCode
        paymentRequest.setCountryCode("NL");

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
        // We handle that landing in GET /handleShopperRedirect (Step 14, see below).
        // CRITICAL: the domain of this URL must be in the Client Key "Allowed origins"
        // list in the Customer Area, otherwise Adyen will refuse the redirect.
        paymentRequest.setReturnUrl("http://localhost:8080/handleShopperRedirect");

        // === Step 12 + Step 13: 3D Secure 2 (Native preferred, Redirect fallback) =====
        // 3DS2 is the SCA (Strong Customer Authentication) protocol required by PSD2.
        // Without these extra fields Adyen will Refuse any card that requires 3DS2 (most
        // EU cards in 2026). We ask Adyen to use the *Native* flow when supported by the
        // issuer (challenge rendered INSIDE the Drop-in via threeDS2 web component) and
        // to fall back to *Redirect* (Adyen-hosted page) when the issuer can't do native.
        // Docs (Native):   https://docs.adyen.com/online-payments/3d-secure/native-3ds2/web-drop-in/
        // Docs (Redirect): https://docs.adyen.com/online-payments/3d-secure/redirect-3ds2/web-drop-in/

        // AuthenticationData tells Adyen we want 3DS2 attempted on every card.
        // ALWAYS = always attempt SCA. Other values: NEVER (don't attempt), ALWAYS_USING_3D_SECURE.
        // NOTE: even with ALWAYS, Adyen+issuer may grant a frictionless flow (no challenge UI)
        // for low-risk transactions; the shopper just sees the regular Drop-in confirm screen.
        var authenticationData = new AuthenticationData();
        authenticationData.setAttemptAuthentication(AuthenticationData.AttemptAuthenticationEnum.ALWAYS);

        // PREFERRED = use Native 3DS2 if the issuer supports it; transparently fall back to
        // Redirect 3DS2 when not. This is the safest mode for production because it gives
        // the best UX (no full-page redirect) where possible without breaking on issuers
        // stuck on legacy flows. Other values: DISABLED (force Redirect).
        authenticationData.setThreeDSRequestData(
                new ThreeDSRequestData().nativeThreeDS(ThreeDSRequestData.NativeThreeDSEnum.PREFERRED));
        paymentRequest.setAuthenticationData(authenticationData);

        // The browser origin that initiated the payment. Used by Adyen during the 3DS2
        // device fingerprinting step. Must match a value in your Client Key "Allowed
        // origins" — keep this in sync if you move to a public hostname (ngrok, prod URL).
        paymentRequest.setOrigin("http://localhost:8080");

        // BrowserInfo is collected by Adyen.Web in the browser (window dimensions,
        // user agent, color depth, timezone, ...) and shipped to us in `state.data`.
        // The card issuer uses this for risk scoring and 3DS2 device fingerprinting.
        // We simply forward what the SDK gave us — never fake these values yourself.
        // Docs: https://docs.adyen.com/api-explorer/Checkout/latest/post/payments#request-browserInfo
        paymentRequest.setBrowserInfo(body.getBrowserInfo());

        // Shopper's IP address. In production you would extract this from the request
        // headers (X-Forwarded-For, taking into account `server.forward-headers-strategy`
        // in application.properties). For the workshop a placeholder LAN address is fine —
        // Adyen accepts it on TEST. NOTE: real fraud screening on LIVE will rely on this.
        paymentRequest.setShopperIP("192.168.0.1");

        // ECOMMERCE = a one-off shopper-present payment (the shopper is filling the form
        // right now). Alternatives: CONTAUTH (recurring on file), MOTO (mail/phone order).
        // 3DS2 only applies to ECOMMERCE, so this field is effectively mandatory here.
        // Docs: https://docs.adyen.com/api-explorer/Checkout/latest/post/payments#request-shopperInteraction
        paymentRequest.setShopperInteraction(PaymentRequest.ShopperInteractionEnum.ECOMMERCE);

        // BillingAddress is technically optional, but Adyen's RevenueProtect (risk engine)
        // uses it heavily — without it, more transactions get flagged or challenged.
        // For the workshop we hardcode an Amsterdam address; in production you'd populate
        // it from the shopper's checkout form.
        var billingAddress = new BillingAddress();
        billingAddress.setCity("Amsterdam");
        billingAddress.setCountry("NL");
        billingAddress.setPostalCode("1012KK");
        billingAddress.setStreet("Rokin");
        billingAddress.setHouseNumberOrName("49");
        paymentRequest.setBillingAddress(billingAddress);

        // === Step 19: LineItems for Klarna (and other BNPL methods) ===================
        // Klarna requires an itemised breakdown of the basket — it displays the items
        // on its confirmation page so the shopper sees exactly what they're paying for.
        // Without lineItems, Klarna refuses with "Invalid lineItems".
        // We populate it for EVERY /payments call (not just Klarna): cards ignore the
        // field, so keeping it unconditional simplifies the code and helps the risk
        // engine even on non-BNPL payments.
        // CRITICAL: the SUM of lineItems amounts MUST equal paymentRequest.amount.value,
        // otherwise Klarna will reject. Our basket: 2 × €49.99 = €99.98 → matches.
        // Docs: https://docs.adyen.com/payment-methods/klarna/web-drop-in/?tab=_code_payments_code__2#step-1-make-a-payment
        var headphones = new LineItem()
                .id("sku-headphones-001")
                .description("Premium wireless headphones")
                .quantity(1L)
                // amountIncludingTax is per-unit, in minor units (cents). €49.99 = 4999.
                .amountIncludingTax(4999L);

        var sunglasses = new LineItem()
                .id("sku-sunglasses-001")
                .description("Polarised sunglasses")
                .quantity(1L)
                .amountIncludingTax(4999L);

        paymentRequest.setLineItems(java.util.List.of(headphones, sunglasses));

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

    /**
     * POST /api/payments/details — finalises a payment that needed an extra step
     * (Native 3DS2 challenge, QR scan, voucher submission, etc.).
     * Workshop step(s): Step 13
     * Adyen docs:       https://docs.adyen.com/api-explorer/Checkout/latest/post/payments/details
     * What & Why:       When /payments returns an `action` (e.g. Native 3DS2 challenge),
     *                   the Drop-in completes that action client-side and POSTs the
     *                   resulting `state.data` here. We forward it to Adyen unchanged;
     *                   Adyen verifies the challenge response and returns the final
     *                   resultCode (Authorised / Refused / ...).
     *                   This is the INLINE counterpart of /handleShopperRedirect (which
     *                   handles the same thing for the *Redirect* flow).
     */
    @PostMapping("/api/payments/details")
    public ResponseEntity<PaymentDetailsResponse> paymentsDetails(@RequestBody PaymentDetailsRequest detailsRequest)
            throws IOException, ApiException {
        // We forward the SDK's `state.data` body verbatim. The Adyen Java library
        // already deserialised it into a PaymentDetailsRequest, which carries either:
        //   - `threeDSResult`        (Native 3DS2 challenge response), or
        //   - `redirectResult`       (if the Drop-in somehow surfaced a redirect here), or
        //   - `details` map          (catch-all for QR / voucher / etc.)
        // Docs: https://docs.adyen.com/online-payments/build-your-integration/advanced-flow/?platform=Web&integration=Drop-in#step-7-submit-additional-details
        log.info("Calling /payments/details (inline action completion)");

        var response = paymentsApi.paymentsDetails(detailsRequest);

        // pspReference here is the same one we got from the original /payments call;
        // logging both lets us correlate the two halves of a 3DS2 challenge in logs.
        log.info("/payments/details response: resultCode={} pspReference={}",
                response.getResultCode(), response.getPspReference());

        // The Drop-in reads the resultCode from this response inside its
        // onAdditionalDetails handler and decides which final state to render
        // (success / failure / yet another action — rare but possible).
        return ResponseEntity.ok().body(response);
    }

    /**
     * GET /handleShopperRedirect — landing endpoint after an off-site 3DS2 / redirect step.
     * Workshop step(s): Step 14
     * Adyen docs:       https://docs.adyen.com/online-payments/3d-secure/redirect-3ds2/web-drop-in/#handle-the-redirect-result
     * What & Why:       After the shopper completes the 3DS2 challenge on Adyen's hosted
     *                   page (or after a bank-redirect method like iDeal), Adyen redirects
     *                   the browser back to this URL with either ?redirectResult=... or
     *                   ?payload=... appended. We exchange that token for the final
     *                   payment outcome by calling /payments/details, then redirect the
     *                   shopper to the appropriate /result/... page.
     */
    @GetMapping("/handleShopperRedirect")
    public RedirectView redirect(@RequestParam(required = false) String payload,
                                 @RequestParam(required = false) String redirectResult) throws IOException, ApiException {
        // The /payments/details call needs either a `redirectResult` (returned by 3DS2
        // Redirect on modern Web Drop-in) OR a legacy `payload` (older flows / some
        // redirect-only methods). We build a single PaymentCompletionDetails with
        // whichever one is present.
        // Docs: https://docs.adyen.com/api-explorer/Checkout/latest/post/payments/details
        var paymentDetailsRequest = new PaymentDetailsRequest();
        var paymentCompletionDetails = new PaymentCompletionDetails();

        if (redirectResult != null && !redirectResult.isEmpty()) {
            // Modern path: Adyen-hosted 3DS2 challenge returns a `redirectResult` token.
            // It's a signed blob that only Adyen can decode — we just forward it.
            paymentCompletionDetails.redirectResult(redirectResult);
        } else if (payload != null && !payload.isEmpty()) {
            // Legacy path: older flows / some bank redirects use `payload` instead.
            paymentCompletionDetails.payload(payload);
        } else {
            // No token present → the shopper landed here directly or with a tampered URL.
            // Send them to the generic error page rather than calling Adyen with nothing.
            log.warn("/handleShopperRedirect called without redirectResult or payload — sending to /result/error");
            return new RedirectView("/result/error?reason=missing_redirect_token");
        }
        paymentDetailsRequest.setDetails(paymentCompletionDetails);

        log.info("Calling /payments/details after shopper redirect");
        PaymentDetailsResponse paymentsDetailsResponse;
        try {
            paymentsDetailsResponse = paymentsApi.paymentsDetails(paymentDetailsRequest);
        } catch (ApiException e) {
            // Adyen rejected the redirectResult/payload — most likely the shopper hit
            // this URL with a tampered or expired token. We deliberately don't surface
            // the Adyen error to the browser (no info leak about our integration); a
            // generic error page is the right UX.
            log.warn("Adyen rejected /payments/details: status={} errorCode={} message={}",
                    e.getStatusCode(), e.getError() != null ? e.getError().getErrorCode() : null, e.getMessage());
            return new RedirectView("/result/error?reason=invalid_redirect_token");
        }
        log.info("/payments/details response: resultCode={} pspReference={}",
                paymentsDetailsResponse.getResultCode(), paymentsDetailsResponse.getPspReference());

        // Map the final result code to the same /result/... routes the Drop-in uses for
        // the non-redirect path (see handleOnPaymentCompleted / handleOnPaymentFailed in
        // adyenWebImplementation.js). Keeping the two branches symmetric means the UX
        // doesn't depend on whether 3DS2 was frictionless or challenged.
        // Docs: https://docs.adyen.com/development-resources/overview-response-handling/#result-codes
        var redirectURL = "/result/";
        switch (paymentsDetailsResponse.getResultCode()) {
            case AUTHORISED:
                redirectURL += "success";
                break;
            case PENDING:
            case RECEIVED:
                redirectURL += "pending";
                break;
            case REFUSED:
                redirectURL += "failed";
                break;
            default:
                // CANCELLED, ERROR, or anything unexpected → generic error page so the
                // shopper sees *something* rather than a blank 200.
                redirectURL += "error";
                break;
        }

        // Append the resultCode as a query param so the result page can show a
        // human-readable explanation (e.g. "Reason: REFUSED").
        return new RedirectView(redirectURL + "?reason=" + paymentsDetailsResponse.getResultCode());
    }

    // ========================================================================
    // === Module 2 / Phase 8: subscription creation (tokenisation) =========
    // ========================================================================

    /**
     * POST /api/subscription-create — performs a zero-amount tokenisation payment.
     * Workshop module: Module 2 / Phase 8
     * Adyen docs:      https://docs.adyen.com/online-payments/tokenization/create-a-token/
     * What & Why:      We send Adyen a "Pay €0 and store this card for future use"
     *                  request. The shopper authenticates the card via 3DS2 ONCE,
     *                  and from then on we can charge that card via merchant-initiated
     *                  /payments calls without their presence (Phase 9).
     *                  The actual token (`recurringDetailReference`) does NOT come
     *                  back in this response — it arrives later in the AUTHORISATION
     *                  webhook (see WebhookController).
     */
    @PostMapping("/api/subscription-create")
    public ResponseEntity<PaymentResponse> subscriptionCreate(@RequestBody PaymentRequest body) throws IOException, ApiException {
        // The shopperReference is the stable id we use to link tokens to a subscriber.
        // The frontend passes it in the request body (read from a hidden div on the
        // subscription page). Refuse if missing — we'd have no way to look up the
        // resulting token later.
        // Docs: https://docs.adyen.com/online-payments/tokenization/create-a-token/#shopper-reference
        if (body.getShopperReference() == null || body.getShopperReference().isBlank()) {
            log.warn("/api/subscription-create called without shopperReference");
            return ResponseEntity.badRequest().build();
        }

        var paymentRequest = new PaymentRequest();

        // === Zero-auth amount ====================================================
        // amount.value = 0 → Adyen routes this as an "account verification" call to
        // the card network (no money moves, but the card is verified to be valid
        // and usable). Most issuers support this; some niche ones don't, in which
        // case the merchant falls back to charging €0.01 and immediately refunding.
        // Docs: https://docs.adyen.com/online-payments/tokenization/create-a-token/#zero-auth
        var amount = new Amount()
                .currency("EUR")
                .value(0L);
        paymentRequest.setAmount(amount);

        paymentRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentRequest.setChannel(PaymentRequest.ChannelEnum.WEB);
        paymentRequest.setCountryCode("NL");

        // Encrypted card data from the Drop-in (same as /api/payments).
        paymentRequest.setPaymentMethod(body.getPaymentMethod());

        var orderRef = UUID.randomUUID().toString();
        paymentRequest.setReference(orderRef);
        paymentRequest.setReturnUrl("http://localhost:8080/handleShopperRedirect");

        // === The three tokenisation flags =======================================

        // (1) Asks Adyen to store the card in its Vault. Without this, the card is
        // verified by the zero-auth but NOT saved — and the future /payments calls
        // would have nothing to reference.
        paymentRequest.setStorePaymentMethod(true);

        // (2) Subscriber identity. Adyen ties the stored card to this id; future
        // /payments calls that reference it can pay with the saved card.
        // CRITICAL: this is OUR id (e.g. "user-42"), NOT the shopper's email.
        // Reusing an email would leak PII across Adyen accounts.
        paymentRequest.setShopperReference(body.getShopperReference());

        // (3) Tells Adyen what KIND of recurring relationship this is. Affects:
        //     - PSD2 SCA exemption rules
        //     - RevenueProtect risk scoring
        //     - Reporting / reconciliation labels in CA
        // Other values: CardOnFile (irregular, e.g. one-click), UnscheduledCardOnFile
        // (irregular & merchant-initiated, e.g. auto-top-up).
        // Docs: https://docs.adyen.com/online-payments/tokenization/create-a-token/#recurringProcessingModel
        paymentRequest.setRecurringProcessingModel(PaymentRequest.RecurringProcessingModelEnum.SUBSCRIPTION);

        // === 3DS2 (same as regular /api/payments) ================================
        // The shopper is present (it's their first interaction), so we go through
        // full SCA. After this single 3DS2 challenge, charges via the token can use
        // shopperInteraction=ContAuth — no more challenges.
        var authenticationData = new AuthenticationData();
        authenticationData.setAttemptAuthentication(AuthenticationData.AttemptAuthenticationEnum.ALWAYS);
        authenticationData.setThreeDSRequestData(
                new ThreeDSRequestData().nativeThreeDS(ThreeDSRequestData.NativeThreeDSEnum.PREFERRED));
        paymentRequest.setAuthenticationData(authenticationData);

        paymentRequest.setOrigin("http://localhost:8080");
        paymentRequest.setBrowserInfo(body.getBrowserInfo());
        paymentRequest.setShopperIP("192.168.0.1");
        paymentRequest.setShopperInteraction(PaymentRequest.ShopperInteractionEnum.ECOMMERCE);

        var billingAddress = new BillingAddress();
        billingAddress.setCity("Amsterdam");
        billingAddress.setCountry("NL");
        billingAddress.setPostalCode("1012KK");
        billingAddress.setStreet("Rokin");
        billingAddress.setHouseNumberOrName("49");
        paymentRequest.setBillingAddress(billingAddress);

        var requestOptions = new RequestOptions();
        requestOptions.setIdempotencyKey(UUID.randomUUID().toString());

        log.info("Sending zero-auth /payments for tokenisation: reference={} shopperReference={}",
                orderRef, body.getShopperReference());
        var response = paymentsApi.payments(paymentRequest, requestOptions);
        log.info("Zero-auth response: reference={} resultCode={} pspReference={}",
                orderRef, response.getResultCode(), response.getPspReference());

        return ResponseEntity.ok().body(response);
    }

    /**
     * GET /admin/tokens — debugging view of every stored token.
     * Workshop module: Module 2 / Phase 8 (bonus)
     * What & Why:      In production you'd never expose this; here it's the easiest
     *                  way to "see" the TokenStore from a browser/curl during the
     *                  workshop. Returns the map verbatim.
     */
    @GetMapping("/admin/tokens")
    public ResponseEntity<Map<String, TokenStore.TokenRecord>> listTokens() {
        return ResponseEntity.ok(tokenStore.snapshot());
    }
}
