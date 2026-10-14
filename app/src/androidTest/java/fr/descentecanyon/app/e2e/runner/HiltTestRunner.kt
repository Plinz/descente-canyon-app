package fr.descentecanyon.app.e2e.runner

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, "dagger.hilt.android.testing.HiltTestApplication", context)
}
