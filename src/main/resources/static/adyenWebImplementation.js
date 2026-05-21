/**
 * adyenWebImplementation.js — Adyen.Web Drop-in bootstrap for the checkout page.
 * Workshop step(s): Step 8 (Step 10 will add onSubmit / result handlers, Step 13 onAdditionalDetails)
 * Adyen docs:       https://docs.adyen.com/online-payments/build-your-integration/advanced-flow/?platform=Web&integration=Drop-in
 * What & Why:       Runs in the browser on /checkout?type=dropin. Fetches the list of
 *                   payment methods from our backend, builds an AdyenCheckout instance,
 *                   then mounts the Drop-in component into <div id="payment">.
 */

// The public Client Key is rendered into a hidden <div> by checkout.html so the
// browser can read it without us having to expose it through a JSON endpoint.
// SECURITY: the Client Key is *public* (designed to be shipped to the browser) —
// only the API key must stay on the server. The Client Key is bound to a list of
// "Allowed origins" in the Customer Area so it can't be used from arbitrary sites.
// Docs: https://docs.adyen.com/development-resources/client-side-authentication/
const clientKey = document.getElementById("clientKey").innerHTML;

// `window.AdyenWeb` is provided by the Adyen.Web SDK <script> loaded in layout.html.
// `AdyenCheckout` is the factory that returns a Checkout core instance.
// `Dropin` is the unified payment method component (the big widget with the list
// of methods + the card form).
// Docs: https://docs.adyen.com/online-payments/upgrade-your-integration/upgrade-to-web-v6 (v6 import shape)
const { AdyenCheckout, Dropin } = window.AdyenWeb;

/**
 * Boots the Drop-in by chaining: fetch payment methods → create AdyenCheckout → mount Dropin.
 * Any failure surfaces via console.error + a generic alert so the dev notices it.
 */
async function startCheckout() {
    try {
        // === Step 8: fetch the list of available payment methods ====================

        // POST to *our* backend, which forwards the request to Adyen with the server-side
        // API key. We use POST (not GET) because the future enriched body may carry
        // amount/country/shopperLocale, and a request body is semantically the right place
        // for those filters.
        // Docs: https://docs.adyen.com/online-payments/build-your-integration/advanced-flow/?platform=Web&integration=Drop-in#post-payment-methods-request
        const paymentMethodsResponse = await fetch("/api/paymentMethods", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
            }
        }).then(response => response.json());

        // === Step 8: AdyenCheckout configuration ====================================

        // This object is passed to AdyenCheckout() and configures the core instance.
        // Every field has a knock-on effect on what the Drop-in renders.
        // Docs: https://docs.adyen.com/online-payments/build-your-integration/advanced-flow/?platform=Web&integration=Drop-in#step-2-create-a-dom-element
        const configuration = {
            // The list of methods we just received. Drop-in will iterate it and render
            // a UI tile for every method that has a matching component in the SDK.
            paymentMethodsResponse: paymentMethodsResponse,

            // Authenticates browser-side Adyen.Web requests (e.g. card encryption,
            // 3DS2 fingerprint collection). Public, see SECURITY note above.
            clientKey,

            // Language of the UI strings. "en_US" → English (US) labels and validation
            // messages. Use "nl_NL" / "de_DE" / etc. to localise.
            locale: "en_US",

            // ISO 3166-1 alpha-2 country of the shopper. Used by Adyen to filter
            // payment methods (e.g. iDeal only shows for NL, Bancontact for BE) and to
            // pick the right Klarna variant. Our briefing is a Dutch store → "NL".
            countryCode: "NL",

            // "test" → SDK talks to checkoutshopper-test endpoints. Must match the
            // backend `Environment.TEST` and the CDN host in layout.html.
            // CRITICAL: switching to "live" requires also switching API key,
            // CDN host, and live URL prefix on the backend.
            environment: "test",

            // Show the built-in "Pay" button inside the Drop-in. Set to false only
            // if you want to render your own button and call `submit()` manually.
            showPayButton: true,

            // Optional UI string overrides. Adyen ships defaults for every locale;
            // we tweak just the CVC label here as a demo of how to customise copy.
            translations: {
                "en-US": {
                    "creditCard.securityCode.label": "CVV/CVC"
                }
            }
        };

        // === Step 8: per-payment-method tweaks ======================================

        // Drop-in lets us override settings for individual methods via
        // `paymentMethodsConfiguration`. Here we only customise `card`.
        // Docs: https://docs.adyen.com/payment-methods/cards/web-drop-in/
        const paymentMethodsConfiguration = {
            card: {
                // Show the brand icon (Visa, MC, ...) inside the card-number input as
                // the shopper types. Pure UX nicety.
                showBrandIcon: true,

                // Render the "Cardholder name" field and require it to be filled.
                // Recommended for risk scoring (Adyen's RevenueProtect uses it).
                hasHolderName: true,
                holderNameRequired: true,

                // Override the default "Credit Card" label.
                name: "Credit or debit card",

                // Pre-fill the amount shown on the Pay button. NOTE: this is *display only*
                // for the button — the real authorisation amount comes from the backend
                // /payments request. Keep them in sync to avoid shopper confusion.
                // Value is in minor units (cents). 9998 = €99.98 (2× €49.99 demo basket).
                // Docs: https://docs.adyen.com/development-resources/currency-codes/
                amount: {
                    value: 9998,
                    currency: "EUR",
                },

                // Placeholder text inside the empty form fields.
                placeholders: {
                    cardNumber: "1234 5678 9012 3456",
                    expiryDate: "MM/YY",
                    securityCodeThreeDigits: "123",
                    securityCodeFourDigits: "1234",
                    holderName: "Developer Relations Team"
                }
            }
        };

        // === Step 8: instantiate Checkout core and mount the Drop-in ================

        // `AdyenCheckout(configuration)` returns a Promise because the SDK may need to
        // load locale resources / payment method assets before it is ready.
        const adyenCheckout = await AdyenCheckout(configuration);

        // `new Dropin(core, options)` builds the Drop-in component. The second argument
        // carries the per-method overrides we defined above. `.mount(selector|element)`
        // injects the rendered DOM into the page. The target div is defined in
        // checkout.html: <div id="payment" class="payment"></div>.
        new Dropin(adyenCheckout, { paymentMethodsConfiguration: paymentMethodsConfiguration })
            .mount(document.getElementById("payment"));
    } catch (error) {
        // Any error inside the boot sequence ends up here. Log the full object to the
        // browser console (it usually contains a useful Adyen error code) and show a
        // generic alert so the dev/QA tester notices the failure.
        // Common causes at this stage:
        //   - clientKey not in "Allowed origins" → 401-style error during AdyenCheckout init
        //   - paymentMethodsResponse empty → no methods enabled in Customer Area
        console.error(error);
        alert("Error occurred. Look at console for details.");
    }
}

// Step 10 (next phase) will implement onSubmit / onPaymentCompleted / onPaymentFailed
// and these stubs will get a real body. Left here so the file's intent stays visible.

// Step 10 - Function to handle payment completion redirects (filled in Phase 3).
function handleOnPaymentCompleted(response) {
    // intentionally empty in Phase 2
}

// Step 10 - Function to handle payment failure redirects (filled in Phase 3).
function handleOnPaymentFailed(response) {
    // intentionally empty in Phase 2
}

// Kick everything off as soon as the script is loaded.
startCheckout();
