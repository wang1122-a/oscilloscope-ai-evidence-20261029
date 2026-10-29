package com.jmu.text2scpi.validation;
import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import com.jmu.text2scpi.service.*;
import com.jmu.text2scpi.ui.MainActivity;
import com.jmu.text2scpi.model.*;
import com.jmu.text2scpi.voice.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.json.*;

/** One bounded, explicit debug microphone capture; voice commands cannot dispatch. */
public final class WakeCaptureValidation {
 private static Field f(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f;}
 public static JSONObject run(Instrumentation test)throws Exception {
  Context c=test.getTargetContext();AtomicReference<OscilloscopeControlService> serviceRef=new AtomicReference<>();CountDownLatch connected=new CountDownLatch(1);
  test.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){serviceRef.set(((OscilloscopeControlService.LocalBinder)b).service());connected.countDown();}public void onServiceDisconnected(ComponentName n){}};
  c.bindService(new Intent(c,OscilloscopeControlService.class),connection,Context.BIND_AUTO_CREATE);
  if(!connected.await(20,TimeUnit.SECONDS))throw new AssertionError("Bind timeout");OscilloscopeControlService service=serviceRef.get();
  long deadline=SystemClock.elapsedRealtime()+45000;
  while(!service.getUiState().getValue().voiceReady){if(SystemClock.elapsedRealtime()>deadline)throw new AssertionError("Voice init timeout");Thread.sleep(100);}
  ControlUiState before=service.getUiState().getValue();boolean continuous=before.continuousListening,feedback=before.feedbackEnabled;
  VoiceController voice=(VoiceController)f(service,"voice").get(service);Field dispatch=f(service,"debugSuppressVoiceDispatch"),observer=f(voice,"debugPcmObserver");dispatch.setBoolean(service,true);
  File root=new File(c.getFilesDir(),"wake-live");root.mkdirs();File stop=new File(root,"stop"),ready=new File(root,"ready"),audio=new File(root,"input.f32");stop.delete();ready.delete();
  AtomicInteger rate=new AtomicInteger();AtomicLong count=new AtomicLong();double[] energy={0};
  try(OutputStream out=new BufferedOutputStream(new FileOutputStream(audio))){
   BiConsumer<float[],Integer> sink=(samples,sampleRate)->{synchronized(out){try{rate.set(sampleRate);ByteBuffer b=ByteBuffer.allocate(samples.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float value:samples){b.putFloat(value);energy[0]+=value*value;}out.write(b.array());count.addAndGet(samples.length);}catch(IOException e){throw new UncheckedIOException(e);}}};
   observer.set(voice,sink);
   test.runOnMainSync(()->{service.setContinuousListening(false);service.setFeedbackEnabled(true);service.enableVoice();service.showOverlay();service.openOscilloscopeApp();});
   try(FileWriter writer=new FileWriter(ready)){writer.write("ready");}
   long until=SystemClock.elapsedRealtime()+180000;
   while(!stop.exists()&&SystemClock.elapsedRealtime()<until)Thread.sleep(250);
  }finally{
   observer.set(voice,null);test.runOnMainSync(()->{service.pauseVoice();service.setContinuousListening(continuous);service.setFeedbackEnabled(feedback);service.hideOverlay();});
   c.getSharedPreferences("bilingual",0).edit().putBoolean("continuous_listening",continuous).commit();c.getSharedPreferences("voice",0).edit().putBoolean("feedback_enabled",feedback).commit();dispatch.setBoolean(service,false);c.unbindService(connection);ready.delete();
  }
  JSONObject result=new JSONObject().put("sample_rate",rate.get()).put("samples",count.get()).put("rms",Math.sqrt(energy[0]/Math.max(1,count.get()))).put("settings_sent",0);
  try(FileWriter writer=new FileWriter(new File(root,"metadata.json"))){writer.write(result.toString());}return result;
 }
}
