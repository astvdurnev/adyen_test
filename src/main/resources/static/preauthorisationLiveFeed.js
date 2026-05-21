/**
 * preauthorisationLiveFeed.js — Live Webhook Feed UI.
 * Workshop module: Module 3 / Phase 11a
 * Adyen docs:      https://docs.adyen.com/development-resources/webhooks/
 * MDN docs:        https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events
 * What & Why:      Renders an always-on, real-time feed of every webhook the
 *                  backend has accepted. Two data sources:
 *                    - GET /api/webhooks/recent (one-shot) for history on page load.
 *                    - GET /api/webhooks/stream (SSE)      for live updates.
 *                  No external libs — vanilla DOM + EventSource is enough.
 */

// ============================================================================
// === Configuration =========================================================
// ============================================================================
const FEED_LIST_ID = "webhook-feed-list";
const FEED_STATUS_ID = "webhook-feed-status";
const FEED_CLEAR_ID = "webhook-feed-clear";
const EMPTY_STATE_CLASS = "webhook-feed-empty";

// Max rendered cards. Defends against memory bloat if a workshop participant
// leaves the page open all afternoon. Older cards drop off the bottom.
const MAX_CARDS = 100;

// ============================================================================
// === Per-page filter =======================================================
// ============================================================================
//
// The server already gates webhooks by WorkshopInstanceId.isOurs() (per
// participant on a shared TEST merchant account). But on this page we want
// to narrow further: ONLY webhooks for payments that were authored on
// /preauthorisation. Otherwise checkout/subscription webhooks from the same
// instance show up here too, which is noise.
//
// We do that on the client side rather than the server, because:
//   - Server doesn't know which "page" the events belong to.
//   - The page already has the authoritative list via /api/payments-list, so
//     we just match webhook pspReference (or additionalData.originalReference
//     for modifications) against that set.
//   - preauthorisationCheckout.js exposes window.preauthKnownPsps and fires
//     a 'preauthKnownPspsUpdated' CustomEvent on every refresh, so we stay
//     in sync without a second poller here.
//
// We also keep a small backlog of events that arrived BEFORE the matching
// pspReference was known (race: SSE webhook beats /api/payments-list poll).
// When the set updates we replay the backlog and render anything that now
// matches.

/** Buffer for events whose pspReference wasn't known yet at arrival time. */
const pendingEvents = [];
const PENDING_MAX = 50; // bound memory; older drops silently.

/** Returns true if the event belongs to this page (preauthorisation). */
function isEventForThisPage(event) {
    const set = window.preauthKnownPsps;
    if (!set || set.size === 0) return false;

    // Direct match — for the very first AUTHORISATION webhook the
    // pspReference IS the original.
    if (event.pspReference && set.has(event.pspReference)) return true;

    // Modifications (CAPTURE / CANCELLATION / REFUND / AUTHORISATION_ADJUSTMENT)
    // carry the original payment's psp in the top-level `originalReference`
    // field. The fallback to additionalData.originalReference is for
    // backward compatibility with smoke-test fixtures that put it there.
    // Docs: https://docs.adyen.com/development-resources/webhooks/understand-notifications/#fields
    if (event.originalReference && set.has(event.originalReference)) return true;
    const origFallback = event.additionalData && event.additionalData.originalReference;
    if (origFallback && set.has(origFallback)) return true;

    return false;
}

// ============================================================================
// === Connection status indicator ==========================================
// ============================================================================

/** Possible states for the badge in the feed header. */
const STATUS = {
    CONNECTING: { text: "Connecting…", className: "webhook-feed-status-idle" },
    CONNECTED:  { text: "Live",         className: "webhook-feed-status-ok" },
    RECONNECT:  { text: "Reconnecting…", className: "webhook-feed-status-warn" },
    ERROR:      { text: "Disconnected", className: "webhook-feed-status-error" },
};

function setStatus(state) {
    const el = document.getElementById(FEED_STATUS_ID);
    if (!el) return;
    el.textContent = state.text;
    el.className = "webhook-feed-status " + state.className;
}

// ============================================================================
// === Rendering =============================================================
// ============================================================================

