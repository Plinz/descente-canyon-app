package fr.descentecanyon.app.e2e.outing

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidTest
import fr.descentecanyon.app.BuildConfig
import fr.descentecanyon.app.domain.model.AppLinks
import fr.descentecanyon.app.domain.model.GroupLevel
import fr.descentecanyon.app.domain.model.OutingSettings
import fr.descentecanyon.app.domain.model.ShareCodec
import fr.descentecanyon.app.domain.model.SharedOuting
import fr.descentecanyon.app.domain.model.SharedOutingSummary
import fr.descentecanyon.app.domain.model.SharedTiming
import fr.descentecanyon.app.e2e.BaseE2eTest
import fr.descentecanyon.app.ui.test.TestTags
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * An outing someone shared: its link (here the one a QR code gives, the outing inside it) opens what
 * it holds, and one tap adds it with the sender's times; the plan offers its own link and QR code.
 */
@HiltAndroidTest
class SharedOutingE2ETest : BaseE2eTest() {

    @Before
    override fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        // Adding the outing plans it: no permission dialog over the screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            instrumentation.uiAutomation.executeShellCommand("appops set --uid $packageName SCHEDULE_EXACT_ALARM allow")
                .let { output -> ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() } }
        }
        super.setUp()
    }

    @Test
    fun anOutingInALinkIsAddedWithTheSendersTimes() {
        // Two days ahead in the canyon's zone: a day still to come wherever the phone is.
        val day = LocalDate.now(ZoneId.of("Europe/Paris")).plusDays(2)
        val shared = SharedOuting(
            canyonId = 101,
            date = day,
            anchorTime = LocalTime.of(8, 0),
            groupSize = 4,
            groupLevel = GroupLevel.AUTONOMOUS,
            timing = SharedTiming(OutingSettings(prepMinutes = 45), null),
            note = "Rendez-vous devant la boulangerie",
            summary = SharedOutingSummary(listOf("Riolan"), meet = LocalTime.of(8, 0)),
        )
        val link = AppLinks(BuildConfig.LINK_HOST).outingInLink(101, "Riolan", ShareCodec.pack(shared))!!
        relaunchWith {
            action = Intent.ACTION_VIEW
            data = link.toUri()
        }

        waitForTag(TestTags.SHARED_OUTING_TITLE)
        waitForTag(TestTags.SHARED_OUTING_ADD)
        composeRule.onNodeWithTag(TestTags.SHARED_OUTING_ADD).performClick()

        // The plan of the outing, planned at once.
        waitForTag(TestTags.OUTING_SUMMARY)
        composeRule.waitUntil(10_000) { runBlocking { outingRepository.observeAll().first().isNotEmpty() } }
        val outing = runBlocking { outingRepository.observeAll().first().single() }
        assertEquals(101, outing.canyonId)
        assertEquals(day, outing.choices!!.date)
        assertEquals(LocalTime.of(8, 0), outing.choices!!.anchorTime)
        assertEquals("Rendez-vous devant la boulangerie", outing.choices!!.note)
        // The sender's 45 min to gear up, not this phone's 20.
        assertEquals(45, outing.choices!!.timing?.settings?.prepMinutes)
        assertEquals(
            day.atTime(8, 45).atZone(ZoneId.of("Europe/Paris")).toInstant(),
            outing.start,
        )
        // A link without code (QR code): nothing to follow on the server.
        assertNull(outing.choices!!.link)

        // Opened again, the link says the outing is there already.
        relaunchWith {
            action = Intent.ACTION_VIEW
            data = link.toUri()
        }
        waitForTag(TestTags.SHARED_OUTING_OPEN)
        assertEquals(1, runBlocking { outingRepository.observeAll().first().size })
    }

    @Test
    fun thePlanOffersItsLinkAsAQrCode() {
        relaunchWith {
            action = Intent.ACTION_VIEW
            data = "https://${BuildConfig.LINK_HOST}/c/101".toUri()
        }
        waitForTag(TestTags.DETAIL_PLAN_OUTING)
        composeRule.onNodeWithTag(TestTags.DETAIL_PLAN_OUTING).performSemanticsAction(SemanticsActions.OnClick)
        waitForTag(TestTags.OUTING_SUMMARY)

        composeRule.onNodeWithTag(TestTags.OUTING_MENU).performClick()
        waitForTag(TestTags.OUTING_LINK_MENU)
        composeRule.onNodeWithTag(TestTags.OUTING_LINK_MENU).performClick()
        waitForTag(TestTags.OUTING_LINK_SHEET)
        composeRule.onNodeWithTag(TestTags.OUTING_LINK_QR).performClick()
        waitForTag(TestTags.OUTING_LINK_QR_CODE)
    }
}
