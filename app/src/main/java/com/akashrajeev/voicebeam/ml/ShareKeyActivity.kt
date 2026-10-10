package com.akashrajeev.voicebeam.ml

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.akashrajeev.voicebeam.core.GroqKey
import java.io.File

/**
 * No screen. Share text containing a Groq key to "Save Groq key" and it is stored in
 * app-private storage. The key is never logged or shown. Restart VoiceBeam afterwards.
 */
class ShareKeyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var saved = false
        try {
            val text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            val key = GroqKey.extract(text)
            if (key != null) {
                val tmp = File(filesDir, GroqKey.FILE_NAME + ".tmp")
                tmp.writeText(key)
                val dest = File(filesDir, GroqKey.FILE_NAME)
                saved = tmp.renameTo(dest) || (dest.delete() && tmp.renameTo(dest))
            }
        } catch (_: Throwable) {}
        Toast.makeText(this, if (saved) "Groq key saved. Fully close and reopen VoiceBeam." else "No valid Groq key found in that text.", Toast.LENGTH_LONG).show()
        finish()
    }
}
