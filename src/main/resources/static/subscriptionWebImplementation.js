/**
 * subscriptionWebImplementation.js — JS for the /subscription page.
 * Workshop module: Module 2 / Phase 8 + Phase 9
 * Adyen docs:      https://docs.adyen.com/online-payments/tokenization/
 * What & Why:      Two responsibilities on one page:
 *                    1. (Phase 8) If no token saved yet — render Adyen Drop-in
 *                       and POST encrypted card data to /api/subscription-create.
 *                    2. (Phase 9) If a token IS saved — wire the "Charge €5.00"
 *                       button to /api/subscription-payment (MIT, no Drop-in).
 *                  Which path runs is decided by the hidden #hasToken div, which
 *                  ViewController pre-renders based on TokenStore lookup.
 */

const clientKey = document.getElementById("clientKey").innerHTML;

// Server-rendered subscriber id. We send it with every /api/subscription-* call
// so the backend can pass it as `shopperReference` to Adyen, which in turn echoes
// it back to us via the AUTHORISATION webhook.
const shopperReference = document.getElementById("shopperReference").innerHTML;

// Thymeleaf renders boolean true/false as text. Comparing to the string literal
// keeps things deterministic — no need to coerce.
const hasToken = (document.getElementById("hasToken").innerHTML.trim() === "true");

const { AdyenCheckout, Dropin } = window.AdyenWeb;

async function startSubscription() {
    try {
        const paymentMethodsResponse = await fetch("/api/paymentMethods", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
            }
        }).then(response => response.json());

        const configuration = {
            paymentMethodsResponse: paymentMethodsResponse,
            clientKey,
            locale: "en_US",
            countryCode: "NL",
            environment: "test",
            showPayButton: true,
            translations: {
                "en-US": {
                    "creditCard.securityCode.label": "CVV/CVC",
                    "payButton": "Subscribe"
                }
            },

            /**
             * Fired when the shopper clicks the "Subscribe" button.
             * What changes vs /checkout:
             *  - URL is /api/subscription-create
             *  - we add `shopperReference` to the body so the backend can link
             *    the resulting Adyen token to a subscriber id
             * The 3DS2 / redirect / additionalDetails plumbing is identical, because
             * the zero-auth payment still goes through SCA exactly once.
             */
            onSubmit: async (state, component, actions) => {
                console.info("[subscription] onSubmit", state, component);
                try {
                    if (state.isValid) {
                        // Spread state.data (encrypted card blob + browserInfo) and
                        // add our shopperReference on top. The backend reads it from
                        // PaymentRequest.shopperReference (which the Adyen Java SDK
                        // happily deserialises from the same JSON shape).
                        const body = {
                            ...state.data,
                            shopperReference: shopperReference,
                        };
                        const { action, order, resultCode } = await fetch("/api/subscription-create", {
                            method: "POST",
                            body: JSON.stringify(body),
                            headers: {
                                "Content-Type": "application/json",
                            }
                        }).then(response => response.json());

                        if (!resultCode) {
                            console.warn("No resultCode in /api/subscription-create response, rejecting.");
                            actions.reject();
                            return;
                        }
                        actions.resolve({ resultCode, action, order });
                    }
                } catch (error) {
                    console.error(error);
                    actions.reject();
                }
            },

            onPaymentCompleted: (result, component) => {
                console.info("[subscription] onPaymentCompleted", result, component);
                handleOnSubscriptionCompleted(result, component);
            },

            onPaymentFailed: (result, component) => {
                console.info("[subscription] onPaymentFailed", result, component);
                handleOnSubscriptionFailed(result, component);
            },

            onError: (error, component) => {
                console.error("[subscription] onError", error.name, error.message, error.stack, component);
                window.location.href = "/result/error?reason=subscription_error";
            },

            // Native 3DS2 inline action handler — identical to the checkout flow.
            // Same endpoint (/api/payments/details) finalises both regular payments
            // and zero-auth tokenisation; Adyen distinguishes them server-side via
            // the original /payments call's flags.
            onAdditionalDetails: async (state, component, actions) => {
                console.info("[subscription] onAdditionalDetails", state, component);
                try {
                    const { resultCode } = await fetch("/api/payments/details", {
                        method: "POST",
                        body: state.data ? JSON.stringify(state.data) : "",
                        headers: {
                            "Content-Type": "application/json",
                        }
                    }).then(response => response.json());

                    if (!resultCode) {
                        console.warn("No resultCode in /api/payments/details response, rejecting.");
                        actions.reject();
                        return;
                    }
                    actions.resolve({ resultCode });
                } catch (error) {
                    console.error(error);
                    actions.reject();
                }
            }
        };

        const paymentMethodsConfiguration = {
            card: {
                showBrandIcon: true,
                hasHolderName: true,
                holderNameRequired: true,
                name: "Card to use for monthly charges",
                // €0.00 — the button shows the verification amount, not the future
                // monthly charge. Adyen's standard pattern is to communicate the
                // recurring price separately in body copy (we do that in the H2).
                amount: {
                    value: 0,
                    currency: "EUR",
                },
                placeholders: {
                    cardNumber: "1234 5678 9012 3456",
                    expiryDate: "MM/YY",
                    securityCodeThreeDigits: "123",
                    securityCodeFourDigits: "1234",
                    holderName: "Workshop Subscriber"
                }
            }
        };

        const adyenCheckout = await AdyenCheckout(configuration);
        new Dropin(adyenCheckout, { paymentMethodsConfiguration: paymentMethodsConfiguration })
            .mount(document.getElementById("payment"));
    } catch (error) {
        console.error(error);
        alert("Error occurred. Look at console for details.");
    }
}

