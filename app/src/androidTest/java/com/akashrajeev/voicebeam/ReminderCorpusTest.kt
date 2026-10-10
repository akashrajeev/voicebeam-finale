package com.akashrajeev.voicebeam
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashrajeev.voicebeam.reminders.ReminderGrounding
import com.akashrajeev.voicebeam.recall.RecallModelFiles
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
/** Separate opt-in phone benchmark, not part of emulator UI/scheduler CI. */
@RunWith(AndroidJUnit4::class) class ReminderCorpusTest {
    @Test fun measureTextAndSyntheticAudio()= runBlocking {
        val i=InstrumentationRegistry.getInstrumentation();val app=i.targetContext.applicationContext as VoiceBeamApp
        assumeTrue("Gemma must already be installed on the test phone",RecallModelFiles.ready(app))
        val corpus=JSONArray(i.context.assets.open("reminders/corpus.json").bufferedReader().readText());val results=JSONArray()
        for(n in 0 until corpus.length()) {
            val case=corpus.getJSONObject(n);val text=case.getString("text");val id=case.getString("id")
            val result=JSONObject().put("id",id).put("expected",case);val start=android.os.SystemClock.elapsedRealtime()
            try {
                val raw=app.recall.instructionText(text);val items=ReminderGrounding.extract(raw,text)
                result.put("text_raw",raw).put("text_count",items.size).put("text_false_extraction",case.optInt("tasks",-1)==0 && items.isNotEmpty())
                val wav=File(app.cacheDir,"corpus-$id.wav")
                i.context.assets.open("reminders/$id.wav").use { src->wav.outputStream().use { src.copyTo(it) } }
                val (transcript,audioRaw)=app.recall.instructionAudio(wav);val audioItems=ReminderGrounding.extract(audioRaw,transcript)
                result.put("asr",transcript).put("audio_raw",audioRaw).put("audio_count",audioItems.size).put("audio_false_extraction",case.optInt("tasks",-1)==0 && audioItems.isNotEmpty());wav.delete()
            } catch(t: Exception) { result.put("error",t.message) }
            result.put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-start);results.put(result)
            File(app.getExternalFilesDir(null),"reminders-corpus-results.json").writeText(results.toString(2))
        }
    }
}
