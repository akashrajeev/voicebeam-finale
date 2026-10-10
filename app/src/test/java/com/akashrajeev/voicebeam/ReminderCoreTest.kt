package com.akashrajeev.voicebeam
import com.akashrajeev.voicebeam.reminders.*
import org.junit.Test
import org.junit.Assert.*
import java.time.ZoneId
class ReminderCoreTest {
    @Test fun literalEvidenceOnly() {
        val t="Come back in two weeks and bring the blood test report."
        val raw="""[{"what":{"value":"Come back","quote":"Come back in two weeks"},"when":{"value":"10:00","quote":"Come back in two weeks"},"bring":{"value":"blood test report","quote":"bring the blood test report"}}]"""
        val f=ReminderGrounding.extract(raw,t).single();assertEquals("Come back",f["what"]?.value);assertFalse(f.containsKey("when"));assertEquals("blood test report",f["bring"]?.value)
    }
    @Test fun unsupportedWhatRejected() { assertTrue(ReminderGrounding.extract("""[{"what":{"value":"Take aspirin","quote":"Take aspirin"}}]""","The weather is nice.").isEmpty()) }
    @Test fun noClockGuess() {
        val t="Take this tablet twice a day after food."
        val f=ReminderGrounding.extract("""[{"what":{"value":"Take this tablet","quote":"Take this tablet twice a day after food."},"when":{"value":"after food","quote":"after food"},"repeat":{"value":"twice a day","quote":"twice a day"}}]""",t).single()
        assertEquals("after food",f["when"]?.value);assertEquals("twice a day",f["repeat"]?.value)
    }
    @Test fun boundaryToken() { assertTrue(ReminderGrounding.tokenMatches("Token 42 please go to counter 14","42","14"));assertFalse(ReminderGrounding.tokenMatches("Token 142 please go to counter 14","42","14"));assertFalse(ReminderGrounding.tokenMatches("Token 42 please go to counter 15","42","14"));assertFalse(ReminderGrounding.tokenMatches("There are 42 chairs","42","")) }
    @Test fun negativeToken() { assertFalse(ReminderGrounding.tokenMatches("Token 42 has not been called","42","")) }
    @Test fun repeatedAlarmSkipsMissedInsteadOfBurst() { assertEquals(240000L,ReminderGrounding.nextOccurrence(60000,1,200000)) }
    @Test fun codecRoundTrip() { val c=ReminderCard("abc",123,"voice.wav","Bring report",mapOf("what" to ReminderField("Bring report","Bring report")),"confirmed",456,60);assertEquals(c,ReminderCodec.decode(ReminderCodec.encode(c))) }
    @Test fun invalidTimeRejected() { assertThrows(Exception::class.java) { ReminderGrounding.localTime("2026-13-01","09:00",ZoneId.of("Asia/Kolkata")) } }
    @Test fun daylightGapRejected() { assertThrows(Exception::class.java) { ReminderGrounding.localTime("2026-03-08","02:30",ZoneId.of("America/New_York")) } }
    @Test fun malformedJsonRejected() { assertThrows(Exception::class.java) { ReminderGrounding.extract("not json","instruction") } }
    @Test fun emptyTranscriptRejectsAll() { assertTrue(ReminderGrounding.extract("[]","").isEmpty()) }
    @Test fun zeroIntervalRejected() { assertThrows(Exception::class.java) { ReminderGrounding.nextOccurrence(1,0,10) } }
}
