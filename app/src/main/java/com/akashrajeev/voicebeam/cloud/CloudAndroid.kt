package com.akashrajeev.voicebeam.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.akashrajeev.voicebeam.core.WavWriter
import com.akashrajeev.voicebeam.record.SessionMeta
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The ElevenLabs key lives only here, encrypted with a non-exportable Android Keystore key. Not in the APK, repo or logs. */
class CloudKeyVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud_clean", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    /** False when the text is not a plausible key or encryption failed. */
    fun save(raw: String?): Boolean {
        val key = ElevenKey.parse(raw) ?: return false
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            val ct = c.doFinal(key.toByteArray(Charsets.UTF_8))
            prefs.edit().putString("k", Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)).apply()
            true
        } catch (_: Throwable) { false }
    }

    fun load(): String? = try {
        val all = Base64.decode(prefs.getString("k", null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    } catch (_: Throwable) { prefs.edit().remove("k").apply(); null }

    fun has(): Boolean = prefs.contains("k") && load() != null
    fun clear() { prefs.edit().remove("k").apply() }

    private companion object { const val ALIAS = "vb_elevenlabs_key" }
}

class PrefsUsageStore(context: Context) : UsageStore {
    private val p = context.applicationContext.getSharedPreferences("cloud_clean", Context.MODE_PRIVATE)
    override fun read(): Pair<String, Int>? = p.getString("month", null)?.let { it to p.getInt("seconds", 0) }
    override fun write(month: String, seconds: Int) { p.edit().putString("month", month).putInt("seconds", seconds).apply() }
}

object CloudClean {
    private fun month() = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    fun ledger(context: Context) = UsageLedger(PrefsUsageStore(context)) { month() }

    /** Cleans a saved session clip. Returns null when saved to cloud_clean.mp3, otherwise the reason to show. Blocking: call off the main thread. */
    fun cleanSession(context: Context, m: SessionMeta, enabled: Boolean, capMinutes: Int): String? {
        val vault = CloudKeyVault(context)
        val service = CloudCleanService(ElevenLabsCleaner({ vault.load() }), ledger(context)) { vault.has() }
        val (samples, rate) = try { WavWriter.read(m.cleanWav) } catch (_: Throwable) { return "Could not read the clip." }
        return when (val r = service.run(enabled, capMinutes, Pcm16k.fromFloats(samples, rate))) {
            is CleanResult.Cleaned -> try { m.cloudClean.writeBytes(r.audio); null } catch (_: Throwable) { "Could not save the cleaned clip." }
            is CleanResult.Fallback -> r.reason.message
        }
    }
}
