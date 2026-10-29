package com.jmu.text2scpi.validation;
import android.app.Instrumentation;
import android.os.*;
import android.net.Uri;
import android.content.Context;
import com.jmu.text2scpi.resources.ResourceStore;
import com.jmu.text2scpi.voice.SpeechRuntime;
import com.jmu.text2scpi.voice.NativeAsr;
import com.jmu.text2scpi.tts.TtsEngine;
import com.jmu.text2scpi.tts.PcmCache;
import com.lilliput.ads10000a.voice.SharedBilingualEngine;
import java.io.*;
import java.nio.*;
import java.util.zip.*;
import org.json.*;

/** Debug validation. Service mode uses the microphone and read-only SCPI queries. */
public final class DeviceValidation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    @Override public void onStart(){Bundle out=new Bundle();JSONObject report=new JSONObject();Context c=getTargetContext();
        try {
            if(!com.jmu.text2scpi.BuildConfig.DEBUG)throw new SecurityException("Debug validation only");
            String mode=args.getString("mode","smoke");report.put("mode",mode).put("microphone_used",false).put("scpi_dispatch",0);
            ResourceStore store=new ResourceStore(c);
            if(mode.equals("wake-flow")){report.put("flow",WakeFlowReplayValidation.run(this)).put("microphone_used",true);
            } else if(mode.equals("wake-replay")){JSONObject replay=WakeReplayValidation.run(c);report.put("replay",replay);if(!replay.getBoolean("all_passed"))throw new AssertionError("Wake replay checks failed; see replay report");
            } else if(mode.equals("wake-live")){report.put("capture",WakeCaptureValidation.run(this)).put("microphone_used",true);
            } else if(mode.equals("competition-service")){JSONObject interaction=CompetitionServiceValidation.run(this);report.put("interaction",interaction).put("microphone_used",true).put("scpi_dispatch",interaction.getJSONArray("continuous_query_cycles").length());
            } else if(mode.equals("import")) {
                File archive=new File(c.getFilesDir(),args.getString("file"));
                if(!archive.getCanonicalPath().startsWith(c.getFilesDir().getCanonicalPath()+File.separator))throw new IOException("private input required");
                report.put("import",store.importUri(Uri.fromFile(archive),text->android.util.Log.i("Bilingual-Validation",text))).put("status",store.status());
            } else if(mode.equals("import-failure")) {
                File before=store.required("common"),bad=new File(c.getCacheDir(),"invalid-package.zip");
                try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(bad))){z.putNextEntry(new ZipEntry("manifest.json"));z.write("{\"type\":\"common\",\"schema_version\":1}".getBytes("UTF-8"));z.closeEntry();}
                boolean rejected=false;
                try{store.importUri(Uri.fromFile(bad),text->{});}catch(Exception expected){rejected=true;}
                if(!rejected||!before.equals(store.required("common")))throw new AssertionError("Invalid import changed active resource");
                boolean interrupted=false;
                try{Thread.currentThread().interrupt();store.importUri(Uri.fromFile(bad),text->{});}catch(InterruptedIOException expected){interrupted=true;}finally{Thread.interrupted();}
                if(!interrupted||!before.equals(store.required("common")))throw new AssertionError("Interrupted import changed active resource");
                report.put("invalid_import_rejected",true).put("interrupted_import_rejected",true).put("active_resource_unchanged",true);bad.delete();
            } else if(mode.equals("baseline-en")) {
                File speech=new File(c.getFilesDir(),"speech");
                NativeAsr.initialize(new File(speech,"model.rknn").getPath(),new File(speech,"chn_jpn_yue_eng_ko_spectok.bpe.model").getPath(),new File(speech,"am.mvn").getPath(),new File(speech,"lm.tsv").getPath(),new File(speech,"decoder.cfg").getPath());
                try{JSONArray requests=requests(c),results=new JSONArray();for(int i=0;i<requests.length();i++){JSONObject item=requests.getJSONObject(i);float[] samples=audio(c,item);long mark=SystemClock.elapsedRealtime();String[] value=NativeAsr.transcribe(samples,true,"");results.put(new JSONObject().put("id",item.getString("id")).put("greedy",value[0].replaceAll("<\\|[^>]*\\|>","").trim()).put("4gram",value[1].replaceAll("<\\|[^>]*\\|>","").trim()).put("elapsed_ms",SystemClock.elapsedRealtime()-mark));}report.put("results",results).put("source","retained_original_1.3.5_private_speech");}finally{NativeAsr.release();}
            } else if(mode.equals("wake-demo")) {
                SpeechRuntime runtime=new SpeechRuntime(c);TtsEngine tts=new TtsEngine(c);
                try {
                    runtime.initializeWakeOnly();tts.initialize();JSONArray results=new JSONArray();float[] chineseReplay=null;
                    PcmCache.Audio acknowledgement=tts.synthesize("我在",0,1f);report.put("wake_reply_pcm_samples",acknowledgement.samples.length);
                    for(String phrase:new String[]{"小欧小欧","Hey oh won"}){
                        PcmCache.Audio audio=tts.synthesize(phrase,0,1f);float[] mono=new com.jmu.text2scpi.voice.AudioResampler(audio.sampleRate).accept(audio.samples);
                        if(phrase.equals("小欧小欧"))chineseReplay=mono;
                        com.k2fsa.sherpa.onnx.OnlineRecognizerConfig probeConfig=new com.k2fsa.sherpa.onnx.OnlineRecognizerConfig();
                        probeConfig.setModelConfig(runtime.getKws().getConfig().getModelConfig());
                        com.k2fsa.sherpa.onnx.OnlineRecognizer probe=new com.k2fsa.sherpa.onnx.OnlineRecognizer(null,probeConfig);
                        com.k2fsa.sherpa.onnx.OnlineStream probeStream=probe.createStream("");
                        try{probeStream.acceptWaveform(java.util.Arrays.copyOf(mono,mono.length+16000),16000);while(probe.isReady(probeStream))probe.decode(probeStream);report.put("probe_"+phrase,probe.getResult(probeStream).getText());}finally{probeStream.release();probe.release();}
                        for(float gain:new float[]{1f,.08f}){
                            runtime.reset();float[] signal=new float[mono.length+32000];for(int i=0;i<mono.length;i++)signal[i+16000]=mono[i]*gain;
                            int hits=0;for(int start=0;start<signal.length;start+=512){String hit=runtime.keyword(java.util.Arrays.copyOfRange(signal,start,Math.min(signal.length,start+512)));if(hit!=null)hits++;}
                            results.put(new JSONObject().put("synthetic_phrase",phrase).put("input_gain",gain).put("wake_hits",hits));
                        }
                    }
                    runtime.reset();int falseHits=0;for(int i=0;i<3800;i++)if(runtime.keyword(new float[512])!=null)falseHits++;
                    report.put("idle_silence_seconds",3800*512/16000.0).put("silence_false_hits",falseHits);
                    if(falseHits!=0)throw new AssertionError("Silence falsely woke assistant");
                    float[] signal=new float[chineseReplay.length+32000];System.arraycopy(chineseReplay,0,signal,16000,chineseReplay.length);int afterIdle=0;
                    for(int start=0;start<signal.length;start+=512)if(runtime.keyword(java.util.Arrays.copyOfRange(signal,start,Math.min(signal.length,start+512)))!=null)afterIdle++;
                    report.put("wake_hits_after_long_idle",afterIdle).put("results",results).put("same_audio_reused_after_idle",true).put("scope","CPU synthetic TTS-to-KWS replay with leading/trailing silence; not human microphone accuracy");
                    if(results.getJSONObject(0).getInt("wake_hits")<1||afterIdle<1)throw new AssertionError("Chinese synthetic wake replay failed");
                }finally{runtime.release();tts.close();}
            } else if(mode.equals("unit-en")) {
                File speech=new File(store.verify("en"),"speech");
                NativeAsr.initialize(new File(speech,"model.rknn").getPath(),new File(speech,"chn_jpn_yue_eng_ko_spectok.bpe.model").getPath(),new File(speech,"am.mvn").getPath(),new File(speech,"lm.tsv").getPath(),new File(speech,"decoder.cfg").getPath());
                try {
                    JSONObject request=new JSONObject().put("file","unit-test-input.f32");float[] samples=audio(c,request);JSONArray results=new JSONArray();
                    for(int i=0;i<3;i++){String[] result=NativeAsr.transcribe(samples,true,"");String raw=result[0].replaceAll("<\\|[^>]*\\|>","").trim();results.put(new JSONObject().put("greedy",raw).put("selected",result[1]).put("unit_diagnostic",result[9]).put("send_gate",com.jmu.text2scpi.voice.EnglishAsrSelection.maySend(raw,result[1])));}
                    report.put("results",results).put("input_samples",samples.length).put("input_sha256",ResourceStore.hash(new File(c.getFilesDir(),"unit-test-input.f32")));
                }finally{NativeAsr.release();}
            } else if(mode.equals("qwen")) {
                SharedBilingualEngine engine=new SharedBilingualEngine(c);
                try {
                    engine.initialize();JSONArray results=new JSONArray();
                    String[][] cases={{"zh","查询通道一的显示状态"},{"en","Query channel one display state"},{"zh","不要打开通道一"},{"en","Do not turn on channel one"},{"en","Set the vertical scale to one volt per division"}};
                    for(String[] test:cases){long start=SystemClock.elapsedRealtime();SharedBilingualEngine.Decision d=engine.process(test[1],test[0]);results.put(new JSONObject().put("language",test[0]).put("text",test[1]).put("status",d.status).put("wire",d.wire).put("raw",d.raw).put("reason",d.reason).put("elapsed_ms",SystemClock.elapsedRealtime()-start));}
                    report.put("results",results);
                }finally{engine.close();}
            } else {
                SpeechRuntime runtime=new SpeechRuntime(c);float[] chineseShort=null,chineseLong=null;String actualShort=null,actualLong=null;
                try {
                    runtime.initialize();report.put("initial_language",runtime.getLanguage()).put("wake",runtime.getWakeStatus());
                    runtime.switchLanguage("zh");java.lang.reflect.Method calibration=SpeechRuntime.class.getDeclaredMethod("calibrationAudio");calibration.setAccessible(true);chineseShort=(float[])calibration.invoke(runtime);chineseLong=java.util.Arrays.copyOf(chineseShort,6*16000);actualShort=runtime.transcribe(chineseShort);actualLong=runtime.transcribe(chineseLong);
                    runtime.switchLanguage("en");report.put("english_loaded",runtime.hasAsr());
                    JSONArray requests=new JSONArray();File requestFile=new File(c.getFilesDir(),"bilingual-replay-requests.json");
                    if(requestFile.isFile())requests=new JSONArray(new String(java.nio.file.Files.readAllBytes(requestFile.toPath()),"UTF-8"));
                    JSONArray results=new JSONArray();
                    for(int i=0;i<requests.length();i++){JSONObject item=requests.getJSONObject(i);File input=new File(c.getFilesDir(),item.getString("file"));if(!input.getCanonicalPath().startsWith(c.getFilesDir().getCanonicalPath()+File.separator))throw new IOException("private input required");byte[] bytes=java.nio.file.Files.readAllBytes(input.toPath());float[] samples=new float[bytes.length/4];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);long mark=SystemClock.elapsedRealtime();String text=runtime.transcribe(samples);results.put(new JSONObject().put("id",item.getString("id")).put("text",text).put("samples",samples.length).put("sha256",ResourceStore.hash(input)).put("elapsed_ms",SystemClock.elapsedRealtime()-mark));}
                    report.put("english_replay",results);
                    runtime.switchLanguage("zh");report.put("chinese_restored",runtime.hasAsr()&&runtime.getLanguage().equals("zh"));
                    File enToken=new File(new File(store.required("en"),"speech"),"decoder.cfg");byte[] backup=java.nio.file.Files.readAllBytes(enToken.toPath());
                    boolean refused=false;try{java.nio.file.Files.write(enToken.toPath(),new byte[]{0});try{runtime.switchLanguage("en");}catch(IOException expected){refused=true;}if(!refused||!runtime.getLanguage().equals("zh")||!runtime.hasAsr())throw new AssertionError("Corrupt target altered active ASR");}finally{java.nio.file.Files.write(enToken.toPath(),backup);}
                    report.put("corrupt_target_blocked_current_asr_preserved",refused);
                    report.put("selected_asr_after_test",runtime.getLanguage());
                }finally{runtime.release();}
                try(OriginalChineseAsr original=new OriginalChineseAsr(new File(store.required("zh"),"speech"))){String baselineShort=original.transcribe(chineseShort),baselineLong=original.transcribe(chineseLong);report.put("chinese_calibration",new JSONObject().put("short_actual",actualShort).put("short_original",baselineShort).put("long_actual",actualLong).put("long_original",baselineLong).put("long_input","same_existing_calibration_padded_to_6s_not_new_human_recording"));if(!baselineShort.equals(actualShort)||!baselineLong.equals(actualLong))throw new AssertionError("Chinese original replay mismatch");}
                TtsEngine tts=new TtsEngine(c);try{tts.initialize();PcmCache.Audio zh=tts.synthesize("已完成",0,1f),en=tts.synthesize("Done.",0,1f);report.put("tts_zh_samples",zh.samples.length).put("tts_en_samples",en.samples.length).put("tts_sample_rate",zh.sampleRate);if(zh.samples.length==0||en.samples.length==0)throw new AssertionError("Empty TTS");}finally{tts.close();}
            }
            report.put("passed",true);out.putString("result",report.toString());finish(-1,out);
        }catch(Throwable failure){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(failure));}catch(Exception ignored){}out.putString("result",report.toString());finish(0,out);}
        finally{try(FileOutputStream f=new FileOutputStream(new File(c.getFilesDir(),"validation-"+args.getString("mode","smoke")+".json"))){f.write(report.toString(2).getBytes("UTF-8"));}catch(Exception ignored){}}
    }

    private static JSONArray requests(Context c)throws Exception {File f=new File(c.getFilesDir(),"bilingual-replay-requests.json");return new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()),"UTF-8"));}
    private static float[] audio(Context c,JSONObject item)throws Exception {File input=new File(c.getFilesDir(),item.getString("file"));if(!input.getCanonicalPath().startsWith(c.getFilesDir().getCanonicalPath()+File.separator))throw new IOException("private input required");byte[] bytes=java.nio.file.Files.readAllBytes(input.toPath());if(bytes.length%4!=0)throw new IOException("Unaligned samples");float[] samples=new float[bytes.length/4];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);return samples;}
}
