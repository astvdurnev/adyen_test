/**
 * preauthorisationCheckout.js — pre-auth form + payments table for /preauthorisation.
 * Workshop module: Module 3 / Phase 11b
 * Adyen docs:      https://docs.adyen.com/online-payments/adjust-authorisation/adjust-with-preauth/
 * What & Why:      Two responsibilities:
 *                    1. Mount an Adyen.Web Drop-in that POSTs to
 *                       /api/preauthorisation with `captureDelayHours=-1`
 *                       (Adyen authorises but doesn't move money).
 *                    2. Poll GET /api/payments-list every 3 seconds and render
 *                       a table of every payment we authored, with a "Capture
 *                       €X" button on rows in status=AUTHORISED.
 *                  3DS2 / inline action handling reuses /api/payments/details
 *                  (same endpoint as /checkout — Adyen distinguishes the flow
 *                  by the original /payments call's flags).
 */

const clientKey = document.getElementById("clientKey").innerHTML;
// Phase 11d — shopperReference forwarded to the server when the user opts
// to save the card. Coming from a hidden div populated by Thymeleaf so the
// JS doesn't need to know how the value is configured (it's an env var).
const shopperReference = (document.getElementById("shopperReference") || {}).innerHTML || "";
const { AdyenCheckout, Dropin } = window.AdyenWeb;

// ============================================================================
// === Amount helpers ========================================================
// ============================================================================

/**
 * Reads the value from the amount input and converts to minor units (cents).
 * Returns `null` if the field is missing or invalid — caller decides what to do.
 * NOTE: we capture the amount ONCE when the Drop-in mounts, so the value shown
 * on the Pay button stays in sync with what's actually charged. Changing the
 * input after mount has no effect; the hint text explains this.
 */
function readAmountMinorUnits() {
    const input = document.getElementById("preauth-amount");
    if (!input) return null;
    const v = parseFloat(input.value);
    if (isNaN(v) || v <= 0) return null;
    return Math.round(v * 100);
}

function fmtAmount(value, currency) {
    if (value == null || currency == null) return "—";
    return (value / 100).toFixed(2) + " " + currency;
}

function fmtDate(isoString) {
    if (!isoString) return "—";
    try {
        return new Date(isoString).toLocaleString();
    } catch {
        return isoString;
    }
}

function shortPsp(psp) {
    if (!psp) return "—";
    return psp.length > 12 ? psp.slice(0, 6) + "…" + psp.slice(-4) : psp;
}

// ============================================================================
// === Drop-in bootstrap =====================================================
// ============================================================================

