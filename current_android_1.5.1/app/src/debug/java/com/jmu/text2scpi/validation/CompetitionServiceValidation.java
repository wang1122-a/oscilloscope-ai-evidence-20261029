package com.jmu.text2scpi.validation;
import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.view.*;
import com.jmu.text2scpi.service.*;
import com.jmu.text2scpi.ui.*;
import com.jmu.text2scpi.model.*;
import com.jmu.text2scpi.voice.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.json.*;

/** Debug-only integration: real TTS and two read-only queries; wake is injected. */
public final class CompetitionServiceValidation {
 private static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
 private static void await(BooleanSupplier test,long timeout,String failure)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(!test.getAsBoolean()){if(SystemClock.elapsedRealtime()>end)throw new AssertionError(failure);Thread.sleep(100);}}
 public static JSONObject run(Instrumentation test)throws Exception {
  Context c=test.getTargetContext();AtomicReference<OscilloscopeControlService> ref=new AtomicReference<>();CountDownLatch connected=new CountDownLatch(1);
  test.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName name,IBinder binder){ref.set(((OscilloscopeControlService.LocalBinder)binder).service());connected.countDown();}public void onServiceDisconnected(ComponentName name){}};
  c.bindService(new Intent(c,OscilloscopeControlService.class),connection,Context.BIND_AUTO_CREATE);
  if(!connected.await(20,TimeUnit.SECONDS))throw new AssertionError("Service bind timeout");
  OscilloscopeControlService service=ref.get();JSONObject report=new JSONObject();
  await(()->{ControlUiState v=service.getUiState().getValue();return v.voiceReady&&v.ttsReady&&v.modelState==ModelState.READY&&!v.resourcesBusy;},40000,"Assistant initialization timeout");
  boolean wasContinuous=service.getUiState().getValue().continuousListening,wasFeedback=service.getUiState().getValue().feedbackEnabled;
  Field suppress=service.getClass().getDeclaredField("debugSuppressVoiceDispatch");suppress.setAccessible(true);suppress.setBoolean(service,true);
  try {
   test.runOnMainSync(()->{service.setContinuousListening(false);service.setFeedbackEnabled(true);service.showOverlay();service.openOscilloscopeApp();});
   VoiceController voice=(VoiceController)field(service,"voice");VoiceTurnFlow flow=(VoiceTurnFlow)field(voice,"turn");
   AtomicLong generation=(AtomicLong)field(voice,"generation");
   @SuppressWarnings("unchecked") Consumer<VoiceEvent> events=(Consumer<VoiceEvent>)field(voice,"state");
   test.runOnMainSync(()->{voice.invalidate();long ticket=flow.wake(),session=generation.incrementAndGet();VoiceEvent wake=new VoiceEvent("已唤醒，正在回应",false,0,session,ticket);events.accept(wake);events.accept(wake);});
   await(()->service.getUiState().getValue().listening&&!service.isFeedbackBusy(),15000,"Wake reply did not resume capture");
   long replies=service.getUiState().getValue().logs.stream().filter(x->x.stage.equals("WAKE_REPLY")).count();
   if(replies!=1)throw new AssertionError("Duplicate or missing wake reply: "+replies);
   report.put("injected_wake_reply_count",replies).put("wake_reply_text","我在").put("capture_resumed_after_wake_reply",true).put("wake_source","debug event injection, not microphone detection");
   test.waitForIdleSync();
   Object overlay=field(service,"overlay");View panel=(View)field(overlay,"window");
   if(!(panel instanceof AssistantPanel))throw new AssertionError("Wake did not expand overlay");
   int flags=((WindowManager.LayoutParams)panel.getLayoutParams()).flags;
   if((flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)==0)throw new AssertionError("Wake overlay stole window focus");
   report.put("wake_overlay_not_focusable",true);
   test.runOnMainSync(()->service.setContinuousListening(true));
   JSONArray cycles=new JSONArray();
   for(int i=0;i<2;i++){
    AtomicReference<String> id=new AtomicReference<>();String text=service.getUiState().getValue().asrLanguage.equals("en")?"Query channel one bandwidth":"查询通道一的带宽";
    test.runOnMainSync(()->id.set(service.submitText(text)));
    await(()->{ControlUiState v=service.getUiState().getValue();return id.get().equals(v.requestId)&&v.listening&&!v.processing&&!v.speaking&&!service.isFeedbackBusy();},35000,"Continuous mode did not resume after query and TTS");
    ControlUiState v=service.getUiState().getValue();long count=v.logs.stream().filter(x->id.get().equals(x.requestId)&&x.stage.equals("FEEDBACK_TEXT")).count();
    if(count!=1)throw new AssertionError("Query feedback was not exactly once: "+count);
    for(LogEntry row:v.logs)if(id.get().equals(row.requestId)&&row.stage.equals("TCP_SEND")&&!row.content.trim().endsWith("?"))throw new AssertionError("Test sent a setting");
    cycles.put(new JSONObject().put("request_id",id.get()).put("query",text).put("scpi",v.scpi).put("feedback_count",count).put("next_capture_started",true));
   }
   report.put("continuous_query_cycles",cycles).put("scope_language",service.getUiState().getValue().scopeLanguage).put("asr_language",service.getUiState().getValue().asrLanguage).put("follow_scope_default",service.getUiState().getValue().followScopeLanguage).put("settings_sent",0);
  } finally {
   suppress.setBoolean(service,false);
   test.runOnMainSync(()->{service.pauseVoice();service.setContinuousListening(wasContinuous);service.setFeedbackEnabled(wasFeedback);service.hideOverlay();});
   c.getSharedPreferences("bilingual",0).edit().putBoolean("continuous_listening",wasContinuous).commit();
   c.getSharedPreferences("voice",0).edit().putBoolean("feedback_enabled",wasFeedback).commit();
   c.unbindService(connection);
  }
  return report;
 }
}
