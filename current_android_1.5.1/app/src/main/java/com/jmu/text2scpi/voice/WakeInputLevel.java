package com.jmu.text2scpi.voice;
/** Bounded boost for quiet KWS input only; ASR samples are never altered here. */
public final class WakeInputLevel {
    private double level=.05;
    public float[] apply(float[] samples){
        double sum=0;for(float x:samples)sum+=x*x;
        double rms=Math.sqrt(sum/Math.max(1,samples.length));level=.8*level+.2*rms;
        float gain=(float)Math.max(1,Math.min(4,.05/Math.max(.001,level)));
        if(gain<=1.01)return samples;
        float[] out=new float[samples.length];for(int i=0;i<out.length;i++)out[i]=Math.max(-1,Math.min(1,samples[i]*gain));return out;
    }
    public void reset(){level=.05;}
}
