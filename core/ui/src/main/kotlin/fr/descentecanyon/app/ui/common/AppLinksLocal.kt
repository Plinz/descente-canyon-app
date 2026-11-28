package fr.descentecanyon.app.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import fr.descentecanyon.app.domain.model.AppLinks

/** The links of the app's own domain, for the texts the screens share; none in previews and tests. */
val LocalAppLinks = staticCompositionLocalOf { AppLinks.NONE }
