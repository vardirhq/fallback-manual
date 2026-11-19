package app.fallbackmanual.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fallbackmanual.core.ArticleSummary
import app.fallbackmanual.core.Category

val Radius = RoundedCornerShape(14.dp)

// -- Bars ---------------------------------------------------------------------

/** The top row of a screen: optional back button, a small mono label, and actions. */
@Composable
fun Bar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 6.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(FmIcons.stroke(FmIcons.BACK, 2.2f), contentDescription = "Back", tint = fm.ink)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title.uppercase(), style = MaterialTheme.typography.labelMedium, color = fm.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        actions()
    }
}

/** The brand mark: a black tile with an orange bookmark ribbon ("the page you keep for emergencies"). */
@Composable
fun LogoMark(size: Int = 30) {
    val ribbon = GenericShape { s, _ ->
        moveTo(0f, 0f); lineTo(s.width, 0f); lineTo(s.width, s.height); lineTo(s.width / 2, s.height * 0.76f)
        lineTo(0f, s.height); close()
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4.3f).dp)).background(fm.ink)) {
        Box(
            Modifier.align(Alignment.TopEnd).padding(end = (size * 0.23f).dp)
                .size(width = (size * 0.27f).dp, height = (size * 0.57f).dp).background(fm.orange, ribbon),
        )
    }
}

enum class Tab(val route: String, val label: String, val icon: String) {
    Home("home", "Home", FmIcons.HOME),
    Manual("manual", "Manual", FmIcons.BOOK),
    Saved("saved", "Saved", FmIcons.BOOKMARK),
    Share("share", "Share", FmIcons.SHARE),
}

@Composable
fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(fm.nav).navigationBarsPadding()) {
        HorizontalDivider(color = fm.rule)
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp)) {
            Tab.entries.forEach { tab ->
                val on = tab == current
                val color = if (on) fm.accentText else fm.muted
                Column(
                    Modifier.weight(1f).fillMaxSize()
                        .clickable(role = Role.Tab, onClickLabel = tab.label) { onSelect(tab) }
                        .semantics { selected = on },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(width = 56.dp, height = 30.dp).clip(RoundedCornerShape(15.dp))
                            .background(if (on) fm.activePill else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) { Icon(FmIcons.stroke(tab.icon), contentDescription = null, tint = color, modifier = Modifier.size(22.dp)) }
                    Text(tab.label, style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp), color = color)
                }
            }
        }
    }
}

// -- Labels and chips ---------------------------------------------------------

