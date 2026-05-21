package com.adyen.workshop.services;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PaymentStore — keeps pspReference -> payment record mappings for the
 * pre-authorisation module.
 * Workshop module: Module 3 / Phase 11b
 * Adyen docs:      https://docs.adyen.com/online-payments/adjust-authorisation/adjust-with-preauth/
 * What & Why:      The pre-auth flow is a multi-step finite-state machine:
 *                  AUTHORISED → (optionally ADJUSTED) → CAPTURED / CANCELLED;
 *                  CAPTURED → (optionally) REFUNDED.
 *
 *                  Each transition is asynchronous — we POST to Adyen, get
 *                  "received", then a webhook later confirms or fails the step.
 *                  PaymentStore is the source of truth for "where is this
 *                  payment in the lifecycle?". The /preauthorisation page polls
 *                  it every 3 seconds to refresh the payments table.
 *
 *                  In production this would be a relational table, joined to
 *                  the orders / customers tables. For the workshop a JSON file
 *                  on disk keeps the state human-readable and easy to inspect.
 *
 *                  SECURITY: we don't store any PAN, just the Adyen pspReference
 *                  + brand + last 4 (when known). Treat pspReference as opaque.
 */
@Service
public class PaymentStore {
    private final Logger log = LoggerFactory.getLogger(PaymentStore.class);

    private final Path storeFile = Paths.get("build", "payments.json");

    /** pspReference → PaymentRecord. ConcurrentHashMap so reads (UI polling)
     *  don't block writes (webhook handlers). */
    private final Map<String, PaymentRecord> payments = new ConcurrentHashMap<>();

    /**
     * Jackson configured for Instant + immutable records. We register the
     * JavaTimeModule so Instant serialises to ISO-8601 strings rather than
     * the default UNIX epoch number (much friendlier to read on disk).
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @PostConstruct
    public void loadFromDisk() {
        if (!Files.exists(storeFile)) {
            log.info("PaymentStore: no existing {} — starting with empty store", storeFile);
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(storeFile);
            Map<String, PaymentRecord> loaded = mapper.readValue(
                    bytes, new TypeReference<HashMap<String, PaymentRecord>>() {});
            payments.putAll(loaded);
            log.info("PaymentStore: loaded {} payment(s) from {}", payments.size(), storeFile);
        } catch (IOException e) {
            log.warn("PaymentStore: failed to read {}, starting empty: {}", storeFile, e.getMessage());
        }
    }

    /**
     * Insert a brand-new payment after a successful /payments call.
     * Idempotent: if we already have this pspReference (e.g. webhook beat the
     * API response) we update the metadata rather than overwrite history.
     */
    public synchronized PaymentRecord create(String pspReference,
                                             String merchantReference,
                                             long amountValue,
                                             String currency,
                                             String paymentMethod) {
        if (pspReference == null || pspReference.isBlank()) {
            log.warn("PaymentStore.create called with blank pspReference, ignoring");
            return null;
        }

        PaymentRecord existing = payments.get(pspReference);
        if (existing != null) {
            log.info("PaymentStore: payment {} already exists (status={}), enriching metadata",
                    pspReference, existing.status());
            // Backfill amount/currency/paymentMethod if we previously created
            // the record from a webhook before the API response landed.
            PaymentRecord enriched = new PaymentRecord(
                    pspReference,
                    nz(merchantReference, existing.merchantReference()),
                    amountValue != 0 ? amountValue : existing.amountValue(),
                    nz(currency, existing.amountCurrency()),
                    nz(paymentMethod, existing.paymentMethod()),
                    existing.status(),
                    existing.createdAt(),
                    Instant.now(),
                    existing.history());
            payments.put(pspReference, enriched);
            flush();
            return enriched;
        }

        Instant now = Instant.now();
        PaymentRecord record = new PaymentRecord(
                pspReference,
                merchantReference,
                amountValue,
                currency,
                paymentMethod,
                Status.AUTHORISED,
                now,
                now,
                List.of(new HistoryEntry(now, "CREATED", "API /payments returned Authorised", null)));
        payments.put(pspReference, record);
        flush();
        log.info("PaymentStore: created payment pspReference={} amount={} {} status={}",
                pspReference, amountValue, currency, Status.AUTHORISED);
        return record;
    }

