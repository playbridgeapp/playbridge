package com.playbridge.player.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/** The mockups' line icons (24x24, 2dp stroke), drawn from their SVG paths. Tint with a ColorFilter. */
private fun strokeIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private val PhoneIcon = strokeIcon(
    "phone",
    "M9.5 2.5h5a2.5 2.5 0 0 1 2.5 2.5v14a2.5 2.5 0 0 1 -2.5 2.5h-5a2.5 2.5 0 0 1 -2.5 -2.5v-14a2.5 2.5 0 0 1 2.5 -2.5z",
    "M11 18h2",
)
private val GroupIcon = strokeIcon(
    "group",
    "M9 4.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0 -7z",
    "M2.5 20c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6",
    "M17.5 6.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0 -5z",
    "M17.5 14c2.4 0 4 1.7 4 4",
)
private val BookmarkIcon = strokeIcon("bookmark", "M6.5 3.5h11v17L12 16.5l-5.5 4z")

@Composable
internal fun phoneIconPainter(): Painter = rememberVectorPainter(remember { PhoneIcon })

@Composable
internal fun groupIconPainter(): Painter = rememberVectorPainter(remember { GroupIcon })

@Composable
internal fun bookmarkIconPainter(): Painter = rememberVectorPainter(remember { BookmarkIcon })
