package fr.descentecanyon.app.data.local.catalog.entity

import androidx.room.Entity
import androidx.room.Index

/** A reviewed passage from the end of a canyon to the start of another one (data-overlay/canyon-chains.json). */
@Entity(
    tableName = "canyon_chains",
    primaryKeys = ["fromCanyonId", "toCanyonId"],
    indices = [Index(value = ["toCanyonId"])],
)
data class CanyonChainEntity(
    val fromCanyonId: Int,
    val toCanyonId: Int,
    /** A [fr.descentecanyon.app.domain.model.CanyonChain.Way] name. */
    val way: String,
    val minutes: Int? = null,
)
