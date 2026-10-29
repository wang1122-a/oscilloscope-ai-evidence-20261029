package com.jmu.text2scpi.validation;
import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.view.*;
import com.jmu.text2scpi.service.*;
import com.jmu.text2scpi.ui.*;
import com.jmu.text2scpi.model.*;
import com.jmu.text2scpi.voice.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.json.*;

/** Recorded mic PCM replaces live samples in debug only; runs actual KWS/voice/TTS flow. */
public final class WakeFlowReplayValidation {
 private static Field f(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f;}
 private static void await(BooleanSupplier condition,long ms,String message)throws Exception{long end=SystemClock.elapsedRealtime()+ms;while(!condition.getAsBoolean()){if(SystemClock.elapsedRealtime()>end)throw new AssertionError(message);Thread.sleep(40);}}
 private static long count(OscilloscopeControlService s,String stage){return s.getUiState().getValue().logs.stream().filter(x->x.stage.equals(stage)).count();}
 public static JSONObject run(Instrumentation test)throws Exception {
  Context c=test.getTargetContext();AtomicReference<OscilloscopeControlService> ref=new AtomicReference<>();CountDownLatch bound=new CountDownLatch(1);
  test.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){ref.set(((OscilloscopeControlService.LocalBinder)b).service());bound.countDown();}public void onServiceDisconnected(ComponentName n){}};
  c.bindService(new Intent(c,OscilloscopeControlService.class),connection,Context.BIND_AUTO_CREATE);if(!bound.await(20,TimeUnit.SECONDS))throw new AssertionError("Bind timeout");
  OscilloscopeControlService s=ref.get();await(()->{ControlUiState v=s.getUiState().getValue();return v.voiceReady&&v.ttsReady&&v.modelState==ModelState.READY;},45000,"Init timeout");
  boolean wasContinuous=s.getUiState().getValue().continuousListening,wasFeedback=s.getUiState().getValue().feedbackEnabled;
  VoiceController voice=(VoiceController)f(s,"voice").get(s);Field sink=f(voice,"debugPcmObserver"),suppress=f(s,"debugSuppressVoiceDispatch");suppress.setBoolean(s,true);
  byte[] bytes=Files.readAllBytes(new File(c.getFilesDir(),"wake-live/input.f32").toPath());float[] recorded=new float[bytes.length/4];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(recorded);
  AtomicReference<float[]> source=new AtomicReference<>();AtomicInteger offset=new AtomicInteger();AtomicBoolean wrongRate=new AtomicBoolean();
  BiConsumer<float[],Integer> inject=(samples,rate)->{Arrays.fill(samples,0);if(rate!=16000){wrongRate.set(true);return;}float[] x=source.get();if(x!=null){int i=offset.getAndAdd(samples.length);if(i<x.length)System.arraycopy(x,i,samples,0,Math.min(samples.length,x.length-i));}};
  sink.set(voice,inject);JSONArray calls=new JSONArray();
  try{
   for(int n=0;n<4;n++){
    boolean continuous=n>=2;int start=new int[]{101,106,111,115}[n];
    test.runOnMainSync(()->{s.setContinuousListening(continuous);s.setFeedbackEnabled(true);s.enableVoice();s.showOverlay();s.openOscilloscopeApp();});
    if(continuous)await(()->s.getUiState().getValue().listening,5000,"Continuous capture did not start");
    else await(voice::isTurnIdle,5000,"Wake mode not idle");
    long hits=count(s,"WAKE_HIT"),replies=count(s,"WAKE_REPLY"),ready=count(s,"WAKE_READY"),began=SystemClock.elapsedRealtime();
    offset.set(0);source.set(Arrays.copyOfRange(recorded,start*16000,(start+4)*16000));
    await(()->count(s,"WAKE_HIT")>hits,6500,"Recorded call missed in collapsed "+(continuous?"continuous":"wake")+" mode");source.set(null);
    await(()->count(s,"WAKE_READY")>ready&&s.getUiState().getValue().listening&&!s.isFeedbackBusy(),7000,"Reply did not resume capture");
    test.waitForIdleSync();if(count(s,"WAKE_REPLY")!=replies+1)throw new AssertionError("Reply count not once");
    Object overlay=f(s,"overlay").get(s);View panel=(View)f(overlay,"window").get(overlay);
    if(!(panel instanceof AssistantPanel)||(((WindowManager.LayoutParams)panel.getLayoutParams()).flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)==0)throw new AssertionError("Overlay focus regression");
    calls.put(new JSONObject().put("input_start_second",start).put("continuous_mode",continuous).put("reply_count",1).put("reply_text","我在").put("capture_resumed",true).put("overlay_not_focusable",true).put("elapsed_ms",SystemClock.elapsedRealtime()-began));
   }
   if(wrongRate.get())throw new AssertionError("Capture rate mismatch");
  }finally{source.set(null);sink.set(voice,null);test.runOnMainSync(()->{s.pauseVoice();s.setContinuousListening(wasContinuous);s.setFeedbackEnabled(wasFeedback);s.hideOverlay();});c.getSharedPreferences("bilingual",0).edit().putBoolean("continuous_listening",wasContinuous).commit();c.getSharedPreferences("voice",0).edit().putBoolean("feedback_enabled",wasFeedback).commit();suppress.setBoolean(s,false);c.unbindService(connection);}
  return new JSONObject().put("source","previously recorded microphone PCM injected into debug capture; production KWS/TTS/overlay flow").put("calls",calls).put("settings_sent",0);
 }
}
