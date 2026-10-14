package fr.descentecanyon.app.domain.model

data class CanyonWatershed(val areaKm2: Double? = null, val geometryJson: String? = null, val bounds: GeoBounds? = null)

data class GeoBounds(val minLongitude: Double, val minLatitude: Double, val maxLongitude: Double, val maxLatitude: Double)
