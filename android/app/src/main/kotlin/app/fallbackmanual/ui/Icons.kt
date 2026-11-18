package app.fallbackmanual.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Icons are SVG path data on a 24×24 grid. Content icons (categories, articles) come from
 * knowledge/icons.yaml through the database; the app's own interface icons are below.
 */
object FmIcons {
    private val cache = HashMap<String, ImageVector>()

    /** A line icon drawn with 2-unit strokes. Tinted by `Icon`. */
    fun stroke(path: String, width: Float = 2f): ImageVector = cache.getOrPut("s$width:$path") {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ).build()
    }

    /** A solid icon. Tinted by `Icon`. */
    fun fill(path: String): ImageVector = cache.getOrPut("f:$path") {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()
    }

    const val SEARCH = "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z M20 20l-3.6-3.6"
    const val BACK = "M15 5l-7 7 7 7"
    const val CLOSE = "M6 6l12 12 M18 6L6 18"
    const val CHEVRON = "M9 5l7 7-7 7"
    const val CHEVRON_DOWN = "M5 9l7 7 7-7"
    const val BOOKMARK = "M6 3h12v18l-6-4-6 4z"
    const val HOME = "M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z"
    const val BOOK = "M4 4.5A1.5 1.5 0 0 1 5.5 3H20v16H5.5A1.5 1.5 0 0 0 4 20.5z M4 20.5A1.5 1.5 0 0 0 5.5 22H20"
    const val SHARE = "M12 3v12 M7 8l5-5 5 5 M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5"
    const val INFO = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M12 11v5 M12 8h.01"
    const val PHONE = "M5 3h4l2 5-2.5 1.5a11 11 0 0 0 6 6L16 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 5a2 2 0 0 1 2-2z"
    const val PLAY = "M8 5v14l11-7z"
    const val SPEAKER = "M4 9v6h4l5 4V5L8 9z M16.5 8.5a5 5 0 0 1 0 7 M19 6a8.5 8.5 0 0 1 0 12"
    const val SUN = "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M12 2v2 M12 20v2 M2 12h2 M20 12h2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M4.9 19.1l1.4-1.4 M17.7 6.3l1.4-1.4"
    const val WARNING = "M12 3l9.5 17h-19z M12 10v4 M12 17h.01"
}
