package com.jmu.text2scpi.voice;
/** Keep the standard wake words; also recognize the joined vowels heard in fast speech. */
public final class WakePronunciation {
    private WakePronunciation(){}
    public static final String LINKED="XIAOOU_LINKED";
    public static String extraKeywords(){return "x iǎo x iǎo :1.5 #0.10 @"+LINKED;}
    public static String display(String keyword,float[] times){
        if(!LINKED.equals(keyword))return keyword.equals("Hey_OWON")?"Hey OWON":keyword;
        // Reject very short repeated syllables and words separated by a long gap.
        if(times==null||times.length!=4)return null;
        float span=times[3]-times[0];
        return span>=.479f&&span<=1.4f&&times[1]-times[0]>=.119f?"小欧小欧":null;
    }
}
