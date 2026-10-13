package com.moneyflow.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object NavigationIcons {
    val Overview = icon("Overview") {
        moveTo(5f, 5f); lineTo(19f, 5f); lineTo(19f, 9f)
        moveTo(19f, 15f); lineTo(19f, 19f); lineTo(5f, 19f)
        curveTo(3.9f, 19f, 3f, 18.1f, 3f, 17f); lineTo(3f, 7f)
        curveTo(3f, 5.9f, 3.9f, 5f, 5f, 5f)
        moveTo(3f, 9f); lineTo(21f, 9f); lineTo(21f, 15f); lineTo(15f, 15f)
        curveTo(13.3f, 15f, 12f, 13.7f, 12f, 12f)
        curveTo(12f, 10.3f, 13.3f, 9f, 15f, 9f)
        moveTo(16.5f, 12f); lineTo(17f, 12f)
    }
    val Analytics = icon("Analytics") {
        moveTo(10f, 3f)
        curveTo(5.5f, 3.5f, 2f, 7.3f, 2f, 12f)
        curveTo(2f, 17f, 6f, 21f, 11f, 21f)
        curveTo(15.7f, 21f, 19.5f, 17.5f, 20f, 13f)
        lineTo(10f, 13f); close()
        moveTo(14f, 3f)
        curveTo(18.2f, 3.5f, 21.5f, 6.8f, 22f, 10f)
        lineTo(14f, 10f); close()
    }
    val Categories = icon("Categories") {
        moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
        moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
        moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
    }
    val Imports = icon("Imports") {
        moveTo(12f, 3f); lineTo(12f, 14f)
        moveTo(8f, 10f); lineTo(12f, 14f); lineTo(16f, 10f)
        moveTo(4f, 15f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 15f)
    }

    private fun icon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()
}
