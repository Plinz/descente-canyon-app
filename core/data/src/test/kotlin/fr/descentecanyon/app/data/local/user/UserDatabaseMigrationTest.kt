package fr.descentecanyon.app.data.local.user

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import fr.descentecanyon.app.data.local.user.entity.RecentCanyonEntity
import fr.descentecanyon.app.domain.model.LogbookEntry
import fr.descentecanyon.app.domain.model.LogbookSource
import fr.descentecanyon.app.domain.model.NiveauDebit
import fr.descentecanyon.app.testutil.TestDatabases
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every user database version must open with today's schema without losing the user's data. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UserDatabaseMigrationTest {

    private val context = TestDatabases.context

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun `version 1 keeps favorites, pending reports and values and gains recents and notes`() = runTest {
        createDatabase(schemaVersion = 1) { db ->
            db.insert("favorites", null, values("canyonId" to 2224, "addedAt" to 1_000L))
            db.insert(
                "pending_debit_submissions",
                null,
                values(
                    "id" to 3, "canyonId" to 2224, "observerName" to "Antoine", "observerEmail" to "a@example.com",
                    "observationDate" to "2026-10-14", "isDescended" to 1, "debitLevel" to "SEC", "waterTemperature" to "INCONNUE",
                    "airTemperature" to "INCONNUE", "comment" to "", "personalComment" to "", "createdAt" to 2_000L,
                ),
            )
            db.insert("key_values", null, values("key" to "contributor.name", "value" to "Antoine", "updatedAt" to 3_000L))
        }

        val database = Room.databaseBuilder(context, UserDatabase::class.java, TEST_DATABASE)
            .addMigrations(*UserDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(listOf(2224), database.favoriteDao().observeIds().first())
            assertEquals(listOf(3L), database.pendingDebitSubmissionDao().getSendable().map { it.id })
            assertEquals("Antoine", database.keyValueDao().get("contributor.name"))

            database.recentCanyonDao().recordView(RecentCanyonEntity(canyonId = 2224, viewedAt = 4_000L), keep = 30)
            database.canyonNoteDao().upsert(
                fr.descentecanyon.app.data.local.user.entity.CanyonNoteEntity(canyonId = 2224, text = "Parking", updatedAt = 5_000L),
            )
            assertEquals(listOf(2224), database.recentCanyonDao().observeRecentIds(10).first())
            assertEquals("Parking", database.canyonNoteDao().observe(2224).first())
        } finally {
            database.close()
        }
    }

    @Test
    fun `version 2 keeps notes and gains the logbook, one entry per canyon and day`() = runTest {
        createDatabase(schemaVersion = 2) { db ->
            db.insert("canyon_notes", null, values("canyonId" to 7, "text" to "Corde 2x40", "updatedAt" to 1_000L))
        }

        val database = Room.databaseBuilder(context, UserDatabase::class.java, TEST_DATABASE)
            .addMigrations(*UserDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("Corde 2x40", database.canyonNoteDao().observe(7).first())
            val repository = fr.descentecanyon.app.data.repository.LogbookRepositoryImpl(
                database.logbookDao(),
                java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC),
            )
            repository.save(LogbookEntry(canyonId = 7, canyonName = "Riolan", date = java.time.LocalDate.of(2026, 7, 14)))
            repository.save(
                LogbookEntry(canyonId = 7, canyonName = "Riolan", date = java.time.LocalDate.of(2026, 7, 14), note = "Débit correct"),
            )
            repository.save(LogbookEntry(canyonId = 8, canyonName = "Bollinette", date = java.time.LocalDate.of(2026, 8, 2)))

            val entries = repository.observeAll().first()
            assertEquals(listOf(8, 7), entries.map { it.canyonId })
            assertEquals("Débit correct", entries.last().note)
        } finally {
            database.close()
        }
    }

    @Test
    fun `version 3 keeps pending reports, to be sent and not refused, and can keep refused ones`() = runTest {
        createDatabase(schemaVersion = 3) { db ->
            db.insert(
                "pending_debit_submissions",
                null,
                values(
                    "id" to 5, "canyonId" to 2224, "observerName" to "Antoine", "observerEmail" to "a@example.com",
                    "observationDate" to "2026-10-14", "isDescended" to 1, "debitLevel" to "SEC", "waterTemperature" to "INCONNUE",
                    "airTemperature" to "INCONNUE", "comment" to "RAS", "personalComment" to "", "createdAt" to 2_000L,
                ),
            )
        }

        val database = Room.databaseBuilder(context, UserDatabase::class.java, TEST_DATABASE)
            .addMigrations(*UserDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.pendingDebitSubmissionDao()
            val pending = dao.getSendable().single()
            assertEquals(0, pending.failedAttempts)
            assertEquals(null, pending.rejectedAt)

            dao.markRejected(pending.id, rejectedAt = 3_000L, reason = "Date invalide")
            assertEquals(emptyList<Any>(), dao.getSendable())
            assertEquals(0, dao.observeCount().first())
            assertEquals("Date invalide", dao.observeAll().first().single().rejectionReason)
        } finally {
            database.close()
        }
    }

    @Test
    fun `version 4 keeps the outings, which gain flow, companions, duration and origin`() = runTest {
        createDatabase(schemaVersion = 4) { db ->
            db.insert(
                "logbook_entries",
                null,
                values(
                    "id" to 1,
                    "canyonId" to 26,
                    "canyonName" to "Furon",
                    "date" to "2026-07-30",
                    "note" to "Super",
                    "createdAt" to 1_000L,
                ),
            )
        }

        val database = Room.databaseBuilder(context, UserDatabase::class.java, TEST_DATABASE)
            .addMigrations(*UserDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = fr.descentecanyon.app.data.repository.LogbookRepositoryImpl(
                database.logbookDao(),
                java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC),
            )
            val kept = repository.observeAll().first().single()
            assertEquals("Super", kept.note)
            assertEquals(LogbookSource.MANUAL, kept.source)
            assertEquals(null, kept.debitLevel)

            repository.save(kept.copy(debitLevel = NiveauDebit.CORRECT, companions = "Paul", durationMinutes = 240))
            val edited = repository.observeAll().first().single()
            assertEquals(NiveauDebit.CORRECT, edited.debitLevel)
            assertEquals("Paul", edited.companions)
            assertEquals(240, edited.durationMinutes)
        } finally {
            database.close()
        }
    }

    @Test
    fun `version 5 turns the outing under way into the first planned outing`() = runTest {
        createDatabase(schemaVersion = 5) { db ->
            db.insert(
                "key_values",
                null,
                values(
                    "key" to "outing.current",
                    "value" to "{\"canyonId\":101,\"canyonName\":\"Riolan\",\"startEpochMs\":1000,\"expectedBackEpochMs\":5000," +
                        "\"alertAtEpochMs\":9000}",
                    "updatedAt" to 500L,
                ),
            )
            db.insert("key_values", null, values("key" to "contributor.name", "value" to "Antoine", "updatedAt" to 3_000L))
        }

        val database = Room.databaseBuilder(context, UserDatabase::class.java, TEST_DATABASE)
            .addMigrations(*UserDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val outing = database.plannedOutingDao().observeAll().first().single()
            assertEquals(101, outing.canyonId)
            assertEquals(1000L, outing.start)
            assertEquals(5000L, outing.expectedBack)
            assertEquals(5000L, outing.latestBack)
            assertEquals(9000L, outing.alertAt)
            assertEquals(null, database.keyValueDao().get("outing.current"))
            assertEquals("Antoine", database.keyValueDao().get("contributor.name"))

            val placeId = database.placeDao().upsert(
                fr.descentecanyon.app.data.local.user.entity.PlaceEntity(
                    name = "Camping",
                    latitude = 44.0,
                    longitude = 6.0,
                    createdAt = 1L,
                ),
            )
            assertEquals(listOf("Camping"), database.placeDao().getAll().map { it.name })
            assertEquals(1L, placeId)
        } finally {
            database.close()
        }
    }

    @Test
    fun `version 6 turns the stay dates of places into stays, and its outings are followed`() = runTest {
        createDatabase(schemaVersion = 6) { db ->
            db.insert(
                "places",
                null,
                values(
                    "id" to 3L,
                    "name" to "Camping",
                    "latitude" to 44.0,
                    "longitude" to 6.0,
                    "stayStart" to "2026-07-12",
                    "stayEnd" to "2026-07-18",
                    "lastUsedAt" to 7L,
                    "createdAt" to 2L,
                ),
            )
            db.insert(
                "places",
                null,
                values("id" to 4L, "name" to "Maison", "latitude" to 45.0, "longitude" to 5.0, "lastUsedAt" to 9L, "createdAt" to 1L),
            )
            listOf(true, false).forEachIndexed { index, informed ->
                db.insert(
                    "planned_outings",
                    null,
                    values(
                        "canyonId" to 101 + index, "canyonName" to "Riolan", "start" to 1000L, "expectedBack" to 5000L,
                        "latestBack" to 6000L, "alertAt" to 9000L, "relativeInformed" to if (informed) 1 else 0,
                        "choicesJson" to "{}", "updatedAt" to 500L,
                    ),
                )
            }
        }

        val database = Room.databaseBuilder(context, UserDatabase::class.java, TEST_DATABASE)
            .addMigrations(*UserDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val stay = database.stayDao().getAll().single()
            assertEquals(Triple(3L, "2026-07-12", "2026-07-18"), Triple(stay.placeId, stay.start, stay.end))
            assertEquals(listOf("Maison" to 9L, "Camping" to 7L), database.placeDao().getAll().map { it.name to it.lastUsedAt })
            val outings = database.plannedOutingDao().observeAll().first().sortedBy { it.canyonId }
            assertEquals(listOf(true, true), outings.map { it.tracked })
            assertEquals(listOf(9000L, null), outings.map { it.informedAlertAt })
            assertEquals(listOf(null, null), outings.map { it.stayId })
            assertEquals("{}", outings.first().choicesJson)
        } finally {
            database.close()
        }
    }

    @Test
    fun `recently viewed canyons are trimmed to the most recent ones`() = runTest {
        val database = TestDatabases.user()
        try {
            val dao = database.recentCanyonDao()
            (1..5).forEach { id -> dao.recordView(RecentCanyonEntity(canyonId = id, viewedAt = id.toLong()), keep = 3) }
            dao.recordView(RecentCanyonEntity(canyonId = 3, viewedAt = 10L), keep = 3)

            assertEquals(listOf(3, 5, 4), dao.observeRecentIds(10).first())
        } finally {
            database.close()
        }
    }

    private fun createDatabase(schemaVersion: Int, fill: (SQLiteDatabase) -> Unit) {
        val schema = File(
            requireNotNull(System.getProperty("repoRoot")),
            "core/data/schemas/fr.descentecanyon.app.data.local.user.UserDatabase/$schemaVersion.json",
        ).readText()
        val database = Json.parseToJsonElement(schema).jsonObject["database"]!!.jsonObject
        val file = context.getDatabasePath(TEST_DATABASE)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            database["entities"]!!.jsonArray.forEach { entity ->
                val table = entity.jsonObject["tableName"]!!.jsonPrimitive.content
                db.execSQL(entity.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            database["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = schemaVersion
            fill(db)
        }
    }

    private fun values(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                is String -> put(key, value)
                else -> error("Unsupported value $value")
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "user-migration-test.db"
    }
}
