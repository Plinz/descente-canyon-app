package fr.descentecanyon.app.data.local.catalog.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.catalog.entity.BibliographyEntryEntity
import fr.descentecanyon.app.data.local.catalog.entity.CanyonBibliographyEntity

@Dao
interface BibliographyDao {

    @Query(
        """
        SELECT be.*
        FROM bibliography_entries be
        INNER JOIN canyon_bibliography cb ON cb.bibliographyId = be.id
        WHERE cb.canyonId = :canyonId
        ORDER BY be.kind, be.title
        """,
    )
    suspend fun getByCanyonId(canyonId: Int): List<BibliographyEntryEntity>
}
