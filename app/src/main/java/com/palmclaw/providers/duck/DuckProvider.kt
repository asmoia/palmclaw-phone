package com.palmclaw.providers.duck

import com.palmclaw.providers.AssistantMessage
import com.palmclaw.providers.ChatMessage
import com.palmclaw.providers.LlmProvider
import com.palmclaw.providers.LlmResponse
import com.palmclaw.providers.LlmStreamEvent
import com.palmclaw.providers.ToolCall
import com.palmclaw.providers.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * DuckProvider — free, no-login LLM through duck.ai inside a hidden WebView.
 *
 * RELIABILITY ARCHITECTURE (three layers, each validated against the live API):
 *
 *   Layer 1 — duck.ai (gpt-5.6-luna, the only model available anonymously):
 *     • few-shot tool protocol instructions (~75% direct tool-call compliance)
 *     • pacing + exponential backoff + fresh JSA challenges (in duck_bridge.js)
 *     • progressive history truncation on 400/context errors
 *   Layer 2 — refusal escalation:
 *     • if the model answers "I can't do that" while tools were offered,
 *       one corrective turn is appended ("the tools are real — emit the JSON")
 *   Layer 3 — Cloudflare Worker fallback (OpenAI-compatible, NATIVE tool calls):
 *     • when duck fails hard (rate-limit storm, downtime, persistent refusal)
 *
 * Context management (duck forgets long conversations):
 *   history window + per-message clipping + whitespace squeezing.
 */
