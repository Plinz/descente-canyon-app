package fr.descentecanyon.app.testutil

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.descentecanyon.app.data.local.catalog.CatalogDatabase
import fr.descentecanyon.app.data.local.user.UserDatabase

/** In-memory Room databases for Robolectric tests. */
object TestDatabases {
    val context: Context get() = ApplicationProvider.getApplicationContext()

    fun catalog(): CatalogDatabase = Room.inMemoryDatabaseBuilder(context, CatalogDatabase::class.java).allowMainThreadQueries().build()

    fun user(): UserDatabase = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build()
}
