package app.fallbackmanual.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Pictures are always shown on a light panel so line drawings stay readable in dark mode.
private val PanelColor = Color(0xFFFFFFFF)
private val PanelShape = RoundedCornerShape(12.dp)

private class LoadedPicture(val bitmap: ImageBitmap?, val credit: String)

/**
 * A picture inside an article. Its description is shown underneath and read by screen readers.
 * Tapping it opens a full-screen view that can be zoomed with two fingers.
 */
@Composable
fun ArticlePicture(id: String, alt: String, modifier: Modifier = Modifier) {
    val state = load(id) { kb ->
        kb.image(id)?.let { img ->
            val bmp = BitmapFactory.decodeByteArray(img.data, 0, img.data.size)?.asImageBitmap()
            LoadedPicture(bmp, "${img.creator} · ${img.license}")
        }
    }
    var fullScreen by rememberSaveable(id) { mutableStateOf(false) }
    val picture = state.value?.getOrNull()

    Column(modifier.fillMaxWidth()) {
        if (picture?.bitmap != null) {
            Box(
                Modifier.fillMaxWidth().clip(PanelShape).background(PanelColor)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), PanelShape)
                    .clickable(onClickLabel = "Open picture full screen", role = Role.Image) { fullScreen = true }
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    picture.bitmap, contentDescription = alt,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit,
                )
            }
        } else if (state.value != null) {
            // Missing or unreadable picture: still tell the reader what it would have shown.
            Box(
                Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, PanelShape).padding(12.dp),
            ) { Text("Picture: $alt", style = MaterialTheme.typography.bodyMedium) }
        }
        Text(
            alt,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (fullScreen && picture?.bitmap != null) {
        FullScreenPicture(picture.bitmap, alt, picture.credit) { fullScreen = false }
    }
}

@Composable
private fun FullScreenPicture(bitmap: ImageBitmap, alt: String, credit: String, close: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = rememberTransformableState { zoomChange, pan, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(PanelColor)) {
            Image(
                bitmap, contentDescription = alt, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(12.dp).transformable(zoom)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            IconButton(
                onClick = close,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .background(Color(0xCCFFFFFF), RoundedCornerShape(50)),
            ) { Icon(Icons.Filled.Close, contentDescription = "Close picture", tint = Color(0xFF1A1C18)) }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xE6FFFFFF)).padding(16.dp),
            ) {
                Text(alt, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF1A1C18))
                Text(credit, style = MaterialTheme.typography.bodySmall, color = Color(0xFF43483F))
            }
        }
    }
}
