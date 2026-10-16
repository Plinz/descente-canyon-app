package fr.descentecanyon.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import fr.descentecanyon.app.domain.model.ThemeMode
import fr.descentecanyon.app.domain.repository.PreferencesRepository
import fr.descentecanyon.app.ui.outing.EmergencyScreen
import fr.descentecanyon.app.ui.outing.EmergencyViewModel
import fr.descentecanyon.app.ui.theme.DescenteCanyonTheme
import javax.inject.Inject

/**
 * The emergency screen on its own, opened straight from the launcher shortcut or from the outing
 * notification, also over the lock screen (manifest): no startup, no navigation, nothing to unlock
 * to read the position and the numbers. Calling or texting still goes through the system apps.
 */
@AndroidEntryPoint
class EmergencyActivity : ComponentActivity() {
    @Inject lateinit var preferencesRepository: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by preferencesRepository.observeThemeMode()
                .collectAsStateWithLifecycle(initialValue = preferencesRepository.getThemeMode())
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // System bar icons follow the app theme, which can differ from the system one.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                )
                onDispose {}
            }
            DescenteCanyonTheme(darkTheme = darkTheme) {
                EmergencyScreen(onBackClick = ::finish)
            }
        }
    }

    companion object {
        /** [canyonId] 0 lets the screen find the canyon from the outing under way or the position. */
        fun intent(context: Context, canyonId: Int = 0): Intent = Intent(context, EmergencyActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            // The view model reads it from its saved state, which the activity fills with its extras.
            .putExtra(EmergencyViewModel.CANYON_ID_KEY, canyonId)
            // A fresh screen for each request: the canyon asked for may not be the one shown before.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
