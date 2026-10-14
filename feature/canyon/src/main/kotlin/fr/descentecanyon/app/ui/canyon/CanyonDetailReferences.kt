package fr.descentecanyon.app.ui.canyon

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.BibliographyEntry
import fr.descentecanyon.app.domain.model.BibliographyKind
import fr.descentecanyon.app.domain.model.Regulation
import fr.descentecanyon.app.ui.common.openUrl
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.LocalDcShapes
import fr.descentecanyon.app.ui.theme.DebitCorrect
import fr.descentecanyon.app.ui.theme.DebitTresGros

@Composable
internal fun BibliographySection(entries: List<BibliographyEntry>, modifier: Modifier = Modifier) {
    val topoguides = entries.filter { it.kind == BibliographyKind.TOPOGUIDE }
    val maps = entries.filter { it.kind == BibliographyKind.MAP }
    val resources = entries.filter { it.kind == BibliographyKind.RESOURCE }

    var expanded by rememberSaveable { mutableStateOf(false) }

    DcCard(modifier = modifier.fillMaxWidth(), variant = DcCardVariant.Surface, contentPadding = 0.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.bibliography),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (topoguides.isNotEmpty()) {
                        BibliographyGroup(title = stringResource(R.string.topoguides_with_count, topoguides.size), entries = topoguides)
                    }
                    if (maps.isNotEmpty()) {
                        BibliographyGroup(title = stringResource(R.string.maps_with_count, maps.size), entries = maps)
                    }
                    if (resources.isNotEmpty()) {
                        BibliographyGroup(title = stringResource(R.string.resources_with_count, resources.size), entries = resources)
                    }
                }
            }
        }
    }
}

@Composable
private fun BibliographyGroup(title: String, entries: List<BibliographyEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = LocalDcColors.current.textSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        entries.forEach { entry ->
            DcCard(variant = DcCardVariant.Condition, contentPadding = 12.dp) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    entry.authors.takeIf { it.isNotEmpty() }?.let { authors ->
                        Text(
                            text = stringResource(R.string.bibliography_authors, authors.joinToString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    bibliographyMetaLine(entry)?.let { meta ->
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    entry.status?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    entry.url?.let { url ->
                        LinkRow(
                            icon = Icons.Default.Language,
                            label = url,
                            url = url,
                        )
                    }
                    entry.detailUrl?.let { url ->
                        LinkRow(
                            icon = Icons.Default.Description,
                            label = stringResource(R.string.open_reference),
                            url = url,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RegulationSection(regulations: List<Regulation>, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    DcCard(modifier = modifier.fillMaxWidth(), variant = DcCardVariant.Warning, contentPadding = 0.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.regulations_with_count, regulations.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    regulations.forEach { regulation ->
                        RegulationItem(regulation = regulation)
                    }
                }
            }
        }
    }
}

@Composable
private fun RegulationItem(regulation: Regulation, modifier: Modifier = Modifier) {
    val status = regulation.status.orEmpty()
    val isInactive = status.contains("obsol", ignoreCase = true) ||
        status.contains("abrog", ignoreCase = true)

    val containerColor = when {
        status.contains("actif", ignoreCase = true) -> DebitCorrect.copy(alpha = 0.12f)
        status.contains("temp", ignoreCase = true) -> DebitTresGros.copy(alpha = 0.12f)
        isInactive -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    }
    val accentColor = when {
        status.contains("actif", ignoreCase = true) -> DebitCorrect
        status.contains("temp", ignoreCase = true) -> DebitTresGros
        isInactive -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    var expanded by rememberSaveable(regulation.id) { mutableStateOf(!isInactive) }
    val statusText = regulation.status?.let { stringResource(R.string.regulation_status, it) }
    val actionText = regulation.action?.let { stringResource(R.string.regulation_action, it) }
    val effectiveDateText = regulation.effectiveDate?.let {
        stringResource(R.string.regulation_effective_date, it)
    }

    DcCard(
        modifier = modifier.fillMaxWidth(),
        variant = DcCardVariant.Warning,
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
                        text = regulation.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    RegulationStatusBadge(text = regulation.status ?: stringResource(R.string.regulation_status_unknown), accentColor)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = accentColor,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    val body = buildString {
                        appendLineIfNotBlank(statusText)
                        appendLineIfNotBlank(actionText)
                        appendLineIfNotBlank(effectiveDateText)
                        appendLineIfNotBlank(regulation.summary)
                        if (!isInactive) {
                            appendLineIfNotBlank(regulation.remark)
                            appendLineIfNotBlank(regulation.details)
                        }
                    }.trim()
                    if (body.isNotBlank()) {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    if (!isInactive) {
                        regulation.attachments.forEach { attachment ->
                            LinkRow(
                                icon = Icons.Default.Description,
                                label = attachment.label,
                                url = attachment.url,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    LinkRow(
                        icon = Icons.Default.Language,
                        label = stringResource(R.string.open_regulation_page),
                        url = regulation.textUrl,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RegulationStatusBadge(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = LocalDcShapes.current.pill,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.16f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.36f)),
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                context.openUrl(url)
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun bibliographyMetaLine(entry: BibliographyEntry): String? {
    val parts = buildList {
        entry.publicationYear?.let { add(it.toString()) }
        entry.editor?.let { add(it) }
        entry.reference?.let { add(it) }
        entry.scale?.let { add(it) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" - ")
}

private fun StringBuilder.appendLineIfNotBlank(value: String?) {
    if (!value.isNullOrBlank()) {
        if (isNotEmpty()) {
            append("\n\n")
        }
        append(value.trim())
    }
}
