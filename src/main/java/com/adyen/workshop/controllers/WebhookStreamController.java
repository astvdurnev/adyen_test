package com.adyen.workshop.controllers;

import com.adyen.workshop.services.WebhookEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * WebhookStreamController — exposes the live webhook feed.
 * Workshop module: Module 3 / Phase 11a
 * Adyen docs:      https://docs.adyen.com/development-resources/webhooks/
 * What & Why:      Two endpoints back the "Live Webhook Feed" UI block on
 *                  /preauthorisation:
 *
 *                    GET /api/webhooks/stream  → Server-Sent Events (SSE).
 *                      Browser opens it via `new EventSource(...)`. Spring keeps
 *                      the HTTP response open; every webhook we receive is
 *                      pushed as a named "webhook" event with the JSON body.
 *
 *                    GET /api/webhooks/recent → JSON snapshot of the last ~50
 *                      events. Used on page load so the UI immediately shows
 *                      recent history before any new event arrives.
 *
 *                  Both endpoints are read-only and safe to call from the
 *                  browser. We deliberately keep them in a separate controller
 *                  so the ApiController stays focused on the payment endpoints.
 */
@RestController
public class WebhookStreamController {
    private final Logger log = LoggerFactory.getLogger(WebhookStreamController.class);

    private final WebhookEventBus eventBus;

    public WebhookStreamController(WebhookEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * GET /api/webhooks/stream — long-lived SSE connection.
     *
     * `produces = text/event-stream` is what makes Spring switch into SSE mode
     * for this method. Returning the SseEmitter tells Spring "I'll write to it
     * later from another thread; don't close the response yet". The actual
     * writes happen in WebhookEventBus.publish().
     *
     * CRITICAL: this endpoint MUST NOT block on the request thread. Long polls
     * or chunked sends from inside this method would consume a Tomcat worker
     * thread per client; SseEmitter offloads everything to the event bus.
     */
    @GetMapping(value = "/api/webhooks/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        log.debug("New SSE client connecting to /api/webhooks/stream");
        return eventBus.register();
    }

    /**
     * GET /api/webhooks/recent — newest-first list of buffered events.
     * Lightweight JSON endpoint so the page can render history without waiting.
     */
    @GetMapping(value = "/api/webhooks/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<WebhookEventBus.WebhookEvent>> recent() {
        return ResponseEntity.ok(eventBus.recent());
    }
}
