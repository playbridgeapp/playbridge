package com.playbridge.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Icons drawn here because the app does not depend on the extended Material icon set. */
internal object TogetherIcons {
    /** Two people, from the mockups' 24x24 stroke icon. */
    val People: ImageVector by lazy {
        ImageVector.Builder("TogetherPeople", 24.dp, 24.dp, 24f, 24f).apply {
            val stroke = SolidColor(Color.Black)
            fun outline(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) = path(
                fill = null, stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
            )
            outline { // head
                moveTo(12.5f, 8f); arcTo(3.5f, 3.5f, 0f, true, true, 5.5f, 8f); arcTo(3.5f, 3.5f, 0f, true, true, 12.5f, 8f); close()
            }
            outline { // shoulders
                moveTo(2.5f, 20f); curveTo(2.5f, 16.4f, 5.4f, 14f, 9f, 14f); curveTo(12.6f, 14f, 15.5f, 16.4f, 15.5f, 20f)
            }
            outline { // second head
                moveTo(20f, 9f); arcTo(2.5f, 2.5f, 0f, true, true, 15f, 9f); arcTo(2.5f, 2.5f, 0f, true, true, 20f, 9f); close()
            }
            outline { // second shoulders
                moveTo(17.5f, 14f); curveTo(19.9f, 14f, 21.5f, 15.7f, 21.5f, 18f)
            }
        }.build()
    }
}
