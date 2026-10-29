package com.jmu.text2scpi.voice;
import java.util.Arrays;
/**
 * 流式音频重采样器。将采集样本转换到语音模型使用的采样率，保存跨音频块的连续状态。
 */
public final class AudioResampler {
    private final int sourceRate, targetRate;
    // 跨块保存已消费采样点数量、下一插值位置及上块末尾样本。
    private long consumed;
    private double next;
    private float previous;
    public AudioResampler(int sourceRate) {
        this(sourceRate,16000);
    }
    public AudioResampler(int sourceRate,int targetRate) {
        this.sourceRate=sourceRate;
        this.targetRate=targetRate;
    }
    /** 转换一块采集样本并返回目标采样率音频；保留上块末尾样本和小数位置，使跨块插值连续。 */
    public float[] accept(float[] samples) {
        if(sourceRate==targetRate || samples.length==0) return samples;
        float[] output=new float[(int)((double)samples.length*targetRate/sourceRate)+3];
        int count=0;
        while(next<consumed+samples.length-1) {
            long index=(long)Math.floor(next);
            int relative=(int)(index-consumed);
            float a=relative<0?previous:samples[relative], b=samples[relative+1];
            output[count++]=a+(float)((b-a)*(next-index));
            next+=(double)sourceRate/targetRate;
        }
        previous=samples[samples.length-1];
        consumed+=samples.length;
        return Arrays.copyOf(output,count);
    }
}
