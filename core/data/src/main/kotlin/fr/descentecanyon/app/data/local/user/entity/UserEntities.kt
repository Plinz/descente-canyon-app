package fr.descentecanyon.app.data.local.user.entity

import androidx.room.ColumnInfo
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
    /** Attempts that failed once the report may have reached the site: it is read back before the next one. */
    @ColumnInfo(defaultValue = "0") val failedAttempts: Int = 0,
    /** When the site refused the report: it is kept for the user and no longer sent. */
    val rejectedAt: Long? = null,
    /** The error the site displayed, if any. */
    val rejectionReason: String? = null,
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

/** Memo written by the user about a canyon; never sent to the site. */
@Entity(tableName = "canyon_notes")
data class CanyonNoteEntity(@PrimaryKey val canyonId: Int, val text: String, val updatedAt: Long)

/** An outing of the user's logbook; one per canyon and day. */
@Entity(tableName = "logbook_entries", indices = [Index(value = ["canyonId", "date"], unique = true)])
data class LogbookEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canyonId: Int,
    val canyonName: String,
    /** ISO date (yyyy-MM-dd), sortable as text. */
    val date: String,
    val note: String? = null,
    val createdAt: Long,
    /** NiveauDebit name of the flow seen. */
    val debitLevel: String? = null,
    /** Companions as typed, separated by commas. */
    val companions: String? = null,
    val durationMinutes: Int? = null,
    /** LogbookSource name: how the outing got into the logbook. */
    @ColumnInfo(defaultValue = "MANUAL") val source: String = "MANUAL",
    /** Report of the site account the outing was imported from. */
    val siteObservationId: Long? = null,
    val updatedAt: Long? = null,
)

/** An outing the user planned; [choicesJson] keeps their choices so the plan reopens as it was. */
@Entity(tableName = "planned_outings", indices = [Index("start")])
data class PlannedOutingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canyonId: Int,
    val canyonName: String,
    /** Leaving the car on foot, epoch ms. */
    val start: Long,
    val expectedBack: Long,
    val latestBack: Long,
    val alertAt: Long,
    /** Leaving the starting place, epoch ms. */
    val departure: Long? = null,
    val groupSize: Int? = null,
    /** The alert time last sent to a relative, epoch ms; null if the plan went to no one. */
    val informedAlertAt: Long? = null,
    val choicesJson: String? = null,
    /** The stay the outing was planned for. */
    val stayId: Long? = null,
    val updatedAt: Long,
)

/** A place outings start from. */
@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val lastUsedAt: Long = 0,
    val createdAt: Long,
)

/** Days spent at a place; [start] and [end] are ISO dates. */
@Entity(tableName = "stays", indices = [Index("placeId")])
data class StayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val placeId: Long,
    val start: String,
    val end: String,
    val createdAt: Long,
)

/** Drive time from a place to the parking of a canyon at [toLatitude], [toLongitude]; [source] is a DriveTimeSource name. */
@Entity(tableName = "drive_times", primaryKeys = ["placeId", "canyonId"])
data class DriveTimeEntity(
    val placeId: Long,
    val canyonId: Int,
    val toLatitude: Double,
    val toLongitude: Double,
    val minutes: Int,
    val distanceKm: Double? = null,
    val source: String,
    val updatedAt: Long,
)
