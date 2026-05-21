package com.adyen.workshop.services;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * WorkshopInstanceId — derives a per-workshop-participant prefix used to tag
 * every `merchantReference` we send to Adyen.
 * Workshop module: Cross-cutting (used from Module 1 onward; introduced when
 *                  the live webhook feed was added in Module 3).
 * Adyen docs:      https://docs.adyen.com/online-payments/build-your-integration/?platform=Web&integration=Drop-in#step-3-make-a-payment
 *                  (see the `reference` field — merchant-side unique id).
 * What & Why:      Multiple workshop participants often share ONE Adyen TEST
 *                  merchant account. That means our webhook listener receives
 *                  notifications for EVERY participant's payments — not just
 *                  ours. If we surfaced all of them in the live feed, the
 *                  signal-to-noise ratio would be terrible.
 *
 *                  Solution: tag every reference we generate with a stable
 *                  prefix derived from the OS user (`wshop-viktordurnev-…`).
 *                  Then the webhook bus filters incoming events by that prefix
 *                  before fanning them out to the UI. Other participants'
 *                  webhooks are still accepted (HMAC checked, 202 returned —
 *                  Adyen never retries) but invisibly dropped from our side.
 *
 *                  NOTE: this is purely a UX filter for shared test accounts.
 *                  In a real production deployment each merchant has their own
 *                  account and webhook URL, so this layer wouldn't exist.
 *
 *                  Override via the `WORKSHOP_INSTANCE_ID` env var if you want
 *                  a custom prefix (handy for paired demos or CI).
 */
@Service
public class WorkshopInstanceId {
    private final Logger log = LoggerFactory.getLogger(WorkshopInstanceId.class);

    // Adyen merchantReference is 1..80 chars, alphanumeric/dash/underscore.
    // We reserve enough room for our prefix while still leaving space for the
    // UUID suffix (36 chars + 1 dash = 37 chars).
    private static final int MAX_PREFIX_LENGTH = 30;

    private final String overridePrefix;

    /** Computed once at @PostConstruct and cached for the JVM lifetime. */
    private String prefix;

    public WorkshopInstanceId(
            @Value("${WORKSHOP_INSTANCE_ID:#{null}}") String overridePrefix) {
        this.overridePrefix = overridePrefix;
    }

    @PostConstruct
    void resolvePrefix() {
        if (overridePrefix != null && !overridePrefix.isBlank()) {
            // Explicit override wins. Still sanitize to keep it Adyen-compliant.
            this.prefix = sanitize(overridePrefix);
            log.info("WorkshopInstanceId: using explicit prefix '{}' (WORKSHOP_INSTANCE_ID)", prefix);
            return;
        }

        // Derive from the OS user name. Stable across restarts (no random
        // suffix), human-readable in webhook bodies and CA Transactions list.
        // We deliberately avoid the hostname — it can change (e.g. corporate
        // hotspots rename the machine).
        String user = System.getProperty("user.name", "anon");
        this.prefix = "wshop-" + sanitize(user);

        // Defensive cap; "anon" or a sanitized username shouldn't blow this.
        if (this.prefix.length() > MAX_PREFIX_LENGTH) {
            this.prefix = this.prefix.substring(0, MAX_PREFIX_LENGTH);
        }
        log.info("WorkshopInstanceId: derived prefix '{}' from OS user '{}'", prefix, user);
    }

    /** Adyen-safe prefix (lowercase alphanumeric + dash, no leading/trailing dashes). */
    public String prefix() {
        return prefix;
    }

    /**
     * Returns a fresh merchantReference: "<prefix>-<UUID>". Use this everywhere
     * we previously called `UUID.randomUUID().toString()` for `setReference()`.
     */
    public String newReference() {
        return prefix + "-" + UUID.randomUUID();
    }

    /**
     * True if a webhook's merchantReference looks like one we created. Treats a
     * null or blank reference as "not ours" (don't mistakenly attribute system
     * notifications to this workshop instance).
     */
    public boolean isOurs(String merchantReference) {
        if (merchantReference == null || merchantReference.isBlank()) return false;
        return merchantReference.startsWith(prefix + "-")
                // Allow exact match too in case some flow uses the bare prefix.
                || merchantReference.equals(prefix);
    }

    /** Sanitises an arbitrary string to Adyen's allowed reference charset. */
    private static String sanitize(String raw) {
        String s = raw.toLowerCase()
                .replaceAll("[^a-z0-9-]", "-")   // anything else → dash
                .replaceAll("-+", "-")             // collapse runs of dashes
                .replaceAll("^-+|-+$", "");         // trim leading/trailing dashes
        if (s.isBlank()) s = "anon";
        return s;
    }
}
