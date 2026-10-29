package com.jmu.text2scpi.voice;
/** JNI entry points share one serially used model; there is no intent or instrument control interface. */
public final class NativeAsr {
    static { System.loadLibrary("english_asr"); }
    private NativeAsr() {}
    public static native void initialize(String model,String tokenizer,String cmvn,String lm,String config);
    public static native String[] transcribe(float[] audio,boolean lmEnabled,String diagnosticPath);
    public static native void release();
}
