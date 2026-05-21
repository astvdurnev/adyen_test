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

## Phase 8 — Subscription create + token capture ✅

**Goal:** the shopper visits a subscription page, completes a €0 zero-auth payment;
Adyen sends an AUTHORISATION webhook containing the `recurringDetailReference`; the
server stores it under the shopper's reference.

Tasks:
- [x] **TokenStore** Spring service backed by `build/tokens.json`
  - `Map<shopperReference, TokenRecord>` where `TokenRecord` has `token`, `brand`,
    `recurringProcessingModel`, `createdAt`
  - Atomic save (write to `.tmp` then rename)
  - Load on `@PostConstruct`
- [x] **`POST /api/subscription-create`** in `ApiController`
  - Body: same as `/api/payments` (payment method data) + `shopperReference`
  - Builds a PaymentRequest with: amount=0 EUR, `storePaymentMethod=true`,
    `recurringProcessingModel=Subscription`, `shopperInteraction=Ecommerce`,
    `shopperReference`, plus all the 3DS2 / risk fields we already use.
- [x] **Extend `WebhookController`** to capture tokens
  - On AUTHORISATION with `success=true`, read `additionalData.recurring.recurringDetailReference`
    and `additionalData.recurring.shopperReference`, push into TokenStore.
- [x] **Frontend** — new page
  - `GET /subscription` route in `ViewController`
  - `templates/subscription.html` (button reads "Subscribe")
  - `static/subscriptionWebImplementation.js` (clone of checkout JS, POSTs to
    `/api/subscription-create` with `shopperReference` in body)
- [x] **`GET /admin/tokens`** debugging endpoint (bonus moved up from Phase 9).

**Verification (done):**
1. `curl GET /admin/tokens` returns an empty map at startup. ✅
2. Pay €0 zero-auth via `/subscription` with a 3DS2-capable Visa. ✅
3. AUTHORISATION webhook arrives → token persisted in `build/tokens.json`. ✅

---

## Phase 9 — Charge the subscription

**Goal:** trigger a €5 merchant-initiated payment using the stored token, no shopper
interaction needed.

