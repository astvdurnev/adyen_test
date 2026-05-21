# Adyen Workshop – Implementation Progress & Plan

This document tracks our progress through the Adyen step-by-step payment integration workshop.
The work is split into **phases**. Each phase ends with a feature that can be manually verified
in the browser (or via webhook delivery) before moving on.

Tech: Java 17 + Spring Boot (backend) · HTML/CSS/JS + Thymeleaf (frontend) · Adyen.Web Drop-in v6 · Adyen Java API library 31.3.0.

Legend: `[x]` done · `[ ]` todo · `[~]` in progress

---

## Phase 0 — Project bootstrap & credentials  *(DONE)*

**Goal:** App boots, environment variables are wired, Hello World endpoint works.

- [x] Spring Boot project compiles and runs (`./gradlew bootRun`)
- [x] `GET /hello-world` returns the Step-0 message
- [x] `ADYEN_API_KEY`, `ADYEN_CLIENT_KEY`, `ADYEN_MERCHANT_ACCOUNT`, `ADYEN_HMAC_KEY` set in `.env`
- [x] `ApplicationConfiguration.java` reads them via `@Value`

**Verification:** open `http://localhost:8080/hello-world` → workshop greeting is shown.

---

## Phase 1 — Adyen client wiring & Drop-in assets  *(DONE)*

**Goal:** A configured `Adyen.Client` Spring bean exists on the backend, and the Adyen.Web
Drop-in script + stylesheet are loaded on every page.

Covers README steps: **4, 6**.

Tasks:
- [x] `configurations/DependencyInjectionConfiguration.java`
  - [x] Build `Config` with `applicationConfiguration.getAdyenApiKey()`
  - [x] `config.setEnvironment(Environment.TEST)`
  - [x] Return `new Client(config)` from the `client()` bean
- [x] `resources/templates/layout.html`
  - [x] Add Adyen.Web v6.36.0 Drop-in `<script>` (test CDN)
  - [x] Add the matching v6.36.0 Drop-in `<link rel="stylesheet">`

**Verification:**
1. [x] `./gradlew bootRun` starts without errors (Spring context loaded, all three beans created).
2. [ ] **Manual check needed in browser:** open `http://localhost:8080/checkout?type=dropin`,
       open DevTools → Console: `window.AdyenWeb` must be defined and expose
       `AdyenCheckout` + `Dropin`.
3. [x] CDN URLs return 200 (verified via `curl`: JS 582 KB, CSS 137 KB).

*(Drop-in won't render yet — that's Phase 2.)*

---

## Phase 2 — Show available payment methods

**Goal:** The Drop-in mounts on the checkout page and renders the payment methods configured
for our merchant account (card, plus anything else enabled in the Customer Area).

Covers README steps: **7, 8**.

Tasks:
- [ ] Backend: `controllers/ApiController.java` → `POST /api/paymentMethods`
  - [ ] Build `PaymentMethodsRequest` with `merchantAccount` from config
  - [ ] Call `paymentsApi.paymentMethods(request)` and return the response
  - [ ] Log request & response
- [ ] Frontend: `static/adyenWebImplementation.js`
  - [ ] Read `clientKey` from the hidden div
  - [ ] `fetch('/api/paymentMethods', { method: 'POST' })`
  - [ ] Build `AdyenCheckout` configuration (locale `en_US`, country `NL`, env `test`,
        amount 9998 EUR, card placeholders, etc.)
  - [ ] Instantiate `AdyenCheckout(configuration)` and mount `new Dropin(...)` on `#payment`

**Verification:**
1. `curl -X POST http://localhost:8080/api/paymentMethods` returns a JSON list of payment methods.
2. Visit `http://localhost:8080/checkout?type=dropin` → Drop-in renders with at least the card form.
3. **Pay** button is visible but does not actually charge yet (no `/payments` endpoint).

**Troubleshooting checklist:**
- Empty list → no payment methods enabled in CA.
- `401 Unauthorized` → API key wrong / not at merchant level.
- Drop-in not loading → `http://localhost:8080` not in *Allowed origins* on the Client Key.

---

## Phase 3 — End-to-end card payment (no 3DS2 yet)  *(BACKEND DONE — browser test pending)*

**Goal:** A shopper can pay with a non-3DS test card and land on the correct result page
(`/result/success`, `/result/failed`, etc.).

Covers README steps: **9, 10, 11**.

