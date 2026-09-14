package com.materialagent.core.model

import com.materialagent.core.arr
import com.materialagent.core.doubleOrNull
import com.materialagent.core.longOrNull
import com.materialagent.core.strOrNull
import com.materialagent.core.bool
import com.materialagent.core.int
import com.materialagent.core.long
import com.materialagent.core.obj
import com.materialagent.core.str
import com.materialagent.core.strAny
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A single server push.
 *
 * `params` always carries `type`; `session_id` and `seq` are absent on global
 * events (`sessions.changed`, `gateway.ready`). `seq` is a per-session,
 * in-process counter used for lossless reconnect replay.
 */
data class GatewayEvent(
    val type: String,
    val sessionId: String?,
    val seq: Int?,
    val payload: JsonObject?,
) {
    val text: String? get() = payload.strAny("text")
    val name: String? get() = payload.str("name")
    val toolId: String? get() = payload.strAny("tool_id", "tool_call_id")
    val requestId: String? get() = payload.str("request_id")
    val status: String? get() = payload.str("status")
    val args: JsonObject? get() = payload.obj("args")

    companion object {
        /** Event types that carry assistant answer text. */
        const val MESSAGE_START = "message.start"
        const val MESSAGE_DELTA = "message.delta"
        const val MESSAGE_INTERIM = "message.interim"
        const val MESSAGE_COMPLETE = "message.complete"
        const val REASONING_DELTA = "reasoning.delta"
        const val REASONING_AVAILABLE = "reasoning.available"
        const val THINKING_DELTA = "thinking.delta"
        const val TOOL_GENERATING = "tool.generating"
        const val TOOL_START = "tool.start"
        const val TOOL_COMPLETE = "tool.complete"
        const val TODO_UPDATED = "todo.updated"
        const val STATUS_UPDATE = "status.update"
        const val APPROVAL_REQUEST = "approval.request"
        const val CLARIFY_REQUEST = "clarify.request"
        const val SUDO_REQUEST = "sudo.request"
        const val SECRET_REQUEST = "secret.request"
        const val SESSION_INFO = "session.info"
        const val SESSION_TITLE = "session.title"
        const val SESSION_USAGE = "session.usage"
        const val SESSIONS_CHANGED = "sessions.changed"
        const val SESSION_RECLAIMED = "session.reclaimed"
        const val TURN_ERROR = "turn.error"
        const val GATEWAY_READY = "gateway.ready"
        const val SKIN_CHANGED = "skin.changed"
        const val BACKGROUND_COMPLETE = "background.complete"
        const val ERROR = "error"
    }
}

/**
 * The server's own identity, sent once on `gateway.ready`.
 *
 * `colors` is the terminal skin's palette — the Hermes gold-on-navy voice.
 * MaterialAgent surfaces it as an optional "Hermes skin" theme so the app can
 * wear the server's colours instead of the wallpaper's.
 */
data class Skin(
    val name: String,
    val colors: Map<String, String>,
    val branding: Branding,
) {
    /** Accent the skin uses for interactive chrome. */
    val accent: String? get() = colors["ui_accent"] ?: colors["banner_accent"]

    companion object {
        fun from(payload: JsonObject?): Skin? {
            val skinObj = payload.obj("skin") ?: return null
            val colors = skinObj.obj("colors")?.let { raw ->
                raw.keys.mapNotNull { key -> raw.str(key)?.let { key to it } }.toMap()
            }.orEmpty()
            val brandingObj = skinObj.obj("branding")
            return Skin(
                name = skinObj.str("name") ?: "default",
                colors = colors,
                branding = Branding(
                    agentName = brandingObj.str("agent_name") ?: "Hermes Agent",
                    welcome = brandingObj.str("welcome").orEmpty(),
                    responseLabel = brandingObj.str("response_label").orEmpty(),
                    promptSymbol = brandingObj.str("prompt_symbol") ?: "❯",
                ),
            )
        }
    }
}

data class Branding(
    val agentName: String,
    val welcome: String,
    val responseLabel: String,
    val promptSymbol: String,
)