/**
 * Route a SUCCESS / PENDING tokenisation result to a result page.
 * Authorised → success: the card was verified AND stored. The token itself
 *                       arrives asynchronously in the AUTHORISATION webhook,
 *                       so the success page is shown before tokens.json
 *                       actually contains the token (watch the server logs!).
 */
function handleOnSubscriptionCompleted(response) {
    switch (response.resultCode) {
        case "Authorised":
            window.location.href = "/result/success?reason=subscription_active";
            break;
        case "Pending":
        case "Received":
            window.location.href = "/result/pending?reason=subscription_pending";
            break;
        default:
            window.location.href = "/result/error?reason=" + encodeURIComponent(response.resultCode || "unknown");
            break;
    }
}

function handleOnSubscriptionFailed(response) {
    switch (response.resultCode) {
        case "Cancelled":
        case "Refused":
            window.location.href = "/result/failed?reason=" + encodeURIComponent(response.resultCode);
            break;
        default:
            window.location.href = "/result/error?reason=" + encodeURIComponent(response.resultCode || "unknown");
            break;
    }
}

// ============================================================================
// === Phase 9 — Charge the stored token =====================================
// ============================================================================

/**
 * Wires the "Charge €5.00 now" button. The button is only present in the DOM
 * when the page was rendered for a shopper who already has a token (see
 * subscription.html `th:if="${hasToken}"`).
 *
 * Calls POST /api/subscription-payment with just `{ shopperReference }`. All
 * sensitive data (token id, amount) is server-side; the browser only triggers.
 */
function wireChargeButton() {
    const button = document.getElementById("charge-button");
    const resultPre = document.getElementById("charge-result");
    if (!button || !resultPre) return;

    button.addEventListener("click", async () => {
        // Disable the button to prevent double-clicks. Real charges are
        // idempotent on the server (UUID key per call), but the UX still wants
        // a "in flight" state.
        button.disabled = true;
        button.textContent = "Charging…";
        resultPre.textContent = "";

        try {
            const response = await fetch("/api/subscription-payment", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ shopperReference: shopperReference }),
            });

            // Backend returns the full PaymentResponse. We display it raw — this
            // is a workshop, after all; in production you'd map it to nice copy.
            const text = await response.text();
            let parsed;
            try { parsed = JSON.parse(text); } catch { parsed = text; }

            if (!response.ok) {
                resultPre.textContent =
                    "HTTP " + response.status + "\n" + JSON.stringify(parsed, null, 2);
                button.textContent = "Charge failed — try again";
                button.disabled = false;
                return;
            }

            // Successful HTTP. resultCode tells us whether Adyen authorised the
            // charge (Authorised) or refused it (Refused).
            const summary =
                "resultCode: " + (parsed.resultCode || "?") +
                "\npspReference: " + (parsed.pspReference || "?") +
                "\n\nFull response:\n" + JSON.stringify(parsed, null, 2);
            resultPre.textContent = summary;

            button.textContent = parsed.resultCode === "Authorised"
                ? "Charged ✓ — click to charge again"
                : "Adyen returned " + parsed.resultCode + " — click to retry";
            button.disabled = false;
        } catch (error) {
            console.error("Charge failed", error);
            resultPre.textContent = "Network error: " + error.message;
            button.textContent = "Charge failed — try again";
            button.disabled = false;
        }
    });
}

// ============================================================================
// === Phase 10 — Cancel subscription ========================================
// ============================================================================

/**
 * Wires the "Cancel subscription" button on /subscription. On success the page
 * is reloaded — the server-side TokenStore now has no entry for this shopper,
 * so ViewController will render the Drop-in path (State A) again.
 *
 * The button is destructive (it deletes the card from the Adyen Vault), so we
 * gate the click on a confirm() prompt.
 */
function wireCancelButton() {
    const button = document.getElementById("cancel-button");
    const chargeButton = document.getElementById("charge-button");
    const resultPre = document.getElementById("charge-result");
    if (!button) return;

    button.addEventListener("click", async () => {
        if (!window.confirm(
            "Cancel subscription for " + shopperReference + "?\n\n" +
            "This deletes the stored card from the Adyen Vault. Cannot be undone."
        )) {
            return;
        }

        button.disabled = true;
        if (chargeButton) chargeButton.disabled = true;
        button.textContent = "Cancelling…";
        if (resultPre) resultPre.textContent = "";

        try {
            const resp = await fetch("/api/subscriptions-cancel", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ shopperReference }),
            });
            const text = await resp.text();
            let parsed;
            try { parsed = JSON.parse(text); } catch { parsed = { raw: text }; }

            if (!resp.ok) {
                if (resultPre) {
                    resultPre.textContent = "HTTP " + resp.status + "\n" + JSON.stringify(parsed, null, 2);
                }
                button.textContent = "Cancel failed — try again";
                button.disabled = false;
                if (chargeButton) chargeButton.disabled = false;
                return;
            }

            // Success → reload the page. The server now reports hasToken=false,
            // so ViewController + Thymeleaf will render the Drop-in flow on
            // the next render, ready for a fresh subscription.
            window.location.reload();
        } catch (e) {
            console.error("Cancel failed", e);
            if (resultPre) resultPre.textContent = "Network error: " + e.message;
            button.textContent = "Cancel failed — try again";
            button.disabled = false;
            if (chargeButton) chargeButton.disabled = false;
        }
    });
}

// ============================================================================
// === Bootstrap =============================================================
// ============================================================================
// We pick exactly one path: if a token is already stored, we don't even load
// Drop-in (no point — we'd just be re-collecting a card we already have).
if (hasToken) {
    wireChargeButton();
    wireCancelButton();
} else {
    startSubscription();
}
