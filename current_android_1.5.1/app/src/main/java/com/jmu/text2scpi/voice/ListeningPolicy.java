package com.jmu.text2scpi.voice;
/** One admission check for both panels and automatic rearming. */
public final class ListeningPolicy {
    private ListeningPolicy(){}
    public static boolean mayRearm(boolean continuous,boolean allowed,boolean ready,boolean idleTurn,boolean resources,boolean processing,boolean confirmation,boolean feedback,boolean transaction,boolean wakeReply){
        return continuous&&allowed&&ready&&idleTurn&&!resources&&!processing&&!confirmation&&!feedback&&!transaction&&!wakeReply;
    }
}