/** One row of `session.list` — a durable, stored conversation. */
data class SessionSummary(
    val id: String,
    val title: String,
    val preview: String,
    val startedAt: Double,
    val messageCount: Int,
    val source: String,
) {
    companion object {
        fun from(obj: JsonObject): SessionSummary? {
            val id = obj.str("id") ?: return null
            return SessionSummary(
                id = id,
                title = obj.str("title").orEmpty().ifBlank { "Untitled session" },
                preview = obj.str("preview").orEmpty(),
                startedAt = obj.get("started_at")?.let { it.doubleOrNull() } ?: 0.0,
                messageCount = obj.int("message_count") ?: 0,
                source = obj.str("source") ?: "unknown",
            )
        }
    }

    /** `cron` sessions are automations, not conversations the user started. */
    val isAutomation: Boolean get() = source == "cron" || id.startsWith("cron_")

    /**
     * The cron job that produced this run, if any.
     *
     * Hermes names a scheduled run `cron_<job>_<yyyymmdd>_<hhmmss>`, so one job
     * leaves a trail of sessions that look identical in a list. The job id is the
     * only thing that ties them together, and it is what lets the inbox fold
     * forty runs into one row per automation.
     */
    val cronJobId: String?
        get() {
            if (!isAutomation) return null
            val body = id.removePrefix("cron_")
            // Trailing `_yyyyMMdd_HHmmss`; drop it and what remains is the job.
            val stamp = Regex("_\\d{8}_\\d{6}$")
            return stamp.find(body)?.let { body.substring(0, it.range.first) }?.ifBlank { null }
        }

    /** Which run this belongs to. Cron jobs group by job; everything else by source. */
    val groupKey: String get() = cronJobId?.let { "cron:$it" } ?: "source:$source"

    /**
     * The name shown on a group header.
     *
     * A scheduled run's title is `<job name> · <when>`, so the job's own name is
     * already in the row — better than the raw job id, which is a hash.
     */
    val groupLabel: String
        get() = when {
            isAutomation -> title.substringBefore(" · ").ifBlank { "Automation" }
            source == "telegram" -> "Telegram"
            source == "cli" -> "Terminal"
            source == "desktop" -> "Desktop"
            source == "tui" -> "Terminal"
            source == "mobile" -> "This app"
            else -> source.replaceFirstChar { it.uppercase() }
        }
}

/** Live session runtime state, pushed as `session.info` after every turn. */
data class SessionInfo(
    val model: String?,
    val provider: String?,
    val reasoningEffort: String?,
    val serviceTier: String?,
    val fast: Boolean,
    val yolo: Boolean,
    val approvalMode: String?,
    val cwd: String?,
    val branch: String?,
    val title: String?,
    val storedSessionId: String?,
    val profileName: String?,
    val personality: String?,
    val running: Boolean,
    val turnStartedAt: Double?,
    val version: String?,
    val usage: Usage?,
    val tools: Map<String, List<String>>,
    val skills: Map<String, List<String>>,
    val mcpServers: JsonArray?,
) {
    companion object {
        fun from(payload: JsonObject?): SessionInfo? {
            if (payload == null) return null
            return SessionInfo(
                model = payload.str("model"),
                provider = payload.str("provider"),
                reasoningEffort = payload.str("reasoning_effort"),
                serviceTier = payload.str("service_tier"),
                fast = payload.bool("fast") ?: false,
                yolo = payload.bool("yolo") ?: false,
                approvalMode = payload.str("approval_mode"),
                cwd = payload.str("cwd"),
                branch = payload.str("branch"),
                title = payload.str("title"),
                storedSessionId = payload.str("stored_session_id"),
                profileName = payload.str("profile_name"),
                personality = payload.str("personality"),
                running = payload.bool("running") ?: false,
                turnStartedAt = payload.get("turn_started_at")?.let { it.doubleOrNull() },
                version = payload.str("version"),
                usage = Usage.from(payload.obj("usage")),
                tools = payload.obj("tools")?.let { toolMap ->
                    toolMap.keys.associateWith { key ->
                        toolMap.arr(key)?.mapNotNull { it.strOrNull() }.orEmpty()
                    }
                }.orEmpty(),
                skills = payload.obj("skills")?.let { skillMap ->
                    skillMap.keys.associateWith { key ->
                        skillMap.arr(key)?.mapNotNull { it.strOrNull() }.orEmpty()
                    }
                }.orEmpty(),
                mcpServers = payload.arr("mcp_servers"),
            )
        }
    }

    val toolCount: Int get() = tools.values.sumOf { it.size }
    val skillCount: Int get() = skills.values.sumOf { it.size }
}

