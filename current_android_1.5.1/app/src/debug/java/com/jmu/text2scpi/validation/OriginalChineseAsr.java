package com.jmu.text2scpi.validation;
import java.io.*;import com.k2fsa.sherpa.onnx.*;
/** Frozen recognizer and transcription methods copied verbatim from the Chinese 1.3.1 delivery. */
final class OriginalChineseAsr implements AutoCloseable {
 private final File root;private OfflineRecognizer shortAsr,longAsr;
 OriginalChineseAsr(File root){this.root=root;shortAsr=recognizer("sensevoice-5.rknn");longAsr=recognizer("sensevoice-15.rknn");}
 private String path(String name){return new File(root,name).getAbsolutePath();}
private OfflineRecognizer recognizer(String name) {
        OfflineSenseVoiceModelConfig sense=new OfflineSenseVoiceModelConfig();
        sense.setModel(path(name));
        sense.setLanguage("zh");
        sense.setUseInverseTextNormalization(true);
        OfflineModelConfig model=new OfflineModelConfig();
        model.setSenseVoice(sense);
        model.setTokens(path("tokens.txt"));
        model.setNumThreads(-4);
        model.setProvider("rknn");
        OfflineRecognizerConfig config=new OfflineRecognizerConfig();
        config.setModelConfig(model);
        return new OfflineRecognizer(null,config);
    }
public String transcribe(float[] samples) {
        if(samples.length>15*16000)throw new IllegalArgumentException("语音超过15秒，请分成单条指令");
        if(samples.length<4800)return "";
        // 按输入时长选 5 秒或 15 秒模型，两者都以 RKNN 后端运行。
        OfflineRecognizer recognizer=samples.length<=5*16000&&shortAsr!=null?shortAsr:longAsr;
        OfflineStream stream=recognizer.createStream();
        try {
            double sum=0;
            for(float v:samples)sum+=v*v;
            double rms=Math.sqrt(sum/samples.length);
            if(rms<.0001)return "";
            float gain=(float)Math.max(.3,Math.min(4,.1/rms));
            float[] normalized=new float[samples.length];
            for(int i=0;i<samples.length;i++)normalized[i]=Math.max(-1,Math.min(1,samples[i]*gain));
            stream.acceptWaveform(normalized,16000);
            recognizer.decode(stream);
            return recognizer.getResult(stream).getText().trim();
        }
        finally {
            stream.release();
        }
    }
 public void close(){if(shortAsr!=null)shortAsr.release();if(longAsr!=null)longAsr.release();}
}
