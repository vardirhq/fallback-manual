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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** The small Markdown subset articles use: ## headings, numbered steps, bullets, paragraphs, **bold**. */
sealed interface Block {
    data class Heading(val text: String) : Block
    data class Step(val number: String, val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Paragraph(val text: String) : Block
}

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
        when {
            line.isEmpty() -> { flush(); inList = false }
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

fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val start = rest.indexOf("**")
        val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
        if (start < 0 || end < 0) { append(rest); break }
        append(rest.substring(0, start))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(rest.substring(start + 2, end)) }
        rest = rest.substring(end + 2)
    }
}

@Composable
fun MarkdownBody(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    var section = ""
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in blocks) {
            when (b) {
                is Block.Heading -> {
                    section = b.text
                    val warn = section == "Warnings"
                    Text(
                        b.text,
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 14.dp).semantics { heading() },
                    )
                }
                is Block.Step -> Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(b.number, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(inline(b.text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
                }
                is Block.Bullet -> Row {
                    val warn = section == "Warnings"
                    Text(
                        if (warn) "⚠" else "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Text(inline(b.text), style = MaterialTheme.typography.bodyLarge)
                }
                is Block.Paragraph -> Text(inline(b.text), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