/** Token accounting for the current context window. */
data class Usage(
    val model: String?,
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val total: Long,
    val calls: Int,
    val contextUsed: Long,
    val contextMax: Long,
    val contextPercent: Int,
    val cacheHitPercent: Int?,
    val avgTps: Double?,
    val avgLatencyS: Double?,
    val compressions: Int,
    val activeSubagents: Int,
) {
    val contextFraction: Float
        get() = if (contextMax <= 0) 0f else (contextUsed.toFloat() / contextMax).coerceIn(0f, 1f)

    companion object {
        fun from(obj: JsonObject?): Usage? {
            if (obj == null) return null
            return Usage(
                model = obj.str("model"),
                input = obj.long("input") ?: 0L,
                output = obj.long("output") ?: 0L,
                reasoning = obj.long("reasoning") ?: 0L,
                total = obj.long("total") ?: 0L,
                calls = obj.int("calls") ?: 0,
                contextUsed = obj.long("context_used") ?: 0L,
                contextMax = obj.long("context_max") ?: 0L,
                contextPercent = obj.int("context_percent") ?: 0,
                cacheHitPercent = obj.int("cache_hit_pct"),
                avgTps = obj.get("avg_tps")?.let { it.doubleOrNull() },
                avgLatencyS = obj.get("avg_latency_s")?.let { it.doubleOrNull() },
                compressions = obj.int("compressions") ?: 0,
                activeSubagents = obj.int("active_subagents") ?: 0,
            )
        }
    }
}

/** A provider row from `model.options`. */
data class ProviderInfo(
    val slug: String,
    val name: String,
    val isCurrent: Boolean,
    val models: List<String>,
    val authenticated: Boolean,
    val authType: String?,
    val warning: String?,
) {
    companion object {
        fun from(obj: JsonObject): ProviderInfo? {
            val slug = obj.str("slug") ?: return null
            return ProviderInfo(
                slug = slug,
                name = obj.str("name") ?: slug,
                isCurrent = obj.bool("is_current") ?: false,
                models = obj.arr("models")?.mapNotNull { it.strOrNull() }.orEmpty(),
                authenticated = obj.bool("authenticated") ?: true,
                authType = obj.str("auth_type"),
                warning = obj.str("warning"),
            )
        }
    }
}

/** A toolset row from `tools.list`. */
data class ToolsetInfo(
    val name: String,
    val description: String,
    val toolCount: Int,
    val enabled: Boolean,
    val tools: List<String>,
) {
    companion object {
        fun from(obj: JsonObject): ToolsetInfo? {
            val name = obj.str("name") ?: return null
            return ToolsetInfo(
                name = name,
                description = obj.str("description").orEmpty(),
                toolCount = obj.int("tool_count") ?: 0,
                enabled = obj.bool("enabled") ?: false,
                tools = obj.arr("tools")?.mapNotNull { it.strOrNull() }.orEmpty(),
            )
        }
    }
}

/** A durable transcript row from `session.history` / `session.resume`. */
data class HistoryRow(
    val role: String,
    val text: String?,
    val toolName: String?,
    val toolContext: String?,
    val toolArgs: JsonObject?,
    val timestamp: Double?,
    val rowId: Long?,
) {
    companion object {
        fun from(obj: JsonObject): HistoryRow? {
            val role = obj.str("role") ?: return null
            return HistoryRow(
                role = role,
                text = obj.str("text"),
                toolName = obj.str("name"),
                toolContext = obj.str("context"),
                toolArgs = obj.obj("args"),
                timestamp = obj.get("timestamp")?.let { it.doubleOrNull() },
                rowId = obj.long("row_id"),
            )
        }
    }
}
