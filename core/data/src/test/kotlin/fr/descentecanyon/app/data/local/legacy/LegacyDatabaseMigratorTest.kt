package fr.descentecanyon.app.data.local.legacy

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import fr.descentecanyon.app.data.local.user.UserDatabase
import fr.descentecanyon.app.testutil.TestDatabases
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LegacyDatabaseMigratorTest {

    private val context = TestDatabases.context
    private lateinit var userDatabase: UserDatabase
    private lateinit var migrator: LegacyDatabaseMigrator

    @Before
    fun setUp() {
        userDatabase = TestDatabases.user()
        migrator = LegacyDatabaseMigrator(context, userDatabase)
    }

    @After
    fun tearDown() {
        userDatabase.close()
        context.deleteDatabase(LegacyDatabaseMigrator.LEGACY_DATABASE_NAME)
    }

    @Test
    fun `nothing happens without a legacy database`() = runTest {
        assertFalse(migrator.hasLegacyDatabase())
        assertNull(migrator.migrate())
    }

    @Test
    fun `user data of a version 14 database is moved then the file deleted`() = runTest {
        createLegacyDatabase(schemaVersion = 14) { db ->
            db.insertCanyon(id = 1, isFavorite = true)
            db.insertCanyon(id = 2, isFavorite = false)
            db.insert(
                "pending_debit_submissions",
                values(
                    "id" to 5, "canyonId" to 1, "observerName" to "Antoine", "observerEmail" to null,
                    "observationDate" to "2026-05-11", "isDescended" to 1, "debitLevel" to "CORRECT",
                    "waterTemperature" to "FROIDE", "airTemperature" to "BON", "comment" to "RAS",
                    "personalComment" to "Perso", "createdAt" to 42L,
                ),
            )
            db.insert("debits", values("id" to 3, "canyonId" to 1, "date" to "2026-05-01", "niveau" to "GROS", "auteur" to "steph"))
            db.insert("photos", values("id" to 9, "canyonId" to 1, "url" to "https://x/p.jpg", "localPath" to "content://media/1"))
            db.insert("app_metadata", values("key" to "home.selected_feed_type", "value" to "FORUM"))
            db.insert("app_metadata", values("key" to "embedded_dataset_version", "value" to "2026-07-16"))
        }

        val report = migrator.migrate()!!

        assertEquals(1, report.favorites)
        assertEquals(listOf(1), userDatabase.favoriteDao().observeIds().first())
        val pending = userDatabase.pendingDebitSubmissionDao().getSendable().single()
        assertEquals("Perso", pending.personalComment)
        assertEquals(42L, pending.createdAt)
        assertEquals("steph", userDatabase.debitDao().getByCanyonId(1).first().single().auteur)
        assertEquals("content://media/1", userDatabase.photoDao().getById(9)?.localPath)
        assertEquals("FORUM", userDatabase.keyValueDao().get("home.selected_feed_type"))
        assertNull(userDatabase.keyValueDao().get("embedded_dataset_version"))
        assertFalse(migrator.hasLegacyDatabase())
        assertNull(migrator.migrate())
    }

    @Test
    fun `an old version 8 database without weather nor personal comment is migrated`() = runTest {
        createLegacyDatabase(schemaVersion = 8) { db ->
            db.insertCanyon(id = 7, isFavorite = true)
            db.insert(
                "pending_debit_submissions",
                values(
                    "canyonId" to 7, "observerName" to "Antoine", "observerEmail" to "a@example.com",
                    "observationDate" to "2026-03-22", "isDescended" to 0, "debitLevel" to "FILET",
                    "waterTemperature" to "INCONNUE", "airTemperature" to "INCONNUE", "comment" to "", "createdAt" to 1L,
                ),
            )
        }

        val report = migrator.migrate()!!

        assertEquals(1, report.pendingSubmissions)
        assertEquals(0, report.dailyWeather)
        val pending = userDatabase.pendingDebitSubmissionDao().getSendable().single()
        assertEquals("", pending.personalComment)
        assertFalse(pending.isDescended)
        assertTrue(userDatabase.favoriteDao().observeIsFavorite(7).first())
    }

    /** Creates the legacy file with the exact tables Room generated for [schemaVersion]. */
    private fun createLegacyDatabase(schemaVersion: Int, fill: (SQLiteDatabase) -> Unit) {
        val schemaClass = if (schemaVersion <= 8) "AppDatabase" else "DescenteCanyonDatabase"
        val schemaJson = requireNotNull(
            javaClass.classLoader?.getResource("legacy-schemas/fr.descentecanyon.app.data.local.database.$schemaClass/$schemaVersion.json"),
        ).readText()
        val entities = Json.parseToJsonElement(schemaJson).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        val file = context.getDatabasePath(LegacyDatabaseMigrator.LEGACY_DATABASE_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            entities.forEach { entity ->
                val table = entity.jsonObject["tableName"]!!.jsonPrimitive.content
                db.execSQL(entity.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
            db.version = schemaVersion
            fill(db)
        }
    }

    private fun SQLiteDatabase.insertCanyon(id: Int, isFavorite: Boolean) {
        insert(
            "canyons",
            values(
                "id" to id, "nom" to "Canyon $id", "nomComplet" to "Canyon $id", "pays" to "France", "commune" to "",
                "cotation" to "v3a3", "nbVotes" to 0, "url" to "/canyoning/canyon/$id/", "isOffline" to 0,
                "isFavorite" to if (isFavorite) 1 else 0, "lastUpdated" to 0L, "hasSpecificRegulation" to 0,
                "isForbidden" to 0, "sourceType" to "DESCENTE_CANYON", "sourceKey" to "dc:$id",
            ),
        )
    }

    private fun SQLiteDatabase.insert(table: String, values: ContentValues) {
        values.keySet().retainAll(columnsOf(table))
        insertOrThrow(table, null, values)
    }

    private fun SQLiteDatabase.columnsOf(table: String): Set<String> = rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
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
}
