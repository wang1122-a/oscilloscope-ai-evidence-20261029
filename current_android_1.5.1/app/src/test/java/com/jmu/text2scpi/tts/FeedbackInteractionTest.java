package com.jmu.text2scpi.tts;
import com.jmu.text2scpi.model.DeliveryState;
import org.junit.Test;
import static org.junit.Assert.*;
public final class FeedbackInteractionTest {
 @Test public void oneWakeReplyIsExactlyRequestedPhrase(){assertEquals("我在",FeedbackText.wake().text);assertEquals(FeedbackLanguage.CHINESE,FeedbackText.wake().language);}
 @Test public void sentButDisconnectedHasBriefFeedback(){assertEquals("",FeedbackText.failure(FeedbackLanguage.CHINESE,"",DeliveryState.UNKNOWN).text);assertEquals("",FeedbackText.failure(FeedbackLanguage.ENGLISH,"",DeliveryState.UNKNOWN).text);assertEquals("已发送。",FeedbackText.failure(FeedbackLanguage.CHINESE,"",DeliveryState.WRITTEN_UNVERIFIED).text);}
 @Test public void noSecondFeedbackForSameTaskEvenAfterCancel(){FeedbackTickets t=new FeedbackTickets();long epoch=t.current();assertTrue(t.claim(epoch,"request"));assertFalse(t.claim(epoch,"request"));t.cancel();assertFalse(t.claim(t.current(),"request"));}
 @Test public void oldEpochCannotSpeakAfterManualInterruption(){FeedbackTickets t=new FeedbackTickets();long old=t.current();t.cancel();assertFalse(t.claim(old,"late"));assertTrue(t.claim(t.current(),"new"));}
}
