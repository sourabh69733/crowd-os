package org.freegram.shared.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons drawn from the mockup's 24-unit paths, so the shared module needs no icon library. */
object FgIcons {
    private fun line(name: String, d: String, width: Float = 2f) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        .build()

    val Home = line("home", "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z")
    val Nearby = line("nearby", "M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M7.8 7.8a6 6 0 0 0 0 8.4 M16.2 7.8a6 6 0 0 1 0 8.4 M4.9 4.9a10 10 0 0 0 0 14.2 M19.1 4.9a10 10 0 0 1 0 14.2")
    val Plus = line("plus", "M12 5v14 M5 12h14", 2.4f)
    val Profile = line("profile", "M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0 M4 21a8 8 0 0 1 16 0")
    val Settings = line("settings", "M4 6h9 M17 6h3 M4 12h3 M11 12h9 M4 18h11 M19 18h1 M15 4v4 M9 10v4 M17 16v4")
    val Back = line("back", "M15 18l-6-6 6-6")
    val Chevron = line("chevron", "M9 6l6 6-6 6")
    val More = line("more", "M12 5v.01 M12 12v.01 M12 19v.01", 3.2f)
    val Photo = line("photo", "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M7 10a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M21 16l-5-5-8 8")
    val Flag = line("flag", "M5 21V4h11l-2 4 2 4H5")
    val Mute = line("mute", "M11 5L6 9H3v6h3l5 4z M22 9l-6 6 M16 9l6 6")
    val Block = line("block", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M5.6 5.6l12.8 12.8")
    val Trash = line("trash", "M4 7h16 M9 7V4h6v3 M6 7l1 13h10l1-13")
    val Share = line("share", "M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7 M12 3v12 M7 8l5-5 5 5")
    val Hide = line("hide", "M3 3l18 18 M10.6 5.1A10 10 0 0 1 22 12a14 14 0 0 1-3 3.8 M6.6 6.6A14 14 0 0 0 2 12s3.6 7 10 7a9.7 9.7 0 0 0 4.4-1")
    val Copy = line("copy", "M9 9h11v11H9z M5 15H4V4h11v1")
    val Follow = line("follow", "M5 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0 M1 21a8 8 0 0 1 16 0 M20 8v6 M17 11h6")
}
