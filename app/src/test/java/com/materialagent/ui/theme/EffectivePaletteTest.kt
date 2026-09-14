package com.materialagent.ui.theme

import com.materialagent.data.PaletteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette in force, off-device.
 *
 * Both fallbacks used to be decided in two places — inside the theme, and again (by
 * not deciding at all) in the Settings tray, which highlighted whatever was stored.
 * Dynamic colour is the default for a fresh install, so on a device that cannot
 * honour it the tray could highlight "Dynamic" while the app wore Hermes. The rules
 * are small enough to pin down here rather than on an emulator.
 */
class EffectivePaletteTest {

    @Test
    fun dynamicFallsBackWhenThePlatformCannotHonourIt() {
        assertEquals(
            PaletteMode.HERMES,
            effectivePalette(PaletteMode.DYNAMIC, dynamicSupported = false, hasSkin = false),
        )
    }

    @Test
    fun dynamicIsKeptWhereItWorks() {
        assertEquals(
            PaletteMode.DYNAMIC,
            effectivePalette(PaletteMode.DYNAMIC, dynamicSupported = true, hasSkin = false),
        )
        assertEquals(
            PaletteMode.DYNAMIC,
            effectivePalette(PaletteMode.DYNAMIC, dynamicSupported = true, hasSkin = true),
        )
    }

    @Test
    fun aServerSkinWithoutAServerFallsBack() {
        assertEquals(
            PaletteMode.HERMES,
            effectivePalette(PaletteMode.HERMES_SKIN, dynamicSupported = true, hasSkin = false),
        )
    }

    @Test
    fun aServerSkinWithASkinIsKept() {
        assertEquals(
            PaletteMode.HERMES_SKIN,
            effectivePalette(PaletteMode.HERMES_SKIN, dynamicSupported = false, hasSkin = true),
        )
    }

    @Test
    fun hermesIsNeverRewritten() {
        assertEquals(
            PaletteMode.HERMES,
            effectivePalette(PaletteMode.HERMES, dynamicSupported = true, hasSkin = true),
        )
        assertEquals(
            PaletteMode.HERMES,
            effectivePalette(PaletteMode.HERMES, dynamicSupported = false, hasSkin = false),
        )
    }

    @Test
    fun dynamicIsNotOfferedWhereThePlatformCannotHonourIt() {
        listOf(true, false).forEach { skin ->
            val offered = availablePalettes(dynamicSupported = false, hasSkin = skin)
            assertTrue("Dynamic was still offered: $offered", PaletteMode.DYNAMIC !in offered)
        }
        // With neither dynamic colour nor a server skin, Hermes is the only palette
        // left — which is exactly what such a device is already rendering.
        assertEquals(listOf(PaletteMode.HERMES), availablePalettes(dynamicSupported = false, hasSkin = false))
    }

    @Test
    fun theFullSetIsOfferedWhereEverythingWorks() {
        assertEquals(
            listOf(PaletteMode.DYNAMIC, PaletteMode.HERMES, PaletteMode.HERMES_SKIN),
            availablePalettes(dynamicSupported = true, hasSkin = true),
        )
        assertEquals(
            listOf(PaletteMode.DYNAMIC, PaletteMode.HERMES),
            availablePalettes(dynamicSupported = true, hasSkin = false),
        )
    }

    @Test
    fun everyOfferedPaletteIsOneTheTrayCanHighlight() {
        // The invariant that ties the two halves together: whatever the tray offers,
        // the resolved palette has to be in it, or the tray would show nothing selected.
        for (dynamic in listOf(true, false)) {
            for (skin in listOf(true, false)) {
                val offered = availablePalettes(dynamic, skin)
                offered.forEach { stored ->
                    assertTrue(
                        "$stored resolved outside $offered",
                        effectivePalette(stored, dynamic, skin) in offered,
                    )
                }
            }
        }
    }
}
