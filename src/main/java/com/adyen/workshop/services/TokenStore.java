package com.adyen.workshop.services;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TokenStore — keeps shopperReference -> stored payment token mappings.
 * Workshop step(s): Module 2 / Phase 8
 * Adyen docs:       https://docs.adyen.com/online-payments/tokenization/
 * What & Why:       After a successful zero-auth tokenisation, Adyen sends us a
 *                   `recurringDetailReference` in the AUTHORISATION webhook. We
 *                   store it here so the subscription-payment endpoint can later
 *                   charge that card without the shopper being present.
 *
 *                   In production this would be a relational table with proper
 *                   indexes, encryption at rest, and audit logs. For the workshop
 *                   we use a JSON file on disk (build/tokens.json) so the data
 *                   survives Spring DevTools restarts without us spinning up a DB.
 *
 *                   SECURITY: the token itself is not a card number — it's an
 *                   opaque reference that only works for OUR merchant account.
 *                   Still, treat it like a secret in real deployments.
 */
@Service
public class TokenStore {
    private final Logger log = LoggerFactory.getLogger(TokenStore.class);

    // Disk location. `build/` is already in .gitignore (Gradle output dir), so the
    // file never gets committed and is wiped by `./gradlew clean` — perfect for a
    // workshop sandbox. In production this would be a DB connection string.
    private final Path tokensFile = Paths.get("build", "tokens.json");

    // Atomic backing map. We keep it in memory for fast reads and flush to disk
    // synchronously on every mutation (Phase 8 has very low write volume).
    private final Map<String, TokenRecord> tokens = new ConcurrentHashMap<>();

    // Jackson is already on the classpath via spring-boot-starter-web. Reusing one
    // ObjectMapper instance is the standard pattern (it's thread-safe).
    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /**
     * Loads any tokens persisted from a previous run. Called by Spring right after
     * the bean is constructed (before the controllers are wired up to serve traffic).
     */
    @PostConstruct
    public void loadFromDisk() {
        if (!Files.exists(tokensFile)) {
            log.info("TokenStore: no existing {} — starting with empty store", tokensFile);
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(tokensFile);
            Map<String, TokenRecord> loaded = mapper.readValue(
                    bytes, new TypeReference<HashMap<String, TokenRecord>>() {});
            tokens.putAll(loaded);
            log.info("TokenStore: loaded {} token(s) from {}", tokens.size(), tokensFile);
        } catch (IOException e) {
            // Don't crash startup if the file is corrupt — log loudly and start fresh.
            // The previous file is left on disk for forensic inspection.
            log.warn("TokenStore: failed to read {}, starting empty: {}", tokensFile, e.getMessage());
        }
    }

    /**
     * Persists a new token. Overwrites any previous token for the same shopper —
     * Adyen lets a single shopperReference have multiple stored cards, but for the
     * workshop we keep it 1:1 to keep the mental model simple.
     */
    public synchronized void save(String shopperReference, TokenRecord record) {
        // Defensive validation: empty keys / blank tokens would be useless and could
        // mask bugs in the webhook parser.
        if (shopperReference == null || shopperReference.isBlank()) {
            log.warn("TokenStore.save called with blank shopperReference, ignoring");
            return;
        }
        if (record == null || record.token() == null || record.token().isBlank()) {
            log.warn("TokenStore.save called with blank token for shopper {}, ignoring", shopperReference);
            return;
        }
        tokens.put(shopperReference, record);
        flush();
        log.info("TokenStore: stored token for shopper '{}' (brand={}, model={})",
                shopperReference, record.brand(), record.recurringProcessingModel());
    }

    /** Returns the token for a shopper, or null if none stored. */
    public TokenRecord get(String shopperReference) {
        return tokens.get(shopperReference);
    }

    /** Removes the token for a shopper. Returns true if anything was removed. */
    public synchronized boolean remove(String shopperReference) {
        TokenRecord removed = tokens.remove(shopperReference);
        if (removed != null) {
            flush();
            log.info("TokenStore: removed token for shopper '{}'", shopperReference);
            return true;
        }
        return false;
    }

    /** Read-only snapshot of all stored tokens (used by /admin/tokens). */
    public Map<String, TokenRecord> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(tokens));
    }

    /**
     * Atomic write: serialise the whole map to a temp file, then atomic-move it onto
     * the real path. Prevents a half-written tokens.json if the JVM is killed mid-flush.
     */
    private void flush() {
        try {
            Files.createDirectories(tokensFile.getParent());
            Path tmp = tokensFile.resolveSibling("tokens.json.tmp");
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(tokens);
            Files.write(tmp, bytes);
            Files.move(tmp, tokensFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // We deliberately don't propagate — losing the disk copy is bad, but losing
            // the in-memory copy by throwing here would be worse. Log loud and continue.
            log.error("TokenStore: failed to persist {}: {}", tokensFile, e.getMessage(), e);
        }
    }

    /**
     * Immutable record of one stored payment token.
     * Using `record` (Java 16+) gives us equals/hashCode/toString/getters for free.
     * Jackson handles records out of the box in Spring Boot 3.x.
     */
    public record TokenRecord(
            String token,                       // Adyen recurringDetailReference, e.g. "8316812345678901"
            String brand,                       // Card brand for UI display, e.g. "visa", "mc"
            String recurringProcessingModel,    // "Subscription" / "CardOnFile" / "UnscheduledCardOnFile"
            Instant createdAt                   // When we received the webhook
    ) {}
}
