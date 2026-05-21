package com.adyen.workshop.services;

import com.adyen.model.notification.NotificationRequestItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WebhookEventBus — fan-out hub for incoming Adyen webhooks.
 * Workshop module: Module 3 / Phase 11a
 * Adyen docs:      https://docs.adyen.com/development-resources/webhooks/
 * What & Why:      The preauthorisation / capture / cancel / refund flows are
 *                  ASYNCHRONOUS: the /payments/.../captures call returns
 *                  "received" almost immediately, and the actual outcome arrives
 *                  later as an AUTHORISATION / CAPTURE / REFUND webhook.
 *                  To make this tangible in the workshop we want to STREAM
 *                  every webhook to all open /preauthorisation tabs in real
 *                  time. This bus is the in-memory broker:
 *                    - When WebhookController accepts and validates a webhook
 *                      it calls publish(item). We turn it into a typed event,
 *                      keep it in a small ring buffer, and fan it out to every
 *                      registered SseEmitter.
 *                    - When a browser opens /api/webhooks/stream we hand it a
 *                      new SseEmitter via register(). The bus prunes the
 *                      emitter automatically on completion/timeout/error.
 *
 *                  SECURITY: only summary data is streamed — no full PAN, no
 *                  HMAC secret. Since the dev backend has /admin/* unprotected
 *                  anyway, the workshop assumes a trusted local environment.
 *
 *                  Not durable. Events live only in memory; an app restart
 *                  clears the buffer. That's fine for a workshop demo.
 */
@Service
public class WebhookEventBus {
    private final Logger log = LoggerFactory.getLogger(WebhookEventBus.class);

    /** Ring buffer max size. Tuned so a fresh tab gets a useful "recent" snapshot
     *  without us holding onto megabytes of webhook history. */
    private static final int BUFFER_SIZE = 50;

    /** Idle SSE connections are closed after this long. Browsers' EventSource
     *  automatically reconnects, so this just prevents lingering sockets if a
     *  laptop is closed without unloading the tab. 10 minutes is plenty. */
    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1_000L;

    /**
     * CopyOnWriteArrayList → safe for concurrent iteration (publishing) while
     * register()/remove() can also mutate the list. Each "client" (browser tab)
     * gets exactly one entry here.
     */
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * Ring buffer of the last BUFFER_SIZE events. Synchronised on `bufferLock`
     * because ArrayDeque is not thread-safe. We could swap for a concurrent
     * structure, but the lock is uncontended in practice (one webhook every
     * few seconds at most).
     */
    private final Deque<WebhookEvent> buffer = new ArrayDeque<>(BUFFER_SIZE);
    private final Object bufferLock = new Object();

    /**
     * Filter that lets us ignore webhooks from other workshop participants on
     * a shared TEST merchant account. Injected so tests can stub it.
     */
    private final WorkshopInstanceId instanceId;

    @Autowired
    public WebhookEventBus(WorkshopInstanceId instanceId) {
        this.instanceId = instanceId;
    }

    /**
     * Translates an Adyen NotificationRequestItem into our streamable shape,
     * stores it in the ring buffer, and pushes to every connected client.
     * Returns the constructed event, or {@code null} if the webhook didn't
     * match our merchantReference prefix and was silently dropped.
     */
    public WebhookEvent publish(NotificationRequestItem item) {
        // === Filter: only OUR webhooks make it to the feed =====================
        // The webhook is already authenticated (HMAC verified before we got here),
        // so this is a UX filter, not a security one. Adyen sends every merchant
        // account notification to every configured listener; on a shared TEST
        // account that means we'd otherwise see all participants' traffic.
        String ref = item.getMerchantReference();
        if (!instanceId.isOurs(ref)) {
            log.debug("WebhookEventBus filtered out webhook with merchantReference='{}' (not '{}-…')",
                    ref, instanceId.prefix());
            return null;
        }

        WebhookEvent event = WebhookEvent.from(item);

        // 1. Store in the ring buffer (newest at the FRONT — addFirst).
        synchronized (bufferLock) {
            buffer.addFirst(event);
            while (buffer.size() > BUFFER_SIZE) {
                buffer.removeLast();
            }
        }

        // 2. Fan out. We iterate a CopyOnWriteArrayList snapshot, so changes
        //    we make to `emitters` (when an emitter fails) are safe.
        for (SseEmitter emitter : emitters) {
            try {
                // The event "name" is a hint for browser-side filtering with
                // EventSource.addEventListener("webhook", ...). We could also
                // omit it and use the generic onmessage handler; we keep the
                // explicit name for clarity in the workshop.
                emitter.send(SseEmitter.event()
                        .name("webhook")
                        .id(event.id())
                        .data(event));
            } catch (IOException e) {
                // The client likely disconnected. Drop the emitter so we don't
                // keep trying. The SseEmitter framework will also invoke the
                // onError/onCompletion callbacks below shortly after.
                log.debug("SSE publish failed, removing emitter: {}", e.getMessage());
                emitters.remove(emitter);
            } catch (IllegalStateException e) {
                // Spring throws this if the emitter is already completed.
                emitters.remove(emitter);
            }
        }

        log.debug("WebhookEventBus published {} ({} clients, {} buffered)",
                event.eventCode(), emitters.size(), buffer.size());
        return event;
    }

