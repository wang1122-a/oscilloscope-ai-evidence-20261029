package com.jmu.text2scpi.voice;
import java.util.function.LongSupplier;
/**
 * 播报期间的语音输入门控。播放及约 200 毫秒尾音内抑制音频进入识别链，防止助手把自己的声音当作新指令。
 */
public final class PlaybackInputGate {
    private final LongSupplier clock;
    private volatile boolean playing;
    private volatile long tailUntil;
    public PlaybackInputGate(LongSupplier clock) {
        this.clock=clock;
    }
    public void block() {
        playing=true;
    }
    /** 解除播放状态后仍保留 200 ms 尾音屏蔽，避免剩余声音触发下一轮唤醒。 */
    public void release() {
        tailUntil=clock.getAsLong()+200;
        playing=false;
    }
    public boolean blocked() {
        return playing||clock.getAsLong()<tailUntil;
    }
}
