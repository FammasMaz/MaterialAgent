package com.materialagent.ui.screens.chat

import com.materialagent.core.model.ProviderInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model catalogue rules, tested where they can be.
 *
 * The picker itself is Compose, but every decision that can be *wrong* without
 * looking wrong is a pure function here: which rows a query keeps, which row reads
 * as current, whether a search field is worth its height, and whether the
 * reasoning list still offers `max`. `max` in particular is a regression that has
 * happened once already — a session running at max showed no selection at all.
 */
class ModelSearchTest {

    private fun provider(
        slug: String,
        name: String = "",
        models: List<String> = emptyList(),
    ) = ProviderInfo(
        slug = slug,
        name = name,
        isCurrent = false,
        models = models,
        authenticated = true,
        authType = null,
        warning = null,
    )

    private val catalogue = listOf(
        provider("openai", "OpenAI", listOf("gpt-5.6-luna", "gpt-5", "deepseek-v3.2-exp-thinking")),
        provider("anthropic", "Anthropic", listOf("claude-opus-4", "gpt-5")),
    )

    @Test
    fun everyModelIsQualifiedByItsProviderAndNamedByItsDisplayName() {
        val options = ModelSearch.options(catalogue)
        val luna = options.first { it.model == "gpt-5.6-luna" }
        assertEquals("openai/gpt-5.6-luna", luna.qualified)
        assertEquals("OpenAI", luna.provider)

        // A provider with no display name falls back to its slug rather than
        // drawing a blank supporting label.
        val bare = ModelSearch.options(listOf(provider("local", models = listOf("qwen3"))))
        assertEquals("local", bare.single().provider)
    }

    @Test
    fun aDuplicateModelFromTheServerDoesNotBecomeTwoRows() {
        // These strings are the LazyColumn keys, so a duplicate is not merely
        // untidy: it is an "key was already used" crash the moment the list lays out.
        val repeated = listOf(
            provider("openai", "OpenAI", listOf("gpt-5", "gpt-5", "gpt-5.6-luna")),
        )
        val options = ModelSearch.options(repeated)
        assertEquals(2, options.size)
        assertEquals(options.size, options.distinctBy { it.qualified }.size)
    }

    @Test
    fun anEmptyQueryKeepsTheWholeCatalogue() {
        val options = ModelSearch.options(catalogue)
        assertEquals(options, ModelSearch.matches(options, ""))
        assertEquals(options, ModelSearch.matches(options, "   "))
    }

    @Test
    fun theQueryMatchesTheModelTheProviderAndIsCaseInsensitive() {
        val options = ModelSearch.options(catalogue)
        assertEquals(
            listOf("openai/gpt-5.6-luna"),
            ModelSearch.matches(options, "LUNA").map { it.qualified },
        )
        // Matched on the provider's display name, in server order.
        assertEquals(
            listOf("openai/gpt-5.6-luna", "openai/gpt-5", "openai/deepseek-v3.2-exp-thinking"),
            ModelSearch.matches(options, "openai").map { it.qualified },
        )
        assertEquals(
            listOf("anthropic/claude-opus-4", "anthropic/gpt-5"),
            ModelSearch.matches(options, "  Anthropic ").map { it.qualified },
        )
        assertTrue(ModelSearch.matches(options, "nothing-like-this").isEmpty())
    }

    @Test
    fun aSmallCatalogueGetsNoSearchField() {
        // Eight models fit in a panel without help; a field would only take the
        // height the rows want.
        assertFalse(ModelSearch.showsSearchField(0))
        assertFalse(ModelSearch.showsSearchField(ModelSearch.SEARCH_THRESHOLD))
        assertTrue(ModelSearch.showsSearchField(ModelSearch.SEARCH_THRESHOLD + 1))
        assertTrue(ModelSearch.showsSearchField(1485))
    }

    @Test
    fun theCurrentRowIsTheQualifiedMatchAndNotItsNameTwins() {
        val options = ModelSearch.options(catalogue)
        // Qualified: exactly one row, even though two providers offer gpt-5.
        assertEquals("openai/gpt-5", ModelSearch.currentSelection(options, "openai/gpt-5"))
        assertEquals("anthropic/gpt-5", ModelSearch.currentSelection(options, "anthropic/gpt-5"))

        // Bare name, which is the other shape the server reports: the first match
        // wins rather than every provider's copy being marked current.
        assertEquals("openai/gpt-5", ModelSearch.currentSelection(options, "gpt-5"))

        assertNull(ModelSearch.currentSelection(options, null))
        assertNull(ModelSearch.currentSelection(options, ""))
        assertNull(ModelSearch.currentSelection(options, "codex/not-in-the-list"))
    }

    @Test
    fun theHeaderShowsTheModelWithoutItsProviderPrefix() {
        assertEquals("gpt-5.6-luna", ModelSearch.displayName("openai/gpt-5.6-luna"))
        assertEquals("gpt-5.6-luna", ModelSearch.displayName("gpt-5.6-luna"))
        assertEquals("Select model", ModelSearch.displayName(null))
        assertEquals("Select model", ModelSearch.displayName(""))
        assertEquals("Select model", ModelSearch.displayName("openai/"))
    }

    @Test
    fun maxIsStillOfferedAsAReasoningLevel() {
        // Regression guard: this gateway runs sessions at `max`, and a picker
        // without it shows nothing selected for those sessions.
        assertTrue(ModelSearch.REASONING_LEVELS.contains("max"))
        assertEquals(ModelSearch.REASONING_LEVELS.distinct(), ModelSearch.REASONING_LEVELS)
        assertEquals("minimal", ModelSearch.REASONING_LEVELS.first())
    }
}