async function startPreauthCheckout() {
    try {
        // Lock the amount in. If the user clears or types nonsense, fall back
        // to 100.00 EUR (matches the input's default).
        const lockedAmount = readAmountMinorUnits() || 10000;

        const paymentMethodsResponse = await fetch("/api/paymentMethods", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
        }).then((r) => r.json());

        const configuration = {
            paymentMethodsResponse,
            clientKey,
            locale: "en_US",
            countryCode: "NL",
            environment: "test",
            showPayButton: true,
            translations: {
                "en-US": {
                    "creditCard.securityCode.label": "CVV/CVC",
                    "payButton": "Pre-authorise",
                },
            },

            /**
             * Submit handler — what makes this a pre-auth instead of a regular
             * payment is that we POST to /api/preauthorisation (server adds
             * captureDelayHours=-1). The amount comes from the locked-in value
             * we captured before mounting.
             */
            onSubmit: async (state, component, actions) => {
                console.info("[preauth] onSubmit", state, component);
                try {
                    if (!state.isValid) return;

                    const body = {
                        ...state.data,
                        amount: {
                            value: lockedAmount,
                            currency: "EUR",
                        },
                    };
                    // Phase 11d: when the shopper ticked "Save card", the
                    // Drop-in already set storePaymentMethod=true inside
                    // state.data. We additionally forward shopperReference
                    // so the server can attach the token to a customer.
                    // Adyen docs:
                    // https://docs.adyen.com/online-payments/tokenization/create-tokens/
                    if (state.data && state.data.storePaymentMethod === true) {
                        body.shopperReference = shopperReference;
                    }
                    const { action, order, resultCode } = await fetch(
                        "/api/preauthorisation",
                        {
                            method: "POST",
                            headers: { "Content-Type": "application/json" },
                            body: JSON.stringify(body),
                        }
                    ).then((r) => r.json());

                    if (!resultCode) {
                        console.warn("No resultCode in /api/preauthorisation response, rejecting.");
                        actions.reject();
                        return;
                    }

                    // Refresh the table right away so the AUTHORISED row appears
                    // BEFORE Drop-in fires onPaymentCompleted.
                    refreshPaymentsTable();

                    actions.resolve({ resultCode, action, order });
                } catch (e) {
                    console.error(e);
                    actions.reject();
                }
            },

            // Native 3DS2 challenge response (same endpoint as /checkout — Adyen
            // doesn't care which original flow we came from).
            onAdditionalDetails: async (state, component, actions) => {
                console.info("[preauth] onAdditionalDetails", state);
                try {
                    const { resultCode } = await fetch("/api/payments/details", {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: state.data ? JSON.stringify(state.data) : "",
                    }).then((r) => r.json());
                    if (!resultCode) {
                        actions.reject();
                        return;
                    }
                    actions.resolve({ resultCode });
                } catch (e) {
                    console.error(e);
                    actions.reject();
                }
            },

            onPaymentCompleted: (result) => {
                console.info("[preauth] completed", result);
                refreshPaymentsTable();
                // We deliberately do NOT redirect to /result/* — the workshop
                // user stays on /preauthorisation to act on the new row.
            },

            onPaymentFailed: (result) => {
                console.info("[preauth] failed", result);
                refreshPaymentsTable();
            },

            onError: (error) => {
                console.error("[preauth] error", error);
            },
        };

        const paymentMethodsConfiguration = {
            card: {
                showBrandIcon: true,
                hasHolderName: true,
                holderNameRequired: true,
                name: "Card to pre-authorise",
                // Pay button shows the locked amount; the backend trusts the
                // body, not this display value. Keep them aligned so the UX
                // doesn't lie about what's being charged.
                amount: { value: lockedAmount, currency: "EUR" },
                // Phase 11d: "Save card for future payments" checkbox.
                // When ticked, Drop-in adds storePaymentMethod=true to
                // state.data; the server picks that up and sets
                // recurringProcessingModel=CardOnFile + shopperReference so
                // Adyen stores the token in the Vault and emits a
                // recurringDetailReference on the AUTHORISATION webhook.
                // Adyen docs:
                //   https://docs.adyen.com/payment-methods/cards/web-drop-in#optional-configuration
                //   https://docs.adyen.com/online-payments/tokenization/create-tokens/
                enableStoreDetails: true,
            },
        };

        const adyenCheckout = await AdyenCheckout(configuration);
        new Dropin(adyenCheckout, { paymentMethodsConfiguration })
            .mount(document.getElementById("payment"));
    } catch (e) {
        console.error(e);
        alert("Error occurred. Look at console for details.");
    }
}

// ============================================================================
// === Payments table ========================================================
// ============================================================================

/** Maps a PaymentStore.Status to (label, css class). */
const STATUS_BADGES = {
    AUTHORISED:        { label: "Authorised",         cls: "status-authorised" },
    ADJUSTED:          { label: "Adjusted",           cls: "status-authorised" },
    CAPTURE_REQUESTED: { label: "Capture pending…",   cls: "status-pending" },
    CAPTURED:          { label: "Captured",           cls: "status-success" },
    CAPTURE_FAILED:    { label: "Capture failed",     cls: "status-fail" },
    CANCEL_REQUESTED:  { label: "Cancel pending…",    cls: "status-pending" },
    CANCELLED:         { label: "Cancelled",          cls: "status-neutral" },
    CANCEL_FAILED:     { label: "Cancel failed",      cls: "status-fail" },
    REFUND_REQUESTED:  { label: "Refund pending…",    cls: "status-pending" },
    REFUNDED:          { label: "Refunded",           cls: "status-neutral" },
    REFUND_FAILED:     { label: "Refund failed",      cls: "status-fail" },
};

function statusBadge(status) {
    const cfg = STATUS_BADGES[status] || { label: status || "?", cls: "status-neutral" };
    return '<span class="status-badge ' + cfg.cls + '">' + cfg.label + '</span>';
}