Tasks:
- [x] Backend: `POST /api/payments` in `ApiController.java`
  - [x] Build `PaymentRequest` with amount EUR 99.98, merchant account, channel `WEB`
  - [x] Copy `paymentMethod` from request body
  - [x] Random `reference` (`UUID.randomUUID().toString()`)
  - [x] `returnUrl = "http://localhost:8080/handleShopperRedirect"`
  - [x] Add `RequestOptions` with idempotency key (Step 11)
  - [x] Call `paymentsApi.payments(request, requestOptions)`
- [x] Frontend: `adyenWebImplementation.js`
  - [x] Add `onSubmit(state, component, actions)` → POST to `/api/payments`, then `actions.resolve({ resultCode, action, order })`
  - [x] Add `onPaymentCompleted` / `onPaymentFailed` / `onError`
  - [x] Implement `handleOnPaymentCompleted` and `handleOnPaymentFailed` to redirect to `/result/...`

**Verification:**
1. [x] Curl test against backend: card `test_4111111111111111` → `resultCode: Authorised`,
       pspReference assigned, logs correlate request/response by reference.
2. [ ] **Manual browser test pending:** open `http://localhost:8080/checkout?type=dropin`,
       pay with `4111 1111 1111 1111` / `03/30` / `737` → redirect to `/result/success`.
3. [ ] **Manual:** pay with a "Refused" test card → redirect to `/result/failed`.

---

## Phase 4 — 3D Secure 2 (Redirect flow)  *(DONE — browser-verified by user)*

**Goal:** Cards that require strong customer authentication complete the redirect-based 3DS2
challenge and return to our result pages.

Covers README steps: **12, 14**.

Tasks:
- [x] Extend `POST /api/payments` (Step 12) with:
  - [x] `AuthenticationData` with `attemptAuthentication = ALWAYS`
  - [x] `paymentRequest.setOrigin("http://localhost:8080")`
  - [x] `paymentRequest.setBrowserInfo(body.getBrowserInfo())`
  - [x] `paymentRequest.setShopperIP("192.168.0.1")` *(or read from request)*
  - [x] `paymentRequest.setShopperInteraction(ECOMMERCE)`
  - [x] `BillingAddress` (Amsterdam / NL / 1012KK / Rokin 49) to satisfy risk rules
- [x] Add `GET /handleShopperRedirect` (Step 14)
  - [x] Accept `redirectResult` *and* `payload` query params
  - [x] Build `PaymentCompletionDetails`, populate the field that's present
  - [x] Call `paymentsApi.paymentsDetails(request)`
  - [x] Map `resultCode` to `/result/success | pending | failed | error`
  - [x] Return `RedirectView` with `?reason=<resultCode>`
  - [x] **Extra:** graceful `try/catch ApiException` so tampered/expired tokens land on
        `/result/error?reason=invalid_redirect_token` instead of a 500 page

**Verification:**
1. [x] Frictionless 3DS2 path still works (curl with non-3DS card → Authorised).
2. [x] Missing redirect params → `/result/error?reason=missing_redirect_token` (302).
3. [x] Bogus `redirectResult` → `/result/error?reason=invalid_redirect_token` (302).
4. [ ] **Manual browser test pending:** pay with a 3DS2-mandatory test card
       (e.g. `4917 6100 0000 0000`), complete the challenge on Adyen's hosted page,
       and land on `/result/success`.

---

## Phase 5 — Native 3D Secure 2  *(DONE — browser-verification pending)*

**Goal:** Trigger and complete a 3DS2 challenge inline (no Adyen-hosted redirect),
using the `onAdditionalDetails` hook on the Drop-in.

Covers README step: **13**.

Tasks:
- [x] Backend: enable `ThreeDSRequestData().nativeThreeDS(PREFERRED)` on the
      `/api/payments` request so Adyen tries Native 3DS2 first and falls back to
      Redirect only when the issuer can't do native.
- [x] Backend: implement `POST /api/payments/details` →
      `paymentsApi.paymentsDetails(detailsRequest)`, with reference-correlated logging.
- [x] Frontend: add `onAdditionalDetails(state, component, actions)` to the Drop-in
      configuration → POST `state.data` to `/api/payments/details`, then
      `actions.resolve({ resultCode })`.

**Verification:**
1. [x] Non-3DS card stays frictionless (curl: Authorised, no action).
2. [x] `/api/payments/details` endpoint reachable (curl with empty body → expected
       Adyen-side 500 / malformed reject).
