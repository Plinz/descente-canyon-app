package fr.descentecanyon.app.data.local.catalog.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.catalog.entity.WatershedEntity
import fr.descentecanyon.app.domain.model.CanyonWatershed
import fr.descentecanyon.app.domain.model.GeoBounds
import kotlinx.coroutines.flow.Flow

@Dao
interface WatershedDao {

    @Query(
        """
        SELECT areaKm2, bboxMinLongitude, bboxMinLatitude, bboxMaxLongitude, bboxMaxLatitude
        FROM watersheds
        WHERE canyonId = :canyonId
        """,
    )
    suspend fun getMetadataByCanyonId(canyonId: Int): WatershedMetadataProjection?

    @Query(
        """
        SELECT areaKm2, bboxMinLongitude, bboxMinLatitude, bboxMaxLongitude, bboxMaxLatitude
        FROM watersheds
        WHERE canyonId = :canyonId
        """,
    )
    fun observeMetadataByCanyonId(canyonId: Int): Flow<WatershedMetadataProjection?>

    @Query("SELECT geometryJson FROM watersheds WHERE canyonId = :canyonId")
    suspend fun getGeometryByCanyonId(canyonId: Int): String?
}

data class WatershedMetadataProjection(
    val areaKm2: Double? = null,
    val bboxMinLongitude: Double? = null,
    val bboxMinLatitude: Double? = null,
    val bboxMaxLongitude: Double? = null,
    val bboxMaxLatitude: Double? = null,
)

fun WatershedMetadataProjection.toDomain(): CanyonWatershed = CanyonWatershed(
    areaKm2 = areaKm2,
    geometryJson = null,
    bounds = if (
        bboxMinLongitude != null &&
        bboxMinLatitude != null &&
        bboxMaxLongitude != null &&
        bboxMaxLatitude != null
    ) {
        GeoBounds(
            minLongitude = bboxMinLongitude,
            minLatitude = bboxMinLatitude,
            maxLongitude = bboxMaxLongitude,
            maxLatitude = bboxMaxLatitude,
        )
    } else {
        null
    },
)
