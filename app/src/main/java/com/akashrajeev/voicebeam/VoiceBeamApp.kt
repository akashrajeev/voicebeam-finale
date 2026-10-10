package com.akashrajeev.voicebeam

import android.app.Application
import com.akashrajeev.voicebeam.engine.VoiceBeamEngine

class VoiceBeamApp : Application() {
    lateinit var engine: VoiceBeamEngine
        private set

    val recall by lazy { com.akashrajeev.voicebeam.recall.RecallRepository(this) }

    val reminders by lazy { com.akashrajeev.voicebeam.reminders.ReminderRepository(this) }

    override fun onCreate() {
        super.onCreate()
        engine = VoiceBeamEngine(this)
        // Opening the app reconciles alarms after permission changes or force-stop.
        com.akashrajeev.voicebeam.reminders.ReminderScheduler(this).restore()
    }
}
