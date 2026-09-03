package fr.descentecanyon.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "daily_weather",
    primaryKeys = ["canyonId", "date"],
    foreignKeys = [
        ForeignKey(
            entity = CanyonEntity::class,
            parentColumns = ["id"],
            childColumns = ["canyonId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("canyonId")],
)
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
    val weatherCode: Int? = null,
    val fetchedAtEpochMs: Long,
)
