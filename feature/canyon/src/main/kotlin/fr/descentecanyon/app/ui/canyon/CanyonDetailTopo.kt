package fr.descentecanyon.app.ui.canyon

import android.util.Patterns
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CanyonDetail
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.LocalDcColors

internal fun LazyListScope.topoItems(detail: CanyonDetail) {
    val sectionModifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    detail.accesAval?.let { text ->
        item {
            CollapsibleSection(
                title = if (detail.accesAmont.isNullOrBlank()) {
                    stringResource(R.string.access)
                } else {
                    stringResource(R.string.access_downstream)
                },
                content = text,
                modifier = sectionModifier,
            )
        }
    }
    detail.accesAmont?.let { text ->
        item {
            CollapsibleSection(
                title = stringResource(R.string.access_upstream),
                content = text,
                modifier = sectionModifier,
            )
        }
    }
    detail.approche?.let { text ->
        item {
            CollapsibleSection(
                title = stringResource(R.string.approach),
                content = text,
                modifier = sectionModifier,
            )
        }
    }
    detail.descente?.let { text ->
        item {
            CollapsibleSection(
                title = stringResource(R.string.descent),
                content = text,
                modifier = sectionModifier,
            )
        }
    }
    detail.retour?.let { text ->
        item {
            CollapsibleSection(
                title = stringResource(R.string.return_path),
                content = text,
                modifier = sectionModifier,
            )
        }
    }
    detail.engagement?.let { text ->
        item {
            CollapsibleSection(
                title = stringResource(R.string.engagement),
                content = text,
                modifier = sectionModifier,
            )
        }
    }
    detail.periode?.let { text ->
        item {
            CollapsibleSection(
                title = stringResource(R.string.period),
                content = text,
                modifier = sectionModifier,
            )
        }
    }
    detail.geologie?.let { text ->
        item {
            CollapsibleSection(
                title = stringResource(R.string.geology),
                content = text,
                modifier = sectionModifier,
            )
        }
    }
    detail.historique?.let { text ->
        item {
            CollapsibleSection(
                title = stringResource(R.string.history),
                content = text,
                modifier = sectionModifier,
            )
        }
    }
    detail.remarques?.let { text ->
        item {
            CollapsibleSection(
                title = stringResource(R.string.notes),
                content = text,
                modifier = sectionModifier,
            )
        }
    }
    if (detail.bibliography.isNotEmpty()) {
        item {
            BibliographySection(
                entries = detail.bibliography,
                modifier = sectionModifier,
            )
        }
    }
    if (detail.regulations.isNotEmpty()) {
        item {
            RegulationSection(
                regulations = detail.regulations,
                modifier = sectionModifier,
            )
        }
    }
    item {
        Spacer(modifier = Modifier.height(132.dp)) // FAB clearance
    }
}

@Composable
private fun CollapsibleSection(title: String, content: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val preview = remember(content) { normalizeSectionText(content).lineSequence().joinToString(" ").trim() }
    val colors = LocalDcColors.current

    DcCard(
        modifier = modifier.fillMaxWidth(),
        variant = if (expanded) DcCardVariant.Elevated else DcCardVariant.Surface,
        contentPadding = 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!expanded && preview.isNotBlank()) {
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(color = colors.borderSubtle)
                    LinkifiedSectionText(
                        content = content,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkifiedSectionText(content: String, modifier: Modifier = Modifier) {
    val linkColor = MaterialTheme.colorScheme.primary
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val text = remember(content, linkColor) {
        buildLinkifiedAnnotatedString(
            content = normalizeSectionText(content),
            linkColor = linkColor,
        )
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(color = bodyColor),
        modifier = modifier,
    )
}

private val HtmlLineBreakRegex = Regex("""(?i)<br\s*/?>""")

private fun normalizeSectionText(content: String): String = content
    .replace("\r\n", "\n")
    .replace(HtmlLineBreakRegex, "\n")

private fun buildLinkifiedAnnotatedString(content: String, linkColor: Color): AnnotatedString {
    val matcher = Patterns.WEB_URL.matcher(content)
    return buildAnnotatedString {
        var currentIndex = 0
        while (matcher.find()) {
            val rawMatch = matcher.group().orEmpty()
            val linkText = rawMatch.trimTrailingUrlPunctuation()
            if (linkText.isBlank()) {
                continue
            }

            append(content.substring(currentIndex, matcher.start()))
            withLink(
                LinkAnnotation.Url(
                    url = normalizeExternalUrl(linkText),
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            ) {
                append(linkText)
            }
            append(rawMatch.substring(linkText.length))
            currentIndex = matcher.end()
        }

        if (currentIndex < content.length) {
            append(content.substring(currentIndex))
        }
    }
}

private fun normalizeExternalUrl(url: String): String =
    if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
        url
    } else {
        "https://$url"
    }

private fun String.trimTrailingUrlPunctuation(): String = trimEnd('.', ',', ';', ':', ')', ']', '}')
