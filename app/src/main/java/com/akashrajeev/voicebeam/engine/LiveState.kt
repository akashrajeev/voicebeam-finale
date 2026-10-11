package com.akashrajeev.voicebeam.engine

import com.akashrajeev.voicebeam.core.ConsentPhase
import com.akashrajeev.voicebeam.core.CaptionSegment
import com.akashrajeev.voicebeam.core.TrackedFace

enum class SaveMode { AUDIO, AUDIO_VIDEO, CAPTIONS }
enum class CaptionBurn { BURNED, SRT, NONE }

data class RecordingState(
    val active: Boolean = false,
    val mode: SaveMode = SaveMode.AUDIO_VIDEO,
    val startedAtMs: Long = 0L,
    val sessionId: String? = null,
    val exporting: Boolean = false,
    val lastMessage: String? = null,
)

data class LiveState(
    val modelsReady: Boolean = false,
    val modelError: String? = null,
    val listening: Boolean = false,
    val audioError: String? = null,
    val audioOnly: Boolean = false,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val mirrored: Boolean = false,
    val faces: List<TrackedFace> = emptyList(),
    val lockedId: Int? = null,
    val lockedSpeaking: Float = 0f,
    val targetProbability: Float = 1f,
    val gain: Float = 1f,
    val voiceLearned: Boolean = false,
    val voiceEnrollmentActive: Boolean = false,
    val voiceEnrollmentProgress: Float = 0f,
    val wearerEnrollmentActive: Boolean = false,
    val wearerEnrollmentProgress: Float = 0f,
    val wearerLearned: Boolean = false,
    val wearerVetoEnabled: Boolean = false,
    val voiceMatch: Float? = null,
    val inputLevel: Float = 0f,
    val segments: List<CaptionSegment> = emptyList(),
    val partial: String = "",
    val partialIsTarget: Boolean = true,
    val earphones: String? = null,
    val recording: RecordingState = RecordingState(),
    val stageUrl: String? = null,
    val consentPhase: ConsentPhase = ConsentPhase.IDLE,
    val consentMessage: String = "",
    val consentRecords: Int = 0,
    val consentFaceId: Int? = null,
    val consentProgress: Float = 0f,
    val consentCapturedAtMs: Long = 0L,
)
