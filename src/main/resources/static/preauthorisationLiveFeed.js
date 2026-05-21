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
            prependCard(event);
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

        // /recent returns newest-first, but prependCard re-prepends, so to
        // preserve order we render OLDEST first (i.e. iterate reversed).
        const list = document.getElementById(FEED_LIST_ID);
        const empty = list && list.querySelector("." + EMPTY_STATE_CLASS);
        if (empty) empty.remove();

        for (let i = items.length - 1; i >= 0; i--) {
            const card = buildCard(items[i]);
            // For history we don't run the entrance animation.
            list.appendChild(card);
        }
    } catch (err) {
        console.warn("loadRecent failed", err);
    }
}

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
