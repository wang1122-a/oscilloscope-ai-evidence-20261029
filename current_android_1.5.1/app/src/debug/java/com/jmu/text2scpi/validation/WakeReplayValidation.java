package com.jmu.text2scpi.validation;
import android.content.Context;
import com.jmu.text2scpi.voice.*;
import com.jmu.text2scpi.tts.*;
import com.jmu.text2scpi.resources.ResourceStore;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Replay the captured input through production KWS; no live mic or SCPI. */
public final class WakeReplayValidation {
 private static float[] read(File file)throws Exception{byte[] bytes=Files.readAllBytes(file.toPath());float[] x=new float[bytes.length/4];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(x);return x;}
 private static JSONArray replay(SpeechRuntime runtime,float[] x,boolean pad)throws Exception{
  runtime.reset();if(pad){float[] padded=new float[x.length+64000];System.arraycopy(x,0,padded,32000,x.length);x=padded;}
  JSONArray hits=new JSONArray();for(int i=0;i<x.length;i+=512){String hit=runtime.keyword(Arrays.copyOfRange(x,i,Math.min(i+512,x.length)));if(hit!=null)hits.put(new JSONObject().put("second",(i+512)/16000.0).put("keyword",hit));}return hits;
 }
 public static JSONObject run(Context c)throws Exception {
  SpeechRuntime runtime=new SpeechRuntime(c);JSONObject report=new JSONObject();float[] input=read(new File(c.getFilesDir(),"wake-live/input.f32"));
  try{
   runtime.initializeWakeOnly();JSONArray hits=replay(runtime,input,false);report.put("recorded_input_hits",hits).put("input_samples",input.length);
   double[][] windows={{101,105},{106,110},{111,115},{115,119}};int found=0;
   for(double[] window:windows){boolean hit=false;for(int i=0;i<hits.length();i++){double second=hits.getJSONObject(i).getDouble("second");hit|=second>=window[0]&&second<=window[1];}if(hit)found++;}
   report.put("last_four_call_windows_detected",found);boolean ok=found==4;
   JSONArray negatives=new JSONArray(),requests=new JSONArray(new String(Files.readAllBytes(new File(c.getFilesDir(),"bilingual-replay-requests.json").toPath()),"UTF-8"));
   for(int i=0;i<requests.length();i++){JSONObject request=requests.getJSONObject(i);JSONArray falseHits=replay(runtime,read(new File(c.getFilesDir(),request.getString("file"))),true);negatives.put(new JSONObject().put("id",request.getString("id")).put("false_hits",falseHits));if(falseHits.length()!=0)ok=false;}
   report.put("existing_command_negatives",negatives);
   try(TtsEngine tts=new TtsEngine(c)){tts.initialize();JSONArray speech=new JSONArray();for(String phrase:new String[]{"小小的波形","小小","小手小手","小时小时","校准通道一","我在","已设置并确认","请再说一遍"}){PcmCache.Audio audio=tts.synthesize(phrase,0,1f);float[] mono=new AudioResampler(audio.sampleRate).accept(audio.samples);File negativesDir=new File(c.getFilesDir(),"wake-live/negative");negativesDir.mkdirs();ByteBuffer bytes=ByteBuffer.allocate(mono.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float value:mono)bytes.putFloat(value);Files.write(new File(negativesDir,phrase+".f32").toPath(),bytes.array());JSONArray falseHits=replay(runtime,mono,true);speech.put(new JSONObject().put("synthetic_negative",phrase).put("false_hits",falseHits));}report.put("synthetic_negatives",speech);for(int i=0;i<speech.length();i++)if(speech.getJSONObject(i).getJSONArray("false_hits").length()!=0)ok=false;}
   JSONArray afterIdle=replay(runtime,new float[130*16000],false);report.put("silence_false_hits",afterIdle.length());if(afterIdle.length()!=0)ok=false;
   JSONArray replayed=replay(runtime,Arrays.copyOfRange(input,101*16000,120*16000),true);report.put("after_130s_idle_hits",replayed);if(replayed.length()!=4)ok=false;report.put("all_passed",ok);
  }finally{runtime.release();}
  try(OriginalChineseAsr asr=new OriginalChineseAsr(new File(new ResourceStore(c).required("zh"),"speech"))){JSONArray labels=new JSONArray();for(int start:new int[]{96,101,106,111,115})labels.put(new JSONObject().put("start",start).put("asr",asr.transcribe(Arrays.copyOfRange(input,start*16000,(start+4)*16000))));report.put("whole_clip_asr_labels",labels);}
  return report;
 }
}
