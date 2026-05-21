package com.adyen.workshop.controllers;

import com.adyen.model.notification.NotificationRequest;
import com.adyen.model.notification.NotificationRequestItem;
import com.adyen.util.HMACValidator;
import com.adyen.workshop.configurations.ApplicationConfiguration;
import com.adyen.workshop.services.PaymentStore;
import com.adyen.workshop.services.TokenStore;
import com.adyen.workshop.services.WebhookEventBus;
import com.adyen.workshop.services.WorkshopInstanceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.SignatureException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * WebhookController — receives asynchronous payment notifications from Adyen.
 * Workshop step(s): Step 16
 * Adyen docs:       https://docs.adyen.com/development-resources/webhooks/
 * What & Why:       Payment authorisations on Adyen are confirmed authoritatively via
 *                   webhooks, not the synchronous /payments response. We listen at
 *                   POST /webhooks (exposed to the internet via ngrok), verify each
 *                   notification with HMAC so we know it really came from Adyen, log
 *                   the event, and ALWAYS reply 202 Accepted to stop Adyen retrying.
 */
@RestController
public class WebhookController {
    private final Logger log = LoggerFactory.getLogger(WebhookController.class);

    // Holds ADYEN_HMAC_KEY (read from env / application.properties). The HMAC key is
    // a separate secret from the API key — generated when you create the webhook in
    // the Customer Area, and used ONLY to verify notification authenticity.
    // SECURITY: never log this value.
    private final ApplicationConfiguration applicationConfiguration;

    // Helper from the Adyen Java library that recomputes the HMAC of an incoming
    // NotificationRequestItem and compares it to the `hmacSignature` field Adyen sent.
    // Docs: https://docs.adyen.com/development-resources/webhooks/verify-hmac-signatures/
    private final HMACValidator hmacValidator;

    // Used to persist `recurringDetailReference` tokens that arrive in AUTHORISATION
    // webhooks for zero-auth tokenisation flows (Module 2 / Phase 8).
    private final TokenStore tokenStore;

    // Module 3 / Phase 11a — fan-out hub that streams every accepted webhook
    // to every open /preauthorisation page via SSE.
    private final WebhookEventBus eventBus;

    // Per-participant prefix used to ignore other people's webhooks on a shared
    // TEST merchant account (token capture + live feed both gate on this).
    private final WorkshopInstanceId instanceId;

    // Module 3 / Phase 11b — payment state machine updated from webhook events.
    private final PaymentStore paymentStore;

    @Autowired
    public WebhookController(ApplicationConfiguration applicationConfiguration,
                             HMACValidator hmacValidator,
                             TokenStore tokenStore,
                             WebhookEventBus eventBus,
                             WorkshopInstanceId instanceId,
                             PaymentStore paymentStore) {
        this.applicationConfiguration = applicationConfiguration;
        this.hmacValidator = hmacValidator;
        this.tokenStore = tokenStore;
        this.eventBus = eventBus;
        this.instanceId = instanceId;
        this.paymentStore = paymentStore;
    }

