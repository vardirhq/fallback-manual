package app.fallbackmanual.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * The small Markdown subset articles use: ## headings, numbered steps, bullets, paragraphs,
 * **bold**, [links](article:id), and pictures on their own line: ![description](image:id).
 */
sealed interface Block {
    data class Heading(val text: String) : Block
    data class Step(val number: String, val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Image(val id: String, val alt: String) : Block
}

private val IMAGE = Regex("""^!\[([^\]]+)\]\(image:([a-z0-9-]+)\)$""")

private val STEP = Regex("""^(\d+)\.\s+(.*)$""")
private val BULLET = Regex("""^[-*]\s+(.*)$""")

fun parseMarkdown(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim())
        para.clear()
    }
    var inList = false // true while the previous line was part of a list item
    for (raw in md.lines()) {
        val line = raw.trim()
        val step = STEP.find(line)
        val bullet = BULLET.find(line)
        val image = IMAGE.find(line)
        when {
            line.isEmpty() -> { flush(); inList = false }
            image != null -> { flush(); inList = false; out += Block.Image(image.groupValues[2], image.groupValues[1]) }
            line.startsWith("#") -> { flush(); inList = false; out += Block.Heading(line.trimStart('#').trim()) }
            step != null -> { flush(); inList = true; out += Block.Step(step.groupValues[1], step.groupValues[2]) }
            bullet != null -> { flush(); inList = true; out += Block.Bullet(bullet.groupValues[1]) }
            inList -> out += when (val last = out.removeAt(out.lastIndex)) {
                is Block.Step -> last.copy(text = last.text + " " + line)
                is Block.Bullet -> last.copy(text = last.text + " " + line)
                else -> last
            }
            else -> para.append(line).append(' ')
        }
    }
    flush()
    return out
}

private val INLINE = Regex("""\*\*(.+?)\*\*|\[([^\]]+)\]\(article:([a-z0-9-]+)\)""")

/** **bold** and [text](article:id) links. Without [onLink], links render as plain text. */
fun inline(text: String, linkColor: Color = Color.Unspecified, onLink: ((String) -> Unit)? = null): AnnotatedString =
    buildAnnotatedString {
        var last = 0
        for (m in INLINE.findAll(text)) {
            append(text.substring(last, m.range.first))
            if (m.groups[1] != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            } else {
                val label = m.groupValues[2]
                val id = m.groupValues[3]
                if (onLink == null) {
                    append(label)
                } else {
                    val style = SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)
                    withLink(LinkAnnotation.Clickable("article:$id", TextLinkStyles(style)) { onLink(id) }) { append(label) }
                }
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }

/** One step of an article's '## Steps' section, with the pictures placed under it. */
data class StepItem(val number: String, val text: String, val images: List<Block.Image>)

fun stepsOf(markdown: String): List<StepItem> {
    val out = mutableListOf<StepItem>()
    var section = ""
    for (b in parseMarkdown(markdown)) {
        when {
            b is Block.Heading -> section = b.text
            section != "Steps" -> {}
            b is Block.Step -> out += StepItem(b.number, b.text, emptyList())
            b is Block.Image && out.isNotEmpty() -> out[out.lastIndex] = out.last().let { it.copy(images = it.images + b) }
        }
    }
    return out
}

@Composable
fun MarkdownBody(markdown: String, modifier: Modifier = Modifier, onLink: ((String) -> Unit)? = null) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val linkColor = fm.accentText
    fun rich(text: String) = inline(text, linkColor, onLink)
    var section = ""
    var under: Block? = null // the step, bullet or paragraph that the next picture belongs to
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in blocks) {
            if (b !is Block.Image) under = b
            when (b) {
                is Block.Heading -> {
                    section = b.text
                    val warn = section == "Warnings"
                    Text(
                        b.text.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (warn) MaterialTheme.colorScheme.error else fm.forest,
                        modifier = Modifier.padding(top = 18.dp).semantics { heading() },
                    )
                }
                is Block.Step -> Row(verticalAlignment = Alignment.Top) {
                    Text(
                        b.number,
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 30.sp),
                        color = fm.accentText,
                        modifier = Modifier.width(36.dp),
                    )
                    Text(rich(b.text), style = MaterialTheme.typography.bodyLarge, color = fm.ink,
                        modifier = Modifier.padding(start = 10.dp))
                }
                is Block.Bullet -> Row {
                    val warn = section == "Warnings"
                    if (warn) {
                        Icon(FmIcons.stroke(FmIcons.WARNING), contentDescription = "Warning",
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp, end = 10.dp).size(20.dp))
                    } else {
                        Text("•", style = MaterialTheme.typography.bodyLarge, color = fm.muted, modifier = Modifier.padding(end = 10.dp))
                    }
                    Text(rich(b.text), style = MaterialTheme.typography.bodyLarge, color = fm.ink)
                }
                is Block.Paragraph -> Text(rich(b.text), style = MaterialTheme.typography.bodyLarge, color = fm.ink)
                // A picture lines up with the text of the step or bullet it belongs to.
                is Block.Image -> {
                    val indent = when (under) {
                        is Block.Step -> 46.dp
                        is Block.Bullet -> 30.dp
                        else -> 0.dp
                    }
                    ArticlePicture(b.id, b.alt, Modifier.padding(start = indent, bottom = 6.dp))
                }
            }
        }
    }
}
