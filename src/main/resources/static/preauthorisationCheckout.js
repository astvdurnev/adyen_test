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

        const captureEnabled = row.status === "AUTHORISED";

        tr.innerHTML =
            '<td><code title="' + esc(row.pspReference) + '">' +
                esc(shortPsp(row.pspReference)) + '</code></td>' +
            '<td>' + esc(fmtAmount(row.amountValue, row.amountCurrency)) + '</td>' +
            '<td>' + statusBadge(row.status) + '</td>' +
            '<td>' + esc(fmtDate(row.createdAt)) + '</td>' +
            '<td>' +
                '<button class="row-capture-button btn-primary-small"' +
                (captureEnabled ? '' : ' disabled') + '>' +
                    'Capture ' + esc(fmtAmount(row.amountValue, row.amountCurrency)) +
                '</button>' +
                '<span class="row-result"></span>' +
            '</td>';
        tbody.appendChild(tr);
    }
    wireCaptureButtons();
}

/**
 * Captures a payment by pspReference. Updates the row inline; the AUTHORISED →
 * CAPTURE_REQUESTED transition is server-driven, so the next poll will refresh
 * the badge from "Capture pending…" to "Captured" once the webhook arrives.
 */
async function captureOne(pspReference, row) {
    const button = row.querySelector(".row-capture-button");
    const status = row.querySelector(".row-result");
    button.disabled = true;
    button.textContent = "Capturing…";
    status.textContent = "";
    status.className = "row-result";

    try {
        const resp = await fetch("/api/capture", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ pspReference }),
        });
        const text = await resp.text();
        let parsed;
        try { parsed = JSON.parse(text); } catch { parsed = { raw: text }; }

        if (!resp.ok) {
            status.textContent = "Capture failed (HTTP " + resp.status + ")";
            status.className = "row-result row-result-error";
            button.disabled = false;
            return;
        }
        status.textContent = parsed.status + " (modification " +
            shortPsp(parsed.pspReference) + ") — waiting for CAPTURE webhook";
        status.className = "row-result row-result-pending";
    } catch (e) {
        console.error(e);
        status.textContent = "Network error: " + e.message;
        status.className = "row-result row-result-error";
        button.disabled = false;
    }
}

function wireCaptureButtons() {
    document.querySelectorAll("tr[data-psp]").forEach((row) => {
        const button = row.querySelector(".row-capture-button");
        if (!button || button.disabled) return;
        button.addEventListener("click", () => {
            const psp = row.getAttribute("data-psp");
            captureOne(psp, row);
        });
    });
}

// ============================================================================
// === Polling ==============================================================
// ============================================================================

/** Fetches the snapshot and re-renders. Errors are swallowed so a transient
 *  network blip doesn't break the page. */
async function refreshPaymentsTable() {
    try {
        const resp = await fetch("/api/payments-list");
        if (!resp.ok) return;
        const rows = await resp.json();
        renderPaymentsTable(rows);
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
