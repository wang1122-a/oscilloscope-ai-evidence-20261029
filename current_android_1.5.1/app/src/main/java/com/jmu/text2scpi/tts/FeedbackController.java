package com.jmu.text2scpi.tts;
import android.content.*;
import android.media.*;
import android.os.*;
import android.util.Log;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
/**
 * 拥有 TTS 合成、缓存与播放的独立工作线程。播报按请求去重，取消使迟到结果失效；故障只影响播报，不回流到设备发送。
 */
public final class FeedbackController implements AutoCloseable {
    public interface Listener {
        void status(boolean ready,boolean speaking,String detail);
        void speechInputSuppressed(boolean suppressed);
        default void completed(){}
    }
    private final Context context;
    private final Listener listener;
    private final BooleanSupplier controlBusy;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"tts-worker"));
    private final FeedbackTickets tickets=new FeedbackTickets();
    // 协调用户停止操作与工作线程写音频，播放器最终只由拥有它的线程释放。
    private final Object playbackLock=new Object();
    private final TtsEngine engine;
    private final PcmCache cache;
    private final AudioManager audioManager;
    private final SharedPreferences preferences;
    private volatile boolean enabled,ready,closed,speaking,busy;
    private volatile FeedbackLanguage language;
    private AudioTrack track;
    private AudioFocusRequest focus;
    private final int voice=0;
    private final float speed=1f;
    private final List<FeedbackText.Message> warmPhrases=FeedbackText.commonPhrases();
    private int warmIndex;
    public FeedbackController(Context context,BooleanSupplier controlBusy,Listener listener) {
        this.context=context;
        this.controlBusy=controlBusy;
        this.listener=listener;
        preferences=context.getSharedPreferences("voice",Context.MODE_PRIVATE);
        enabled=preferences.getBoolean("feedback_enabled",true);
        language=FeedbackLanguage.preference(preferences.getString("feedback_language","AUTO"));
        audioManager=context.getSystemService(AudioManager.class);
        engine=new TtsEngine(context);
        cache=new PcmCache(new File(context.getCacheDir(),"tts-phrases"));
    }
    /** 在 TTS 工作线程加载引擎，成功后安排常用句缓存预生成；失败只更新反馈状态。 */
    public void initialize() {
        worker.execute(()-> {
            try {
                engine.initialize();
                FeedbackText.Message wake=FeedbackText.wake();String wakeKey=PcmCache.key(engine.getIdentity(),wake.language.name(),voice,speed,wake.text);
                if(cache.get(wakeKey)==null)cache.put(wakeKey,engine.synthesize(wake.text,voice,speed));
                ready=true;status(false,"离线语音反馈已就绪");scheduleWarm();
            }
            catch(Exception|LinkageError e) {
                engine.close();status(false,"语音反馈不可用，文字结果保留："+e.getMessage());Log.e("Voice-TTS","初始化失败",e);
            }
        }
        );
    }
    public boolean isEnabled() {
        return enabled;
    }
    public FeedbackLanguage getLanguage() {
        return language;
    }
    public long epoch() {
        return tickets.current();
    }
    public boolean isBusy() {
        return busy;
    }
    public void setEnabled(boolean value) {
        enabled=value;
        preferences.edit().putBoolean("feedback_enabled",value).apply();
        if(!value)stop();
        else if(ready)scheduleWarm();
    }
    public void setLanguage(FeedbackLanguage value) {
        stop();
        language=value;
        preferences.edit().putString("feedback_language",value.name()).apply();
    }
    /** Called once from transaction completion or explicit rejection, never from UI rendering. */
    /** 事务完成或明确拒绝时调用一次。请求去重后先读完整句缓存，未命中才合成；所有播放前再次核对代次。 */
    public void speak(long epoch,String requestId,FeedbackText.Message message) {speak(epoch,requestId,message,null);}
    public void speak(long epoch,String requestId,FeedbackText.Message message,Runnable complete) {
        if(closed||!enabled||!tickets.claim(epoch,requestId)){finish(complete);return;}
        if(message.text.isEmpty()){finish(complete);return;}
        final long start=SystemClock.elapsedRealtime();busy=true;
        worker.execute(()-> {
            try {
                if(!valid(epoch))return;
                if(!ready){status(false,"语音反馈未就绪，文字结果保留");return;}
                String key=PcmCache.key(engine.getIdentity(),message.language.name(),voice,speed,message.text);
                PcmCache.Audio audio=cache.get(key);boolean hit=audio!=null;
                if(audio==null){audio=engine.synthesize(message.text,voice,speed);if(valid(epoch))try{cache.put(key,audio);}catch(Exception e){Log.w("Voice-TTS","缓存写入失败",e);}}
                if(valid(epoch))play(epoch,requestId,audio,start,hit);
            }catch(Exception|LinkageError e){if(valid(epoch))status(false,"语音反馈失败，文字结果保留："+e.getMessage());Log.e("Voice-TTS","合成或播放失败",e);}
            finally{if(tickets.valid(epoch))busy=false;finish(complete);}
        });
    }
    private void finish(Runnable complete){main.post(()->{if(!closed){if(complete!=null)complete.run();listener.completed();}});}
    private boolean valid(long epoch) {
        return !closed&&enabled&&tickets.valid(epoch);
    }
    /** 取得音频焦点后播放浮点 PCM，播放和尾音阶段屏蔽识别输入。只由拥有播放器的线程释放 AudioTrack。 */
    private void play(long epoch,String id,PcmCache.Audio audio,long start,boolean cached)throws Exception {
        AudioTrack owned=null;
        AudioFocusRequest ownedFocus=null;
        try {
            synchronized(playbackLock) {
                if(!valid(epoch))return;
                AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                ownedFocus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change-> {
                    if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)stop();
                }
                ,main).build();
                if(audioManager.requestAudioFocus(ownedFocus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IllegalStateException("无法取得播放焦点");
                focus=ownedFocus;
                int min=AudioTrack.getMinBufferSize(audio.sampleRate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT);
                if(min<=0)throw new IllegalStateException("不支持当前音频输出格式");
                owned=new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(new AudioFormat.Builder().setSampleRate(audio.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min*2,audio.sampleRate/5*4)).setTransferMode(AudioTrack.MODE_STREAM).build();
                if(owned.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("播放器初始化失败");
                // 播放器就绪后屏蔽识别输入，防止本机播报被当作用户指令。
                track=owned;
                listener.speechInputSuppressed(true);
                speaking=true;
                status(true,"正在播报");
                owned.play();
            }
            int offset=0;
            boolean logged=false;
            while(offset<audio.samples.length&&valid(epoch)) {
                int n;
                synchronized(playbackLock) {
                    if(!valid(epoch)||track!=owned)break;
                    n=owned.write(audio.samples,offset,Math.min(4096,audio.samples.length-offset),AudioTrack.WRITE_NON_BLOCKING);
                }
                if(n<0)throw new IllegalStateException("AudioTrack 写入失败："+n);
                if(n==0) {
                    Thread.sleep(5);
                    continue;
                }
                offset+=n;
                if(!logged) {
                    logged=true;
                    Log.i("Voice-TTS-TIMING","request="+id+" cache="+cached+" firstPcmMs="+(SystemClock.elapsedRealtime()-start)+" sampleRate="+audio.sampleRate);
                }
            }
            // Drain the hardware queue before ending suppression. Bounded wait also detects a stalled output.
            long deadline=SystemClock.elapsedRealtime()+audio.samples.length*1000L/audio.sampleRate+3000;
            while(valid(epoch)&&Integer.toUnsignedLong(owned.getPlaybackHeadPosition())<offset) {
                if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("音频播放超时");
                Thread.sleep(10);
            }
        }
        finally {
            synchronized(playbackLock) {
                if(owned!=null) {
                    try {
                        owned.pause();
                        owned.flush();
                    }
                    catch(Exception ignored) {
                    }
                    owned.release();
                }
                if(owned!=null&&track==owned) {
                    track=null;
                    speaking=false;
                    listener.speechInputSuppressed(false);
                    if(valid(epoch))status(false,"离线语音反馈已就绪");
                }
                if(ownedFocus!=null)audioManager.abandonAudioFocusRequest(ownedFocus);
                if(focus==ownedFocus)focus=null;
            }
        }
    }
    /** Immediately invalidates synthesis callbacks. Only the worker that owns AudioTrack releases it. */
    /** 立即使旧代次失效并暂停清空播放队列；已经开始的 native 合成允许退出，但结果不再播放。 */
    public void stop() {
        synchronized(playbackLock) {
            tickets.cancel();
            boolean hadPlayback=track!=null||speaking;
            if(track!=null)try {
                track.pause();
                track.flush();
            }
            catch(Exception ignored) {
            }
            speaking=false;
            busy=false;
            if(hadPlayback)listener.speechInputSuppressed(false);
            status(false,ready?"离线语音反馈已就绪":"正在加载语音反馈");
        }
    }
    private void status(boolean playing,String detail) {
        main.post(()-> {
            if(!closed)listener.status(ready,playing,detail);
        }
        );
    }
    private void scheduleWarm() {
        if(!closed&&!worker.isShutdown())worker.schedule(this::warmOne,1200,TimeUnit.MILLISECONDS);
    }
    /** 控制链空闲时预生成一条常用反馈；控制、录音或确认繁忙时推迟，避免抢占交互资源。 */
    private void warmOne() {
        if(closed||!ready||warmIndex>=warmPhrases.size())return;
        if(!enabled||controlBusy.getAsBoolean()||speaking) {
            scheduleWarm();
            return;
        }
        FeedbackText.Message message=warmPhrases.get(warmIndex++);
        try {
            String key=PcmCache.key(engine.getIdentity(),message.language.name(),voice,speed,message.text);
            if(cache.get(key)==null)cache.put(key,engine.synthesize(message.text,voice,speed));
        }
        catch(Exception e) {
            Log.w("Voice-TTS","常用句缓存预生成失败，保留按需合成",e);
        }
        scheduleWarm();
    }
    /** 使旧播报失效，在已排队的合成任务之后释放引擎，并停止新的预生成任务。 */
    @Override public void close() {
        if(closed)return;
        stop();
        closed=true;
        worker.execute(engine::close);
        worker.shutdown();
        main.removeCallbacksAndMessages(null);
    }
}
