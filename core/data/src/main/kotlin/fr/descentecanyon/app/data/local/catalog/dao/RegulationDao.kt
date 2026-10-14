package fr.descentecanyon.app.data.local.catalog.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.catalog.entity.CanyonRegulationEntity
import fr.descentecanyon.app.data.local.catalog.entity.RegulationTextEntity

@Dao
interface RegulationDao {

    @Query(
        """
        SELECT rt.*
        FROM regulation_texts rt
        INNER JOIN canyon_regulations cr ON cr.regulationId = rt.id
        WHERE cr.canyonId = :canyonId
        ORDER BY rt.title
        """,
    )
    suspend fun getByCanyonId(canyonId: Int): List<RegulationTextEntity>
}
