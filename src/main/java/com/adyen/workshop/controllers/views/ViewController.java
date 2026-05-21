package com.adyen.workshop.controllers.views;

import com.adyen.workshop.configurations.ApplicationConfiguration;
import com.adyen.workshop.services.TokenStore;
import com.adyen.workshop.services.WorkshopInstanceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Controller
public class ViewController {
    private final Logger log = LoggerFactory.getLogger(ViewController.class);

    private final ApplicationConfiguration applicationConfiguration;
    // TokenStore is consulted at page-render time so we can show either the
    // Drop-in (no token yet) or the "Charge €5.00" UI (token already saved).
    private final TokenStore tokenStore;
    // Exposed to the /preauthorisation page so the live-feed badge can show
    // which merchantReference prefix is being filtered in.
    private final WorkshopInstanceId instanceId;

    public ViewController(ApplicationConfiguration applicationConfiguration,
                          TokenStore tokenStore,
                          WorkshopInstanceId instanceId) {
        this.applicationConfiguration = applicationConfiguration;
        this.tokenStore = tokenStore;
        this.instanceId = instanceId;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/preview")
    public String preview(@RequestParam String type, Model model) {
        model.addAttribute("type", type);
        return "preview";
    }

    @GetMapping("/checkout")
    public String checkout(@RequestParam String type, Model model) {
        model.addAttribute("type", type);
        model.addAttribute("clientKey", this.applicationConfiguration.getAdyenClientKey());
        return "checkout";
    }

    @GetMapping("/result/{type}")
    public String result(@PathVariable String type, Model model) {
        model.addAttribute("type", type);
        return "result";
    }

    @GetMapping("/redirect")
    public String redirect(Model model) {
        model.addAttribute("clientKey", this.applicationConfiguration.getAdyenClientKey());
        return "redirect";
    }

    /**
     * GET /subscription — renders the page where a shopper subscribes
     * to a recurring service (Module 2 / Phase 8).
     *
     * Optional ?shopperReference= query parameter lets the workshop participant
     * test multiple "subscribers" from the same browser. Defaults to a stable
     * value so repeated tests update the same row in the token store instead of
     * filling it with duplicates.
     */
    @GetMapping("/subscription")
    public String subscription(
            @RequestParam(name = "shopperReference", required = false, defaultValue = "workshop-shopper-001")
            String shopperReference,
            Model model) {
        log.info("Rendering /subscription page for shopperReference={}", shopperReference);
        model.addAttribute("clientKey", this.applicationConfiguration.getAdyenClientKey());
        model.addAttribute("shopperReference", shopperReference);

        // Phase 9: if we already have a stored token for this shopper, the page
        // shows the "Charge €5.00" block instead of the Drop-in. We expose the
        // brand (e.g. "visa") so the template can render "Visa **** —" UX.
        TokenStore.TokenRecord existing = tokenStore.get(shopperReference);
        if (existing != null) {
            model.addAttribute("hasToken", true);
            model.addAttribute("tokenBrand", existing.brand());
        } else {
            model.addAttribute("hasToken", false);
        }
        return "subscription";
    }

    /**
     * GET /subscription/admin — operator-facing view of every stored token.
     * Workshop module: Module 2 / Phase 9 (admin tooling)
     * What & Why:      A "control room" page that emulates what a billing-ops
     *                  dashboard would show: every subscriber with a stored card,
     *                  a per-row "Charge €5.00" button, and one big "Emulate
     *                  Scheduled Job" button to charge them all at once.
     *
     *                  SECURITY: in production this would be behind auth and a
     *                  feature flag (it can spend real money). For the workshop
     *                  we keep it open under a path that's "obviously admin".
     */
    @GetMapping("/subscription/admin")
    public String subscriptionAdmin(Model model) {
        // Format Instant -> "21 May 2026 13:55" in the server's local zone so the
        // template doesn't have to deal with ISO-8601 + UTC offsets. ZoneId.systemDefault
        // is fine for a single-developer workshop machine; a real backend would render
        // in the operator's preferred timezone.
        DateTimeFormatter dateFormatter =
                DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.systemDefault());

        Map<String, TokenStore.TokenRecord> snapshot = tokenStore.snapshot();
        List<Map<String, Object>> rows = new ArrayList<>(snapshot.size());

        for (Map.Entry<String, TokenStore.TokenRecord> entry : snapshot.entrySet()) {
            TokenStore.TokenRecord token = entry.getValue();
            // LinkedHashMap → predictable column order (Thymeleaf reads keys in iter order).
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shopperReference", entry.getKey());
            row.put("brand", token.brand() != null ? token.brand() : "—");
            row.put("createdAt", token.createdAt() != null
                    ? dateFormatter.format(token.createdAt()) : "—");
            // Mask the token: show only the last 4 chars. Token isn't a PAN but
            // we still avoid leaving full identifiers in HTML for screen-share hygiene.
            row.put("tokenMasked", maskToken(token.token()));
            rows.add(row);
        }

        log.info("Rendering /subscription/admin: {} subscriber(s)", rows.size());
        model.addAttribute("subscriptions", rows);
        model.addAttribute("amountLabel", "\u20ac5.00"); // Euro sign + cents, server-side
        return "subscription-admin";
    }

    /** Shows only the last 4 chars of a token, prefixed with "…". */
    private static String maskToken(String token) {
        if (token == null) return "—";
        if (token.length() <= 4) return token;
        return "\u2026" + token.substring(token.length() - 4);
    }

    /**
     * GET /preauthorisation — entry page for Module 3.
     * Workshop module: Module 3 / Phase 11a
     * Adyen docs:      https://docs.adyen.com/online-payments/adjust-authorisation/adjust-with-preauth/
     * What & Why:      In Phase 11a this page is intentionally minimal — just
     *                  the Live Webhook Feed block at the bottom — so we can
     *                  validate the SSE infrastructure before building the
     *                  preauth/capture UI on top of it in Phase 11b.
     */
    @GetMapping("/preauthorisation")
    public String preauthorisation(Model model) {
        log.info("Rendering /preauthorisation page");
        model.addAttribute("clientKey", this.applicationConfiguration.getAdyenClientKey());
        model.addAttribute("instancePrefix", this.instanceId.prefix());
        // Module 3 / Phase 11d — exposes the configured shopper id so the
        // "Save card for future payments" checkbox knows which Adyen Vault
        // entry to associate the token with on AUTHORISATION webhook.
        model.addAttribute("shopperReference",
                this.applicationConfiguration.getAdyenShopperReference());
        return "preauthorisation";
    }
}
