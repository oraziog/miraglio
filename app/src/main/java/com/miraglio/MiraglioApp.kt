package com.miraglio

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.miraglio.power.BackgroundPowerController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MiraglioApp : Application() {

    @Inject
    lateinit var powerController: BackgroundPowerController

    override fun onCreate() {
        super.onCreate()
        // Dopo super.onCreate() Hilt ha gia' iniettato i campi.
        powerController.install(ProcessLifecycleOwner.get())
    }
}