class DuckProvider(
    private val model: String,
    private val client: OkHttpClient? = null,
    context: android.content.Context? = null
) : LlmProvider {

    companion object {
        const val DEFAULT_MODEL = "gpt-5.6-luna"

        /** Only gpt-5.6-luna is reachable anonymously (sol/terra/gpt-5.1/4o-mini → 404). */
        val SUPPORTED_MODELS = listOf("gpt-5.6-luna")

        private const val MAX_HISTORY_MESSAGES = 16
        private const val MAX_TOOL_RESULT_CHARS = 5000
        private const val MAX_MESSAGE_CHARS = 8000
        /** Total wire-text budget per request — duck.ai rejects oversized input
         *  with 429 ERR_INPUT_LIMIT (validated: ~8-11k chars pass, 20k fails). */
        private const val MAX_TOTAL_CHARS = 9000

        // Layer 3 — OpenAI-compatible fallback (user's own Cloudflare Worker).
        // Native tool calling; swap these constants if the endpoint changes.
        private const val FALLBACK_BASE_URL =
            "https://palmclaw-ai.heminacearbi.workers.dev/v1/chat/completions"
        private const val FALLBACK_API_KEY = "sk-palmclaw-9k2m4xq7tv"
        private const val FALLBACK_MODEL = "llama-3.3-70b"

        private val REFUSAL_MARKERS = listOf(
            "نمی‌توانم", "نمی‌تونم", "نمی توانم", "نمی تونم", "دسترسی ندارم", "ندارم دسترسی",
            "در دسترس نیست", "امکان باز", "امکان انجام", "قادر نیستم", "دسترسی به",
            "can't", "cannot", "can not", "unable to", "not able to", "don't have access",
            "do not have access", "no access", "won't be able", "cannot access", "i'm unable"
        )
    }

    init {
        context?.let { DuckWebViewBridge.init(it) }
    }

    // ── LlmProvider ──────────────────────────────────────────────────────────

    override suspend fun chat(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): LlmResponse {
        duckChatWithRetries(messages, toolsSpec)?.let { return it }
        // Layer 3 (network — always off the main thread)
        withContext(Dispatchers.IO) { fallbackChat(messages, toolsSpec) }?.let { return it }
        throw IOException("Duck.ai unavailable and fallback failed — try again in a minute")
    }

    override fun chatStream(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): Flow<LlmStreamEvent> = channelFlow {
        // Live deltas from duck (Layer 1); the Final event is authoritative —
        // if duck refuses or fails, escalation/fallback replace the outcome.
        val wire = buildRequest(messages, toolsSpec)
        val result = runCatching {
            DuckWebViewBridge.chat(model, wire.first.toString()) { chunk ->
                trySend(LlmStreamEvent.DeltaText(chunk))
            }
        }.getOrNull()

        if (result != null && result.optBoolean("ok")) {
            val text = result.optString("text")
            val parsed = parseAssistantMessage(text)
            if (parsed.assistant.toolCalls.isNotEmpty() ||
                toolsSpec.isEmpty() ||
                !looksLikeRefusal(text)
            ) {
                send(LlmStreamEvent.Final(parsed))
                close(); return@channelFlow
            }
            // Layer 2 — refusal while tools were offered: escalate
            val escalated = escalate(wire.second, wire.first, messages, toolsSpec)
            if (escalated != null) {
                send(LlmStreamEvent.Final(escalated))
                close(); return@channelFlow
            }
        }

        // Layer 3 — fallback with native tool calls
        val fb = withContext(Dispatchers.IO) { runCatching { fallbackChat(messages, toolsSpec) }.getOrNull() }
        if (fb != null) {
            send(LlmStreamEvent.Final(fb))
        } else {
            send(LlmStreamEvent.Error(
                "Duck.ai ${result?.optString("error") ?: "failed"} and fallback unavailable"
            ))
        }
        close()
    }

    // ── Layer 1 + 2: duck with retries ───────────────────────────────────────

    private suspend fun duckChatWithRetries(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): LlmResponse? {
        // Ladder: shrink history window AND total char budget on context errors.
        val budgets = intArrayOf(MAX_TOTAL_CHARS, 6000, 4000, 2500)
        var bestPlainAnswer: LlmResponse? = null
        var lastError: String? = null

        for (budget in budgets) {
            val (wireMessages, instructions) = buildRequest(messages, toolsSpec, maxTotalChars = budget)
            val result = runCatching { DuckWebViewBridge.chat(model, wireMessages.toString()) }.getOrNull()
                ?: return bestPlainAnswer // bridge hard-crashed → layers below

            if (result.optBoolean("ok")) {
                val text = result.optString("text")
                if (text.isBlank()) { lastError = "empty response"; continue }
                val parsed = parseAssistantMessage(text)
                if (parsed.assistant.toolCalls.isNotEmpty()) return parsed
                if (toolsSpec.isEmpty() || !looksLikeRefusal(text)) return parsed
                // refusal → Layer 2
                val escalated = escalate(instructions, wireMessages, messages, toolsSpec)
                if (escalated != null) {
                    if (escalated.assistant.toolCalls.isNotEmpty()) return escalated
                    if (bestPlainAnswer == null) bestPlainAnswer = escalated
                }
                if (bestPlainAnswer == null) bestPlainAnswer = parsed
                break // refusal won't improve with truncation
            }

            val err = result.optString("error", "unknown error")
            lastError = err
            val recoverable = err.contains("HTTP 400") ||
                err.contains("ERR_BAD_REQUEST", ignoreCase = true) ||
                err.contains("ERR_INPUT_LIMIT", ignoreCase = true) ||
                err.contains("empty response") ||
                err.contains("context", ignoreCase = true) ||
                err.contains("too long", ignoreCase = true) ||
                err.contains("too large", ignoreCase = true)
            if (!recoverable) break
        }

        // duck exhausted: try fallback before giving up (chat() calls it next),
        // but if fallback is unavailable the caller sees the duck error.
        lastError?.let { lastDuckError = it }
        return bestPlainAnswer // may be null → chat() proceeds to Layer 3
    }

    @Volatile private var lastDuckError: String? = null

    /** Layer 2 — one corrective turn after a refusal. Returns parsed response or null. */
    private suspend fun escalate(
        instructions: String,
        wireMessagesSoFar: JSONArray,
        originalMessages: List<ChatMessage>,
        toolsSpec: List<ToolSpec>
    ): LlmResponse? {
        val lastUser = originalMessages.lastOrNull { it.role.equals("user", true) }?.content ?: return null
        val escalated = JSONArray()
        for (i in 0 until wireMessagesSoFar.length()) escalated.put(wireMessagesSoFar[i])
        escalated.put(org.json.JSONObject()
            .put("role", "user")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put(
                "text",
                "CORRECTION: The tools ARE real and are executed on the user's device by the agent runtime. " +
                    "Never refuse a phone action. Reply ONLY with the tool_calls JSON for this request: \"$lastUser\""
            ))))
        val result = runCatching { DuckWebViewBridge.chat(model, escalated.toString()) }.getOrNull() ?: return null
        if (!result.optBoolean("ok")) return null
        val text = result.optString("text")
        if (text.isBlank()) return null
        return parseAssistantMessage(text)
    }

    private fun looksLikeRefusal(text: String): Boolean {
        val lower = text.lowercase()
        return REFUSAL_MARKERS.any { lower.contains(it.lowercase()) }
    }

    // ── Layer 3: Cloudflare Worker fallback (native tools) ────────────────────

    private fun fallbackChat(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): LlmResponse? {
        val ok = client ?: return null
        val body = JSONObject().apply {
            put("model", FALLBACK_MODEL)
            put("temperature", 0.2)
            put("max_tokens", 4096)
            put("messages", fallbackMessages(messages, toolsSpec))
            if (toolsSpec.isNotEmpty()) {
                val tools = JSONArray()
                for (t in toolsSpec) {
                    tools.put(JSONObject().put("type", "function").put("function", JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("parameters", t.parameters)))
                }
                put("tools", tools)
            }
        }
        val req = Request.Builder()
            .url(FALLBACK_BASE_URL)
            .addHeader("Authorization", "Bearer $FALLBACK_API_KEY")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            ok.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val parsed = JSONObject(resp.body?.string() ?: return@runCatching null)
                val msg = parsed.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                val toolCalls = mutableListOf<ToolCall>()
                msg.optJSONArray("tool_calls")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val tc = arr.getJSONObject(i)
                        val fn = tc.getJSONObject("function")
                        toolCalls.add(ToolCall(
                            id = tc.optString("id", "fb_$i"),
                            name = fn.optString("name"),
                            argumentsJson = fn.optString("arguments", "{}")
                        ))
                    }
                }
                LlmResponse(assistant = AssistantMessage(
                    content = msg.optString("content"),
                    toolCalls = toolCalls
                ))
            }
        }.getOrNull()
    }

    /** Maps (possibly duck text-protocol) history into OpenAI messages for the fallback. */
    private fun fallbackMessages(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): JSONArray {
        val out = JSONArray()
        if (toolsSpec.isNotEmpty()) {
            out.put(JSONObject().put("role", "system").put(
                "content",
                "You are a phone-control agent on the user's Android device. Use the provided tools for any phone action instead of refusing."
            ))
        }
        for (m in messages.takeLast(MAX_HISTORY_MESSAGES)) {
            when (m.role.lowercase()) {
                "system" -> out.put(JSONObject().put("role", "system").put("content", clip(m.content, 30000)))
                "user" -> out.put(JSONObject().put("role", "user").put("content", clip(m.content, 30000)))
                "assistant" -> {
                    val rendered = buildString {
                        append(clip(m.content))
                        m.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                            if (isNotEmpty()) append("\n")
                            append("Tool calls made: ")
                            append(JSONArray(calls.map { renderToolCall(it) }).toString())
                        }
                    }
                    if (rendered.isNotBlank()) out.put(JSONObject().put("role", "assistant").put("content", rendered))
                }
                "tool" -> out.put(JSONObject()
                    .put("role", "user")
                    .put("content", "TOOL RESULT" + (m.toolCallId?.let { " (call $it)" } ?: "") + ":\n"
                        + clip(m.content, 10000)))
            }
        }
        return out
    }

    // ── wire format for duck ──────────────────────────────────────────────────

    /**
     * Converts PalmClaw ChatMessages into duck.ai wire format.
     * Budget-aware: messages are included NEWEST-first until [maxTotalChars]
     * is exhausted, then emitted chronologically — so a bloated history can
     * never trigger duck's ERR_INPUT_LIMIT, and the most recent (most
     * relevant) turns always survive.
     */
    internal fun buildRequest(
        messages: List<ChatMessage>,
        toolsSpec: List<ToolSpec>,
        maxHistory: Int = MAX_HISTORY_MESSAGES,
        maxTotalChars: Int = MAX_TOTAL_CHARS
    ): Pair<JSONArray, String> {
        val instructions = buildToolInstructions(toolsSpec)
        val kept = messages.takeLast(maxHistory.coerceAtLeast(1))

        val included = mutableListOf<JSONObject>()
        var budget = maxTotalChars - instructions.length

        // newest → oldest inclusion
        for (m in kept.asReversed()) {
            val perMsgCap = if (included.isEmpty()) budget.coerceAtLeast(500) else MAX_MESSAGE_CHARS
            val j: JSONObject? = when (m.role.lowercase()) {
                "system" -> userMsg("SYSTEM INSTRUCTIONS (always obey):\n" + clip(m.content, perMsgCap))
                "user" -> userMsg(clip(m.content, perMsgCap))
                "assistant" -> {
                    val rendered = buildString {
                        if (m.content.isNotBlank()) append(clip(m.content, perMsgCap))
                        m.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                            if (isNotEmpty()) append("\n\n")
                            append("I requested tool calls:\n")
                            append(JSONArray(calls.map { renderToolCall(it) }).toString())
                        }
                    }
                    if (rendered.isNotBlank()) assistantMsg(rendered) else null
                }
                "tool" -> userMsg(
                    "TOOL RESULT" + (m.toolCallId?.let { " (call $it)" } ?: "") + ":\n"
                        + clip(m.content, minOf(MAX_TOOL_RESULT_CHARS, perMsgCap))
                )
                else -> null
            }
            if (j == null) continue
            val size = j.toString().length
            if (included.isNotEmpty() && budget - size < 0) break // older messages won't fit
            included.add(j)
            budget -= size
        }

        val out = JSONArray()
        if (instructions.isNotBlank()) out.put(userMsg(instructions))
        included.asReversed().forEach { out.put(it) } // back to chronological order
        return out to instructions
    }

    private fun clip(s: String, max: Int = MAX_MESSAGE_CHARS): String {
        var t = s
        if (t.length > 2000) {
            t = t.replace(Regex("[ \\t]{3,}"), "  ").replace(Regex("\\n{3,}"), "\n\n")
        }
        return if (t.length <= max) t else t.take(max) + "\n…[truncated, ${t.length - max} chars omitted]"
    }

    private fun userMsg(text: String) = JSONObject()
        .put("role", "user")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))

    private fun assistantMsg(text: String) = JSONObject()
        .put("role", "assistant")
        .put("content", "")
        .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", text)))

    private fun renderToolCall(c: ToolCall): JSONObject = JSONObject()
        .put("name", c.name)
        .put("arguments", runCatching { JSONObject(c.argumentsJson) }.getOrDefault(JSONObject()))

    // ── few-shot tool protocol (validated wording — see ab tests) ─────────────

    private fun buildToolInstructions(tools: List<ToolSpec>): String {
        if (tools.isEmpty()) return ""
        val arr = JSONArray()
        for (t in tools) {
            arr.put(JSONObject()
                .put("name", t.name)
                .put("description", t.description)
                .put("parameters", t.parameters))
        }
        return """You are a phone-control agent. You control the user's Android phone through REAL tools that the agent runtime executes. This is not a simulation.

Available tools (JSON Schema):
$arr

To call tools, reply with ONLY this JSON (no markdown, no extra text):
{"tool_calls":[{"name":"<tool>","arguments":{...}}]}
Otherwise reply with plain text.

Examples of correct behavior:
User: Open Chrome.
Assistant: {"tool_calls":[{"name":"launch_app","arguments":{"app":"Chrome"}}]}
User: What's on my screen?
Assistant: {"tool_calls":[{"name":"get_ui_tree","arguments":{}}]}
User: TOOL RESULT: {"apps":["Chrome","Telegram"],"current":"home"}
Assistant: You are on the home screen with Chrome and Telegram visible.
User: What is 2+2?
Assistant: 4

Rules: ALWAYS use a tool for phone actions instead of claiming you cannot. Never fabricate tool results. After a TOOL RESULT, continue the task."""
    }

    // ── response parsing ──────────────────────────────────────────────────────

    internal fun parseAssistantMessage(text: String): LlmResponse {
        val call = extractToolCalls(text)
        return if (call != null) {
            LlmResponse(assistant = AssistantMessage(content = "", toolCalls = call))
        } else {
            LlmResponse(assistant = AssistantMessage(content = text))
        }
    }

    private fun extractToolCalls(text: String): List<ToolCall>? {
        val cleaned = text.trim()
        wholeParse(cleaned)?.let { return it }
        var idx = cleaned.indexOf("\"tool_calls\"")
        while (idx >= 0) {
            val open = cleaned.lastIndexOf('{', idx)
            if (open >= 0) {
                val candidate = balancedFrom(cleaned, open)
                if (candidate != null) wholeParse(candidate)?.let { return it }
            }
            idx = cleaned.indexOf("\"tool_calls\"", idx + 1)
        }
        return null
    }

    private fun wholeParse(candidate: String): List<ToolCall>? {
        val parsed = runCatching { JSONObject(candidate) }.getOrNull() ?: return null
        val calls = parsed.optJSONArray("tool_calls") ?: return null
        val out = mutableListOf<ToolCall>()
        for (i in 0 until calls.length()) {
            val c = calls.optJSONObject(i) ?: continue
            val name = c.optString("name").trim()
            if (name.isEmpty()) continue
            val args = c.opt("arguments") ?: JSONObject()
            val argsJson = when (args) {
                is JSONObject -> args.toString()
                is String -> runCatching { JSONObject(args).toString() }.getOrDefault(args)
                else -> "{}"
            }
            out.add(ToolCall(id = "duck_${System.nanoTime()}_${i}", name = name, argumentsJson = argsJson))
        }
        return out.ifEmpty { null }
    }

    private fun balancedFrom(s: String, open: Int): String? {
        var depth = 0
        var inString = false
        var escape = false
        for (i in open until s.length) {
            val ch = s[i]
            if (escape) { escape = false; continue }
            when {
                ch == '\\' && inString -> escape = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth++
                !inString && ch == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(open, i + 1)
                }
            }
        }
        return null
    }
}
