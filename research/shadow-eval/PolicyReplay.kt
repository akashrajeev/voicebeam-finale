import com.akashrajeev.voicebeam.core.ShadowListeningPolicy
import com.akashrajeev.voicebeam.core.TargetState
import java.io.File

/** Offline replay of the production helper, not a second Python implementation. */
fun main(args: Array<String>) {
    val policy = ShadowListeningPolicy()
    println("time_s,mix,candidate_floor")
    File(args[0]).readLines().drop(1).filter { it.isNotBlank() }.forEach { line ->
        val c = line.split(',')
        val speech = c[2].toBooleanStrict()
        val rms = c[3].toFloat()
        val proposed = policy.proposeMix(c[4].toFloat(), TargetState.valueOf(c[1]), speech, rms)
        val floor = policy.sampleFloor(speech, rms)
        println("${c[0]},$proposed,${floor ?: ""}")
    }
}
