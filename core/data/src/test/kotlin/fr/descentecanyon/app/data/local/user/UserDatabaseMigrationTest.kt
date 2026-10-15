package fr.descentecanyon.app.data.local.user

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import fr.descentecanyon.app.data.local.user.entity.RecentCanyonEntity
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
            assertEquals(listOf(3L), database.pendingDebitSubmissionDao().getAll().map { it.id })
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
            repository.add(7, "Riolan", java.time.LocalDate.of(2026, 7, 14), null)
            repository.add(7, "Riolan", java.time.LocalDate.of(2026, 7, 14), "Débit correct")
            repository.add(8, "Bollinette", java.time.LocalDate.of(2026, 8, 2), null)

            val entries = repository.observeAll().first()
            assertEquals(listOf(8, 7), entries.map { it.canyonId })
            assertEquals("Débit correct", entries.last().note)
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
                is String -> put(key, value)
                else -> error("Unsupported value $value")
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "user-migration-test.db"
    }
}
