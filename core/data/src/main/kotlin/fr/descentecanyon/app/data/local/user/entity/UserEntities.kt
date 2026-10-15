package fr.descentecanyon.app.data.local.user.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Data owned by the user or fetched at runtime. Canyon ids refer to the catalog database: there is
 * no foreign key across databases, a canyon missing from a newer catalog is simply skipped.
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(@PrimaryKey val canyonId: Int, val addedAt: Long = System.currentTimeMillis())

/** A debit report written offline (or when the site could not be reached), waiting to be sent. */
@Entity(tableName = "pending_debit_submissions")
data class PendingDebitSubmissionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canyonId: Int,
    val observerName: String,
    val observerEmail: String?,
    val observationDate: String,
    val isDescended: Boolean,
    val debitLevel: String,
    val waterTemperature: String,
    val airTemperature: String,
    val comment: String,
    val personalComment: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** Debit observations of a canyon, cached from its observations page. */
@Entity(tableName = "debits", indices = [Index("canyonId")])
data class DebitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canyonId: Int,
    val date: String, // ISO date string (yyyy-MM-dd)
    val niveau: String, // NiveauDebit name
    val auteur: String? = null,
    val isDescended: Boolean? = null,
    val waterTemperature: String? = null,
    val airTemperature: String? = null,
    val commentaire: String? = null,
)

/** Photos of a canyon, cached from its photo page; [localPath] is set once saved to the gallery. */
@Entity(tableName = "photos", indices = [Index("canyonId")])
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canyonId: Int,
    val url: String,
    val thumbnailUrl: String? = null,
    val auteur: String? = null,
    val description: String? = null,
    val localPath: String? = null,
)

/** Daily weather inputs of the debit model, cached per canyon. */
@Entity(tableName = "daily_weather", primaryKeys = ["canyonId", "date"], indices = [Index("canyonId")])
data class DailyWeatherEntity(
    val canyonId: Int,
    val date: String,
    val timezone: String,
    val targetLatitude: Double,
    val targetLongitude: Double,
    val targetSource: String,
    val sourceKind: String,
    val precipitationSum: Double? = null,
    val rainSum: Double? = null,
    val snowfallSum: Double? = null,
    val temperature2mMean: Double? = null,
    val temperature2mMin: Double? = null,
    val temperature2mMax: Double? = null,
    val precipitationHours: Double? = null,
    val fetchedAtEpochMs: Long,
)

/** Small persisted values: home feed snapshots, selected feed... */
@Entity(tableName = "key_values")
data class KeyValueEntity(@PrimaryKey val key: String, val value: String, val updatedAt: Long = System.currentTimeMillis())

/** Canyons whose detail the user opened, most recent first (trimmed to a few dozen). */
@Entity(tableName = "recent_canyons")
data class RecentCanyonEntity(@PrimaryKey val canyonId: Int, val viewedAt: Long)

/** Private note written by the user about a canyon; never sent anywhere. */
@Entity(tableName = "canyon_notes")
data class CanyonNoteEntity(@PrimaryKey val canyonId: Int, val text: String, val updatedAt: Long)
