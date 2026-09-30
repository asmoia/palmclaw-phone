/**
 * duck_bridge.js v2 — hardened transport for duck.ai anonymous chat.
 * Injected into the duck.ai WebView by DuckWebViewBridge (Kotlin).
 *
 * Robustness features (each mapped to a failure mode observed in live testing):
 *  [F1] Anonymous rate limiting (418 ERR_CHALLENGE / 429 ERR_RATE_LIMIT)
 *       → pacing between requests + exponential backoff w/ jitter + fresh JSA per retry
 *  [F2] Challenge-solve hiccups (iframe races, CSP) → internal retries inside solveJsa
 *  [F3] Hung requests → AbortController timeout per fetch (chat 150s, status 20s)
 *  [F4] Stream interrupted mid-read → partial text is preserved; if retries also fail,
 *       the partial answer is returned (marked partial:true) instead of a hard error
 *  [F5] Empty model responses → gentle retry
 *  [F6] Concurrent calls (agent loop + heartbeat) → strict serialization queue
 *  [F7] ToS / onboarding dialogs appearing mid-session → MutationObserver auto-dismiss
 *  [F8] Page reloads (bridge lost) → Kotlin detects via __duckPing and re-injects this file
 *  [F9] 4xx (bad request / context too large) → reported with status; Kotlin retries
 *       with progressively truncated history
 *
 * Wire format (validated against live API):
 *   user      {role:"user",      content:[{type:"text",text}]}
 *   assistant {role:"assistant", content:"", parts:[{type:"text",text}]}
 */
