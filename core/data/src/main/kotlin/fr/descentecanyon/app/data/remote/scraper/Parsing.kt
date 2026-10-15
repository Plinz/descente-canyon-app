package fr.descentecanyon.app.data.remote.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs a parser on a background thread: walking a big page (a canyon with thousands of flow
 * reports) takes seconds on slow phones and must never block the main thread.
 */
internal suspend fun <T> parsing(block: () -> T): T = withContext(Dispatchers.Default) { block() }
