package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.local.user.UserDatabase
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.domain.model.GearGroup
import fr.descentecanyon.app.domain.model.GearItem
import fr.descentecanyon.app.domain.model.GearKey
import fr.descentecanyon.app.domain.model.GearListSettings
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.MessageAudience
import fr.descentecanyon.app.domain.model.MessageSection
import fr.descentecanyon.app.domain.model.OutingSettings
import fr.descentecanyon.app.testutil.TestDatabases
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OutingPreferencesRepositoryImplTest {

    private val database: UserDatabase = TestDatabases.user()
    private val repository = OutingPreferencesRepositoryImpl(database.keyValueDao())

    @After
    fun tearDown() = database.close()

    @Test
    fun `settings start from the defaults and keep the user's values within bounds`() = runTest {
        assertEquals(OutingSettings(), repository.observeSettings().first())

        val custom =
            OutingSettings(prepMinutes = 30, largeGroupFactor = 1.4, defaultGroupSize = 6, defaultGroupLevel = GroupLevel.WITH_BEGINNERS)
        repository.setSettings(custom)
        assertEquals(custom, repository.observeSettings().first())

        // A value added by a later version or broken by hand reads as the default or within bounds.
        database.keyValueDao().put(KeyValueEntity("outing.settings", "{\"prepMinutes\":500,\"unknown\":1}"))
        assertEquals(OutingSettings(prepMinutes = 120), repository.observeSettings().first())
        database.keyValueDao().put(KeyValueEntity("outing.settings", "not json"))
        assertEquals(OutingSettings(), repository.observeSettings().first())
    }

    @Test
    fun `each kind of message keeps its own sections`() = runTest {
        assertEquals(MessageAudience.RELATIVE.defaultSections, repository.observeMessageSections().first()[MessageAudience.RELATIVE])

        repository.setMessageSections(MessageAudience.TEAM, setOf(MessageSection.MEETING, MessageSection.GEAR))
        val sections = repository.observeMessageSections().first()
        assertEquals(setOf(MessageSection.MEETING, MessageSection.GEAR), sections[MessageAudience.TEAM])
        assertEquals(MessageAudience.GUESTS.defaultSections, sections[MessageAudience.GUESTS])
    }

    @Test
    fun `the gear list keeps items taken off and added`() = runTest {
        assertEquals(GearListSettings(), repository.observeGearList().first())

        val gear = GearListSettings(
            hidden = setOf(GearKey.KNIFE, GearKey.TOWEL),
            custom = listOf(GearItem.Custom("custom:1", GearGroup.SHARED, "Kit spit")),
        )
        repository.setGearList(gear)
        assertEquals(gear, repository.observeGearList().first())
    }
}
