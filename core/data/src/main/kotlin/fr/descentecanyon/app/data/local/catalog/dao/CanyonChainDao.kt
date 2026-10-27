package fr.descentecanyon.app.data.local.catalog.dao

import androidx.room.Dao
import androidx.room.Query
import fr.descentecanyon.app.data.local.catalog.entity.CanyonChainEntity
import fr.descentecanyon.app.domain.model.CanyonChain

@Dao
interface CanyonChainDao {

    @Query("SELECT * FROM canyon_chains WHERE fromCanyonId = :canyonId")
    suspend fun getFrom(canyonId: Int): List<CanyonChainEntity>
}

/** Null for a way this version of the app does not know. */
fun CanyonChainEntity.toDomain(): CanyonChain? = CanyonChain.Way.entries.firstOrNull { it.name == way }?.let {
    CanyonChain(fromCanyonId = fromCanyonId, toCanyonId = toCanyonId, way = it, minutes = minutes)
}