/** Formats a server Instant (ISO-8601 with Z) into local "HH:MM:SS". */
function fmtTime(isoString) {
    if (!isoString) return "—";
    try {
        const d = new Date(isoString);
        return d.toLocaleTimeString();
    } catch {
        return isoString;
    }
}

/** Formats minor units + currency into "100.00 EUR". Returns "—" if either is missing. */
function fmtAmount(value, currency) {
    if (value == null || currency == null) return "—";
    return (value / 100).toFixed(2) + " " + currency;
}

/**
 * Builds the DOM for one webhook card. Each card:
 *  - Header: time, eventCode pill, success/refused badge, pspReference (short).
 *  - Body summary: merchantReference, amount, paymentMethod.
 *  - Hidden details: full additionalData JSON, toggled by clicking the card.
 */
function buildCard(event) {
    const card = document.createElement("div");
    card.className = "webhook-card webhook-card-" + (event.success ? "ok" : "fail");
    card.setAttribute("data-event-id", event.id);

    // Header.
    const header = document.createElement("div");
    header.className = "webhook-card-header";
    header.innerHTML =
        '<span class="webhook-time">' + fmtTime(event.receivedAt) + '</span>' +
        '<span class="webhook-event-code">' + escapeHtml(event.eventCode || "?") + '</span>' +
        '<span class="webhook-badge ' + (event.success ? "ok" : "fail") + '">' +
            (event.success ? "success" : "refused") + '</span>' +
        '<span class="webhook-psp" title="pspReference">' + escapeHtml(event.pspReference || "—") + '</span>';
    card.appendChild(header);

    // Summary.
    const summary = document.createElement("div");
    summary.className = "webhook-card-summary";
    summary.innerHTML =
        '<span><strong>amount:</strong> ' + escapeHtml(fmtAmount(event.amountValue, event.amountCurrency)) + '</span>' +
        '<span><strong>method:</strong> ' + escapeHtml(event.paymentMethod || "—") + '</span>' +
        '<span><strong>ref:</strong> <code>' + escapeHtml(event.merchantReference || "—") + '</code></span>' +
        // originalReference is populated only for modification events (CAPTURE
        // / CANCELLATION / REFUND / AUTHORISATION_ADJUSTMENT). Showing it makes
        // it obvious which original auth a modification belongs to without
        // having to expand the JSON details panel.
        (event.originalReference
            ? '<span><strong>original:</strong> <code>' + escapeHtml(event.originalReference) + '</code></span>'
            : '') +
        (event.reason ? '<span><strong>reason:</strong> ' + escapeHtml(event.reason) + '</span>' : '');
    card.appendChild(summary);

    // Details (hidden by default).
    const details = document.createElement("pre");
    details.className = "webhook-card-details hidden";
    details.textContent = JSON.stringify(event, null, 2);
    card.appendChild(details);

    // Toggle on click. We attach to the card root so anywhere on it expands.
    card.addEventListener("click", () => {
        details.classList.toggle("hidden");
        card.classList.toggle("expanded");
    });

    return card;
}

