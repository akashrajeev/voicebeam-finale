package com.akashrajeev.voicebeam

import android.app.Application
import com.akashrajeev.voicebeam.engine.VoiceBeamEngine

class VoiceBeamApp : Application() {
    lateinit var engine: VoiceBeamEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = VoiceBeamEngine(this)
    }
}
