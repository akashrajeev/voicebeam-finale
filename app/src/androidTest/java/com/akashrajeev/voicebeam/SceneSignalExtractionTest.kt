package com.akashrajeev.voicebeam

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.core.*
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Offline synchronous video landmarks, real tracker/lip features, no enrolled voice.
 * VIDEO mode,8fps differ from live GPU/asynchronous capture. Record that distinction.
 */
@RunWith(AndroidJUnit4::class)
class SceneSignalExtractionTest {
    @Test fun extractUnenrolledSceneSignals() {
        val ins=InstrumentationRegistry.getInstrumentation();val c=ins.targetContext;val args=InstrumentationRegistry.getArguments()
        val root=File(c.filesDir,"replay");val video=File(root,"video.mp4");require(video.isFile)
        val x=args.getString("lockX")?.toFloat() ?: .25f
        val y=args.getString("lockY")?.toFloat() ?: .5f
        val lockMs=args.getString("lockMs")?.toLong() ?: 3000L
        val offset=args.getString("videoOffsetMs")?.toLong() ?: error("Supply measured videoOffsetMs (videoPTS=audioMs+offset)")
        val retriever=MediaMetadataRetriever();retriever.setDataSource(video.absolutePath)
        val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        val options=FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("models/face_landmarker.task").build())
            .setRunningMode(RunningMode.VIDEO).setNumFaces(4)
            .setMinFaceDetectionConfidence(.5f).setMinFacePresenceConfidence(.5f).setMinTrackingConfidence(.5f).build()
        val lm=FaceLandmarker.createFromOptions(c,options);val tracker=FaceTracker();var locked=false
        try {
            File(root,"signals.csv").bufferedWriter().use { out ->
                out.write("time_ms,hasLock,lockedSpeaking,othersSpeaking,voiceMatch,voiceActive,lockedVisible,audioOnly,wearerMatch,wearerVetoEnabled,visionAgeMs,voiceLearned\n")
                var audioMs=0L
                if(offset<0) out.write("0,false,0,0,,false,false,false,,false,-1,false\n")
                audioMs=maxOf(0L,-offset)
                while(audioMs+offset<duration) {
                    val bmp=retriever.getFrameAtTime((audioMs+offset)*1000,MediaMetadataRetriever.OPTION_CLOSEST)
                    if(bmp!=null) {
                        val result=lm.detectForVideo(BitmapImageBuilder(bmp).build(),audioMs)
                        val obs=result.faceLandmarks().map { pts ->
                            val box=Box(pts.minOf{it.x()},pts.minOf{it.y()},pts.maxOf{it.x()},pts.maxOf{it.y()})
                            val a=pts[13];val b=pts[14]
                            FaceObservation(box,kotlin.math.hypot(a.x()-b.x(),a.y()-b.y())/box.height.coerceAtLeast(.001f))
                        }
                        tracker.update(audioMs,obs)
                        if(!locked&&audioMs>=lockMs){locked=tracker.lockAt(x,y,audioMs)!=null}
                        val faces=tracker.snapshot(audioMs);val id=tracker.lockedId;val target=faces.firstOrNull{it.id==id}
                        val age=target?.let{audioMs-it.lastSeenMs} ?: -1L
                        out.write(listOf(audioMs,id!=null,target?.speaking ?: 0f,faces.filter{it.id!=id}.maxOfOrNull{it.speaking} ?: 0f,
                            "",false,target!=null&&age in 0..399,false,"",false,age,false).joinToString(",")+"\n")
                        bmp.recycle()
                    }
                    audioMs+=125
                }
            }
            require(locked){"No target locked at specified position/time; rerun with verified normalized coordinates"}
        } finally {lm.close();retriever.release()}
    }
}