/** Tiny HTML-escape helper. */
function esc(s) {
    if (s == null) return "";
    return String(s)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

/**
 * Action matrix per PaymentStore.Status. Each entry is a flag — the row
 * renderer enables/disables buttons based on these.
 * Mirror the server-side `requireStatus()` gating in ApiController so the
 * UI never shows a button that the API will reject with 409.
 */
const ACTIONS_BY_STATUS = {
    AUTHORISED:        { adjust: true,  capture: true,  cancel: true,  refund: false },
    ADJUSTED:          { adjust: true,  capture: true,  cancel: true,  refund: false },
    CAPTURE_REQUESTED: { adjust: false, capture: false, cancel: false, refund: false },
    CAPTURED:          { adjust: false, capture: false, cancel: false, refund: true  },
    CAPTURE_FAILED:    { adjust: false, capture: false, cancel: false, refund: false },
    CANCEL_REQUESTED:  { adjust: false, capture: false, cancel: false, refund: false },
    CANCELLED:         { adjust: false, capture: false, cancel: false, refund: false },
    CANCEL_FAILED:     { adjust: false, capture: false, cancel: false, refund: false },
    REFUND_REQUESTED:  { adjust: false, capture: false, cancel: false, refund: false },
    REFUNDED:          { adjust: false, capture: false, cancel: false, refund: false },
    REFUND_FAILED:     { adjust: false, capture: false, cancel: false, refund: false },
};

/** Re-renders the table from a snapshot. Avoids in-place edits — the dataset
 *  is small enough that a full rebuild keeps the code simple. */
function renderPaymentsTable(rows) {
    const tbody = document.getElementById("preauth-table-body");
    if (!tbody) return;

    if (!Array.isArray(rows) || rows.length === 0) {
        tbody.innerHTML =
            '<tr><td colspan="5" class="preauth-table-empty">' +
            'No pre-authorised payments yet. Use the form above.' +
            '</td></tr>';
        return;
    }

    tbody.innerHTML = "";
    for (const row of rows) {
        const tr = document.createElement("tr");
        tr.setAttribute("data-psp", row.pspReference);

        const allowed = ACTIONS_BY_STATUS[row.status] ||
            { adjust: false, capture: false, cancel: false, refund: false };
        const amountStr = fmtAmount(row.amountValue, row.amountCurrency);

        // Button bar. Each button always rendered (so the row layout doesn't
        // shift as status changes), but disabled when the status doesn't allow
        // that action. Tooltip hints why each is off.
        const buttons =
            '<button class="row-action btn-adjust"  data-action="adjust"'  +
                (allowed.adjust  ? '' : ' disabled') +
                ' title="Adjust the authorised amount">Adjust</button>' +
            '<button class="row-action btn-capture" data-action="capture"' +
                (allowed.capture ? '' : ' disabled') +
                ' title="Capture the full authorised amount">Capture ' + esc(amountStr) + '</button>' +
            '<button class="row-action btn-cancel"  data-action="cancel"'  +
                (allowed.cancel  ? '' : ' disabled') +
                ' title="Cancel (void) the authorisation">Cancel</button>' +
            '<button class="row-action btn-refund"  data-action="refund"'  +
                (allowed.refund  ? '' : ' disabled') +
                ' title="Refund the captured amount">Refund</button>';

        tr.innerHTML =
            '<td><code title="' + esc(row.pspReference) + '">' +
                esc(shortPsp(row.pspReference)) + '</code></td>' +
            '<td>' + esc(amountStr) + '</td>' +
            '<td>' + statusBadge(row.status) + '</td>' +
            '<td>' + esc(fmtDate(row.createdAt)) + '</td>' +
            '<td class="row-actions-cell">' + buttons +
                '<div class="row-result"></div>' +
            '</td>';
        tbody.appendChild(tr);
    }
    wireActionButtons();
}

/**
 * Calls a modification endpoint and updates the row's status pill.
 *
 * @param {object} opts
 * @param {string} opts.url        endpoint to POST to
 * @param {object} opts.body       request body
 * @param {HTMLElement} opts.row   <tr> element
 * @param {HTMLButtonElement} opts.button button that was clicked
 * @param {string} opts.busyLabel  what to show on the button while in flight
 * @param {string} opts.pendingNote message under "row-result" while awaiting webhook
 * @param {string} opts.errorPrefix label for HTTP failure messages
 */
async function postModification(opts) {
    const { url, body, row, button, busyLabel, pendingNote, errorPrefix } = opts;
    const statusEl = row.querySelector(".row-result");

    const previousLabel = button.textContent;
    button.disabled = true;
    button.textContent = busyLabel;
    statusEl.textContent = "";
    statusEl.className = "row-result";

    try {
        const resp = await fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
        });
        const text = await resp.text();
        let parsed;
        try { parsed = JSON.parse(text); } catch { parsed = { raw: text }; }

        if (!resp.ok) {
            const errMsg = parsed.message || parsed.error || ("HTTP " + resp.status);
            statusEl.textContent = errorPrefix + ": " + errMsg;
            statusEl.className = "row-result row-result-error";
            button.disabled = false;
            button.textContent = previousLabel;
            return;
        }
        statusEl.textContent = parsed.status + " (modification " +
            shortPsp(parsed.pspReference) + ") — " + pendingNote;
        statusEl.className = "row-result row-result-pending";
        // Force an immediate snapshot refresh so the row's status pill updates
        // to *_REQUESTED right away rather than waiting up to 3 seconds.
        refreshPaymentsTable();
    } catch (e) {
        console.error(e);
        statusEl.textContent = "Network error: " + e.message;
        statusEl.className = "row-result row-result-error";
        button.disabled = false;
        button.textContent = previousLabel;
    }
}