3. [ ] **Manual browser test pending:** pay with a 3DS2-mandatory card. The challenge
       should now appear INSIDE the Drop-in widget (small modal) instead of a full-page
       redirect to a `test.adyen.com` URL.

---

## Phase 6 — Webhooks with HMAC verification  *(DONE)*

**Goal:** Adyen's notifications hit our `/webhooks` endpoint, are HMAC-validated, and are acknowledged with 202.

Covers README step: **16**.

Tasks:
- [x] CA: Standard webhook configured to hit the ngrok URL.
- [x] CA: HMAC key generated and stored in `ADYEN_HMAC_KEY` (`.env`).
- [x] Implement `controllers/WebhookController.java#webhooks`:
  - [x] Parse `NotificationRequest.fromJson(json)`
  - [x] For the first `NotificationRequestItem`:
    - [x] `hmacValidator.validateHMAC(item, hmacKey)` — false → `422`
    - [x] Log the event (event code, success, pspReference, amount)
    - [x] Return `202 [accepted]`
  - [x] Catch `SignatureException` → `422`, anything else → `500` (so Adyen retries)
  - [x] Extra: empty-batch payload also returns `422` (won't retry-loop)

**Verification:**
1. [x] ngrok tunnel `coolingly-supercretaceous-branden.ngrok-free.dev` reachable from outside (returns 422 to a tampered payload).
2. [x] Synthetic webhook with valid HMAC → `202 [accepted]`.
3. [x] Synthetic webhook with invalid HMAC → `422`.
4. [x] **Real AUTHORISATION webhook from Adyen** arrived (pspReference `CJGXBM2G5Z6JS975`) and was logged as `Webhook OK`.

---

## Phase 7 — Extra payment methods  *(DONE — browser tests pending)*

Covers README steps: **18, 19**.

- [x] **iDeal**
  - [x] iDeal enabled in Customer Area (verified — appears in /paymentMethods response).
  - [x] Enrich `/api/paymentMethods` with `amount` (EUR 99.98), `countryCode=NL`,
        `shopperLocale=nl_NL`, `channel=WEB` so Adyen returns iDeal in the list.
  - [ ] **Manual browser test:** select iDeal, pick a test bank, complete the redirect.
- [x] **Klarna**
  - [x] Klarna enabled in Customer Area (verified — `klarna_paynow` in /paymentMethods).
  - [x] Add `LineItems` (headphones + sunglasses, 2 × €49.99) to the `/api/payments`
        request — sum equals `paymentRequest.amount.value` as required.
  - [x] Add `countryCode=NL` to `paymentRequest` (Klarna refuses with "Invalid issuer
        countrycode" without it).
  - [x] Verified via curl: Klarna returns `RedirectShopper` + `action.redirect` →
        Klarna-hosted confirmation flow.
  - [ ] **Manual browser test:** select Klarna, click through the Klarna confirmation
        page, complete on Adyen TEST.

**Verification:** `curl /api/paymentMethods` returns `ideal | scheme | klarna_paynow`.
Klarna via curl produces a valid `RedirectShopper` response (no validation errors).

---

---

# Module 2 — Tokenization (Subscriptions)

Briefing (from `README_TOKENIZATION.md`): the store pivots from one-shot sales to
subscriptions: rent the headphones for **€5 / month**. We need to tokenise the
shopper's card via a zero-auth payment, store the returned `recurringDetailReference`,
and later charge €5 with merchant-initiated (`ContAuth`) payments. The shopper can
cancel anytime, which deletes the token from Adyen Vault.

Adyen concepts in play:
- `storePaymentMethod = true` — flag on the first /payments call that asks Adyen to
  vault the card.
- `recurringProcessingModel = Subscription` — fixed amount, fixed interval (our model).
- `shopperInteraction = Ecommerce` (first tokenisation, shopper-present) vs
  `ContAuth` (subsequent charges, merchant-initiated).
- `shopperReference` — our stable id for the shopper, links them to all their stored
  tokens in the Adyen Vault. Generated server-side as a UUID per "subscriber".

Architecture choices (agreed with user):
- **Token storage:** `build/tokens.json` on disk (`ConcurrentHashMap` mirror in memory,
  flushed on every mutation). Survives restarts; gitignored via the existing `build/`
  rule. In production this would be a relational table.
- **UI:** dedicated `/subscription?type=dropin` page (separate template + JS file from
  the existing `/checkout`) — cleaner mental model for teaching.
- **Recurring model:** `Subscription` only (as in README); other modes can be added
  later by branching the request.

---

## Phase 8 — Subscription create + token capture

**Goal:** the shopper visits a subscription page, completes a €0 zero-auth payment;
Adyen sends an AUTHORISATION webhook containing the `recurringDetailReference`; the
server stores it under the shopper's reference.

Tasks:
- [ ] **TokenStore** Spring service backed by `build/tokens.json`
  - `Map<shopperReference, TokenRecord>` where `TokenRecord` has `token`, `brand`,
    `recurringProcessingModel`, `createdAt`
  - Atomic save (write to `.tmp` then rename)
  - Load on `@PostConstruct`
- [ ] **`POST /api/subscription-create`** in `ApiController`
  - Body: same as `/api/payments` (payment method data) + `shopperReference`
  - Builds a PaymentRequest with: amount=0 EUR, `storePaymentMethod=true`,
    `recurringProcessingModel=Subscription`, `shopperInteraction=Ecommerce`,
    `shopperReference`, plus all the 3DS2 / risk fields we already use.
- [ ] **Extend `WebhookController`** to capture tokens
  - On AUTHORISATION with `success=true`, read `additionalData.recurring.recurringDetailReference`
    and `additionalData.recurring.shopperReference`, push into TokenStore.
- [ ] **Frontend** — new page
  - `GET /subscription` route in `ViewController`
  - `templates/subscription.html` (clones checkout.html, button reads "Subscribe")
  - `static/subscriptionWebImplementation.js` (clones the checkout JS but POSTs to
    `/api/subscription-create`, no LineItems on the request)

**Verification:**
1. `curl GET /admin/tokens` (TBD) returns an empty map at startup.
2. Pay €0 zero-auth via the new page with a card that supports tokenisation
   (e.g. Visa `4111 1111 1111 1111` is fine on TEST).
3. AUTHORISATION webhook arrives → server log shows `Stored token for shopper {ref}: {token}`.
4. `build/tokens.json` on disk now contains the entry.

---

## Phase 9 — Charge the subscription

**Goal:** trigger a €5 merchant-initiated payment using the stored token, no shopper
interaction needed.

Tasks:
- [ ] **`POST /api/subscription-payment`** in `ApiController`
  - Body: `{ "shopperReference": "..." }`
  - Look up token in TokenStore → 404 if missing.
  - Build PaymentRequest: amount=500 EUR (€5), `paymentMethod={ storedPaymentMethodId: <token> }`,
    `shopperReference`, `shopperInteraction=ContAuth`, `recurringProcessingModel=Subscription`.
- [ ] **(Bonus)** `GET /admin/tokens` — list of stored tokens for debugging.

**Verification:**
1. After Phase 8, `curl POST /api/subscription-payment -d '{"shopperReference":"foo"}'`
   returns `Authorised` with a new pspReference.
2. New AUTHORISATION webhook arrives for the €5 charge.
3. Calling the endpoint with an unknown `shopperReference` returns 404.

---

## Phase 10 — Cancel the subscription

**Goal:** remove the token from both the Adyen Vault and our local store.

Tasks:
- [ ] **`POST /api/subscriptions-cancel`** in `ApiController`
  - Body: `{ "shopperReference": "..." }`
  - Look up token → 404 if missing.
  - Call Adyen `DELETE /storedPaymentMethods/{tokenId}?shopperReference=...&merchantAccount=...`
    via the Java library's `RecurringApi` (or low-level resource if needed).
  - Remove from local TokenStore on success.

**Verification:**
1. Cancel removes the entry from `tokens.json`.
2. Following `subscription-payment` for the same `shopperReference` → 404 / Adyen
   "stored method not found".
3. Token disappears from CA → Customers → Stored Payment Methods.

---

## Out of scope (for now)

- Preauthorisation module (`README_PREAUTHORISATION.md`)
- Production environment / live keys
- Scheduled monthly billing (would need a Spring `@Scheduled` job — easy to add on
  top of Phase 9, but not required by the workshop)

---

## How we work each phase

1. Implement all tasks in the phase.
2. Restart the server (`./gradlew bootRun`) and run the verification steps above.
3. Tick the boxes in this file and move on to the next phase.
