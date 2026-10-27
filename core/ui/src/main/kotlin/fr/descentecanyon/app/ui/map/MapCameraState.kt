package fr.descentecanyon.app.ui.map

/** Camera position of a map, saved to restore it after navigation or recreation. */
data class MapCameraState(val latitude: Double, val longitude: Double, val zoom: Double)

/** Asks a map to move to a position, such as a place found by its name; a new [id] moves it again. */
data class MapFocusRequest(val id: Int, val latitude: Double, val longitude: Double, val zoom: Double)
