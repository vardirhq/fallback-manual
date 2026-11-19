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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fallbackmanual.AppSharing
import app.fallbackmanual.R
import kotlinx.coroutines.launch

val LocalSharing = staticCompositionLocalOf<AppSharing> { error("no AppSharing") }

@Composable
fun ShareScreen(bottomBar: @Composable () -> Unit = {}) {
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
                context.getString(R.string.share_no_app)
            } catch (e: Exception) {
                context.getString(R.string.share_prepare_failed, e.message ?: e.toString())
            } finally {
                busy = false
            }
        }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AppSharing.APK_MIME)) { uri ->
        if (uri != null) scope.launch {
            status = runCatching { sharing.saveTo(uri) }
                .fold({ context.getString(R.string.share_saved) }, { context.getString(R.string.share_save_failed, it.message ?: it.toString()) })
        }
    }

    Scaffold(containerColor = fm.bg, topBar = { Bar(stringResource(R.string.bar_share), null) }, bottomBar = bottomBar) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.share_intro),
                style = MaterialTheme.typography.bodyLarge,
            )

            when (availability) {
                is AppSharing.Availability.Ready -> {
                    Text(
                        stringResource(R.string.share_size, availability.sizeBytes / 1_000_000.0),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { send(asZip = false) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.padding(end = 10.dp))
                        Text(stringResource(R.string.share_send))
                    }
                    OutlinedButton(
                        onClick = { send(asZip = true) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text(stringResource(R.string.share_send_zip)) }
                    Text(
                        stringResource(R.string.share_bluetooth_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { saver.launch(sharing.suggestedFileName()) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text(stringResource(R.string.share_save_copy)) }
                }
                AppSharing.Availability.SplitInstall -> Callout(
                    CalloutKind.Caution,
                    stringResource(R.string.share_split_title),
                    stringResource(R.string.share_split_body),
                )
                AppSharing.Availability.Missing -> Callout(
                    CalloutKind.Caution,
                    stringResource(R.string.share_missing_title),
                    stringResource(R.string.share_missing_body),
                )
            }

            status?.let { Callout(CalloutKind.Info, it) }

            SectionTitle(stringResource(R.string.share_other_phone))
            listOf(
                R.string.share_other_1, R.string.share_other_2, R.string.share_other_3, R.string.share_other_4,
            ).forEachIndexed { i, s ->
                MarkdownBody("${i + 1}. ${stringResource(s)}")
            }

            SectionTitle(stringResource(R.string.share_genuine))
            Text(
                stringResource(R.string.share_genuine_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                fingerprint ?: stringResource(R.string.share_fingerprint_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
