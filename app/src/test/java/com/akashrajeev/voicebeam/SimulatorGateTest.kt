package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.core.*
import org.junit.Test
import org.junit.Assert.*
class SimulatorGateTest {
 @Test fun visibleOtherMaySuppressBelowVadWithoutBlockingTarget() {
  val g=TargetGate(16f);g.quietOthers=.86f
  repeat(50){g.process(GateInputs(true,0f,.9f,.5f,false))}
  assertEquals(TargetState.OTHER,g.state);assertTrue(g.gain<.03f)
  repeat(20){g.process(GateInputs(true,.9f,0f,.9f,true))}
  assertEquals(TargetState.TARGET,g.state);assertTrue(g.gain>.94f)
 }
 @Test fun uncertainAndOverlapStillPass() {
  for(i in listOf(GateInputs(true,0f,0f,.5f,false),GateInputs(true,.9f,.9f,.5f,true))){
   val g=TargetGate(16f);repeat(50){g.process(i)};assertTrue(g.gain>.99f);assertFalse(g.boostAllowed)
  }
 }
 @Test fun audioOnlyDoesNotUseOthersLipsWhenBelowVad() {
  val g=TargetGate(16f);repeat(50){g.process(GateInputs(true,0f,.9f,.5f,false,audioOnly=true))}
  assertEquals(TargetState.UNCERTAIN,g.state);assertTrue(g.gain>.99f)
 }
}
