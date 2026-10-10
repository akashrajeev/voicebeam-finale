package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.footage.*
import org.junit.Assert.*
import org.junit.Test
class FootageCoreTest {
    @Test fun similarEmbeddingsGroupWithoutPeopleClaims() {
        val c=FootageClusterer();assertEquals(0,c.assign(floatArrayOf(1f,0f)).first)
        assertEquals(0,c.assign(floatArrayOf(0.99f,0.1f)).first)
        assertEquals(1,c.assign(floatArrayOf(0f,1f)).first)
        assertEquals(-1,c.assign(null).first)
    }
    @Test fun capDoesNotForceWrongGroup() {
        val c=FootageClusterer(0.8f,2);c.assign(floatArrayOf(1f,0f,0f));c.assign(floatArrayOf(0f,1f,0f))
        assertEquals(-1,c.assign(floatArrayOf(0f,0f,1f)).first)
    }
    @Test fun zeroInvalidAndWrongDimensionFailClosed() {
        val c=FootageClusterer();assertEquals(-1,c.assign(floatArrayOf(0f,0f)).first)
        assertEquals(-1,c.assign(floatArrayOf(Float.NaN)).first)
        assertEquals(0f,FootageClusterer.cosine(floatArrayOf(1f),floatArrayOf(1f,0f)),0f)
    }
    @Test fun timelineKeepsGapsAndChunksAtGemmaLimit() {
        val w=listOf(FootageWindow(0,3000,0,.2f,1f),FootageWindow(3000,7000,0,.2f,1f),FootageWindow(8000,40000,0,.2f,1f),FootageWindow(7000,8000,1,.2f,1f))
        assertEquals(listOf(0L to 7000L,8000L to 33000L,33000L to 40000L),FootageTimeline.ranges(w,0))
    }
    @Test fun mergingDropsOnlyChangedAttribution() {
        val w=listOf(FootageWindow(0,3000,0,.2f,1f),FootageWindow(3000,6000,1,.2f,1f))
        val c=FootageClip("a","Test","v","a",6000,"ready",w,mapOf(0 to "A",1 to "B"),1,listOf(FootageLine(0,3000,0,"hi","ready")))
        val merged=FootageTimeline.merge(c,1,0);assertEquals(0,merged.selected);assertTrue(merged.lines.isEmpty());assertEquals(1,merged.groups().size)
    }
    @Test fun resamplingBlockBoundariesPreserveCountAndValues() {
        val a=FootageResampler(48000);val parts=mutableListOf<Float>();repeat(100) { parts+=a.add(FloatArray(480) { .25f }).toList() }
        assertEquals(16000,parts.size);assertTrue(parts.all { kotlin.math.abs(it-.25f)<.00001f })
    }
    @Test fun resamplingMonoIdentity() {
        val a=FootageResampler(16000);val x=FloatArray(100) { it/100f };assertArrayEquals(x,a.add(x),0f)
    }
    @Test fun loudestSuggestionUsesRmsNotIdentity() {
        val c=FootageClip("a","t","v","a",9000,"ready",listOf(FootageWindow(0,3000,0,.1f,1f),FootageWindow(3000,6000,1,.5f,1f)))
        assertEquals(1,c.groups().first().id)
    }

    @Test fun offlineGroupingDoesNotConsumeCapByEarlyArrival() {
        val result=FootageOfflineGroups.assign(listOf(floatArrayOf(1f,0f,0f),floatArrayOf(0f,1f,0f),floatArrayOf(0f,0f,1f),floatArrayOf(0f,0f,1f)),.8f,2)
        assertTrue(result[2].first>=0);assertEquals(result[2].first,result[3].first)
        assertEquals(1,result.count { it.first<0 })
    }
    @Test fun offlineInvalidWindowsStayUncertain() {
        assertEquals(-1,FootageOfflineGroups.assign(listOf(null,floatArrayOf(Float.NaN),floatArrayOf(1f)),.3f,6)[0].first)
    }
}
