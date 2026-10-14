package fr.descentecanyon.app.domain.model

data class CachedItems<T>(val items: List<T> = emptyList(), val syncedAtEpochMs: Long? = null)
