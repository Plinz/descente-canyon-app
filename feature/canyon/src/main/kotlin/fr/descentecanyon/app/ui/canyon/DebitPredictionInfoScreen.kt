package fr.descentecanyon.app.ui.canyon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.DebitPredictionDriver
import fr.descentecanyon.app.domain.model.DebitPredictionInfoSummary
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.components.CompactAppBar
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebitPredictionInfoScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: DebitPredictionInfoViewModel = hiltViewModel(),
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.prediction_info_title),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        when {
            uiState.isLoading -> LoadingContent(innerPadding)
            uiState.summary != null -> DebitPredictionInfoContent(
                summary = uiState.summary,
                modifier = Modifier.padding(innerPadding),
                contentPadding = contentPadding,
            )
            else -> ErrorContent(
                message = uiState.error?.asString() ?: stringResource(R.string.prediction_info_error),
                onBackClick = onBackClick,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun LoadingContent(innerPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorContent(message: String, onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onBackClick) {
            Text(text = stringResource(R.string.back))
        }
    }
}

@Composable
private fun DebitPredictionInfoContent(summary: DebitPredictionInfoSummary, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val numberFormat = NumberFormat.getIntegerInstance(Locale.getDefault())

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            InfoIntroCard(
                title = stringResource(R.string.prediction_info_intro_title),
                body = stringResource(R.string.prediction_info_intro_body),
            )
        }

        item {
            InfoSection(
                title = stringResource(R.string.prediction_info_reading_title),
                paragraphs = listOf(
                    stringResource(R.string.prediction_info_reading_p1),
                    stringResource(R.string.prediction_info_reading_p2),
                    stringResource(R.string.prediction_info_reading_p3),
                    stringResource(R.string.prediction_info_reading_p4),
                ),
            )
        }

        item {
            InfoSection(
                title = stringResource(R.string.prediction_info_data_title),
                paragraphs = listOf(
                    stringResource(R.string.prediction_info_data_p1),
                    stringResource(R.string.prediction_info_data_p2),
                ),
            )
        }

        item {
            StatsCard(
                title = stringResource(R.string.prediction_info_training_title),
                stats = listOf(
                    stringResource(R.string.prediction_info_stat_total_observations, numberFormat.format(summary.totalObservationCount)),
                    stringResource(R.string.prediction_info_stat_train_rows, numberFormat.format(summary.trainRowCount)),
                    stringResource(R.string.prediction_info_stat_calibration_rows, numberFormat.format(summary.calibrationRowCount)),
                    stringResource(R.string.prediction_info_stat_test_rows, numberFormat.format(summary.testRowCount)),
                    stringResource(R.string.prediction_info_stat_feature_count, numberFormat.format(summary.featureCount)),
                    stringResource(R.string.prediction_info_stat_canyon_count, numberFormat.format(summary.canyonCount)),
                    stringResource(R.string.prediction_info_stat_massif_count, numberFormat.format(summary.massifCount)),
                    stringResource(R.string.prediction_info_stat_region_count, numberFormat.format(summary.regionCount)),
                ),
            )
        }

        item {
            InfoSection(
                title = stringResource(R.string.prediction_info_influence_title),
                paragraphs = listOf(
                    stringResource(R.string.prediction_info_influence_p1),
                ),
            )
        }

        items(summary.topDrivers) { driver ->
            DriverCard(title = stringResource(driver.titleRes), description = stringResource(driver.descriptionRes))
        }

        item {
            InfoSection(
                title = stringResource(R.string.prediction_info_limits_title),
                paragraphs = listOf(
                    stringResource(R.string.prediction_info_limits_p1),
                    stringResource(R.string.prediction_info_limits_p2),
                    stringResource(R.string.prediction_info_limits_p3),
                ),
            )
        }

        item {
            InfoSection(
                title = stringResource(R.string.prediction_info_good_use_title),
                paragraphs = listOf(
                    stringResource(R.string.prediction_info_good_use_p1),
                    stringResource(R.string.prediction_info_good_use_p2),
                ),
            )
        }
    }
}

@Composable
private fun InfoIntroCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun InfoSection(title: String, paragraphs: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        paragraphs.forEach { paragraph ->
            Text(text = paragraph, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatsCard(title: String, stats: List<String>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            stats.forEach { stat ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(text = "•", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stat, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DriverCard(title: String, description: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val DebitPredictionDriver.titleRes: Int
    get() = when (this) {
        DebitPredictionDriver.CANYON_HISTORY -> R.string.prediction_driver_canyon_history_title
        DebitPredictionDriver.AREA_CONTEXT -> R.string.prediction_driver_area_context_title
        DebitPredictionDriver.RECENT_RAIN -> R.string.prediction_driver_recent_rain_title
        DebitPredictionDriver.TEMPERATURE_SNOW -> R.string.prediction_driver_temperature_snow_title
        DebitPredictionDriver.SEASON -> R.string.prediction_driver_season_title
        DebitPredictionDriver.WATERSHED -> R.string.prediction_driver_watershed_title
    }

private val DebitPredictionDriver.descriptionRes: Int
    get() = when (this) {
        DebitPredictionDriver.CANYON_HISTORY -> R.string.prediction_driver_canyon_history_description
        DebitPredictionDriver.AREA_CONTEXT -> R.string.prediction_driver_area_context_description
        DebitPredictionDriver.RECENT_RAIN -> R.string.prediction_driver_recent_rain_description
        DebitPredictionDriver.TEMPERATURE_SNOW -> R.string.prediction_driver_temperature_snow_description
        DebitPredictionDriver.SEASON -> R.string.prediction_driver_season_description
        DebitPredictionDriver.WATERSHED -> R.string.prediction_driver_watershed_description
    }
