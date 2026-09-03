package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.entity.CanyonFavoriteFolderCrossRef
import fr.descentecanyon.app.data.local.entity.FavoriteFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteFolderDao {

    @Query("SELECT * FROM favorite_folders ORDER BY createdAt ASC")
    fun getAllFolders(): Flow<List<FavoriteFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FavoriteFolderEntity): Long

    @Query("DELETE FROM favorite_folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: Int)

    @Query("DELETE FROM canyon_favorite_folder_cross_ref WHERE folderId = :folderId")
    suspend fun deleteFolderCrossRefs(folderId: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addCanyonToFolder(crossRef: CanyonFavoriteFolderCrossRef)

    @Query("DELETE FROM canyon_favorite_folder_cross_ref WHERE canyonId = :canyonId AND folderId = :folderId")
    suspend fun removeCanyonFromFolder(canyonId: Int, folderId: Int)

    @Query("DELETE FROM canyon_favorite_folder_cross_ref WHERE canyonId = :canyonId")
    suspend fun removeAllFoldersForCanyon(canyonId: Int)

    @Query("SELECT folderId FROM canyon_favorite_folder_cross_ref WHERE canyonId = :canyonId")
    fun getFolderIdsForCanyon(canyonId: Int): Flow<List<Int>>

    @Query("SELECT canyonId FROM canyon_favorite_folder_cross_ref WHERE folderId = :folderId")
    fun getCanyonIdsForFolder(folderId: Int): Flow<List<Int>>

    @Query("SELECT * FROM canyon_favorite_folder_cross_ref")
    fun getAllCrossRefs(): Flow<List<CanyonFavoriteFolderCrossRef>>
}
