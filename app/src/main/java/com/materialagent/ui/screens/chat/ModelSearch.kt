package com.materialagent.ui.screens.chat

import com.materialagent.core.model.ProviderInfo

/** One row of the model picker: `qualified` is what gets sent to the server. */
data class ModelOption(
    val qualified: String,
    val model: String,
    val provider: String,
)

/**
 * The model catalogue as data, split out of the picker so it can be tested
 * without a device.
 *
 * This server advertises a four-figure number of models, and everything here is
 * what runs on every keystroke of the search field. The three rules that are easy
 * to get subtly wrong — which rows match, which one row is "current", and whether
 * a search field is warranted at all — are the ones checked in `ModelSearchTest`.
 */
object ModelSearch {

    /**
     * The reasoning levels this gateway accepts, ascending.
     *
     * `max` is a real level the server reports and was once missing here, which
     * left a session running at max showing no selection at all. It is spelled out
     * as a constant rather than inline at the call site so a test can hold that.
     */
    val REASONING_LEVELS = listOf("minimal", "low", "medium", "high", "max")

    /**
     * Below this many models a search field is furniture: it takes a row of height
     * that the list wants, to filter nothing.
     */
    const val SEARCH_THRESHOLD = 8

    /**
     * Every model the server offers, qualified by its provider.
     *
     * Deduplicated on `qualified` because the result is used as LazyColumn keys,
     * and a single duplicate from the server would crash the list rather than
     * merely draw a row twice.
     */
    fun options(providers: List<ProviderInfo>): List<ModelOption> = providers
        .flatMap { provider ->
            val providerName = provider.name.ifBlank { provider.slug }
            provider.models.map { model ->
                ModelOption("${provider.slug}/$model", model, providerName)
            }
        }
        .distinctBy { it.qualified }

    /**
     * The models to draw for [query], in server order.
     *
     * The needle is matched against the qualified id, which already contains the
     * bare model name, and against the provider's display name — so "anthropic"
     * narrows to that provider and "deepseek-v3.2" narrows to that model.
     */
    fun matches(options: List<ModelOption>, query: String): List<ModelOption> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return options
        return options.filter {
            it.qualified.lowercase().contains(needle) || it.provider.lowercase().contains(needle)
        }
    }

    fun showsSearchField(modelCount: Int): Boolean = modelCount > SEARCH_THRESHOLD

    /**
     * The `qualified` id of the row that should read as current, if any.
     *
     * The server usually reports the qualified id, but it can report the bare model
     * name instead, so both are tried — qualified first, because a bare name is
     * ambiguous across providers and the old rule (test either, per row) marked
     * every provider's copy of the same model as current.
     */
    fun currentSelection(options: List<ModelOption>, current: String?): String? {
        if (current.isNullOrBlank()) return null
        options.firstOrNull { it.qualified == current }?.let { return it.qualified }
        return options.firstOrNull { it.model == current }?.qualified
    }

    /**
     * The pill's label: the model without its provider prefix, since the header has
     * no room for both.
     */
    fun displayName(model: String?): String =
        model?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Select model"
}
