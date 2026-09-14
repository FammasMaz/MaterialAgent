package com.materialagent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive shape scale.
 *
 * Two deliberate departures from baseline M3: every radius is bumped one step
 * (16 → 22, 28 → 32) because expressive surfaces read as *soft* objects, and
 * the small end stays tight enough that chips and tool badges still look like
 * instruments rather than pills.
 */
val MaterialAgentShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Corner radii used by hand-built surfaces that don't map to a scale step. */
object AgentShapes {
    val bubbleTail = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 6.dp)
    val bubbleTailEnd = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 6.dp, bottomStart = 22.dp)
    val bubbleFull = RoundedCornerShape(22.dp)
    val toolCard = RoundedCornerShape(18.dp)

    /**
     * The composer's radius, animated between these two: tight while a turn is
     * live so the send/steer/stop button sits close to the text, roomier when
     * idle. Dp values rather than shapes because `animateDpAsState` needs a
     * number, and they are a pair — changing one alone makes the morph look
     * accidental.
     */
    val composerActive = 20.dp
    val composerIdle = 26.dp
}
