package fr.descentecanyon.app.data.local.catalog.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.catalog.entity.SearchIndexEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchIndexDao {
    @Query("SELECT * FROM search_index ORDER BY id")
    fun observeAll(): Flow<List<SearchIndexEntity>>

    @Query("SELECT COUNT(*) FROM search_index")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SearchIndexEntity)
}
