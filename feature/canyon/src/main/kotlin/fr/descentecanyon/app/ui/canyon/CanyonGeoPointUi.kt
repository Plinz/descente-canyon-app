package fr.descentecanyon.app.ui.canyon

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.GeoPoint
import fr.descentecanyon.app.domain.model.GeoPointSource
import fr.descentecanyon.app.domain.model.GeoPointType
import fr.descentecanyon.app.ui.theme.CanyonBlue
import fr.descentecanyon.app.ui.theme.CanyonBlueDark
import fr.descentecanyon.app.ui.theme.CotationDifficile
import fr.descentecanyon.app.ui.theme.CotationFacile
import fr.descentecanyon.app.ui.theme.DebitInconnu
import fr.descentecanyon.app.ui.theme.RockBrownLight
import java.util.Locale

@Composable
fun GeoPoint.displayName(): String = normalizedTitleResource()?.let { stringResource(it) } ?: normalizedRawTitle() ?: when (type) {
    GeoPointType.PARKING_AMONT -> stringResource(R.string.geo_point_parking_upstream)
    GeoPointType.PARKING_AVAL -> stringResource(R.string.geo_point_parking_downstream)
    GeoPointType.ENTREE -> stringResource(R.string.geo_point_entry)
    GeoPointType.SORTIE -> stringResource(R.string.geo_point_exit)
    GeoPointType.POINT_REMARQUABLE -> stringResource(R.string.geo_point_remarkable)
    GeoPointType.ECHAPPATOIRE -> stringResource(R.string.geo_point_escape)
    GeoPointType.UNKNOWN -> stringResource(R.string.geo_point_gps)
}

fun GeoPoint.displaySubtitle(): String? = remark?.trim()?.takeIf { it.isNotBlank() }

/** Where the point comes from when it is not on the canyon sheet of the site, so that it is checked on the spot. */
@Composable
fun GeoPoint.sourceNote(): String? = sourceNoteResource()?.let { stringResource(it) }

@StringRes
fun GeoPoint.sourceNoteResource(): Int? = when (source) {
    GeoPointSource.SITE -> null
    GeoPointSource.TOPO -> R.string.geo_point_source_topo
    GeoPointSource.OPENCANYON -> R.string.geo_point_source_opencanyon
    GeoPointSource.ESTIMATED -> R.string.geo_point_source_estimated
}

fun GeoPoint.navigationLabel(context: Context): String =
    normalizedTitleResource()?.let(context::getString) ?: normalizedRawTitle() ?: context.getString(type.defaultTitleResource())

@StringRes
private fun GeoPoint.normalizedTitleResource(): Int? {
    val rawTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when (rawTitle.lowercase(Locale.FRENCH)) {
        "parking amont" -> R.string.geo_point_parking_upstream
        "parking aval" -> R.string.geo_point_parking_downstream
        "parking" -> R.string.geo_point_parking
        "départ du canyon", "depart du canyon" -> R.string.geo_point_entry
        "sortie du canyon" -> R.string.geo_point_exit
        "point remarquable de l'approche ou du retour" -> R.string.geo_point_remarkable_approach_return
        "point remarquable à l'intérieur du canyon", "point remarquable a l'interieur du canyon" -> R.string.geo_point_remarkable_inside
        else -> null
    }
}

private fun GeoPoint.normalizedRawTitle(): String? {
    val rawTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return rawTitle.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString() }
}

@StringRes
private fun GeoPointType.defaultTitleResource(): Int = when (this) {
    GeoPointType.PARKING_AMONT -> R.string.geo_point_parking_upstream
    GeoPointType.PARKING_AVAL -> R.string.geo_point_parking_downstream
    GeoPointType.ENTREE -> R.string.geo_point_entry
    GeoPointType.SORTIE -> R.string.geo_point_exit
    GeoPointType.POINT_REMARQUABLE -> R.string.geo_point_remarkable
    GeoPointType.ECHAPPATOIRE -> R.string.geo_point_escape
    GeoPointType.UNKNOWN -> R.string.geo_point_gps
}

fun GeoPointType.navigationPriority(): Int = when (this) {
    GeoPointType.PARKING_AMONT -> 0
    GeoPointType.PARKING_AVAL -> 1
    GeoPointType.ENTREE -> 2
    GeoPointType.SORTIE -> 3
    GeoPointType.POINT_REMARQUABLE -> 4
    GeoPointType.ECHAPPATOIRE -> 5
    GeoPointType.UNKNOWN -> 6
}

fun GeoPointType.mapColor(): Color = when (this) {
    GeoPointType.PARKING_AMONT -> CanyonBlue
    GeoPointType.PARKING_AVAL -> Color(0xFF7C3AED)
    GeoPointType.ENTREE -> CotationFacile
    GeoPointType.SORTIE -> CotationDifficile
    GeoPointType.POINT_REMARQUABLE -> RockBrownLight
    GeoPointType.ECHAPPATOIRE -> CanyonBlueDark
    GeoPointType.UNKNOWN -> DebitInconnu
}
