package fr.descentecanyon.app.data.repository

import android.app.Application
import fr.descentecanyon.app.data.local.user.UserDatabase
import fr.descentecanyon.app.data.local.user.entity.KeyValueEntity
import fr.descentecanyon.app.domain.model.AccidentCircumstance
import fr.descentecanyon.app.domain.model.AccidentReport
import fr.descentecanyon.app.domain.model.BreathingTrouble
import fr.descentecanyon.app.domain.model.InjuryArea
import fr.descentecanyon.app.domain.model.Responsiveness
import fr.descentecanyon.app.domain.model.VictimCheck
import fr.descentecanyon.app.testutil.TestDatabases
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccidentReportRepositoryImplTest {

    private val database: UserDatabase = TestDatabases.user()
    private val now = Instant.parse("2026-07-14T14:00:00Z")
    private var clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val repository = AccidentReportRepositoryImpl(
        database.keyValueDao(),
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = clock.instant()
        },
    )

    @After
    fun tearDown() = database.close()

    @Test
    fun `the report is kept whole, then forgotten a day after its last change`() = runTest {
        val report = AccidentReport(
            accidentTime = now.minusSeconds(1800),
            injuredCount = 1,
            peopleOnSite = 5,
            circumstances = setOf(AccidentCircumstance.JUMP, AccidentCircumstance.COLD_EXHAUSTION),
            conscious = true,
            breathing = true,
            heavyBleeding = false,
            canWalk = false,
            injuries = setOf(InjuryArea.LEG_ANKLE),
            waterRising = false,
            location = "Pied du rappel de 25 m",
            checks = listOf(VictimCheck(now, Responsiveness.CONFUSED, BreathingTrouble.SOME, pain = 6, cold = true, note = "grelotte")),
        )
        repository.save(report)
        assertEquals(report, repository.observe().first())

        clock = Clock.fixed(now.plus(AccidentReport.EXPIRY).minusSeconds(60), ZoneOffset.UTC)
        assertEquals(report, repository.observe().first())
        clock = Clock.fixed(now.plus(AccidentReport.EXPIRY), ZoneOffset.UTC)
        assertNull(repository.observe().first())
    }

    @Test
    fun `an emptied report and a cleared one are gone`() = runTest {
        repository.save(AccidentReport(waterRising = true))
        repository.save(AccidentReport())
        assertNull(repository.observe().first())

        repository.save(AccidentReport(injuredCount = 2))
        repository.clear()
        assertNull(repository.observe().first())
    }

    @Test
    fun `answers a later version dropped read as unanswered`() = runTest {
        database.keyValueDao().put(
            KeyValueEntity(
                "emergency.accident_report",
                """{"savedAtEpochMs":${now.toEpochMilli()},"circumstances":["JUMP","AVALANCHE"],"injuries":["HEAD","TAIL"],""" +
                    """"checks":[{"atEpochMs":${now.toEpochMilli()},"responds":"SLEEPY","pain":3}]}""",
            ),
        )

        val report = repository.observe().first()
        assertEquals(setOf(AccidentCircumstance.JUMP), report?.circumstances)
        assertEquals(setOf(InjuryArea.HEAD), report?.injuries)
        assertEquals(VictimCheck(now, pain = 3), report?.checks?.single())

        database.keyValueDao().put(KeyValueEntity("emergency.accident_report", "not json"))
        assertNull(repository.observe().first())
    }
}
