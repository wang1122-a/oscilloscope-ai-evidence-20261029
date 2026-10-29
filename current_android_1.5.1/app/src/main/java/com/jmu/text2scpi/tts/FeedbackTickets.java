package com.jmu.text2scpi.tts;
import java.util.LinkedHashSet;
/**
 * 播报请求的代次与去重控制。用户停止、切换或开启新会话后，旧代次不能继续播放。
 */
public final class FeedbackTickets {
    private long epoch;
    private final LinkedHashSet<String> seen=new LinkedHashSet<>();
    public synchronized long current() {
        return epoch;
    }
    public synchronized long cancel() {
        return ++epoch;
    }
    public synchronized boolean valid(long value) {
        return value==epoch;
    }
    public synchronized boolean claim(long value,String requestId) {
        if(value!=epoch||requestId==null||seen.contains(requestId))return false;
        seen.add(requestId);
        while(seen.size()>512)seen.remove(seen.iterator().next());
        return true;
    }
}
