package com.luckycatpaw.luckyfilestv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Every corner radius in the app.
 *
 * Kept just off zero. Many TV panels scale the rendered frame before it reaches
 * the screen, and a true 0 dp corner picks up stair-stepping in the process,
 * while 2-3 dp still reads as a hard edge from the sofa.
 */
object AppShapes {
    /** File grid tiles. */
    val Tile = RoundedCornerShape(3.dp)

    /** Buttons, rows, text fields — anything focusable. */
    val Control = RoundedCornerShape(2.dp)

    /** Dialog containers. */
    val Card = RoundedCornerShape(4.dp)

    /** Selection markers and value badges. */
    val Badge = RoundedCornerShape(2.dp)

    /** Progress track and fill. */
    val Bar = RoundedCornerShape(1.dp)
}
