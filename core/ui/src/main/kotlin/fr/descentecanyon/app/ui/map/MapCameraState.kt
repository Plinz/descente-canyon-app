package fr.descentecanyon.app.ui.map

/** Camera position of a map, saved to restore it after navigation or recreation. */
data class MapCameraState(val latitude: Double, val longitude: Double, val zoom: Double)

/** Asks a map to move to a position, such as a place found by its name; a new [id] moves it again. */
data class MapFocusRequest(val id: Int, val latitude: Double, val longitude: Double, val zoom: Double)

/**
 * Asks a map to show the area from [south]-[west] to [north]-[east], such as the canyons found by a
 * search, clear of the controls over its edges ([paddingTop], [paddingBottom], [paddingSides], in
 * pixels); a new [id] moves it again.
 */
data class MapBoundsRequest(
    val id: Int,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val paddingTop: Int = 0,
    val paddingBottom: Int = 0,
    val paddingSides: Int = 0,
)

/**
 * Room kept clear around what a map frames by itself (its points, tracks and watershed), under the
 * controls drawn over its edges, in dp. A map given one frames tightly, up to a street-level zoom.
 */
data class MapFitPadding(val start: Float, val top: Float, val end: Float, val bottom: Float)
