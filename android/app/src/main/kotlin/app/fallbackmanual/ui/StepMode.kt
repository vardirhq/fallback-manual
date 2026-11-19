package app.fallbackmanual.ui

import android.speech.tts.TextToSpeech
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fallbackmanual.R
import app.fallbackmanual.core.Article

/**
 * One step at a time, in very large text, for when hands are busy: big Back/Next buttons, the
 * screen kept on, optional read-aloud with the phone's own (offline) voice, and a beat guide for
 * steps the article gives a rhythm for (CPR compressions). Always dark, to save battery.
 */
@Composable
fun StepModeScreen(id: String, back: () -> Unit) {
    val data = load(id) { kb -> kb.article(id) }
    FallbackTheme(dark = true) {
        Box(Modifier.fillMaxSize().background(fm.bg)) {
            Loaded(data) { a ->
                if (a == null) LoadFailed(IllegalStateException(stringResource(R.string.article_not_found, id)))
                else StepMode(a, back)
            }
        }
    }
}

@Composable
private fun StepMode(a: Article, close: () -> Unit) {
    val steps = remember(a.bodyMarkdown) { stepsOf(a.bodyMarkdown) }
    if (steps.isEmpty()) {
        LoadFailed(IllegalStateException(stringResource(R.string.no_steps)))
        return
    }
    var index by rememberSaveable(a.id) { mutableIntStateOf(0) }
    var speaking by rememberSaveable { mutableStateOf(false) }
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    val plain = remember(step) { inline(step.text).text }
    val last = index == steps.lastIndex

    KeepScreenOn()
    val voice = rememberVoice()
    val spoken = stringResource(R.string.step_spoken, step.number, plain)
    LaunchedEffect(index, speaking, voice.ready) {
        if (speaking && voice.ready) voice.say(spoken) else voice.stop()
    }

    val onOff = stringResource(if (speaking) R.string.state_on else R.string.state_off)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close, modifier = Modifier.size(48.dp)) {
                Icon(FmIcons.stroke(FmIcons.CLOSE, 2.2f), contentDescription = stringResource(R.string.step_close), tint = fm.ink)
            }
            Text(stringResource(R.string.step_of, a.code, step.number, steps.size), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = fm.ink, modifier = Modifier.weight(1f))
            Icon(FmIcons.stroke(FmIcons.SUN), contentDescription = null, tint = fm.muted, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.screen_on), style = MaterialTheme.typography.labelSmall, color = fm.muted, modifier = Modifier.padding(start = 6.dp))
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            steps.indices.forEach { k ->
                val c = when {
                    k < index -> fm.orange
                    k == index -> fm.ink
                    else -> fm.rule
                }
                Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c))
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(step.number, style = MaterialTheme.typography.displayLarge, color = fm.orange)
            Text(
                plain,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 34.sp, lineHeight = 38.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                color = fm.ink,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            step.images.forEach { img -> ArticlePicture(img.id, img.alt) }
            val n = index + 1
            if (a.rhythm?.covers(n) == true) BeatGuide(a.rhythm!!.bpm)
        }

        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(Radius).border(2.dp, fm.rule, Radius)
                    .clickable(role = Role.Switch) { speaking = !speaking }
                    .semantics { stateDescription = onOff },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(FmIcons.stroke(FmIcons.SPEAKER), contentDescription = null, tint = fm.ink, modifier = Modifier.size(22.dp))
                Text(
                    stringResource(
                        when {
                            speaking && voice.failed -> R.string.voice_missing
                            speaking -> R.string.voice_reading
                            else -> R.string.voice_read
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium, color = fm.ink, modifier = Modifier.padding(start = 10.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(16.dp)).background(fm.surface)
                        .clickable(enabled = index > 0, role = Role.Button) { index-- },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.step_back), style = MaterialTheme.typography.headlineSmall, color = if (index > 0) fm.ink else fm.rule)
                }
                BigButton(stringResource(if (last) R.string.step_done else R.string.step_next), Modifier.weight(2f), height = 76) {
                    if (last) close() else index++
                }
            }
        }
    }
}

/** A pulsing circle at the article's rhythm, e.g. 110 a minute for chest compressions. */
@Composable
private fun BeatGuide(bpm: Int) {
    val period = 60_000 / bpm
    val pulse by rememberInfiniteTransition(label = "beat").animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = period
                1f at 0
                0.72f at (period * 0.35f).toInt() using FastOutSlowInEasing
                1f at period
            },
            RepeatMode.Restart,
        ),
        label = "pulse",
    )
    Row(
        Modifier.fillMaxWidth().clip(Radius).border(2.dp, fm.rule, Radius).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).scale(pulse).clip(CircleShape).background(fm.orange))
        Column(Modifier.padding(start = 14.dp)) {
            Text(stringResource(R.string.beat_title), style = MaterialTheme.typography.labelMedium, color = fm.ink)
            Text(stringResource(R.string.beat_rate, bpm), style = MaterialTheme.typography.bodyMedium, color = fm.muted)
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** Android's text-to-speech. Uses whatever voice is installed on the phone; works offline. */
private class Voice {
    var tts: TextToSpeech? = null
    var ready by mutableStateOf(false)
    var failed by mutableStateOf(false)
    fun say(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "step") }
    fun stop() { tts?.stop() }
}

@Composable
private fun rememberVoice(): Voice {
    val context = LocalContext.current
    val voice = remember { Voice() }
    DisposableEffect(Unit) {
        // Read in the app's language (Norwegian articles in a Norwegian voice), if the phone has one.
        val locale = context.resources.configuration.locales[0]
        val t = TextToSpeech(context) { status ->
            val result = if (status == TextToSpeech.SUCCESS) voice.tts?.setLanguage(locale) else null
            if (result != null && result >= TextToSpeech.LANG_AVAILABLE) {
                voice.ready = true
            } else {
                voice.failed = true
            }
        }
        voice.tts = t
        onDispose {
            t.stop()
            t.shutdown()
        }
    }
    return voice
}
