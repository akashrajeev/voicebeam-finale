import com.akashrajeev.voicebeam.core.GateInputs
import com.akashrajeev.voicebeam.core.TargetGate
import java.io.File

/** Exact gate replay from recorded inputs. No heuristic mapping of probability to state. */
fun main(args:Array<String>) {
    val rows=File(args[0]).readLines();val names=rows.first().split(',')
    val gate=TargetGate(frameMs=args.getOrNull(2)?.toFloat() ?: 16f);gate.quietOthers=args.getOrNull(1)?.toFloat() ?: .86f
    println("time_ms,gate,gain,probability,gain_error,probability_error")
    for(line in rows.drop(1).filter{it.isNotBlank()}) {
        val c=line.split(',');val m=names.zip(c).toMap()
        fun b(k:String)=m.getValue(k).toBooleanStrict()
        fun f(k:String)=m.getValue(k).toFloat()
        fun nullable(k:String)=m[k]?.takeIf{it.isNotEmpty()}?.toFloat()
        val i=GateInputs(hasLock=b("hasLock"),lockedSpeaking=f("lockedSpeaking"),othersSpeaking=f("othersSpeaking"),
            voiceMatch=nullable("voiceMatch"),voiceActive=b("voiceActive"),lockedVisible=b("lockedVisible"),
            audioOnly=b("audioOnly"),wearerMatch=nullable("wearerMatch"),wearerVetoEnabled=b("wearerVetoEnabled"),
            visionAgeMs=m.getValue("visionAgeMs").toLong(),voiceLearned=b("voiceLearned"))
        gate.process(i)
        val ge=m["actualGain"]?.takeIf{it.isNotEmpty()}?.let{kotlin.math.abs(it.toFloat()-gate.gain)}
        val pe=m["actualProbability"]?.takeIf{it.isNotEmpty()}?.let{kotlin.math.abs(it.toFloat()-gate.probability)}
        require(ge==null || ge<.00001f){"Gain mismatch at ${m["time_ms"]}"}
        require(pe==null || pe<.00001f){"Probability mismatch at ${m["time_ms"]}"}
        println("${m["time_ms"]},${gate.state},${gate.gain},${gate.probability},${ge ?: ""},${pe ?: ""}")
    }
}
