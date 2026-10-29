package com.jmu.text2scpi.voice;
import org.junit.Test;
import static org.junit.Assert.*;
public final class EnglishAsrSelectionTest {
 @Test public void restoresOriginalWholeSentenceDecoder(){assertEquals("Turn on channel 1.",EnglishAsrSelection.choose("To on channel 1.","Turn on channel 1."));}
 @Test public void neverFallsBackToBadWholeSentence(){String beam="Change channel 1 vertical scale to 100 ms per division.";assertEquals(beam,EnglishAsrSelection.choose("Chenz China 1 vertical scale to 100 mW per division.",beam));}
 @Test public void powerToTimeCannotSend(){assertFalse(EnglishAsrSelection.maySend("Change China 1 which consume to 200 mW per division.","Change channel 1 to 200 ms per division."));}
 @Test public void explicitVoltageCannotBecomeTime(){assertFalse(EnglishAsrSelection.maySend("Set channel one to 200 millivolts","Set channel one to 200 ms"));}
 @Test public void acceptsEquivalentUnitSpelling(){assertTrue(EnglishAsrSelection.maySend("Set CH1 to 200 millivolts","Set CH1 to 200 mV"));}
 @Test public void acousticCandidateCanResolvePowerAbbreviation(){assertTrue(EnglishAsrSelection.maySend("Change China to vertical scale to 500 mWs per division.","Change channel2 vertical scale to 500 mV per division."));}
 @Test public void cannotChangeNumberDuringUnitResolution(){assertFalse(EnglishAsrSelection.maySend("vertical scale to 100 mW per division","vertical scale to 200 mV per division"));}
 @Test public void unitResolutionRequiresVerticalObject(){assertFalse(EnglishAsrSelection.maySend("change channel 1 to 100 mW per division","change channel 1 to 100 mV per division"));}
 @Test public void explicitTimeKeepsOriginalBehavior(){assertTrue(EnglishAsrSelection.maySend("Set timebase to 200 milliseconds","Set timebase to 200 ms"));}
 @Test public void wrongDimensionRemainsBlocked(){assertFalse(EnglishAsrSelection.maySend("vertical scale to 200 ms","vertical scale to 200 ms"));}
}
