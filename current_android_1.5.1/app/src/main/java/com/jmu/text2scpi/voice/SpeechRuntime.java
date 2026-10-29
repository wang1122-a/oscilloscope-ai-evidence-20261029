package com.jmu.text2scpi.voice;
import android.content.Context;
import com.jmu.text2scpi.resources.ResourceStore;
import android.util.Log;
import com.jmu.text2scpi.intent.AssetInstaller;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
/**
 * 离线 ASR、VAD 和 KWS 运行时。加载及校验模型，以启动校准选择 VAD 后端，按语音长度选择 ASR 模型；资源由语音工作线程管理。
 */
public final class SpeechRuntime {
    private final Context context;
    private File root,asrRoot;
    private String language;
    private boolean englishLoaded,releaseUncertain;
    private OfflineRecognizer shortAsr,longAsr;
    private KeywordSpotter kws;
    private OnlineStream kwsStream,kwsAlternate;
    private final WakeInputLevel wakeLevel=new WakeInputLevel();
    private long keywordSamples,quietSamples;
    private Vad vad;
    private String wakeStatus="点击说话",vadProvider="cpu";
    public SpeechRuntime(Context context) {
        this.context=context;
        language=context.getSharedPreferences("bilingual",0).getString("asr_language","zh");
    }
    public File getRoot() {
        return root;
    }
    public Vad getVad() {
        return vad;
    }
    public KeywordSpotter getKws() {
        return kws;
    }
    public OnlineStream getKwsStream() {
        return kwsStream;
    }
    public String getWakeStatus() {
        return wakeStatus;
    }
    public String getVadProvider() {
        return vadProvider;
    }
    /** 校验并安装语音资产，创建 ASR/KWS，读取随包校准音频选择 VAD 后端，再预热模型。校准文件是运行依赖。 */
    public void initialize() {
        try {
            // 先安装离线资产，再创建 ASR、VAD 和唤醒对象。
            root=new File(new ResourceStore(context).verify("common"),"speech");
            loadAsr(language);
            selectVad();
            initializeKeywords();
            // 使用随包校准音频预热两种时长的 ASR，并走一次关键词流。
            float[] calibration=calibrationAudio();
            transcribe(calibration);
            transcribe(Arrays.copyOf(calibration,6*16000));
            long mark=System.nanoTime();
            for(int i=0;i<calibration.length;i+=512)keyword(Arrays.copyOfRange(calibration,i,Math.min(i+512,calibration.length)));
            reset();
            Log.i("Voice-Runtime","READY ASR=RKNN(NPU,5s/15s) VAD="+vadProvider+" KWS=CPU kwsCalibrationMs="+(System.nanoTime()-mark)/1000000+" "+wakeStatus);
        }
        catch(IOException e) {
            throw new IllegalStateException("语音资产加载失败",e);
        }
    }
    public void selectInitialLanguage(String selected){if(hasAsr())throw new IllegalStateException("ASR already loaded");language=selected;}
    /** CPU-only entry used by replay validation; no ASR is loaded. */
    public void initializeWakeOnly()throws IOException {root=new File(new ResourceStore(context).verify("common"),"speech");initializeKeywords();if(kws==null)throw new IOException("Wake model unavailable");}
    private void initializeKeywords(){
        try {
                OnlineTransducerModelConfig transducer=new OnlineTransducerModelConfig();
                transducer.setEncoder(path("kws/encoder.onnx"));
                transducer.setDecoder(path("kws/decoder.onnx"));
                transducer.setJoiner(path("kws/joiner.onnx"));
                OnlineModelConfig model=new OnlineModelConfig();
                model.setTransducer(transducer);
                model.setTokens(path("kws/tokens.txt"));
                model.setNumThreads(1);
                model.setProvider("cpu");
                KeywordSpotterConfig config=new KeywordSpotterConfig();
                config.setModelConfig(model);
                config.setKeywordsFile(path("kws/keywords.txt"));
                config.setKeywordsScore(1.5f);
                config.setKeywordsThreshold(.25f);
                config.setNumTrailingBlanks(1);
                config.setMaxActivePaths(4);
                kws=new KeywordSpotter(null,config);
                createWakeStreams();
                wakeStatus="说「小欧小欧」或「Hey OWON」";
            }
        catch(Exception e) {
                Log.e("Voice-KWS","唤醒不可用，保留点击入口",e);
            }
    }
    private String path(String name) {
        return new File(root,name).getAbsolutePath();
    }
    /** One CPU model, two stream alignments avoid losing a call at chunk boundaries. */
    private void createWakeStreams(){
        if(kwsStream!=null)kwsStream.release();if(kwsAlternate!=null)kwsAlternate.release();
        kwsStream=kws.createStream(WakePronunciation.extraKeywords());
        kwsAlternate=kws.createStream(WakePronunciation.extraKeywords());
        kwsAlternate.acceptWaveform(new float[1280],16000);
    }
    /** 使用指定的 SenseVoice RKNN 文件创建中文识别器；5 秒与 15 秒模型复用同一份 tokens。 */
    private OfflineRecognizer recognizer(String name) {
        OfflineSenseVoiceModelConfig sense=new OfflineSenseVoiceModelConfig();
        sense.setModel(new File(asrRoot,name).getAbsolutePath());
        sense.setLanguage("zh");
        sense.setUseInverseTextNormalization(true);
        OfflineModelConfig model=new OfflineModelConfig();
        model.setSenseVoice(sense);
        model.setTokens(new File(asrRoot,"tokens.txt").getAbsolutePath());
        model.setNumThreads(-4);
        model.setProvider("rknn");
        OfflineRecognizerConfig config=new OfflineRecognizerConfig();
        config.setModelConfig(model);
        return new OfflineRecognizer(null,config);
    }
    /** 按 provider 选择 CPU ONNX 或 RKNN VAD 文件；两条路线使用相同的分句阈值和 16 kHz 输入。 */
    public Vad createVad(String provider) {
        if("en".equals(language))provider="cpu"; // Preserve original English 1.3.5 segmentation backend.
        SileroVadModelConfig silero=new SileroVadModelConfig();
        silero.setModel(path(provider.equals("rknn")?"silero-vad-v4-rk3588.rknn":"silero_vad.onnx"));
        silero.setThreshold(.35f);
        silero.setMinSilenceDuration(.6f);
        silero.setMinSpeechDuration(.2f);
        silero.setWindowSize(512);
        silero.setMaxSpeechDuration("en".equals(language)?12f:15f);
        VadModelConfig config=new VadModelConfig();
        config.setSileroVadModelConfig(silero);
        config.setSampleRate(16000);
        config.setNumThreads(1);
        config.setProvider(provider);
        return new Vad(null,config);
    }
    /** 读取正式运行用的校准 WAV，转换为浮点样本，供 VAD 后端选择与 ASR 预热。 */
    private float[] calibrationAudio()throws IOException {
        byte[] bytes=Files.readAllBytes(new File(root,"vad-calibration.wav").toPath());
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int offset=12;
        while(offset+8<=bytes.length) {
            int size=b.getInt(offset+4);
            if(size<0||offset+8L+size>bytes.length)throw new IOException("VAD calibration WAV invalid");
            if(new String(bytes,offset,4,StandardCharsets.US_ASCII).equals("data")) {
                float[] samples=new float[size/2];
                for(int i=0;i<samples.length;i++)samples[i]=b.getShort(offset+8+i*2)/32768f;
                return samples;
            }
            offset+=8+size+size%2;
        }
        throw new IOException("VAD calibration WAV invalid");
    }
    /** 对 CPU 和 RKNN VAD 使用同一校准音频，各测多轮并取中位耗时；后端必须识别出语音才能参与选择。 */
    private void selectVad()throws IOException {
        if("en".equals(language)){vadProvider="cpu";vad=createVad("cpu");return;}
        float[] calibration=calibrationAudio(),audio=Arrays.copyOf(calibration,calibration.length+16000);
        Map<String,Long> timings=new HashMap<>();
        for(String provider:Arrays.asList("cpu","rknn")) {
            Vad candidate=createVad(provider);
            try {
                long[] trials=new long[3];
                for(int trial=0;trial<4;trial++) {
                    candidate.reset();
                    long start=System.nanoTime();
                    for(int i=0;i<audio.length;i+=512)candidate.acceptWaveform(Arrays.copyOfRange(audio,i,Math.min(i+512,audio.length)));
                    long ns=System.nanoTime()-start;
                    if(candidate.empty())throw new IllegalStateException("VAD "+provider+" 未识别校准语音");
                    if(trial>0)trials[trial-1]=ns;
                }
                Arrays.sort(trials);
                timings.put(provider,trials[1]);
            }
            finally {
                candidate.release();
            }
        }
        vadProvider=timings.get("rknn")<=timings.get("cpu")?"rknn":"cpu";
        vad=createVad(vadProvider);
        String message="VAD median CPU="+timings.get("cpu")/1000000.0+"ms NPU="+timings.get("rknn")/1000000.0+"ms selected="+vadProvider;
        Log.i("Voice-Runtime",message);
        Files.write(new File(context.getFilesDir(),"voice-runtime-profile.txt").toPath(),message.getBytes(StandardCharsets.UTF_8));
    }
    /** 输入为 16 kHz 单声道浮点样本。拒绝超过 15 秒的语音，短句优先 5 秒模型；识别流在 finally 中释放。 */
    public String transcribe(float[] samples) {return transcribeDetailed(samples).getCorrected();}
    public SpeechTextResult transcribeDetailed(float[] samples) {
        String raw=transcribeRaw(samples);
        ChineseFourGram lm=chineseLm;
        String selected=raw;
        if("zh".equals(language)&&lm!=null&&context.getSharedPreferences("bilingual",0).getBoolean("zh_lm_enabled",true)){
            ChineseFourGram.Decision d=lm.apply(raw);selected=d.text;Log.i("Voice-ZH_LM",d.reason+" gain="+d.gain);
        } else if("en".equals(language)){return lastEnglishResult;}
        return new SpeechTextResult(raw,selected,!selected.trim().isEmpty(),selected.equals(raw)?0:4);
    }
    private volatile ChineseFourGram chineseLm;
    public boolean hasChineseLm(){return chineseLm!=null;}
    public void prepareChineseLm()throws IOException {
        ResourceStore store=new ResourceStore(context);
        if(!store.installed("zh_lm")){chineseLm=null;return;}
        try{ChineseFourGram target=new ChineseFourGram(new File(store.verify("zh_lm"),"lm"));chineseLm=target;}
        catch(IOException|RuntimeException e){chineseLm=null;throw new IOException("中文4-gram不可用，保留原始识别："+e.getMessage(),e);}
    }
    private SpeechTextResult lastEnglishResult=new SpeechTextResult("","",false,0);
    private String transcribeRaw(float[] samples) {
        if("en".equals(language)) {
            if(samples.length>12*16000)throw new IllegalArgumentException("英文指令超过12秒，请分句重说");
            String[] result=NativeAsr.transcribe(samples,context.getSharedPreferences("bilingual",0).getBoolean("lm_enabled",true),"");
            boolean lm=context.getSharedPreferences("bilingual",0).getBoolean("lm_enabled",true);
            String greedy=result[0].replaceAll("<\\|[^>]*\\|>","").trim();
            String beam=lm?result[1].replaceAll("<\\|[^>]*\\|>","").trim():greedy;
            String selected=lm?EnglishAsrSelection.choose(greedy,beam):greedy;
            Log.i("Voice-EN_ASR","greedy="+greedy+" beam="+beam+" selected="+selected+" lm="+lm);
            lastEnglishResult=new SpeechTextResult(greedy,selected,!selected.isEmpty()&&EnglishAsrSelection.maySend(greedy,selected),0,"beam="+beam+" lm="+lm+" unitCandidate="+(result.length>9?result[9]:"original"));
            return selected;
        }
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
    /** 持续送入小块音频并查询唤醒结果；命中后重置 KWS 状态，返回可显示的关键词。 */
    public String keyword(float[] samples) {
        if(kws==null||kwsStream==null)return null;
        double sum=0;for(float value:samples)sum+=value*value;
        quietSamples=Math.sqrt(sum/Math.max(1,samples.length))<.002?quietSamples+samples.length:0;keywordSamples+=samples.length;
        if(keywordSamples>120L*16000&&quietSamples>16000){createWakeStreams();keywordSamples=0;quietSamples=0;wakeLevel.reset();}
        float[] enhanced=wakeLevel.apply(samples);kwsStream.acceptWaveform(enhanced,16000);kwsAlternate.acceptWaveform(enhanced,16000);
        String first=decodeWake(kwsStream),second=decodeWake(kwsAlternate);
        if(first!=null||second!=null){kws.reset(kwsStream);kws.reset(kwsAlternate);return first!=null?first:second;}
        return null;
    }
    private String decodeWake(OnlineStream stream){
        while(kws.isReady(stream)) {
            kws.decode(stream);
            KeywordSpotterResult result=kws.getResult(stream);String keyword=result.getKeyword();
            if(!keyword.trim().isEmpty()) {
                kws.reset(stream);
                String display=WakePronunciation.display(keyword,result.getTimestamps());
                if(display!=null){Log.i("Voice-KWS","HIT "+keyword+" "+Arrays.toString(result.getTimestamps()));return display;}
            }
        }
        return null;
    }
    /** 清空 VAD 与唤醒流的本轮状态，避免上一句残留进入下一次监听。 */
    public void reset() {
        if(vad!=null)vad.reset();
        if(kwsStream!=null&&kws!=null)kws.reset(kwsStream);
        if(kwsAlternate!=null&&kws!=null)kws.reset(kwsAlternate);
    }
    public String getLanguage(){return language;}
    public boolean hasAsr(){return !releaseUncertain&&(englishLoaded||longAsr!=null);}
    public int maxUtteranceMillis(){return "en".equals(language)?12000:15000;}
    private void loadAsr(String selected) throws IOException {
        if(releaseUncertain)throw new IOException("原模型释放状态不确定，请重启助手");
        asrRoot=new File(new ResourceStore(context).verify(selected),"speech");
        language=selected;
        if("en".equals(selected)) {
            englishLoaded=true;
            NativeAsr.initialize(new File(asrRoot,"model.rknn").getPath(),new File(asrRoot,"chn_jpn_yue_eng_ko_spectok.bpe.model").getPath(),new File(asrRoot,"am.mvn").getPath(),new File(asrRoot,"lm.tsv").getPath(),new File(asrRoot,"decoder.cfg").getPath());
            // Initialization returned successfully; the flag also covers partial native initialization.
        } else {
            try {shortAsr=recognizer("sensevoice-5.rknn");longAsr=recognizer("sensevoice-15.rknn");}
            catch(RuntimeException|LinkageError e){releaseAsr();throw e;}
        }
    }
    private void releaseAsr(){
        try {
        if(englishLoaded){NativeAsr.release();englishLoaded=false;}
        if(shortAsr!=null){shortAsr.release();shortAsr=null;}
        if(longAsr!=null){longAsr.release();longAsr=null;}
        }catch(RuntimeException|LinkageError e){releaseUncertain=true;throw e;}
    }
    /** Caller has joined capture/inference threads and owns the NPU scheduler lock. */
    public void switchLanguage(String selected) throws IOException {
        if(!selected.equals("zh")&&!selected.equals("en"))throw new IllegalArgumentException("不支持的识别语言");
        new ResourceStore(context).verify(selected);
        if(selected.equals(language)&&hasAsr())return;
        String previous=language;
        releaseAsr();
        try {
            loadAsr(selected);float[] warm=calibrationAudio();transcribe(warm);if("zh".equals(selected))transcribe(Arrays.copyOf(warm,6*16000));
            if(vad!=null){Vad previousVad=vad;vad=null;previousVad.release();}selectVad();reset();
        } catch(Exception|LinkageError failure) {
            releaseAsr();
            try{loadAsr(previous);if(vad!=null){Vad previousVad=vad;vad=null;previousVad.release();}selectVad();reset();}
            catch(Exception|LinkageError rollback){failure.addSuppressed(rollback);throw new IOException("模型切换与恢复失败，请重启助手",failure);}
            throw new IOException("新模型加载失败，已恢复原模型："+failure.getMessage(),failure);
        }
    }
    /** 释放 ASR、唤醒流与 VAD 原生对象；调用前必须确保语音处理线程已退出。 */
    public void release() {
        releaseAsr();
        if(vad!=null) {
            vad.release();
            vad=null;
        }
        if(kwsStream!=null) {
            kwsStream.release();
            kwsStream=null;
        }
        if(kwsAlternate!=null){kwsAlternate.release();kwsAlternate=null;}
        if(kws!=null) {
            kws.release();
            kws=null;
        }
        if(shortAsr!=null) {
            shortAsr.release();
            shortAsr=null;
        }
        if(longAsr!=null) {
            longAsr.release();
            longAsr=null;
        }
    }
}