    /**
     * Transition a payment to a new status with an audit entry.
     * Returns the updated record, or null if the pspReference is unknown.
     */
    public synchronized PaymentRecord transition(String pspReference,
                                                 Status newStatus,
                                                 String eventCode,
                                                 String note) {
        PaymentRecord existing = payments.get(pspReference);
        if (existing == null) {
            log.debug("PaymentStore.transition: pspReference={} not in store, skipping", pspReference);
            return null;
        }
        Instant now = Instant.now();
        List<HistoryEntry> history = new ArrayList<>(existing.history());
        history.add(new HistoryEntry(now, eventCode, note, newStatus.name()));

        PaymentRecord updated = new PaymentRecord(
                existing.pspReference(),
                existing.merchantReference(),
                existing.amountValue(),
                existing.amountCurrency(),
                existing.paymentMethod(),
                newStatus,
                existing.createdAt(),
                now,
                history);
        payments.put(pspReference, updated);
        flush();
        log.info("PaymentStore: pspReference={} {} → {} ({})",
                pspReference, existing.status(), newStatus, eventCode);
        return updated;
    }

    public PaymentRecord get(String pspReference) {
        return payments.get(pspReference);
    }

    /**
     * Snapshot for the UI, newest first. We sort on read because the underlying
     * ConcurrentHashMap doesn't preserve insertion order, and the JSON file
     * shouldn't impose UI ordering choices.
     */
    public List<PaymentRecord> snapshot() {
        List<PaymentRecord> list = new ArrayList<>(payments.values());
        list.sort(Comparator.comparing(PaymentRecord::createdAt).reversed());
        return Collections.unmodifiableList(list);
    }

    private void flush() {
        try {
            Files.createDirectories(storeFile.getParent());
            Path tmp = storeFile.resolveSibling("payments.json.tmp");
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payments);
            Files.write(tmp, bytes);
            Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("PaymentStore: failed to persist {}: {}", storeFile, e.getMessage(), e);
        }
    }

    private static String nz(String v, String fallback) {
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    /**
     * Finite-state machine of a pre-authorised payment.
     * Workshop docs:
     *   AUTHORISED       — /payments returned Authorised, money reserved.
     *   CAPTURE_REQUESTED — /captures POSTed, awaiting CAPTURE webhook.
     *   CAPTURED          — CAPTURE webhook success=true.
     *   CAPTURE_FAILED    — CAPTURE_FAILED webhook (issuer / bank rejection).
     *   CANCEL_REQUESTED  — /cancels POSTed, awaiting CANCELLATION webhook.
     *   CANCELLED         — CANCELLATION webhook success=true.
     *   REFUND_REQUESTED  — /refunds POSTed, awaiting REFUND webhook.
     *   REFUNDED          — REFUND webhook success=true.
     *   REFUND_FAILED     — REFUND_FAILED or REFUNDED_REVERSED webhook.
     */
    public enum Status {
        AUTHORISED,
        ADJUSTED,
        CAPTURE_REQUESTED,
        CAPTURED,
        CAPTURE_FAILED,
        CANCEL_REQUESTED,
        CANCELLED,
        REFUND_REQUESTED,
        REFUNDED,
        REFUND_FAILED
    }

    /** One row in the payments table. */
    public record PaymentRecord(
            String pspReference,
            String merchantReference,
            long amountValue,
            String amountCurrency,
            String paymentMethod,
            Status status,
            Instant createdAt,
            Instant updatedAt,
            List<HistoryEntry> history
    ) {}

    /** A single transition in a payment's audit trail. */
    public record HistoryEntry(
            Instant at,
            String eventCode,
            String note,
            String newStatus
    ) {}
}
