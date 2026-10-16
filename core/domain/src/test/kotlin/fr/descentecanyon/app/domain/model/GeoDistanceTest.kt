package fr.descentecanyon.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoDistanceTest {

    @Test
    fun `bearings point to the compass direction of the target`() {
        assertEquals(0.0, bearingDegrees(45.0, 6.0, 45.1, 6.0), 0.1)
        assertEquals(90.0, bearingDegrees(45.0, 6.0, 45.0, 6.1), 0.1)
        assertEquals(180.0, bearingDegrees(45.0, 6.0, 44.9, 6.0), 0.1)
        assertEquals(270.0, bearingDegrees(45.0, 6.0, 45.0, 5.9), 0.1)
        assertEquals(CompassDirection.NE, CompassDirection.of(bearingDegrees(45.0, 6.0, 45.01, 6.014)))
        assertEquals(CompassDirection.N, CompassDirection.of(359.0))
        assertEquals(CompassDirection.N, CompassDirection.of(-10.0))
        assertEquals(CompassDirection.SW, CompassDirection.of(225.0))
        assertEquals(CompassDirection.NW, CompassDirection.of(337.0))
    }

    @Test
    fun `the distance to a segment is the one to its closest point`() {
        // A segment 2 km long, west to east, 1 km north of the position.
        val north = 45.0 + 1.0 / 111.195
        assertEquals(1.0, distanceToSegmentKm(45.0, 6.0, north, 5.99, north, 6.01), 0.01)
        // Beyond the end of the segment, the distance is the one to that end.
        val end = haversineKm(45.0, 6.0, north, 5.98)
        assertEquals(end, distanceToSegmentKm(45.0, 6.0, north, 5.96, north, 5.98), 0.01)
        // A segment reduced to a point.
        assertEquals(haversineKm(45.0, 6.0, 45.01, 6.0), distanceToSegmentKm(45.0, 6.0, 45.01, 6.0, 45.01, 6.0), 0.01)
    }

    @Test
    fun `the distance to a canyon follows its route from the parking to the exit`() {
        val points = listOf(
            GeoPoint(canyonId = 1, type = GeoPointType.PARKING_AVAL, latitude = 45.0, longitude = 6.0),
            GeoPoint(canyonId = 1, type = GeoPointType.ENTREE, latitude = 45.03, longitude = 6.0),
            GeoPoint(canyonId = 1, type = GeoPointType.SORTIE, latitude = 45.03, longitude = 6.04),
        )
        // Halfway between the entry and the exit, 3 km from both: on the route.
        assertEquals(0.0, distanceToCanyonKm(45.03, 6.02, points)!!, 0.01)
        // On the approach from the downstream parking, without upstream parking.
        assertEquals(0.0, distanceToCanyonKm(45.015, 6.0, points)!!, 0.01)
        assertNull(distanceToCanyonKm(45.0, 6.0, emptyList()))
    }
}