/** Tiny HTML-escape so eventCode / refs can't break the DOM. */
function escapeHtml(s) {
    if (s == null) return "";
    return String(s)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

/** Prepends an event card to the list, removing the empty-state hint and
 *  trimming the list to MAX_CARDS to keep memory bounded. */
function prependCard(event) {
    const list = document.getElementById(FEED_LIST_ID);
    if (!list) return;

    // Drop empty-state hint on first real event.
    const empty = list.querySelector("." + EMPTY_STATE_CLASS);
    if (empty) empty.remove();

    const card = buildCard(event);
    list.insertBefore(card, list.firstChild);

    // Trim from the bottom.
    while (list.children.length > MAX_CARDS) {
        list.removeChild(list.lastChild);
    }

    // CSS animates .webhook-card-new for ~600ms then we drop the class.
    card.classList.add("webhook-card-new");
    setTimeout(() => card.classList.remove("webhook-card-new"), 600);
}

// ============================================================================
// === SSE wiring ===========================================================
// ============================================================================

let eventSource = null;

function openStream() {
    setStatus(STATUS.CONNECTING);
    // EventSource auto-reconnects with exponential backoff; we just listen for
    // open / error events to flip the status badge.
    eventSource = new EventSource("/api/webhooks/stream");

    eventSource.addEventListener("open", () => setStatus(STATUS.CONNECTED));

    eventSource.addEventListener("connected", (e) => {
        // Server sends this as a "hello" on connect; nothing to render, but it
        // confirms the badge can flip to "Live".
        setStatus(STATUS.CONNECTED);
        try { console.info("Webhook stream connected:", JSON.parse(e.data)); } catch {}
    });

    eventSource.addEventListener("webhook", (e) => {
        try {
            const event = JSON.parse(e.data);
            if (isEventForThisPage(event)) {
                prependCard(event);
                return;
            }
            // Race: this might be the AUTHORISATION webhook for a preauth we
            // just created, but the next /api/payments-list poll hasn't
            // populated the set yet. Stash it so the 'updated' listener can
            // render it if the set catches up within a second or two.
            pendingEvents.push(event);
            while (pendingEvents.length > PENDING_MAX) pendingEvents.shift();
        } catch (err) {
            console.warn("Failed to parse webhook SSE event", err, e.data);
        }
    });

    eventSource.addEventListener("error", () => {
        // EventSource will retry automatically. We just flip the badge so the
        // user knows the feed might be stale.
        // eventSource.readyState:
        //   0 = CONNECTING (reconnecting after error)
        //   1 = OPEN
        //   2 = CLOSED (permanent failure)
        if (eventSource.readyState === EventSource.CLOSED) {
            setStatus(STATUS.ERROR);
        } else {
            setStatus(STATUS.RECONNECT);
        }
    });
}

// ============================================================================
// === Initial history fetch =================================================
// ============================================================================

async function loadRecent() {
    try {
        const resp = await fetch("/api/webhooks/recent");
        if (!resp.ok) {
            console.warn("Failed to load /api/webhooks/recent:", resp.status);
            return;
        }
        const items = await resp.json();
        if (!Array.isArray(items) || items.length === 0) return;

        // Apply the same per-page filter as live events. Non-matching items
        // are stashed in pendingEvents so they can flow in once the
        // pspReference set catches up (matters on first page load when the
        // /api/payments-list poll might land AFTER us).
        const matched = [];
        for (const ev of items) {
            if (isEventForThisPage(ev)) {
                matched.push(ev);
            } else {
                pendingEvents.push(ev);
            }
        }
        while (pendingEvents.length > PENDING_MAX) pendingEvents.shift();

        if (matched.length === 0) return;

        const list = document.getElementById(FEED_LIST_ID);
        const empty = list && list.querySelector("." + EMPTY_STATE_CLASS);
        if (empty) empty.remove();

        // /recent returns newest-first, but prependCard re-prepends, so to
        // preserve order we render OLDEST first (i.e. iterate reversed).
        for (let i = matched.length - 1; i >= 0; i--) {
            const card = buildCard(matched[i]);
            list.appendChild(card);
        }
    } catch (err) {
        console.warn("loadRecent failed", err);
    }
}

// When the pspReference set updates (every 3s after the polling loop in
// preauthorisationCheckout.js), replay any pending events that now match.
// Newly matched events are inserted at the top with the usual animation —
// they really did arrive recently, just before we knew their owner.
window.addEventListener("preauthKnownPspsUpdated", () => {
    if (pendingEvents.length === 0) return;
    const stillPending = [];
    for (const ev of pendingEvents) {
        if (isEventForThisPage(ev)) {
            prependCard(ev);
        } else {
            stillPending.push(ev);
        }
    }
    pendingEvents.length = 0;
    pendingEvents.push(...stillPending);
});

// ============================================================================
// === Clear button =========================================================
// ============================================================================

function wireClearButton() {
    const button = document.getElementById(FEED_CLEAR_ID);
    if (!button) return;
    button.addEventListener("click", () => {
        const list = document.getElementById(FEED_LIST_ID);
        if (!list) return;
        list.innerHTML =
            '<p class="' + EMPTY_STATE_CLASS + '">List cleared. New events will appear here.</p>';
    });
}

// ============================================================================
// === Bootstrap =============================================================
// ============================================================================

loadRecent().then(openStream);
wireClearButton();
