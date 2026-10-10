package com.akashrajeev.voicebeam

import android.app.Application
import com.akashrajeev.voicebeam.engine.VoiceBeamEngine

class VoiceBeamApp : Application() {
    lateinit var engine: VoiceBeamEngine
        private set

    val footage by lazy { com.akashrajeev.voicebeam.footage.FootageRepository(this) }
    val recall by lazy { com.akashrajeev.voicebeam.recall.RecallRepository(this) }

    override fun onCreate() {
        super.onCreate()
        engine = VoiceBeamEngine(this)
    }
}
