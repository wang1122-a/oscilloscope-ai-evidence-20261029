package com.jmu.text2scpi.voice;
/**
 * 语音状态事件。携带监听状态、电平和会话编号，服务使用编号过滤已失效事件。
 */
public final class VoiceEvent {
    public final String state;
    public final boolean listening;
    public final float level;
    public final long session,wakeTicket;
    public VoiceEvent(String state) {
        this(state,false,0f,0);
    }
    public VoiceEvent(String state,boolean listening,float level,long session) {
        this(state,listening,level,session,0);
    }
    public VoiceEvent(String state,boolean listening,float level,long session,long wakeTicket) {
        this.wakeTicket=wakeTicket;
        this.state=state;
        this.listening=listening;
        this.level=level;
        this.session=session;
    }
}
