package app.fallbackmanual.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fallbackmanual.AppSharing
import kotlinx.coroutines.launch

val LocalSharing = staticCompositionLocalOf<AppSharing> { error("no AppSharing") }

@Composable
fun ShareScreen(back: () -> Unit) {
    val sharing = LocalSharing.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val availability = remember { sharing.availability() }
    val fingerprint = remember { sharing.signingFingerprint() }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun send(asZip: Boolean) {
        busy = true
        scope.launch {
            status = try {
                context.startActivity(sharing.shareIntent(sharing.prepare(asZip)))
                null
            } catch (e: ActivityNotFoundException) {
                "No app on this phone can send files. Try \"Save a copy\" instead."
            } catch (e: Exception) {
                "Could not prepare the file: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AppSharing.APK_MIME)) { uri ->
        if (uri != null) scope.launch {
            status = runCatching { sharing.saveTo(uri) }
                .fold({ "Saved. Copy it to the other phone and open it there." }, { "Could not save: ${it.message}" })
        }
    }

    Scaffold(topBar = { Bar("Share this app", back) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Give Fallback Manual to a phone nearby. No internet or mobile signal is needed.",
                style = MaterialTheme.typography.bodyLarge,
            )

            when (availability) {
                is AppSharing.Availability.Ready -> {
                    Text(
                        "Size: %.1f MB. The manual and everything in it are included.".format(availability.sizeBytes / 1_000_000.0),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { send(asZip = false) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.padding(end = 10.dp))
                        Text("Send with Quick Share, Bluetooth…")
                    }
                    OutlinedButton(
                        onClick = { send(asZip = true) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("Send as ZIP") }
                    Text(
                        "Some phones refuse to send or receive app files over Bluetooth. If that happens, send it as a ZIP; the other phone unzips it with its Files app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { saver.launch(sharing.suggestedFileName()) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("Save a copy (SD card, USB, Downloads)") }
                }
                AppSharing.Availability.SplitInstall -> Callout(
                    CalloutKind.Caution,
                    "This copy can't be shared",
                    "It was installed from an app store in several parts, and Android can't install those parts on another phone. " +
                        "Install the complete version from F-Droid or the project's releases page, and it can be shared from then on.",
                )
                AppSharing.Availability.Missing -> Callout(
                    CalloutKind.Caution,
                    "The app file could not be found",
                    "Sharing isn't available on this phone.",
                )
            }

            status?.let { Callout(CalloutKind.Info, it) }

            SectionTitle("On the other phone")
            listOf(
                "Accept the file when it arrives. With Quick Share, both phones need Bluetooth on and must be close together.",
                "Open the file. If it arrived as a ZIP, open it in the Files app and extract it first.",
                "If Android asks, allow that app to install unknown apps. Android shows the setting.",
                "Tap Install, then open Fallback Manual. It works offline straight away.",
            ).forEachIndexed { i, s ->
                MarkdownBody("${i + 1}. $s")
            }

            SectionTitle("Check it's genuine")
            Text(
                "Both phones should show the same signing fingerprint here, or under About. If they differ, the copy was " +
                    "rebuilt by someone else: don't rely on it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                fingerprint ?: "Unavailable on this phone",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
