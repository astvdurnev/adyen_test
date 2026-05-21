/**
 * subscriptionAdmin.js — UI logic for the /subscription/admin operator dashboard.
 * Workshop module: Module 2 / Phase 9 (admin tooling)
 * Adyen docs:      https://docs.adyen.com/online-payments/tokenization/use-token/
 * What & Why:      Two interactions:
 *                    1. Per-row "Charge €5.00" → POST /api/subscription-payment
 *                       with that row's shopperReference. Mirrors the single-shot
 *                       button on /subscription.
 *                    2. "Emulate Scheduled Job" → POST /api/subscriptions-charge-all,
 *                       which charges every stored token sequentially. The
 *                       response is an array we render into the result panel and
 *                       also distribute back to each row's status badge.
 *                  No Adyen.Web SDK is needed here — the shopper isn't present.
 */

// ============================================================================
// === Per-row "Charge €5.00" buttons ========================================
// ============================================================================

/**
 * Sends a single MIT charge for one shopperReference. Updates the matching row's
 * inline status text. Returns the parsed response (or { error } on failure) so
 * the batch path can reuse this function.
 */
async function chargeOne(shopperReference, rowEl) {
    const button = rowEl.querySelector(".row-charge-button");
    const status = rowEl.querySelector(".row-result");
    button.disabled = true;
    button.textContent = "Charging…";
    status.textContent = "";

    try {
        const resp = await fetch("/api/subscription-payment", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ shopperReference }),
        });
        const text = await resp.text();
        let parsed;
        try { parsed = JSON.parse(text); } catch { parsed = { raw: text }; }

        if (!resp.ok) {
            status.textContent = "HTTP " + resp.status;
            status.className = "row-result row-result-error";
            button.textContent = "Charge €5.00";
            button.disabled = false;
            return { shopperReference, error: "HTTP " + resp.status, ...parsed };
        }

        // Pretty-print the result code next to the button.
        const rc = parsed.resultCode || "?";
        status.textContent = rc + " (" + (parsed.pspReference || "no-psp") + ")";
        status.className = rc === "Authorised" ? "row-result row-result-ok" : "row-result row-result-error";
        button.textContent = "Charge €5.00";
        button.disabled = false;
        return { shopperReference, ...parsed };
    } catch (e) {
        status.textContent = "Error: " + e.message;
        status.className = "row-result row-result-error";
        button.textContent = "Charge €5.00";
        button.disabled = false;
        return { shopperReference, error: e.message };
    }
}

function wirePerRowButtons() {
    // One delegated handler per row keeps the code simple and survives any future
    // dynamic re-renders (e.g. if we add a "refresh" button later).
    document.querySelectorAll("tr[data-shopper-reference]").forEach((row) => {
        const shopperReference = row.getAttribute("data-shopper-reference");
        const button = row.querySelector(".row-charge-button");
        if (!button) return;
        button.addEventListener("click", () => chargeOne(shopperReference, row));
    });
}

// ============================================================================
// === "Emulate Scheduled Job" — batch charge ================================
// ============================================================================

/**
 * Hits the batch endpoint, then updates each row's status from the response and
 * dumps the full payload into the result panel so the workshop participant can
 * inspect Adyen's actual replies.
 */
async function wireBatchButton() {
    const batchButton = document.getElementById("emulate-job-button");
    const resultPre = document.getElementById("batch-result");
    if (!batchButton || !resultPre) return;

    batchButton.addEventListener("click", async () => {
        batchButton.disabled = true;
        batchButton.textContent = "Running scheduled job…";
        resultPre.textContent = "";

        // Pre-clear every row's status so the user sees the new run isn't
        // contaminated by the previous one.
        document.querySelectorAll(".row-result").forEach((el) => {
            el.textContent = "";
            el.className = "row-result";
        });
        document.querySelectorAll(".row-charge-button").forEach((b) => {
            b.disabled = true;
            b.textContent = "Pending…";
        });

        try {
            const resp = await fetch("/api/subscriptions-charge-all", { method: "POST" });
            const text = await resp.text();
            let payload;
            try { payload = JSON.parse(text); } catch { payload = text; }

            if (!resp.ok) {
                resultPre.textContent = "HTTP " + resp.status + "\n" + JSON.stringify(payload, null, 2);
                batchButton.textContent = "Emulate Scheduled Job (charge all)";
                batchButton.disabled = false;
                return;
            }

            // Distribute results back to rows by shopperReference.
            if (Array.isArray(payload)) {
                payload.forEach((row) => {
                    const tr = document.querySelector(
                        'tr[data-shopper-reference="' + cssEscape(row.shopperReference) + '"]'
                    );
                    if (!tr) return;
                    const status = tr.querySelector(".row-result");
                    const button = tr.querySelector(".row-charge-button");

                    if (row.error) {
                        status.textContent = "Error: " + row.error;
                        status.className = "row-result row-result-error";
                    } else {
                        status.textContent = (row.resultCode || "?") +
                            " (" + (row.pspReference || "no-psp") + ")";
                        status.className =
                            row.resultCode === "Authorised"
                                ? "row-result row-result-ok"
                                : "row-result row-result-error";
                    }
                    if (button) {
                        button.disabled = false;
                        button.textContent = "Charge €5.00";
                    }
                });
            }

            resultPre.textContent = JSON.stringify(payload, null, 2);
            batchButton.textContent = "Emulate Scheduled Job (charge all)";
            batchButton.disabled = false;
        } catch (e) {
            console.error("Batch charge failed", e);
            resultPre.textContent = "Network error: " + e.message;
            batchButton.textContent = "Emulate Scheduled Job (charge all)";
            batchButton.disabled = false;
        }
    });
}

/**
 * Tiny helper to escape arbitrary strings inside an attribute selector.
 * Modern browsers ship CSS.escape but we polyfill for safety.
 * MDN: https://developer.mozilla.org/en-US/docs/Web/API/CSS/escape
 */
function cssEscape(value) {
    if (window.CSS && typeof window.CSS.escape === "function") {
        return window.CSS.escape(value);
    }
    return String(value).replace(/[^a-zA-Z0-9_-]/g, (c) => "\\" + c);
}

// ============================================================================
// === Bootstrap =============================================================
// ============================================================================
wirePerRowButtons();
wireBatchButton();
