package fr.descentecanyon.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.CotationRating
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.DcCardVariant
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.test.TestTags

/** The three parts of a cotation, one tab each. */
private enum class CotationPart { VERTICAL, AQUATIC, ENGAGEMENT }

/**
 * What a cotation means, after the technical classification standards of the FFME (2015), the
 * reference of the 2024 technical and safety rules: the canyon of [cotation] decoded, then each
 * scale level by level with the canyon's own level marked, and the limits of a cotation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CotationGuideDialog(cotation: String?, onDismiss: () -> Unit) {
    val rating = CotationRating.parse(cotation)
    var part by rememberSaveable { mutableStateOf(CotationPart.VERTICAL) }
    val contentWidth = rememberDcContentWidth(maxWidth = 720.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                CompactAppBar(
                    title = stringResource(R.string.cotation_guide_title),
                    navigation = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight().width(contentWidth).testTag(TestTags.COTATION_GUIDE),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (rating.isKnown) item(key = "canyon") { CanyonCotationCard(rating) }
                    item(key = "intro") { Text(stringResource(R.string.cotation_guide_intro), style = MaterialTheme.typography.bodyMedium) }
                    // Kept at the top while the levels scroll: another scale is one tap away.
                    stickyHeader(key = "tabs") {
                        PrimaryTabRow(selectedTabIndex = part.ordinal, containerColor = MaterialTheme.colorScheme.background) {
                            CotationPart.entries.forEach { option ->
                                Tab(
                                    selected = part == option,
                                    onClick = { part = option },
                                    text = { Text(stringResource(option.tabLabel())) },
                                )
                            }
                        }
                    }
                    val current = when (part) {
                        CotationPart.VERTICAL -> rating.vertical
                        CotationPart.AQUATIC -> rating.aquatic
                        CotationPart.ENGAGEMENT -> rating.engagement
                    }
                    part.levels().forEachIndexed { index, criteria ->
                        val level = index + 1
                        item(key = "${part.name}-$level") { LevelCard(part, level, criteria, isCurrent = level == current) }
                    }
                    item(key = "notes") { NotesCard() }
                    item(key = "source") {
                        Text(
                            stringResource(R.string.cotation_guide_source),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalDcColors.current.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/** The cotation of the canyon in words: one line per part. */
@Composable
private fun CanyonCotationCard(rating: CotationRating) {
    DcCard(modifier = Modifier.fillMaxWidth(), variant = DcCardVariant.Condition) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.cotation_guide_this_canyon, rating.raw),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            rating.vertical?.let { level ->
                PartLine(
                    code = "v$level",
                    text = levelName(level)?.let {
                        stringResource(R.string.cotation_guide_vertical_line, stringResource(it))
                    },
                )
            }
            rating.aquatic?.let { level ->
                PartLine(
                    code = "a$level",
                    text = levelName(level)?.let {
                        stringResource(R.string.cotation_guide_aquatic_line, stringResource(it))
                    },
                )
            }
            rating.engagement?.let { level ->
                PartLine(code = CotationRating.engagementLabel(level), text = ENGAGEMENT.getOrNull(level - 1)?.let { stringResource(it) })
            }
        }
    }
}

@Composable
private fun PartLine(code: String, text: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(code, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 36.dp))
        Text(text ?: stringResource(R.string.cotation_guide_beyond_scale), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LevelCard(part: CotationPart, level: Int, @StringRes criteria: Int, isCurrent: Boolean) {
    val code = when (part) {
        CotationPart.VERTICAL -> "v$level"
        CotationPart.AQUATIC -> "a$level"
        CotationPart.ENGAGEMENT -> CotationRating.engagementLabel(level)
    }
    val title = when (part) {
        CotationPart.ENGAGEMENT -> code
        else -> levelName(level)?.let { "$code · ${stringResource(it)}" } ?: code
    }
    DcCard(
        modifier = Modifier.fillMaxWidth(),
        variant = if (isCurrent) DcCardVariant.Condition else DcCardVariant.Surface,
        contentPadding = 12.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isCurrent) {
                    Text(
                        stringResource(R.string.cotation_guide_current),
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalDcColors.current.water,
                    )
                }
            }
            Text(stringResource(criteria), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NotesCard() {
    DcCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.cotation_guide_notes_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            listOf(
                R.string.cotation_guide_note_flow,
                R.string.cotation_guide_note_group,
                R.string.cotation_guide_note_criteria,
            ).forEach { note ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(note), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@StringRes
private fun CotationPart.tabLabel(): Int = when (this) {
    CotationPart.VERTICAL -> R.string.cotation_guide_tab_vertical
    CotationPart.AQUATIC -> R.string.cotation_guide_tab_aquatic
    CotationPart.ENGAGEMENT -> R.string.cotation_guide_tab_engagement
}

private fun CotationPart.levels(): List<Int> = when (this) {
    CotationPart.VERTICAL -> VERTICAL
    CotationPart.AQUATIC -> AQUATIC
    CotationPart.ENGAGEMENT -> ENGAGEMENT
}

/** The name of a difficulty level of the v and a scales; none beyond 7, the scale being open. */
@StringRes
private fun levelName(level: Int): Int? = LEVEL_NAMES.getOrNull(level - 1)

private val LEVEL_NAMES = listOf(
    R.string.cotation_level_1,
    R.string.cotation_level_2,
    R.string.cotation_level_3,
    R.string.cotation_level_4,
    R.string.cotation_level_5,
    R.string.cotation_level_6,
    R.string.cotation_level_7,
)

private val VERTICAL = listOf(
    R.string.cotation_guide_v1,
    R.string.cotation_guide_v2,
    R.string.cotation_guide_v3,
    R.string.cotation_guide_v4,
    R.string.cotation_guide_v5,
    R.string.cotation_guide_v6,
    R.string.cotation_guide_v7,
)

private val AQUATIC = listOf(
    R.string.cotation_guide_a1,
    R.string.cotation_guide_a2,
    R.string.cotation_guide_a3,
    R.string.cotation_guide_a4,
    R.string.cotation_guide_a5,
    R.string.cotation_guide_a6,
    R.string.cotation_guide_a7,
)

private val ENGAGEMENT = listOf(
    R.string.cotation_guide_e1,
    R.string.cotation_guide_e2,
    R.string.cotation_guide_e3,
    R.string.cotation_guide_e4,
    R.string.cotation_guide_e5,
    R.string.cotation_guide_e6,
)
