package fr.descentecanyon.app.ui.map

/** Camera position of a map, saved to restore it after navigation or recreation. */
data class MapCameraState(val latitude: Double, val longitude: Double, val zoom: Double)
