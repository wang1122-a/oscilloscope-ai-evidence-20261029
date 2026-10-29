package com.jmu.text2scpi.tts;
import android.content.Context;
import com.jmu.text2scpi.intent.AssetInstaller;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/**
 * 固定的离线 Melo TTS 引擎。使用 sherpa JNI 在 CPU 上合成，4 线程；校验词典与音频结果，工作线程结束后释放模型。
 */
public final class TtsEngine implements AutoCloseable {
    private final Context context;
    private File root;
    private OfflineTts tts;
    private String identity;
    private final Set<String> englishWords=new HashSet<>();
    public TtsEngine(Context context) {
        this.context=context;
        
    }
    /** 按完整 TTS 资源清单建立资产身份，用于缓存失效；加载模型、词典及 FST，并核对常用回复英文词汇。 */
    public void initialize()throws IOException {
        com.jmu.text2scpi.resources.ResourceStore resources=new com.jmu.text2scpi.resources.ResourceStore(context);
        root=new File(resources.required("common"),"tts");
        Map<String,String> manifest=resources.hashes("common","tts");
        // Includes lexicon/FST identities, not just the weight file, for cache invalidation.
        identity="vits-melo-tts-zh_en-v1:"+manifest.toString();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(new File(root,"lexicon.txt")),StandardCharsets.UTF_8))) {
            String line;
            while((line=reader.readLine())!=null) {
                int split=line.indexOf(' ');
                if(split>0)englishWords.add(line.substring(0,split).toLowerCase(Locale.ROOT));
            }
        }
        OfflineTtsVitsModelConfig vits=new OfflineTtsVitsModelConfig();
        vits.setModel(path("model.onnx"));
        vits.setLexicon(path("lexicon.txt"));
        vits.setTokens(path("tokens.txt"));
        vits.setDictDir(path("dict"));
        OfflineTtsModelConfig model=new OfflineTtsModelConfig();
        model.setVits(vits);
        model.setNumThreads(4);
        model.setProvider("cpu");
        OfflineTtsConfig config=new OfflineTtsConfig();
        config.setModel(model);
        config.setMaxNumSentences(1);
        config.setRuleFsts(path("date.fst")+","+path("number.fst")+","+path("phone.fst"));
        tts=new OfflineTts(null,config);
        for(FeedbackText.Message message:FeedbackText.commonPhrases())validateWords(message.text);
    }
    private String path(String name) {
        return new File(root,name).getAbsolutePath();
    }
    public String getIdentity() {
        if(identity==null)throw new IllegalStateException("TTS 未初始化");
        return identity;
    }
    private void validateWords(String text) {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("[A-Za-z]+").matcher(text);
        while(m.find())if(!englishWords.contains(m.group().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("TTS 英文词典缺词："+m.group());
    }
    /** 校验长度与英文词典覆盖，再调用 CPU TTS 生成 PCM；返回完整句样本及采样率，空音频或非法样本作为失败。 */
    public PcmCache.Audio synthesize(String text,int voice,float speed) {
        if(tts==null)throw new IllegalStateException("TTS 尚未就绪");
        if(text.length()>240)throw new IllegalArgumentException("播报过长");
        validateWords(text);
        GeneratedAudio audio=tts.generate(text,voice,speed);
        if(audio.getSamples().length==0)throw new IllegalStateException("TTS 返回空音频");
        for(float sample:audio.getSamples())if(!Float.isFinite(sample))throw new IllegalStateException("TTS 返回非法音频");
        return new PcmCache.Audio(audio.getSamples(),audio.getSampleRate());
    }
    @Override public void close() {
        if(tts!=null) {
            tts.release();
            tts=null;
        }
    }
}
