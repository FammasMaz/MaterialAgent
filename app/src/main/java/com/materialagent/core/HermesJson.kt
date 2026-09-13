package com.materialagent.core

import kotlinx.serialization.json.Json

/** Single JSON configuration for every wire and persisted document. */
val HermesJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}
