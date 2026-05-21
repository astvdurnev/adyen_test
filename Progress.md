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

## Phase 4 — 3D Secure 2 (Redirect flow)

**Goal:** Cards that require strong customer authentication complete the redirect-based 3DS2
challenge and return to our result pages.

Covers README steps: **12, 14**.

Tasks:
- [ ] Extend `POST /api/payments` (Step 12) with:
  - [ ] `AuthenticationData` with `attemptAuthentication = ALWAYS`
  - [ ] `paymentRequest.setOrigin("http://localhost:8080")`
  - [ ] `paymentRequest.setBrowserInfo(body.getBrowserInfo())`
  - [ ] `paymentRequest.setShopperIP("192.168.0.1")` *(or read from request)*
  - [ ] `paymentRequest.setShopperInteraction(ECOMMERCE)`
  - [ ] `BillingAddress` (Amsterdam / NL / 1012KK / Rokin 49) to satisfy risk rules
- [ ] Add `GET /handleShopperRedirect` (Step 14)
  - [ ] Accept `redirectResult` *and* `payload` query params
  - [ ] Build `PaymentCompletionDetails`, populate the field that's present
  - [ ] Call `paymentsApi.paymentsDetails(request)`
  - [ ] Map `resultCode` to `/result/success | pending | failed | error`
  - [ ] Return `RedirectView` with `?reason=<resultCode>`

**Verification:**
1. Pay with a 3DS2-mandatory test card (e.g. `4917 6100 0000 0000` or any "3D Secure 2" entry on the [Adyen test cards page](https://docs.adyen.com/development-resources/testing/test-card-numbers/)).
2. Browser is redirected to the Adyen-hosted challenge page.
3. After completing the challenge, browser returns to `localhost:8080/handleShopperRedirect?redirectResult=...` and gets forwarded to `/result/success`.

---

## Phase 5 — Native 3D Secure 2 *(optional)*

**Goal:** Trigger and complete a 3DS2 challenge inline (no Adyen-hosted redirect),
using the `onAdditionalDetails` hook on the Drop-in.

Covers README step: **13**.

Tasks:
- [ ] Switch authentication preference to native:
  - [ ] `authenticationData.setThreeDSRequestData(new ThreeDSRequestData().nativeThreeDS(PREFERRED))`
- [ ] Backend: `POST /api/payments/details` calls `paymentsApi.paymentsDetails(request)`
- [ ] Frontend: add `onAdditionalDetails(state, component, actions)` →
      POST `/api/payments/details`, then `actions.resolve({ resultCode })`

**Verification:** Pay with a card that supports Native 3DS2; the challenge is shown inside the
Drop-in (no browser redirect) and on completion the user lands on the result page.

---

## Phase 6 — Webhooks with HMAC verification

**Goal:** Adyen's notifications hit our `/webhooks` endpoint, are HMAC-validated, and are acknowledged with 202.

Covers README step: **16**.

Tasks:
- [ ] CA: create a *Standard webhook* pointing to the ngrok URL (`https://<ngrok>.dev/webhooks`).
- [ ] CA: generate and copy the HMAC key into `ADYEN_HMAC_KEY` (already in `.env`).
- [ ] Implement `controllers/WebhookController.java#webhooks`:
  - [ ] Parse `NotificationRequest.fromJson(json)`
  - [ ] For the first `NotificationRequestItem`:
    - [ ] `hmacValidator.validateHMAC(item, hmacKey)` — if false → `422 Unprocessable Entity`
    - [ ] Log the event
    - [ ] Return `202 Accepted`
  - [ ] Catch `SignatureException` → `422`, other → `500`

**Verification:**
1. `./scripts/ngrok.sh` is running and exposing `coolingly-supercretaceous-branden.ngrok-free.dev`.
2. Trigger a test webhook from CA → server logs show `Received webhook with event ...` and HMAC validation succeeds.
3. Tamper with the HMAC key in `.env`, restart, send a test webhook → server returns 422.
4. Run an end-to-end payment → AUTHORISATION webhook arrives and is logged.

---

## Phase 7 — Extra payment methods *(stretch goals)*

Covers README steps: **18, 19**.

- [ ] **iDeal**
  - [ ] Enable iDeal in the Customer Area for our merchant
  - [ ] Verify it appears in the Drop-in list
  - [ ] Complete an end-to-end test payment
- [ ] **Klarna**
  - [ ] Enable Klarna in the Customer Area
  - [ ] Add `LineItems` to the `/payments` request
  - [ ] Complete an end-to-end test payment

**Verification:** Both methods show up in the Drop-in on `/checkout?type=dropin`, can be selected, and complete on the Adyen TEST environment.

---

## Out of scope (for now)

- Tokenization module (`README_TOKENIZATION.md`)
- Preauthorisation module (`README_PREAUTHORISATION.md`)
- Production environment / live keys

---

## How we work each phase

1. Implement all tasks in the phase.
2. Restart the server (`./gradlew bootRun`) and run the verification steps above.
3. Tick the boxes in this file and move on to the next phase.
