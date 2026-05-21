/**
 * subscriptionWebImplementation.js — Adyen.Web Drop-in for the /subscription page.
 * Workshop module: Module 2 / Phase 8
 * Adyen docs:      https://docs.adyen.com/online-payments/tokenization/create-a-token/
 * What & Why:      Same Drop-in pattern as adyenWebImplementation.js, but:
 *                    - posts to /api/subscription-create (not /api/payments)
 *                    - sends `shopperReference` so the backend can tie the
 *                      resulting token to a subscriber id
 *                    - displays a €0.00 button (we're verifying, not charging)
 *                  Kept as a separate file for clarity — copy-paste of the original
 *                  is intentional so workshop participants can diff the two and see
 *                  exactly what changes for tokenisation.
 */

const clientKey = document.getElementById("clientKey").innerHTML;

// Server-rendered subscriber id. We send it with every /api/subscription-create
// call so the backend can pass it as `shopperReference` to Adyen, which in turn
// echoes it back to us via the AUTHORISATION webhook.
const shopperReference = document.getElementById("shopperReference").innerHTML;

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

startSubscription();
