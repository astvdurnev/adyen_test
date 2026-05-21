package com.adyen.workshop.controllers;

import com.adyen.model.notification.NotificationRequest;
import com.adyen.model.notification.NotificationRequestItem;
import com.adyen.util.HMACValidator;
import com.adyen.workshop.configurations.ApplicationConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.SignatureException;
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

    @Autowired
    public WebhookController(ApplicationConfiguration applicationConfiguration, HMACValidator hmacValidator) {
        this.applicationConfiguration = applicationConfiguration;
        this.hmacValidator = hmacValidator;
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
}
