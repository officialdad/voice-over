package com.voiceover

import android.app.Application
import com.google.android.material.color.DynamicColors

class VoiceOverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
