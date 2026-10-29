package com.jmu.text2scpi.voice;

/** Handshake between capture, wake acknowledgement and the service callback. */
public final class VoiceTurnFlow {
    public enum Phase {IDLE, ARMING, ACKNOWLEDGING, LISTENING, RECOGNIZING, RESULT_PENDING}
    private Phase phase=Phase.IDLE;
    private long ticket;
    public synchronized long wake(){if(phase!=Phase.IDLE&&phase!=Phase.LISTENING)return 0;phase=Phase.ACKNOWLEDGING;return ++ticket;}
    public synchronized boolean finishWake(long expected){if(ticket!=expected||phase!=Phase.ACKNOWLEDGING)return false;phase=Phase.ARMING;return true;}
    public synchronized void manual(){ticket++;phase=Phase.ARMING;}
    public synchronized boolean begin(){if(phase!=Phase.ARMING)return false;phase=Phase.LISTENING;return true;}
    public synchronized void recognizing(){phase=Phase.RECOGNIZING;}
    public synchronized void posted(){if(phase==Phase.RECOGNIZING)phase=Phase.RESULT_PENDING;}
    public synchronized void handled(){if(phase==Phase.RESULT_PENDING)phase=Phase.IDLE;}
    public synchronized void discard(){ticket++;phase=Phase.IDLE;}
    public synchronized void suppress(){if(phase!=Phase.ACKNOWLEDGING)discard();}
    public synchronized Phase phase(){return phase;}
    public synchronized boolean idle(){return phase==Phase.IDLE;}
}