function wireActionButtons() {
    document.querySelectorAll("tr[data-psp]").forEach((row) => {
        const psp = row.getAttribute("data-psp");
        row.querySelectorAll(".row-action").forEach((button) => {
            if (button.disabled) return;
            const action = button.getAttribute("data-action");
            button.addEventListener("click", () => handleAction(action, psp, row, button));
        });
    });
}

/**
 * Dispatch table for the four row actions. Each branch knows how to build
 * its request body and what user-facing copy to show.
 */
function handleAction(action, pspReference, row, button) {
    switch (action) {
        case "capture":
            postModification({
                url: "/api/capture",
                body: { pspReference },
                row, button,
                busyLabel: "Capturing…",
                pendingNote: "waiting for CAPTURE webhook",
                errorPrefix: "Capture failed",
            });
            return;

        case "cancel":
            if (!confirm("Cancel this authorisation? Funds will be released.")) return;
            postModification({
                url: "/api/cancel",
                body: { pspReference },
                row, button,
                busyLabel: "Cancelling…",
                pendingNote: "waiting for CANCELLATION webhook",
                errorPrefix: "Cancel failed",
            });
            return;

        case "refund":
            if (!confirm("Refund the full captured amount?")) return;
            postModification({
                url: "/api/refund",
                body: { pspReference },
                row, button,
                busyLabel: "Refunding…",
                pendingNote: "waiting for REFUND webhook",
                errorPrefix: "Refund failed",
            });
            return;

        case "adjust": {
            // Prompt for a new total in major units. We default to the
            // current amount so the typing cost is minimal when the user
            // just wants to nudge it.
            const currentRowAmountCell = row.children[1]?.textContent || "";
            const currentMajor = parseFloat(currentRowAmountCell);
            const input = prompt(
                "New total amount (EUR). Enter a value greater than 0.\n" +
                "Current: " + (isNaN(currentMajor) ? "?" : currentMajor.toFixed(2)),
                isNaN(currentMajor) ? "1.00" : currentMajor.toFixed(2)
            );
            if (input === null) return;
            const major = parseFloat(input);
            if (isNaN(major) || major <= 0) {
                alert("Invalid amount.");
                return;
            }
            const minor = Math.round(major * 100);
            postModification({
                url: "/api/modify-amount",
                body: { pspReference, amount: { value: minor, currency: "EUR" } },
                row, button,
                busyLabel: "Adjusting…",
                pendingNote: "waiting for AUTHORISATION_ADJUSTMENT webhook",
                errorPrefix: "Adjust failed",
            });
            return;
        }
    }
}

// ============================================================================
// === Polling ==============================================================
// ============================================================================

/**
 * Set of pspReferences that belong to THIS page. Exported on window so the
 * live feed (preauthorisationLiveFeed.js) can filter incoming webhooks down
 * to events relevant to /preauthorisation only. Without this filter the feed
 * also shows /checkout and /subscription events from the same instance,
 * which is too noisy when you're focused on the preauth flow.
 *
 * The set is the source of truth shared between the polling loop and the
 * SSE filter, so both stay in sync without explicit cross-component events.
 */
window.preauthKnownPsps = window.preauthKnownPsps || new Set();
window.dispatchEvent(new CustomEvent("preauthKnownPspsReady"));

/** Fetches the snapshot, re-renders, and refreshes the known-pspReferences
 *  set used by the live feed for filtering. Errors are swallowed so a
 *  transient network blip doesn't break the page. */
async function refreshPaymentsTable() {
    try {
        const resp = await fetch("/api/payments-list");
        if (!resp.ok) return;
        const rows = await resp.json();
        renderPaymentsTable(rows);

        // Keep the live feed's filter in sync with what's actually in
        // PaymentStore. We rebuild the set from scratch because a payment can
        // never DISAPPEAR from the store; if anything its set is monotonically
        // growing, but `Set` operations are cheap enough that we don't bother
        // with deltas.
        const next = new Set();
        for (const r of rows || []) next.add(r.pspReference);
        window.preauthKnownPsps = next;
        window.dispatchEvent(new CustomEvent("preauthKnownPspsUpdated"));
    } catch (e) {
        console.debug("payments-list refresh failed:", e.message);
    }
}

// 3-second poll. Cheap on the server (in-memory snapshot) and the latency hides
// the asynchronous nature of webhooks well in a workshop demo. If we ever care
// about exactness, switch to SSE-pushed table updates.
setInterval(refreshPaymentsTable, 3000);

// ============================================================================
// === Bootstrap =============================================================
// ============================================================================
refreshPaymentsTable();
startPreauthCheckout();
