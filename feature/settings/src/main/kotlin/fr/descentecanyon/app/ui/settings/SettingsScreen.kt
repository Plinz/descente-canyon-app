package fr.descentecanyon.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.ThemeMode
import fr.descentecanyon.app.ui.common.openUrl
import fr.descentecanyon.app.ui.common.startActivitySafely
import fr.descentecanyon.app.ui.components.CompactAppBar
import fr.descentecanyon.app.ui.design.DcCard
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.rememberDcContentWidth
import fr.descentecanyon.app.ui.design.rememberDcScreenHorizontalPadding

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onOpenPendingDebits: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val contentWidth = rememberDcContentWidth()
    val horizontalPadding = rememberDcScreenHorizontalPadding()
    var openDialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }
    val versionName = remember(context) { context.appVersionName() }

    openDialog?.let { dialog ->
        SettingsTextDialog(dialog = dialog, onDismiss = { openDialog = null })
    }

    Scaffold(
        containerColor = LocalDcColors.current.backgroundBase,
        topBar = {
            CompactAppBar(
                title = stringResource(R.string.settings_title),
                navigation = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .width(contentWidth)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = 16.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
                        ThemeModeOptions(selected = uiState.themeMode, onSelected = viewModel::setThemeMode)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    item {
                        SettingsSection(title = stringResource(R.string.settings_section_language)) {
                            SettingsLinkRow(
                                icon = Icons.Default.Language,
                                title = stringResource(R.string.settings_language_app),
                                subtitle = context.currentLanguageName(),
                                onClick = { context.openAppLocaleSettings() },
                            )
                        }
                    }
                }
                item {
                    SettingsSection(title = stringResource(R.string.settings_section_notifications)) {
                        SettingsLinkRow(
                            icon = Icons.Default.Notifications,
                            title = stringResource(R.string.settings_notifications_system),
                            subtitle = stringResource(R.string.settings_notifications_system_hint),
                            onClick = { context.openAppNotificationSettings() },
                        )
                    }
                }
                item {
                    SettingsSection(title = stringResource(R.string.settings_section_contributions)) {
                        SettingsLinkRow(
                            icon = Icons.Default.CloudUpload,
                            title = stringResource(R.string.pending_debits_title),
                            subtitle = if (uiState.pendingDebitCount > 0) {
                                pluralStringResource(R.plurals.debit_pending_count, uiState.pendingDebitCount, uiState.pendingDebitCount)
                            } else {
                                stringResource(R.string.pending_debits_empty_title)
                            },
                            onClick = onOpenPendingDebits,
                        )
                    }
                }
                item {
                    SettingsSection(title = stringResource(R.string.settings_section_offline)) {
                        uiState.catalogSize?.let { size ->
                            Text(
                                text = pluralStringResource(R.plurals.settings_offline_catalog, size, size),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_offline_refresh),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalDcColors.current.textSecondary,
                        )
                    }
                }
                item {
                    SettingsSection(title = stringResource(R.string.settings_section_about)) {
                        SettingsLinkRow(
                            icon = Icons.Default.Info,
                            title = stringResource(R.string.settings_version),
                            subtitle = versionName,
                            onClick = null,
                        )
                        Text(
                            text = stringResource(R.string.settings_unofficial),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalDcColors.current.textSecondary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        SettingsLinkRow(
                            icon = Icons.Default.Public,
                            title = stringResource(R.string.settings_website),
                            subtitle = stringResource(R.string.settings_website_hint),
                            trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                            onClick = { context.openUrl(SITE_URL) },
                        )
                        SettingsLinkRow(
                            icon = Icons.Default.Source,
                            title = stringResource(R.string.settings_data_sources),
                            onClick = { openDialog = SettingsDialog.DATA_SOURCES },
                        )
                        SettingsLinkRow(
                            icon = Icons.Default.Code,
                            title = stringResource(R.string.settings_licenses),
                            onClick = { openDialog = SettingsDialog.LICENSES },
                        )
                        SettingsLinkRow(
                            icon = Icons.Default.PrivacyTip,
                            title = stringResource(R.string.settings_privacy),
                            onClick = { openDialog = SettingsDialog.PRIVACY },
                        )
                    }
                }
            }
        }
    }
}

private enum class SettingsDialog {
    DATA_SOURCES,
    LICENSES,
    PRIVACY,
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        DcCard(modifier = Modifier.fillMaxWidth(), contentPadding = 8.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 8.dp), content = content)
        }
    }
}

@Composable
private fun ThemeModeOptions(selected: ThemeMode, onSelected: (ThemeMode) -> Unit) {
    Column(modifier = Modifier.selectableGroup()) {
        ThemeMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = mode == selected, role = Role.RadioButton, onClick = { onSelected(mode) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = mode == selected, onClick = null)
                Text(
                    text = when (mode) {
                        ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                        ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun SettingsLinkRow(
    icon: ImageVector,
    title: String,
    onClick: (() -> Unit)?,
    subtitle: String? = null,
    trailingIcon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = LocalDcColors.current.textSecondary)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = LocalDcColors.current.textSecondary)
            }
        }
        if (trailingIcon != null) {
            Icon(trailingIcon, contentDescription = null, tint = LocalDcColors.current.textMuted)
        }
    }
}

@Composable
private fun SettingsTextDialog(dialog: SettingsDialog, onDismiss: () -> Unit) {
    val title = when (dialog) {
        SettingsDialog.DATA_SOURCES -> stringResource(R.string.settings_data_sources)
        SettingsDialog.LICENSES -> stringResource(R.string.settings_licenses)
        SettingsDialog.PRIVACY -> stringResource(R.string.settings_privacy)
    }
    val body = when (dialog) {
        SettingsDialog.DATA_SOURCES -> stringResource(R.string.settings_data_sources_body)
        SettingsDialog.LICENSES -> stringResource(R.string.settings_licenses_intro) + "\n\n" +
            OPEN_SOURCE_LIBRARIES.joinToString("\n") { "• $it" }
        SettingsDialog.PRIVACY -> stringResource(R.string.settings_privacy_body)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

private fun Context.appVersionName(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName
}.getOrNull().orEmpty()

private fun Context.currentLanguageName(): String {
    val locale = resources.configuration.locales[0]
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

private fun Context.openAppNotificationSettings() {
    startActivitySafely(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
}

private fun Context.openAppLocaleSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        startActivitySafely(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}

private const val SITE_URL = "https://www.descente-canyon.com"

/** Main libraries shipped in the app and their licenses. */
private val OPEN_SOURCE_LIBRARIES = listOf(
    "Android Jetpack (Compose, Room, WorkManager, Navigation, DataStore…) — Apache 2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization — Apache 2.0",
    "Dagger Hilt — Apache 2.0",
    "OkHttp — Apache 2.0",
    "Coil — Apache 2.0",
    "jsoup — MIT",
    "MapLibre Native — BSD 2-Clause",
    "ONNX Runtime — MIT",
    "Google Play services Location — Android SDK License",
)
