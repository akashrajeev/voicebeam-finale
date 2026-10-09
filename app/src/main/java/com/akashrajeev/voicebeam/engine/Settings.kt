package com.akashrajeev.voicebeam.engine

import android.content.Context

data class Settings(
    val quietOthers: Float = 0.94f,      // 0..1, how much to turn down everyone else (enh-exp: stronger default)
    val boostDb: Float = 6f,           // extra loudness in the earphones
    val denoise: Float = .85f,            // 0..1 noise removal strength (enh-exp: stronger default)
    val useSceneMic: Boolean = true,    // point the mic at the scene (back camera side)
    val saveMode: SaveMode = SaveMode.AUDIO_VIDEO,
    val captionBurn: CaptionBurn = CaptionBurn.BURNED,
    val showOthersCaptions: Boolean = true,
    val keepRawAudio: Boolean = false,
    val stageEnabled: Boolean = false,
    val captionSize: Int = 1,           // 0 small, 1 medium, 2 large
    val onboarded: Boolean = false,
    val hd1080: Boolean = false,
    val debugFeed: Boolean = false,
    val tseExperiment: Boolean = false, // enh-exp: enable the (currently passthrough) extraction stage
)

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("voicebeam", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        quietOthers = p.getFloat("quiet", 0.94f),
        boostDb = p.getFloat("boost", 6f),
        denoise = p.getFloat("denoise", .85f),
        useSceneMic = p.getBoolean("sceneMic", true),
        saveMode = runCatching { SaveMode.valueOf(p.getString("saveMode", null)!!) }.getOrDefault(SaveMode.AUDIO_VIDEO),
        captionBurn = runCatching { CaptionBurn.valueOf(p.getString("burn", null)!!) }.getOrDefault(CaptionBurn.BURNED),
        showOthersCaptions = p.getBoolean("others", true),
        keepRawAudio = p.getBoolean("raw", false),
        stageEnabled = p.getBoolean("stage", false),
        captionSize = p.getInt("capSize", 1),
        onboarded = p.getBoolean("onboarded", false),
        hd1080 = p.getBoolean("hd1080", false),
        debugFeed = p.getBoolean("dbgFeed", false),
        tseExperiment = p.getBoolean("tseExp", false),
    )

    fun save(s: Settings) {
        p.edit()
            .putFloat("quiet", s.quietOthers).putFloat("boost", s.boostDb).putFloat("denoise", s.denoise)
            .putBoolean("sceneMic", s.useSceneMic).putString("saveMode", s.saveMode.name).putString("burn", s.captionBurn.name)
            .putBoolean("others", s.showOthersCaptions).putBoolean("raw", s.keepRawAudio).putBoolean("stage", s.stageEnabled)
            .putInt("capSize", s.captionSize).putBoolean("onboarded", s.onboarded).putBoolean("hd1080", s.hd1080).putBoolean("dbgFeed", s.debugFeed)
            .putBoolean("tseExp", s.tseExperiment)
            .apply()
    }
}
