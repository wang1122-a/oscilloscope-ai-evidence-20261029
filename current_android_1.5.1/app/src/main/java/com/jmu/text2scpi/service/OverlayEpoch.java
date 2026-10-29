package com.jmu.text2scpi.service;
/**
 * 悬浮窗会话代次。标记当前可见会话，避免旧请求或旧语音事件在用户收起之后重新展开界面。
 */
public final class OverlayEpoch {
    private long generation,lastSession;
    public long getGeneration() {
        return generation;
    }
    public void replaced() {
        generation++;
    }
    public boolean newListening(long session,boolean listening) {
        if(!listening || session<=lastSession) return false;
        lastSession=session;
        return true;
    }
    public boolean timerCurrent(long generation) {
        return this.generation==generation;
    }
}