(function () {
  if (window.__duckBridgeReady) return;
  window.__duckBridgeReady = true;

  const BRIDGE = window.__duckAndroid; // injected via addJavascriptInterface
  const BRIDGE_VERSION = 2;

  const CHAT_TIMEOUT_MS = 150000;
  const STATUS_TIMEOUT_MS = 20000;
  const MIN_INTERVAL_MS = 4000;   // duck.ai throttles rapid anonymous requests
  const MAX_ATTEMPTS = 5;

  let jsaCounter = 1;
  let currentJsa = null;
  let lastChatAt = 0;
  let queue = Promise.resolve();  // [F6] serialization

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const jitter = (base) => Math.round(base * (0.7 + Math.random() * 0.6));

  function report(callId, obj) {
    try { BRIDGE && BRIDGE.onResult(callId, JSON.stringify(obj)); } catch (e) {}
  }
  function emitDelta(callId, chunk) {
    try { BRIDGE && BRIDGE.onDelta(callId, chunk); } catch (e) {}
  }

  // ── [F7] auto-dismiss ToS / onboarding dialogs ─────────────────────────────
  try {
    const dismiss = () => {
      try {
        const btn = [...document.querySelectorAll('button,[role=button]')].find((b) => {
          const t = (b.textContent || "").trim();
          const r = b.getBoundingClientRect();
          return r.width > 0 && (t === "Continue" || t === "Got It" || t === "Got It!");
        });
        if (btn) btn.click();
      } catch (e) {}
    };
    new MutationObserver(() => dismiss()).observe(document.documentElement, {
      childList: true, subtree: true,
    });
    dismiss();
  } catch (e) {}

  // ── challenge solving ──────────────────────────────────────────────────────
  async function solveJsaOnce(b64) {
    const started = Date.now();
    const code = atob(b64);
    const ifr = document.createElement("iframe");
    ifr.srcdoc =
      '<!DOCTYPE html><html><head><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'unsafe-inline\'"></head><body></body></html>';
    ifr.style.display = "none";
    document.body.appendChild(ifr);
    try {
      await new Promise((r) => { ifr.onload = r; setTimeout(r, 800); });
      const result = await new Promise((resolve, reject) => {
        const id = jsaCounter++;
        window.__jsaCallbacks = window.__jsaCallbacks || {};
        const doc = ifr.contentDocument || (ifr.contentWindow && ifr.contentWindow.document);
        if (!doc || !doc.body) return reject(new Error("iframe doc unavailable"));
        const s = doc.createElement("script");
        s.textContent =
          "try { window.parent.__jsaCallbacks[" + id + "](null, " + code + "); }" +
          " catch (e) { window.parent.__jsaCallbacks[" + id + "](e, null); }";
        window.__jsaCallbacks[id] = (err, res) => {
          if (err) reject(err); else resolve(res);
          delete window.__jsaCallbacks[id];
        };
        doc.body.appendChild(s);
        setTimeout(() => {
          if (window.__jsaCallbacks[id]) {
            delete window.__jsaCallbacks[id];
            reject(new Error("jsa timeout"));
          }
        }, 8000);
      });
      if (typeof result !== "object" || result === null || !("client_hashes" in result)) {
        throw new Error("bad jsa result: " + JSON.stringify(result).slice(0, 140));
      }
      const hashed = await Promise.all(
        (result.client_hashes || []).map(async (h) => {
          const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(h));
          return btoa(String.fromCharCode.apply(null, new Uint8Array(buf)));
        })
      );
      return btoa(JSON.stringify({
        ...result,
        client_hashes: hashed,
        meta: {
          ...(result.meta || {}),
          origin: location.origin,
          stack: (new Error()).stack
            ? (new Error()).stack.split("\n").slice(0, 5).join("\n")
            : "no-stack",
          duration: String(Date.now() - started),
        },
      }));
    } finally {
      ifr.remove();
    }
  }

  async function solveJsa(b64) { // [F2] retry transient solve failures
    let lastErr;
    for (let a = 1; a <= 3; a++) {
      try { return await solveJsaOnce(b64); }
      catch (e) { lastErr = e; await sleep(400 * a); }
    }
    throw lastErr;
  }

  // ── fetch with timeout [F3] ────────────────────────────────────────────────
  async function fetchT(url, opts, ms) {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), ms);
    try { return await fetch(url, { ...opts, signal: ctl.signal }); }
    finally { clearTimeout(t); }
  }

  async function freshJsa() {
    let lastErr;
    for (let a = 1; a <= 2; a++) {
      try {
        const st = await fetchT("/duckchat/v1/status", {
          headers: { "x-vqd-accept": "1", "Cache-Control": "no-store" },
        }, STATUS_TIMEOUT_MS);
        if (!st.ok) throw new Error("status HTTP " + st.status);
        const ch = st.headers.get("x-vqd-hash-1");
        currentJsa = ch ? await solveJsa(ch) : null;
        return currentJsa;
      } catch (e) { lastErr = e; if (a === 2) throw lastErr; await sleep(1500); }
    }
  }

  // ── SSE reading with partial recovery [F4] ────────────────────────────────
  async function readSse(resp, onDelta) {
    const reader = resp.body.getReader();
    const dec = new TextDecoder();
    let buf = "", text = "";
    const consume = (line) => {
      if (!line.startsWith("data:")) return;
      const d = line.replace(/^data: ?/, "").trim();
      if (!d || d === "[DONE]") return;
      let j;
      try { j = JSON.parse(d); } catch { return; }
      if (typeof j.message === "string" && j.message.length) {
        text += j.message;
        if (onDelta) onDelta(j.message);
      } else if (j.action === "error") {
        const err = new Error("stream error: " + JSON.stringify(j).slice(0, 160));
        err.partialText = text;
        throw err;
      }
    };
    while (true) {
      let chunk;
      try { chunk = await reader.read(); }
      catch (e) { e.partialText = text; throw e; }
      if (chunk.done) break;
      buf += dec.decode(chunk.value, { stream: true });
      const lines = buf.split("\n");
      buf = lines.pop();
      for (const line of lines) consume(line);
    }
    if (buf) consume(buf);
    return text;
  }

  // ── one chat attempt ───────────────────────────────────────────────────────
  async function rawChat(model, messages, callId) {
    const wait = MIN_INTERVAL_MS - (Date.now() - lastChatAt); // [F1] pacing
    if (wait > 0) await sleep(wait);
    if (!currentJsa) await freshJsa();

    const r = await fetchT("/duckchat/v1/chat", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "x-vqd-hash-1": currentJsa,
      },
      body: JSON.stringify({
        model: model,
        messages: messages,
        canUseTools: true,
        reasoningEffort: "none",
        canUseApproxLocation: null,
        canDelegateImageGeneration: null,
        canShowGreeting: false,
      }),
    }, CHAT_TIMEOUT_MS);
    lastChatAt = Date.now();

    const next = r.headers.get("x-vqd-hash-1");
    const adoptNext = async () => {
      if (next) { try { currentJsa = await solveJsa(next); } catch (e) { currentJsa = null; } }
    };

    if (r.ok) {
      await adoptNext();
      try {
        return { ok: true, text: await readSse(r, (ch) => emitDelta(callId, ch)) };
      } catch (e) {
        return {
          ok: false, status: 0, type: "STREAM_INTERRUPTED",
          body: String((e && e.message) || e), partial: e.partialText || "",
        };
      }
    }

    const bodyText = await r.text();
    let errType = "";
    try { errType = JSON.parse(bodyText).type || ""; } catch (e) {}
    await adoptNext();
    return { ok: false, status: r.status, type: errType, body: bodyText.slice(0, 200) };
  }

  // ── full retry loop ────────────────────────────────────────────────────────
  async function doChat(callId, model, messages) {
    let lastErr = null;
    let bestPartial = "";
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      let r;
      try {
        r = await rawChat(model, messages, callId);
      } catch (e) { // network / timeout / aborted
        lastErr = "network: " + ((e && e.message) || String(e));
        if (String(lastErr).includes("abort")) lastErr = "timeout: request exceeded " + (CHAT_TIMEOUT_MS / 1000) + "s";
        await sleep(jitter(2500));
        try { currentJsa = null; await freshJsa(); } catch (e2) {}
        continue;
      }

      if (r.ok) {
        if (r.text && r.text.trim()) {
          report(callId, { ok: true, text: r.text });
          return;
        }
        lastErr = "empty response"; // [F5]
        await sleep(jitter(2000));
        continue;
      }

      lastErr = "HTTP " + r.status + (r.type ? " " + r.type : "") + (r.body ? ": " + r.body : "");
      if (r.partial && r.partial.length > bestPartial.length) bestPartial = r.partial;

      if ((r.status === 418 || r.status === 429 || r.status >= 500) && r.type !== "ERR_INPUT_LIMIT") { // [F1]
        await sleep(jitter(Math.min(3000 * Math.pow(2, attempt - 1), 30000)));
        try { currentJsa = null; await freshJsa(); } catch (e2) {}
        continue;
      }
      break; // other 4xx: fatal — let Kotlin decide (e.g. truncate history) [F9]
    }

    if (bestPartial.trim().length > 0) { // [F4] prefer a partial answer over nothing
      report(callId, { ok: true, text: bestPartial, partial: true, note: "stream interrupted; partial answer" });
      return;
    }
    report(callId, { ok: false, error: String(lastErr || "unknown") });
  }

  // ── public entry points ────────────────────────────────────────────────────
  /** Kotlin calls this per request. payloadJson: {callId, model, messages:[…]} */
  window.__duckChat = function (payloadJson) {
    let p;
    try { p = JSON.parse(payloadJson); } catch (e) {
      report("unknown", { ok: false, error: "bridge: bad payload" });
      return;
    }
    const { callId, model, messages } = p;
    queue = queue
      .then(() => doChat(callId, model, messages))
      .catch((e) => report(callId, { ok: false, error: "bridge: " + ((e && e.message) || String(e)) }));
  };

  /** [F8] Kotlin health check: returns version, or undefined if bridge was wiped by a reload. */
  window.__duckPing = function () { return BRIDGE_VERSION; };

  window.__duckAcceptTos = function () {
    try {
      const c = [...document.querySelectorAll('button, [role=button]')]
        .find((e) => (e.textContent || "").trim().startsWith("Continue"));
      if (c) { const r = c.getBoundingClientRect(); if (r.width > 0) c.click(); }
    } catch (e) {}
  };
})();
