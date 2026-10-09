import com.akashrajeev.voicebeam.core.*
import java.io.*
fun main(args:Array<String>) {
 val data=DataInputStream(FileInputStream(args[0]));val output=DataOutputStream(FileOutputStream(args[1]));val meta=File(args[2]).readLines();val gate=TargetGate(frameMs=16f);val env=ListenEnvelope();val vad=EnergyVad();val clean=FloatArray(256);val gated=FloatArray(256);val out=FloatArray(256)
 gate.quietOthers=args.getOrNull(4)?.toFloat() ?: .8f
 var old="";var flips=0;var missed=0;var targetFrames=0
 for(line in meta){val v=line.split(',');for(i in clean.indices)clean[i]=data.readFloat();val t=v[0]=="1";val other=v[1]=="1";val wearer=v[2]=="1";val score=v[3].toFloat();val g=gate.process(GateInputs(true,if(t).9f else 0f,if(other||wearer).9f else 0f,score,vad.isVoice(clean),voiceLearned=true));if(gate.state.name!=old){flips++;old=gate.state.name};if(t){targetFrames++;if(g<.31623f)missed++};val boost=if(gate.boostAllowed)TargetGate.dbToLinear(6f) else 1f
 if(args.getOrNull(3)=="5"){var peak=0f;for(x in clean)peak=maxOf(peak,kotlin.math.abs(x*g));val b=minOf(boost,.95f/peak.coerceAtLeast(1e-9f));for(i in out.indices)out[i]=clean[i]*g*b}
 else env.process(clean,256,g,boost,gated,out)
 for(x in out)output.writeFloat(x)}
 output.close();println("stateTransitions=$flips targetFrames=$targetFrames targetBelowMinus10dB=$missed")
}