    /**
     * POST /webhooks — Adyen calls this with a JSON envelope describing a payment event
     * (AUTHORISATION, CAPTURE, REFUND, CHARGEBACK, ...).
     * Workshop step(s): Step 16
     * Adyen docs:       https://docs.adyen.com/development-resources/webhooks/verify-hmac-signatures/
     * What & Why:       Adyen wraps one-or-more NotificationRequestItem inside a
     *                   NotificationRequest. We validate the HMAC on the first item
     *                   and acknowledge with 202 Accepted. If we return anything else
     *                   (or time out > 10s), Adyen WILL retry — with exponential backoff
     *                   for up to ~8 days. So: validate fast, ack fast, process async.
     */
    @PostMapping("/webhooks")
    public ResponseEntity<String> webhooks(@RequestBody String json) {
        // NOTE: we log the RAW JSON at INFO level for the workshop so you can inspect
        // exactly what Adyen sends. In production lower this to DEBUG (or scrub
        // additionalData fields) — the body can contain card BIN, last 4 digits, and
        // shopper email if you opted in to those fields in the Customer Area.
        log.info("Received webhook: {}", json);

        try {
            // The Adyen Java library parses the JSON envelope into typed objects.
            // A single HTTP call CAN contain multiple events (Adyen batches them), but
            // in practice for Standard webhooks it's usually a single item.
            // Docs: https://docs.adyen.com/api-explorer/Webhooks/latest/post/AUTHORISATION
            var notificationRequest = NotificationRequest.fromJson(json);

            // We only look at the first item for the workshop. In production iterate the
            // full list — Adyen will retry the WHOLE batch if you 422 any one of them,
            // so make sure your processing is idempotent (use pspReference as the key).
            Optional<NotificationRequestItem> first = notificationRequest.getNotificationItems().stream().findFirst();
            if (first.isEmpty()) {
                // Malformed/empty payload. 422 tells Adyen "we won't ever accept this" so
                // it stops retrying (vs. 500 which triggers retries).
                log.warn("Empty notification batch from Adyen");
                return ResponseEntity.unprocessableEntity().build();
            }
            NotificationRequestItem item = first.get();

            // === HMAC verification =====================================================
            // SECURITY: the /webhooks endpoint is public on the internet. Without HMAC
            // verification, anyone could POST a fake "AUTHORISATION SUCCESS" notification
            // and trick us into shipping goods unpaid. The HMAC proves the payload was
            // generated by someone who knows our shared secret (ADYEN_HMAC_KEY).
            // Algorithm: HMAC-SHA256 over a concatenated string of specific fields
            // (pspReference, merchantReference, originalReference, merchantAccountCode,
            // value, currency, eventCode, success), compared to item.additionalData.hmacSignature.
            if (!hmacValidator.validateHMAC(item, applicationConfiguration.getAdyenHmacKey())) {
                // Bad signature → reject with 422 so Adyen STOPS retrying (it's not a
                // transient error). 401/403 would also be defensible; we follow the
                // Adyen examples convention here.
                log.warn("HMAC validation FAILED for pspReference={} eventCode={}",
                        item.getPspReference(), item.getEventCode());
                return ResponseEntity.unprocessableEntity().build();
            }

            // From here on the notification is trusted. In a real backend we'd publish
            // it to a queue / write to an "orders" table / fire an internal event.
            // For the workshop a log line is enough — open the server console to see them.
            // Docs (event codes): https://docs.adyen.com/development-resources/webhooks/understand-notifications/
            log.info("Webhook OK: eventCode={} success={} pspReference={} merchantReference={} amount={} {}",
                    item.getEventCode(),
                    item.isSuccess(),
                    item.getPspReference(),
                    item.getMerchantReference(),
                    item.getAmount() != null ? item.getAmount().getValue() : null,
                    item.getAmount() != null ? item.getAmount().getCurrency() : null);

            // Module 2 / Phase 8 — capture tokenisation result.
            // After a successful zero-auth payment with storePaymentMethod=true,
            // Adyen sends back the token in additionalData. We extract it here
            // (only for successful AUTHORISATION events) and persist via TokenStore.
            maybeStoreSubscriptionToken(item);

            // Module 3 / Phase 11b — update the pre-auth state machine.
            // Mirrors webhook eventCode → PaymentStore.Status transition so the
            // /preauthorisation UI table reflects what's actually happened.
            maybeUpdatePaymentState(item);

            // Module 3 / Phase 11a — broadcast to every open dashboard.
            // Done AFTER all server-side state mutations (TokenStore, etc.) so
            // the live feed reflects what the rest of the app already sees.
            eventBus.publish(item);

            // CRITICAL: 202 Accepted with body "[accepted]" is what Adyen expects. The body
            // is informational — only the 2xx status really matters — but matching the
            // documented convention makes debugging in CA "Webhook events" view easier.
            // Docs: https://docs.adyen.com/development-resources/webhooks/best-practices/#acknowledge-the-webhook
            return ResponseEntity.accepted().body("[accepted]");
        } catch (SignatureException e) {
            // Thrown if validateHMAC encounters a structural problem (e.g. the
            // hmacSignature field is missing). Treat as "bad signature".
            log.warn("HMAC SignatureException: {}", e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        } catch (Exception e) {
            // Anything else (JSON parse error, unexpected null, ...) → 500 so Adyen
            // retries. This buys us a chance to fix a bug without losing notifications.
            log.error("Unexpected error processing webhook", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Extract `recurringDetailReference` (the token) from an AUTHORISATION webhook
     * and persist it via TokenStore.
     * Workshop modules:
     *   - Module 2 / Phase 8  — subscription tokenisation (zero-auth)
     *   - Module 3 / Phase 11d — "Save card" checkbox during preauth
     * Adyen docs:      https://docs.adyen.com/online-payments/tokenization/create-tokens/#receive-token
     * What & Why:      Adyen returns the token AFTER the synchronous /payments
     *                  response, via a webhook. additionalData carries:
     *                    - recurring.recurringDetailReference: the token itself
     *                    - recurring.shopperReference:         our shopper id
     *                    - recurringProcessingModel:           Subscription / CardOnFile
     *                    - paymentMethod:                       brand (visa, mc, ...)
     *                  Only successful AUTHORISATION events carry a token; other
     *                  event codes are ignored here.
     */
    private void maybeStoreSubscriptionToken(NotificationRequestItem item) {
        // Filter to relevant events. CAPTURE / REFUND / CANCELLATION webhooks
        // also exist but never carry recurringDetailReference.
        if (!"AUTHORISATION".equals(item.getEventCode()) || !item.isSuccess()) {
            return;
        }

        // Filter to OUR workshop instance — see WorkshopInstanceId comment.
        // Other participants on a shared TEST account would otherwise pollute
        // this developer's TokenStore with their tokens.
        if (!instanceId.isOurs(item.getMerchantReference())) {
            log.debug("Token capture skipped — merchantReference '{}' not for prefix '{}-'",
                    item.getMerchantReference(), instanceId.prefix());
            return;
        }

        Map<String, String> additionalData = item.getAdditionalData();
        if (additionalData == null) {
            return;
        }

        // Adyen uses dotted keys inside the flat additionalData map.
        // Docs: https://docs.adyen.com/development-resources/webhooks/additional-settings/#recurring
        String token = additionalData.get("recurring.recurringDetailReference");
        String shopperRef = additionalData.get("recurring.shopperReference");

        if (token == null || shopperRef == null) {
            // Not a tokenisation event (regular one-off payment, or storePaymentMethod
            // wasn't true on the original /payments call). That's normal — just log
            // at DEBUG level so it doesn't spam the console.
            log.debug("AUTHORISATION webhook without recurring detail (regular payment) pspReference={}",
                    item.getPspReference());
            return;
        }

        // Brand is useful for UI ("Visa ending in 1111") but optional.
        String brand = additionalData.get("paymentMethod");

        // recurringProcessingModel is echoed back by Adyen when present in the
        // original /payments call. We use it to label the TokenRecord so the
        // admin UI can tell Subscription tokens (zero-auth, scheduled charges)
        // apart from CardOnFile tokens (saved during checkout/preauth, used
        // for shopper-initiated re-purchases). Falls back to "Subscription"
        // for backward compatibility with Phase 8 records.
        String model = additionalData.getOrDefault("recurringProcessingModel", "Subscription");

        log.info("Token captured: shopperReference={} brand={} model={} token={}",
                shopperRef, brand, model, token);
        tokenStore.save(shopperRef, new TokenStore.TokenRecord(
                token,
                brand,
                model,
                Instant.now()));
    }

    /**
     * Maps an incoming webhook event onto a PaymentStore status transition.
     * Workshop module: Module 3 / Phase 11b (and extended by 11c).
     * Adyen docs:      https://docs.adyen.com/development-resources/webhooks/understand-notifications/
     * What & Why:      The pre-auth lifecycle is driven by webhooks. The original
     *                  /payments call gave us a pspReference; every subsequent
     *                  /captures, /cancels, /refunds notification carries that
     *                  same pspReference in `originalReference` (and a new psp
     *                  for the modification itself). We look up the original
     *                  one in PaymentStore and transition its status.
     *
     *                  Only processes webhooks tagged with our instance prefix
     *                  (same multi-tenant guard as the SSE bus).
     */
    private void maybeUpdatePaymentState(NotificationRequestItem item) {
        // Same multi-tenant filter as elsewhere — don't mutate state for events
        // belonging to other workshop participants on a shared TEST account.
        if (!instanceId.isOurs(item.getMerchantReference())) {
            return;
        }

        String eventCode = item.getEventCode();
        if (eventCode == null) return;

        // The lookup key is ALWAYS the original payment's pspReference. For
        // AUTHORISATION events the item's own pspReference IS the original;
        // for modifications (CAPTURE / CANCELLATION / REFUND /
        // AUTHORISATION_ADJUSTMENT) Adyen sets the original payment's psp in
        // the TOP-LEVEL `originalReference` field (NOT inside additionalData)
        // and the new psp on the item itself.
        // Docs: https://docs.adyen.com/development-resources/webhooks/understand-notifications/#fields
        //
        // (Earlier we looked this up inside additionalData, which silently
        //  failed for every modification webhook — PaymentStore never
        //  transitioned past *_REQUESTED. The UI stayed stuck on "pending"
        //  until the next polling cycle, when nothing had changed server-side.)
        String originalPsp = item.getOriginalReference();
        // Fallback: some older payloads (or test fixtures) put it in
        // additionalData. Keep tolerating that so we don't regress on
        // hand-crafted webhooks used in smoke tests.
        if ((originalPsp == null || originalPsp.isBlank()) && item.getAdditionalData() != null) {
            originalPsp = item.getAdditionalData().get("originalReference");
        }
        String lookupPsp = (originalPsp != null && !originalPsp.isBlank())
                ? originalPsp
                : item.getPspReference();
        boolean success = item.isSuccess();
        String reason = item.getReason() != null ? item.getReason() : "";

        switch (eventCode) {
            // First-time auth — only useful if we somehow received the webhook
            // BEFORE the API response (rare, but Adyen's docs say to handle it).
            // The /api/preauthorisation handler creates the record on success,
            // so the transition will normally be a no-op.
            case "AUTHORISATION" -> {
                if (success) {
                    paymentStore.transition(lookupPsp,
                            PaymentStore.Status.AUTHORISED,
                            eventCode,
                            "Authorisation confirmed by webhook");
                }
                // Failed AUTHORISATION webhooks for payments that weren't even
                // recorded locally are noise — ignore.
            }

            // Capture finalisation — money actually moved (or didn't).
            // Docs: https://docs.adyen.com/online-payments/capture/#capture-events
            case "CAPTURE" -> paymentStore.transition(lookupPsp,
                    success ? PaymentStore.Status.CAPTURED : PaymentStore.Status.CAPTURE_FAILED,
                    eventCode,
                    success ? "Funds captured" : "Capture refused: " + reason);

            case "CAPTURE_FAILED" -> paymentStore.transition(lookupPsp,
                    PaymentStore.Status.CAPTURE_FAILED,
                    eventCode,
                    "Capture failed: " + reason);

            // Adjust authorisation outcome.
            // Docs: https://docs.adyen.com/online-payments/adjust-authorisation/adjust-with-preauth/#asynchronous-flow
            // The /api/modify-amount handler already optimistically wrote the
            // new amount + ADJUSTED status, so a successful webhook is just a
            // confirmation. On failure we annotate history but keep the
            // optimistic amount (workshop simplification — productionising
            // this would mean tracking pending adjustments separately).
            case "AUTHORISATION_ADJUSTMENT" -> paymentStore.transition(lookupPsp,
                    success ? PaymentStore.Status.ADJUSTED : PaymentStore.Status.AUTHORISED,
                    eventCode,
                    success ? "Adjust confirmed" : "Adjust failed: " + reason);

            // Cancel outcomes.
            // CANCELLATION — Adyen processed our explicit /cancels request.
            // TECHNICAL_CANCEL — Adyen voided the auth on its own (e.g.
            //                    the auth window expired before capture).
            // Docs: https://docs.adyen.com/online-payments/cancel/
            case "CANCELLATION" -> paymentStore.transition(lookupPsp,
                    success ? PaymentStore.Status.CANCELLED : PaymentStore.Status.CANCEL_FAILED,
                    eventCode,
                    success ? "Cancellation confirmed" : "Cancellation failed: " + reason);

            case "TECHNICAL_CANCEL" -> paymentStore.transition(lookupPsp,
                    PaymentStore.Status.CANCELLED,
                    eventCode,
                    "Technical cancel by Adyen: " + reason);

            // Refund outcomes.
            // Docs: https://docs.adyen.com/online-payments/refund/
            // REFUND          — refund went through OR failed (check success).
            // REFUND_FAILED   — explicit failure event.
            // REFUNDED_REVERSED — the issuer reversed a previously successful
            //                     refund. Treat as a refund failure so the UI
            //                     surfaces the regression.
            case "REFUND" -> paymentStore.transition(lookupPsp,
                    success ? PaymentStore.Status.REFUNDED : PaymentStore.Status.REFUND_FAILED,
                    eventCode,
                    success ? "Refund confirmed" : "Refund failed: " + reason);

            case "REFUND_FAILED" -> paymentStore.transition(lookupPsp,
                    PaymentStore.Status.REFUND_FAILED,
                    eventCode,
                    "Refund failed: " + reason);

            case "REFUNDED_REVERSED" -> paymentStore.transition(lookupPsp,
                    PaymentStore.Status.REFUND_FAILED,
                    eventCode,
                    "Refund reversed by issuer: " + reason);

            default -> log.debug("Webhook eventCode '{}' not mapped to PaymentStore", eventCode);
        }
    }
}