    /**
     * Creates a new SseEmitter, registers it, and wires up cleanup callbacks.
     * Callers (the @GetMapping for /api/webhooks/stream) must return this
     * emitter from the controller method — Spring writes the HTTP response
     * lazily and keeps the connection open until the emitter completes.
     */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.add(emitter);

        // Spring invokes these callbacks on the container's thread; they must
        // be non-blocking. We use them only to remove the emitter from our list.
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("SSE emitter completed; {} clients left", emitters.size());
        });
        emitter.onTimeout(() -> {
            emitter.complete();
            emitters.remove(emitter);
            log.debug("SSE emitter timed out; {} clients left", emitters.size());
        });
        emitter.onError(throwable -> {
            emitter.complete();
            emitters.remove(emitter);
            log.debug("SSE emitter errored: {}", throwable.getMessage());
        });

        // Send an initial "hello" so the browser immediately sees the connection
        // is alive (no waiting for the first real webhook). We also send the
        // current instance prefix so the UI can show "Filter: wshop-viktor-..."
        // in its status badge.
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of(
                    "at", Instant.now().toString(),
                    "bufferedEvents", bufferSize(),
                    "instancePrefix", instanceId.prefix())));
        } catch (IOException ignored) {
            // Best-effort; if this initial send fails we'll learn via the
            // onError callback above.
        }

        log.debug("SSE emitter registered; {} clients total", emitters.size());
        return emitter;
    }

    /** Snapshot of the ring buffer, newest first. Used by /api/webhooks/recent
     *  so a freshly opened page shows the recent history before any new event. */
    public List<WebhookEvent> recent() {
        synchronized (bufferLock) {
            return new ArrayList<>(buffer);
        }
    }

    /** Current ring buffer size — used by the "connected" hello event. */
    public int bufferSize() {
        synchronized (bufferLock) {
            return buffer.size();
        }
    }

    /**
     * Streamable shape of a webhook. Keeps just the fields the live feed wants
     * to display + the full additionalData blob for the expandable details panel.
     * Field order is preserved by Jackson (record canonical order), which keeps
     * the JSON readable when devs inspect it in Network tab.
     */
    public record WebhookEvent(
            String id,
            Instant receivedAt,
            String eventCode,
            boolean success,
            String pspReference,
            // Adyen's TOP-LEVEL "originalReference" — populated for modification
            // events (CAPTURE / CANCELLATION / REFUND / AUTHORISATION_ADJUSTMENT)
            // and is the pspReference of the ORIGINAL /payments call. The Live
            // Webhook Feed's per-page filter uses this to recognise modification
            // events for payments authored on /preauthorisation, since the
            // event's own pspReference would be the modification's psp (not in
            // the page's "known pspReferences" set).
            // Docs: https://docs.adyen.com/development-resources/webhooks/understand-notifications/#fields
            String originalReference,
            String merchantReference,
            String paymentMethod,
            Long amountValue,
            String amountCurrency,
            String reason,
            Map<String, String> additionalData
    ) {
        /** Adapter: NotificationRequestItem → WebhookEvent. */
        static WebhookEvent from(NotificationRequestItem item) {
            Long amountValue = (item.getAmount() != null) ? item.getAmount().getValue() : null;
            String amountCurrency = (item.getAmount() != null) ? item.getAmount().getCurrency() : null;
            return new WebhookEvent(
                    // We generate our own id — Adyen's pspReference is also a
                    // good candidate but it isn't unique across multiple events
                    // for the same payment (AUTHORISATION + CAPTURE share it).
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    item.getEventCode(),
                    item.isSuccess(),
                    item.getPspReference(),
                    item.getOriginalReference(),
                    item.getMerchantReference(),
                    item.getPaymentMethod(),
                    amountValue,
                    amountCurrency,
                    item.getReason(),
                    item.getAdditionalData() != null ? item.getAdditionalData() : Map.of()
            );
        }
    }
}
