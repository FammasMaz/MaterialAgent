package com.materialagent.data

import com.materialagent.core.arr
import com.materialagent.core.obj
import com.materialagent.core.objOrNull
import com.materialagent.core.strOrNull
import com.materialagent.core.model.ProviderInfo
import com.materialagent.core.model.ToolsetInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Read-mostly view of what the agent can do: models, toolsets, skills and MCP
 * servers. All of it is server-owned; this is a cache with a refresh.
 */
class CapabilityRepository(private val connection: HermesConnection) {

    private val _providers = MutableStateFlow<List<ProviderInfo>>(emptyList())
    val providers: StateFlow<List<ProviderInfo>> = _providers.asStateFlow()

    private val _toolsets = MutableStateFlow<List<ToolsetInfo>>(emptyList())
    val toolsets: StateFlow<List<ToolsetInfo>> = _toolsets.asStateFlow()

    private val _skills = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val skills: StateFlow<Map<String, List<String>>> = _skills.asStateFlow()

    private val _mcpServers = MutableStateFlow<List<JsonObject>>(emptyList())
    val mcpServers: StateFlow<List<JsonObject>> = _mcpServers.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val allModels: List<Pair<String, String>>
        get() = _providers.value.flatMap { provider ->
            provider.models.map { model -> "${provider.slug}/$model" to model }
        }

    suspend fun refreshAll() {
        refreshModels()
        refreshTools()
        refreshSkills()
        refreshMcpServers()
    }

    suspend fun refreshModels(): Result<List<ProviderInfo>> =
        connection.send("model.options").fold(
            onSuccess = { payload ->
                val list = payload.arr("providers")
                    ?.mapNotNull { it.objOrNull()?.let(ProviderInfo::from) }
                    .orEmpty()
                _providers.value = list
                _lastError.value = null
                Result.success(list)
            },
            onFailure = { error ->
                _lastError.value = error.message
                Result.failure(error)
            },
        )

    suspend fun refreshTools(): Result<List<ToolsetInfo>> =
        connection.send("tools.list").fold(
            onSuccess = { payload ->
                val list = payload.arr("toolsets")
                    ?.mapNotNull { it.objOrNull()?.let(ToolsetInfo::from) }
                    .orEmpty()
                    .sortedBy { it.name }
                _toolsets.value = list
                Result.success(list)
            },
            onFailure = { Result.failure(it) },
        )

    suspend fun refreshSkills(): Result<Map<String, List<String>>> =
        connection.send("skills.manage").fold(
            onSuccess = { payload ->
                val obj = payload.obj("skills")
                val map = obj?.keys?.associateWith { key ->
                    obj.arr(key)?.mapNotNull { it.strOrNull() }.orEmpty()
                }.orEmpty()
                _skills.value = map
                Result.success(map)
            },
            onFailure = { Result.failure(it) },
        )

    suspend fun refreshMcpServers(): Result<List<JsonObject>> =
        connection.send("mcp.servers.list").fold(
            onSuccess = { payload ->
                val list = (payload.arr("servers") ?: payload.arr("mcp_servers"))
                    ?.mapNotNull { it.objOrNull() }
                    .orEmpty()
                _mcpServers.value = list
                Result.success(list)
            },
            onFailure = { Result.failure(it) },
        )

    /** Enables or disables a toolset server-side. */
    suspend fun setToolsetEnabled(name: String, enabled: Boolean): Result<Unit> =
        connection.send(
            "tools.configure",
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("enabled", JsonPrimitive(enabled))
            },
        ).map { }
}