Tasks:
- [x] **`POST /api/subscription-payment`** in `ApiController`
  - Body: `{ "shopperReference": "..." }` (amount is server-side, fixed €5.00).
  - Look up token in TokenStore → 404 if missing.
  - Build PaymentRequest: amount=500 EUR (€5),
    `paymentMethod=CardDetails().storedPaymentMethodId(<token>).type(SCHEME)`,
    `shopperReference`, `shopperInteraction=ContAuth`, `recurringProcessingModel=Subscription`,
    no returnUrl / no browserInfo / no 3DS2 (it's MIT).
- [x] **`/subscription` page** now adapts to TokenStore state:
  - No token yet → renders Drop-in (Phase 8 flow).
  - Token exists → renders a "Charge €5.00 now" button + result panel; the button
    POSTs to `/api/subscription-payment` and shows the resulting `resultCode` /
    `pspReference`.
- [x] **Admin tooling** (operator dashboard):
  - `POST /api/subscriptions-charge-all` — batch charge endpoint. Loops over the
    TokenStore snapshot, calls the shared `chargeStoredToken()` helper per row,
    catches per-shopper failures so a bad row doesn't kill the rest. Returns a
    JSON array `[{ shopperReference, resultCode, pspReference, refusalReason, error }]`.
  - `GET /subscription/admin` view: table of all subscriptions with per-row
    "Charge €5.00" buttons and a global "Emulate Scheduled Job (charge all)"
    button. Tokens are masked to last 4 chars on display.
  - `static/subscriptionAdmin.js` wires both interaction modes and distributes
    batch results back to each row's inline status.

**README sections covered:** Tokenization "Charge the subscription".

**Verification:**
1. Have a token from Phase 8 (visible in `/admin/tokens` or `/subscription/admin`).
2. Refresh `/subscription` — UI switches to the Charge block.
3. Click "Charge €5.00 now":
   - Server log: `Charging subscription: shopperReference=... amount=500 EUR token=...`.
   - Response JSON shows `resultCode=Authorised` and a new `pspReference`.
   - No 3DS2 challenge (no `action` in the response, `shopperInteraction=ContAuth`).
4. A new AUTHORISATION webhook arrives for €5.00.
5. Calling the endpoint with an unknown `shopperReference` returns HTTP 404.
6. Open `/subscription/admin`:
   - The subscriber row is present, with masked token and "Charge €5.00" button.
   - Per-row button charges that shopper and updates the row's status.
   - "Emulate Scheduled Job" button charges every row and shows the full Adyen
     response payload in the result panel.

---

## Phase 10 — Cancel the subscription ✅

**Goal:** remove the token from both the Adyen Vault and our local store.

Tasks:
- [x] **`RecurringApi`** Spring bean in `DependencyInjectionConfiguration`
  - Shares the same `Client` as `PaymentsApi`.
- [x] **`POST /api/subscriptions-cancel`** in `ApiController`
  - Body: `{ "shopperReference": "..." }`. Returns `{ removed: true, ... }` on success.
  - Look up token → 404 if missing locally.
  - Call Adyen `DELETE /storedPaymentMethods/{tokenId}?shopperReference=...&merchantAccount=...`
    via `RecurringApi.deleteTokenForStoredPaymentDetails(...)`.
  - On Adyen 422/404 (token already gone) we still clean up local store —
    eventual consistency. On Adyen 5xx / network errors the local entry is kept
    so the operator can retry.
- [x] **Admin UI** (`/subscription/admin`)
  - Added Cancel button to each row. Click prompts a confirm() then calls
    `/api/subscriptions-cancel`. On success the row fades out and is removed.
  - When the last row is removed the page auto-reloads to show the empty state.
- [x] **Shopper UI** (`/subscription`)
  - When `hasToken=true`, a "Cancel subscription" button appears next to
    "Charge €5.00 now". Click prompts a confirm() then calls
    `/api/subscriptions-cancel`. On success the page reloads — the next render
    sees `hasToken=false` and shows the Drop-in path again (ready to subscribe
    a fresh card).

**README sections covered:** Tokenization "Cancel the subscription".

**Verification:**
1. Have at least one token (`/admin/tokens` or `/subscription/admin` non-empty).
2. From `/subscription/admin`, click Cancel on a row:
   - Confirm prompt appears with the shopperReference.
   - Server log: `Cancelling subscription: shopperReference=... token=...`
     followed by `Subscription cancelled: shopperReference=...`.
   - Row fades out; `tokens.json` no longer contains that entry; the stored
     method disappears from CA → Customers → Stored Payment Methods.
3. From `/subscription`, click "Cancel subscription":
   - Page reloads back to the Drop-in flow (ready to subscribe again).
4. Calling `POST /api/subscriptions-cancel` with an unknown shopper → HTTP 404.
5. Subsequent `POST /api/subscription-payment` for a cancelled shopper → HTTP 404
   (local store check fails before we ever reach Adyen).

---

## Module 3 — Pre-authorisation (`README_PREAUTHORISATION.md`)

The pre-auth lifecycle is fully asynchronous: every modification (/captures,
/cancels, /refunds, /amountUpdates) returns "received" immediately and the
real outcome arrives later as a webhook. To make this tangible, we built a
live webhook feed FIRST (Phase 11a) so subsequent phases can demonstrate
flows visually as they happen.

### Phase 11a — Live Webhook Feed (SSE) ✅

**Goal:** every accepted webhook is streamed to /preauthorisation in real
time. Multi-participant TEST accounts are handled by tagging every
merchantReference we generate with a per-OS-user prefix and filtering
incoming webhooks against it.

Tasks:
- [x] **`WorkshopInstanceId`** service — derives prefix `wshop-<user>` from
  `System.getProperty("user.name")`; overridable via `WORKSHOP_INSTANCE_ID`
  env var.
- [x] **`WebhookEventBus`** service — `CopyOnWriteArrayList<SseEmitter>` +
  50-event ring buffer. `publish(item)` filters by `instanceId.isOurs()`
  before fan-out; `register()` returns a 10-min `SseEmitter` with onError /
  onTimeout / onCompletion cleanup; sends an initial `connected` hello with
  the prefix so the UI can show what's being filtered.
- [x] **`WebhookController`** calls `eventBus.publish(item)` after HMAC
  validation; `maybeStoreSubscriptionToken()` also gates on
  `instanceId.isOurs()` so other people's tokens never land in our
  TokenStore.
- [x] **`WebhookStreamController`**: `GET /api/webhooks/stream`
  (text/event-stream) returns an SseEmitter; `GET /api/webhooks/recent`
  returns the buffer snapshot as JSON.
- [x] **All `setReference(...)` calls** in `ApiController` now use
  `instanceId.newReference()` (was raw `UUID.randomUUID()`). This keeps the
  prefix consistent for `/api/payments`, `/api/subscription-create`, and
  `/api/subscription-payment`.
- [x] **`/preauthorisation` page** (Phase 11a scaffold): empty preauth
  placeholder + live feed card. Header shows the active prefix.
  `static/preauthorisationLiveFeed.js`: loads `/recent`, opens EventSource,
  renders newest-first cards with click-to-expand JSON, "Clear list"
  button, status badge (Connecting / Live / Reconnecting / Disconnected).
- [x] **`index.html`** now lists Start shopping / Subscribe / Pre-authorise
  as three entries.

**Verification (done):**
1. Compile + DevTools restart: log shows `WorkshopInstanceId: derived prefix
   'wshop-viktordurnev' from OS user 'viktordurnev'`. ✅
2. `GET /preauthorisation` returns HTTP 200 with prefix rendered as `<code>`
   in the feed header. ✅
3. `curl -N /api/webhooks/stream` immediately emits `event: connected` with
   `instancePrefix`. ✅
4. Injected two HMAC-signed test webhooks (one with our prefix, one with
   `other-team-…`). Both got HTTP 202 (Adyen-compatible), but only ours
   appears in `/api/webhooks/recent`. ✅

**Browser test (your part):**
- Open `/preauthorisation` in one tab.
- In another tab, complete a payment on `/checkout?type=dropin` or
  `/subscription`.
- AUTHORISATION webhook arrives → a green card slides into the feed with a
  flash animation; click it to expand `additionalData`.

---

### Phase 11b — Pre-auth + Capture ✅

**Goal:** authorise a card without moving money, then capture (charge) it
later via a separate API call. Cover the basic happy path
preauth → capture and its failure modes.

Tasks:
- [x] **`ModificationsApi`** Spring bean in `DependencyInjectionConfiguration`
  (shares the existing Adyen Client).
- [x] **`PaymentStore`** service backed by `build/payments.json`:
  - Record: `{ pspReference, merchantReference, amount, currency,
    paymentMethod, status, createdAt, updatedAt, history[] }`.
  - Finite-state machine: `AUTHORISED → CAPTURE_REQUESTED → CAPTURED`
    (also `CAPTURE_FAILED`; later `CANCELLED` / `REFUNDED` in 11c).
  - `create()` is idempotent (handles "webhook beat API response").
  - `transition()` appends a `HistoryEntry` so the audit trail is preserved.
- [x] **`POST /api/preauthorisation`** in `ApiController`:
  - Reads `amount` from the body (UI input), defaults to nothing —
    returns HTTP 400 if missing/invalid.
  - Sets `captureDelayHours = -1` to disable auto-capture for THIS payment.
  - Full 3DS2 (Native preferred) flow, identical to `/api/payments`.
  - On `Authorised` response, creates a `PaymentStore` record so the row
    appears in the UI before the AUTHORISATION webhook arrives.
- [x] **`POST /api/capture`** in `ApiController`:
  - Body: `{ pspReference, amount? }`. Defaults to the full stored amount.
  - Calls `modificationsApi.captureAuthorisedPayment(pspRef, …)`.
  - Marks the row as `CAPTURE_REQUESTED` immediately. CAPTURE webhook
    transitions to `CAPTURED` (or `CAPTURE_FAILED`).
  - Idempotency key on each request.
- [x] **`GET /api/payments-list`** — snapshot for the UI poller.
- [x] **`WebhookController.maybeUpdatePaymentState()`** mirrors `eventCode`
  onto a `PaymentStore` transition (`AUTHORISATION`, `CAPTURE`,
  `CAPTURE_FAILED`). Uses `additionalData.originalReference` to look up
  the original payment for modification events. Gated by
  `instanceId.isOurs()` so other participants' webhooks never mutate
  state.
- [x] **`/preauthorisation` page** UI:
  - Amount input (default 100.00 EUR) + Drop-in card → POSTs to
    `/api/preauthorisation`. Amount is locked when Drop-in mounts.
  - Payments table: pspReference (short), amount, status badge,
    createdAt, per-row "Capture" button (only enabled on
    `status=AUTHORISED`).
  - 3-second polling of `/api/payments-list` so the row badge moves
    `AUTHORISED → CAPTURE_REQUESTED → CAPTURED` automatically as
    webhooks arrive.
  - Status badges (`status-authorised`/`-pending`/`-success`/`-fail`/`-neutral`)
    in `application.css`.

**Verification (done):**
1. `GET /api/payments-list` returns `[]` at startup. ✅
2. `POST /api/capture` without body → HTTP 400. ✅
3. `POST /api/capture` with unknown pspRef → HTTP 404. ✅
4. `POST /api/preauthorisation` without amount → HTTP 400. ✅
5. Server log shows `PaymentStore: no existing build/payments.json` on boot. ✅

**Browser test (your part):**
- Open `/preauthorisation`.
- Enter 100.00, Pay with `4917 6100 0000 0000` (3DS2 password `password`).
- Row appears AUTHORISED. AUTHORISATION webhook arrives in the feed.
- Click "Capture €100.00" — badge → "Capture pending…".
- After ~5–10 seconds CAPTURE webhook arrives → badge → "Captured".
- `build/payments.json` reflects every transition with timestamps.

### Phase 11c — Adjust + Cancel + Refund ✅

**Goal:** complete the pre-auth modification toolkit. Now every action
described in `README_PREAUTHORISATION.md` is wired end-to-end.

Tasks:
- [x] `PaymentStore.Status` extended with `CANCEL_FAILED` so we can
  surface the (rare) case where `CANCELLATION` webhook returns
  `success=false`.
- [x] `PaymentStore.updateAmount(...)` — optimistically writes the new
  total + appends a history entry + marks `ADJUSTED`. The new amount is
  reflected in the UI immediately; the AUTHORISATION_ADJUSTMENT webhook
  later confirms it.
- [x] `POST /api/modify-amount`
  (`ModificationsApi.updateAuthorisedAmount`). Status-gated to
  `AUTHORISED` or `ADJUSTED` (can adjust multiple times).
- [x] `POST /api/cancel`
  (`ModificationsApi.cancelAuthorisedPaymentByPspReference`).
  Status-gated to `AUTHORISED` or `ADJUSTED`.
- [x] `POST /api/refund` (`ModificationsApi.refundCapturedPayment`).
  Always full-amount; status-gated to `CAPTURED`.
- [x] `WebhookController.maybeUpdatePaymentState()` extended with:
  - `AUTHORISATION_ADJUSTMENT` → `ADJUSTED` on success, revert to
    `AUTHORISED` on failure.
  - `CANCELLATION` → `CANCELLED` / `CANCEL_FAILED`.
  - `TECHNICAL_CANCEL` → `CANCELLED` (Adyen-initiated, e.g. auth window
    expired).
  - `REFUND` → `REFUNDED` / `REFUND_FAILED`.
  - `REFUND_FAILED` → `REFUND_FAILED`.
  - `REFUNDED_REVERSED` → `REFUND_FAILED` (issuer reversed the refund).
- [x] **`/preauthorisation` UI** — each row now has 4 action buttons
  (Adjust / Capture / Cancel / Refund) with `ACTIONS_BY_STATUS` matrix
  mirroring server-side `requireStatus()` gating. Adjust uses a
  `window.prompt()` for the new amount; Refund is full-only.
- [x] **Per-page filter for the Live Webhook Feed** — the feed now
  shows ONLY webhooks whose `pspReference` or top-level
  `originalReference` is a payment authored on `/preauthorisation`,
  read from `window.preauthKnownPsps` (populated by the polling loop).
  Buffer of pending events handles the race when an AUTHORISATION
  webhook arrives before the first `/api/payments-list` poll completes.

### Phase 11d — Save card during preauth ✅

**Goal:** add a shopper-facing "Save card for future payments"
checkbox on `/preauthorisation`. When ticked, the card is tokenised
and stored in the same `TokenStore` used by Module 2.

Tasks:
- [x] `ApplicationConfiguration.ADYEN_SHOPPER_REFERENCE` env var
  (default `workshop-shopper-001`).
- [x] `ViewController.preauthorisation()` exposes the shopper id to
  the template.
- [x] `preauthorisation.html` hidden `<div id="shopperReference">`.
- [x] `preauthorisationCheckout.js` — `card.enableStoreDetails: true`
  in Drop-in config; when shopper ticks the box Drop-in includes
  `storePaymentMethod: true` in `state.data`, and we forward
  `shopperReference` in the request body.
- [x] `ApiController.preauthorisation()` — when
  `storePaymentMethod=true` and `shopperReference` are present, adds
  `setStorePaymentMethod(true)`, `setShopperReference(...)`, and
  `setRecurringProcessingModel(CARDONFILE)` (CardOnFile because the
  card is being saved for FUTURE shopper-initiated payments, not for
  unattended subscription charges).
- [x] `WebhookController.maybeStoreSubscriptionToken()` now reads
  `recurringProcessingModel` from webhook `additionalData` so
  CardOnFile and Subscription tokens are labelled correctly in
  `TokenStore` (Phase 8 always wrote "Subscription").
- [x] Workshop note: pre-auth additionally sets
  `additionalData.authorisationType = "PreAuth"` (user-contributed
  fix) so the issuer treats the auth as a pre-auth (longer auth
  window, allowed to /amountUpdates). Without this flag Adyen refuses
  `AUTHORISATION_ADJUSTMENT` with "Operation not allowed".

### Bugs fixed in passing while validating 11c/11d

- **`TokenStore` couldn't persist** — Jackson serialiser refused
  `Instant` ("Java 8 date/time type not supported"). `PaymentStore`
  registered `JavaTimeModule`, `TokenStore` did not. Tokens were
  captured in memory but never reached `build/tokens.json`. Fixed by
  adding `.registerModule(new JavaTimeModule())` to `TokenStore`'s
  ObjectMapper, matching `PaymentStore`'s setup.

- **Modification webhooks didn't transition `PaymentStore`** — the
  controller looked for `originalReference` inside
  `additionalData`, but Adyen serialises it at the TOP LEVEL of the
  `NotificationRequestItem`. As a result CAPTURE / CANCELLATION /
  REFUND / AUTHORISATION_ADJUSTMENT lookups missed and the UI stayed
  on `*_REQUESTED` forever. Fixed to use `item.getOriginalReference()`
  with a fallback to the old additionalData path for synthetic test
  fixtures.

- **Live Webhook Feed per-page filter missed modification events** —
  the same bug surfaced in the frontend filter. We now include the
  top-level `originalReference` in the `WebhookEvent` DTO published to
  SSE clients, and the JS filter checks it before falling back to
  `additionalData.originalReference`.

---

## Out of scope (for now)

- Production environment / live keys.
- Scheduled monthly billing (would need a Spring `@Scheduled` job — easy to
  add on top of Phase 9, but not required by the workshop).
- Multi-token-per-shopper (current TokenStore is 1:1).

---

## How we work each phase

1. Implement all tasks in the phase.
2. Restart the server (`./gradlew bootRun`) and run the verification steps above.
3. Tick the boxes in this file and move on to the next phase.
