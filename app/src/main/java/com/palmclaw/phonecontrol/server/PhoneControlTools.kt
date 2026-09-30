package com.palmclaw.phonecontrol.server

import com.palmclaw.phonecontrol.server.ToolRegistry as ApkToolRegistry
import com.palmclaw.tools.Tool
import com.palmclaw.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Bridges the embedded apkmcp tool dispatcher (ported from the standalone APK MCP app)
 * into PalmClaw's Tool interface, so the agent can control the phone directly —
 * no localhost HTTP / MCP hop involved.
 */
fun createPhoneControlToolSet(): List<Tool> =
    ApkToolRegistry.defs.map { def -> PhoneControlTool(def) }

private class PhoneControlTool(
    private val def: ToolDef
) : Tool {
    override val name: String = def.name
    override val description: String = def.description
    override val jsonSchema: JsonObject = def.schema

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = runCatching {
            Json.parseToJsonElement(argumentsJson) as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap())

        val result = runCatching { ApkToolRegistry.call(def.name, args) }
            .getOrElse { t ->
                return@withContext ToolResult(
                    toolCallId = "",
                    content = "phone control error: ${t.message ?: t.javaClass.simpleName}",
                    isError = true
                )
            }

        val content = buildString {
            append(result.text)
            if (result.imageBase64 != null) append("\n[screenshot captured (${result.image64LengthNote()})]")
        }
        ToolResult(toolCallId = "", content = content, isError = result.isError)
    }
}

private fun com.palmclaw.phonecontrol.server.ToolResult.image64LengthNote(): String =
    "image data omitted"
