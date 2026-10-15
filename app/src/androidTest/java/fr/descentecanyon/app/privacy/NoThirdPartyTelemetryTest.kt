package fr.descentecanyon.app.privacy

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app promises not to collect usage data. Libraries can start their own components with the app
 * through the merged manifest (ONNX Runtime 1.29+ registers a telemetry content provider): none may.
 */
class NoThirdPartyTelemetryTest {

    @Test
    fun noTelemetryComponentIsDeclared() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val flags = PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES
        val packageInfo = context.packageManager.packageInfo(context.packageName, flags)
        val components = buildList {
            packageInfo.providers?.mapTo(this) { it.name }
            packageInfo.receivers?.mapTo(this) { it.name }
            packageInfo.services?.mapTo(this) { it.name }
        }

        assertEquals(emptyList<String>(), components.filter { it.contains("telemetry", ignoreCase = true) })
    }

    private fun PackageManager.packageInfo(packageName: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, flags)
        }
}