/** A mono section label with a rule after it, like "EMERGENCY ─────". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = fm.ink,
            modifier = Modifier.semantics { heading() })
        Box(Modifier.padding(start = 10.dp).weight(1f).height(2.dp).background(if (accent) fm.orange else fm.rule))
    }
}

/** Kept for screens that use a plain section heading. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) = SectionLabel(text, modifier)

@Composable
fun Chip(text: String, bg: Color, ink: Color) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = ink,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun DraftStrip(modifier: Modifier = Modifier) {
    Text(
        "DRAFT CONTENT · NOT YET REVIEWED", style = MaterialTheme.typography.labelSmall, color = fm.draftInk,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(fm.draftBg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

// -- Search -------------------------------------------------------------------

@Composable
fun SearchBox(initial: String = "", onSearch: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (text.isNotBlank()) {
            keyboard?.hide()
            onSearch(text.trim())
        }
    }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = fm.ink),
        cursorBrush = SolidColor(fm.accentText),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submit() }),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "What's happening? Describe the problem" },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(58.dp).clip(Radius).background(fm.surface)
                    .border(2.dp, fm.ink, Radius).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(FmIcons.stroke(FmIcons.SEARCH, 2.2f), contentDescription = null, tint = fm.ink, modifier = Modifier.size(22.dp))
                Box(Modifier.weight(1f).padding(start = 12.dp)) {
                    if (text.isEmpty()) {
                        Text("e.g. shivering and confused", style = MaterialTheme.typography.bodyLarge,
                            color = fm.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = "" }) {
                        Icon(FmIcons.stroke(FmIcons.CLOSE), contentDescription = "Clear", tint = fm.muted)
                    }
                }
            }
        },
    )
}

// -- Rows and tiles -----------------------------------------------------------

@Composable
fun IconBox(path: String?, highRisk: Boolean, size: Int = 36) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(if (highRisk) fm.orange else fm.forestBg),
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            Icon(FmIcons.stroke(path), contentDescription = null, tint = if (highRisk) fm.onOrange else fm.forest,
                modifier = Modifier.size((size * 0.56f).dp))
        }
    }
}

@Composable
fun ArticleRow(a: ArticleSummary, supporting: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBox(a.iconPath, a.highRisk)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(a.title, style = MaterialTheme.typography.titleMedium, color = fm.ink)
            val bits = listOfNotNull(a.code, supporting, if (!a.reviewed) "draft" else null)
            Text(bits.joinToString(" · ").uppercase(), style = MaterialTheme.typography.labelSmall, color = fm.muted,
                modifier = Modifier.padding(top = 2.dp))
        }
        Icon(FmIcons.stroke(FmIcons.CHEVRON), contentDescription = null, tint = fm.muted, modifier = Modifier.size(20.dp))
    }
    HorizontalDivider(Modifier.padding(start = 70.dp), color = fm.rule)
}

@Composable
fun CategoryRow(c: Category, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBox(c.iconPath, highRisk = false)
        Text(c.title, style = MaterialTheme.typography.titleMedium, color = fm.ink,
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
        Text(c.articleCount.toString(), style = MaterialTheme.typography.labelMedium, color = fm.muted,
            modifier = Modifier.semantics { contentDescription = "${c.articleCount} articles" })
        Icon(FmIcons.stroke(FmIcons.CHEVRON), contentDescription = null, tint = fm.muted,
            modifier = Modifier.padding(start = 6.dp).size(20.dp))
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = fm.rule)
}

/** A big orange tile for an emergency topic on the home screen. */
@Composable
fun EmergencyTile(a: ArticleSummary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 104.dp).clip(Radius).background(fm.orange).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            a.iconPath?.let { Icon(FmIcons.stroke(it), contentDescription = null, tint = fm.onOrange, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.weight(1f))
            Text(a.code, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = fm.onOrange)
        }
        Text(a.title, style = MaterialTheme.typography.titleLarge, color = fm.onOrange, modifier = Modifier.padding(top = 10.dp))
    }
}

/** The red "call for help" block at the top of high-risk articles. */
@Composable
fun CallBlock(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(Radius).background(fm.emergency).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FmIcons.stroke(FmIcons.PHONE), contentDescription = null, tint = fm.onEmergency, modifier = Modifier.size(28.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text("Call emergency services first", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp),
                color = fm.onEmergency)
            Text("If you can, do it now. Use speakerphone to keep your hands free.",
                style = MaterialTheme.typography.bodyMedium, color = fm.onEmergency, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The large orange action button ("Step by step", "Next"). */
@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, icon: String? = null, height: Int = 64, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(height.dp).clip(Radius).background(fm.orange)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(FmIcons.fill(icon), contentDescription = null, tint = fm.onOrange,
                modifier = Modifier.padding(end = 12.dp).size(26.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 0.7.sp), color = fm.onOrange)
    }
}

/** An outlined secondary button. */
@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(Radius).border(2.dp, fm.ink, Radius)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleMedium, color = fm.ink) }
}

// -- Notices ------------------------------------------------------------------

enum class CalloutKind { Emergency, Caution, Info }

@Composable
fun Callout(kind: CalloutKind, title: String, body: String? = null, modifier: Modifier = Modifier) {
    val (bg, ink) = when (kind) {
        CalloutKind.Emergency -> fm.emergency to fm.onEmergency
        CalloutKind.Caution -> fm.draftBg to fm.draftInk
        CalloutKind.Info -> fm.forestBg to fm.ink
    }
    Row(modifier.fillMaxWidth().clip(Radius).background(bg).padding(14.dp), verticalAlignment = Alignment.Top) {
        if (kind != CalloutKind.Info) {
            Icon(FmIcons.stroke(FmIcons.WARNING), contentDescription = null, tint = ink,
                modifier = Modifier.padding(end = 12.dp).size(24.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = ink)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = ink)
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = fm.accentText) }
}

@Composable
fun LoadFailed(error: Throwable) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Callout(CalloutKind.Emergency, "The manual could not be loaded", error.message ?: error.toString())
    }
}
