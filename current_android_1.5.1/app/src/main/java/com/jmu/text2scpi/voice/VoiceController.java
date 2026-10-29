package com.jmu.text2scpi.voice;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import com.k2fsa.sherpa.onnx.Vad;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
/**
 * 管理录音与语音处理生命周期。采集和识别使用不同线程，有界队列隔离负载；停止使结果失效，关闭等待原生资源不再被使用。
 */
public final class VoiceController {
    public interface Ready {
        void accept(boolean ok,String message);
    }
    public interface Result {
        void accept(long session,SpeechTextResult result,long elapsedMs);
    }
    private final Context context;
    private final BooleanSupplier busy;
    private final Consumer<VoiceEvent> state;
    private final Ready ready;
    private final Result result;
    private final SpeechRuntime runtime;
    private final VoiceTurnFlow turn=new VoiceTurnFlow();
    // generation 标记一句语音，runGeneration 标记一次录音运行；旧线程或迟到结果据此失效。
    private final AtomicLong generation=new AtomicLong(),runGeneration=new AtomicLong();
    private final AtomicLong audioReset=new AtomicLong();
    private final PlaybackInputGate playbackGate=new PlaybackInputGate(SystemClock::elapsedRealtime);
    private final ExecutorService lifecycle=Executors.newSingleThreadExecutor(r->new Thread(r,"voice-lifecycle"));
    private volatile boolean enabled,manual,initialized,closed,overflow,recognizing;
    private volatile AudioRecord recorder;
    private volatile BiConsumer<float[],Integer> debugPcmObserver;
    private Thread capture,worker;
    // 最多保存 64 块待处理音频，采集不会因推理变慢而无限占用内存。
    private volatile ArrayBlockingQueue<float[]> frames=new ArrayBlockingQueue<>(64);
    public VoiceController(Context context,BooleanSupplier busy,Consumer<VoiceEvent> state,Ready ready,Result result) {
        this.context=context;
        this.busy=busy;
        this.state=state;
        this.ready=ready;
        this.result=result;
        runtime=new SpeechRuntime(context);

    }
    /** Called from the service's initialization worker, never from UI. */
    /** 在 NPU 串行调度中加载语音运行时，通过 ready 回调报告能否开始监听。 */
    public void initialize() {
        try {
            NpuScheduler.run(runtime::initialize);
            initialized=true;
            if(!closed)ready.accept(true,runtime.getWakeStatus());
        }
        catch(Exception|LinkageError e) {
            NpuScheduler.run(runtime::release);
            if(!closed)ready.accept(false,"语音加载失败："+e.getMessage());
        }
    }
    public String getLanguage(){return runtime.getLanguage();}
    public void prepareChineseLm()throws java.io.IOException {runtime.prepareChineseLm();}
    public boolean hasChineseLm(){return runtime.hasChineseLm();}
    public boolean isRecognizing(){return recognizing;}
    public boolean isTurnIdle(){return turn.idle()&&!recognizing;}
    public void resultHandled(){turn.handled();}
    public boolean isCapturing(){return turn.phase()==VoiceTurnFlow.Phase.LISTENING;}
    public void finishWakeReply(long ticket){
        if(enabled&&!closed&&turn.finishWake(ticket)){generation.incrementAndGet();manual=true;frames.clear();audioReset.incrementAndGet();}
    }
    public void selectInitialLanguage(String language){if(initialized)throw new IllegalStateException("ASR already initialized");runtime.selectInitialLanguage(language);}

    public void switchLanguage(String selected) throws Exception {
        stop();
        lifecycle.submit(()->{}).get();
        join(capture);join(worker);
        initialized=false;
        try{NpuScheduler.call(()->{try{runtime.switchLanguage(selected);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}return null;});}
        finally{initialized=runtime.hasAsr();}
    }
    public boolean getInitialized() {
        return initialized;
    }
    /** 取得新的录音运行代次；先等待旧线程结束，再创建本轮采集和处理线程。 */
    public synchronized void start() {
        if(enabled||!initialized||closed)return;
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            state.accept(new VoiceEvent("请在主页授予麦克风权限"));
            return;
        }
        enabled=true;
        long run=runGeneration.incrementAndGet();
        lifecycle.execute(()-> {
            join(capture);join(worker);if(active(run))startWorkers(run);
        }
        );
    }
    private boolean active(long run) {
        return enabled&&!closed&&runGeneration.get()==run;
    }
    /** 为本轮监听分配 64 帧有界队列，分别启动录音线程和语音处理线程。 */
    private void startWorkers(long run) {
        overflow=false;
        ArrayBlockingQueue<float[]> queue=new ArrayBlockingQueue<>(64);
        frames=queue;
        worker=new Thread(()-> {
            try {
                processFrames(queue,run);
            }
            catch(Exception e) {
                if(active(run)) {
                    enabled=false;ready.accept(false,"语音处理失败："+e.getMessage());
                }
            }
        }
        ,"voice-inference");
        capture=new Thread(()->capture(queue,run),"voice-capture");
        worker.start();
        capture.start();
    }
    /** 优先尝试 16 kHz 单声道录音，必要时使用 48/44.1 kHz 后重采样；只负责采集入队，退出时释放麦克风。 */
    private void capture(ArrayBlockingQueue<float[]> queue,long run) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        AudioRecord rec=null;
        try {
            if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("麦克风权限已撤销");
            // 优先模型原生采样率，硬件不支持时选择可用输入格式后重采样。
            int sampleRate=16000;
            for(int rate:new int[] {
                16000,48000,44100
            }
            ) {
                try {
                    int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                    if(min<=0)continue;
                    AudioFormat format=new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
                    AudioRecord candidate=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION).setAudioFormat(format).setBufferSizeInBytes(Math.max(min*4,16384)).build();
                    if(candidate.getState()==AudioRecord.STATE_INITIALIZED) {
                        rec=candidate;
                        sampleRate=rate;
                        break;
                    }
                    candidate.release();
                }
                catch(SecurityException e) {
                    throw e;
                }
                catch(Exception ignored) {
                }
            }
            if(rec==null)throw new IllegalStateException("麦克风不支持可用的单声道采集格式");
            recorder=rec;
            if(!active(run))return;
            rec.startRecording();
            boolean announced=false;long healthAt=0;
            short[] buffer=new short[(int)(sampleRate*.032)];
            AudioResampler resampler=new AudioResampler(sampleRate);
            while(active(run)) {
                int n=rec.read(buffer,0,buffer.length);
                if(n<=0)throw new IllegalStateException("麦克风读取失败("+n+")");
                if(!announced){announced=true;ready.accept(true,runtime.getWakeStatus());}
                float[] audio=new float[n];
                for(int i=0;i<n;i++)audio[i]=buffer[i]/32768f;
                if(com.jmu.text2scpi.BuildConfig.DEBUG){BiConsumer<float[],Integer> observer=debugPcmObserver;if(observer!=null)observer.accept(audio,sampleRate);}
                long healthNow=SystemClock.elapsedRealtime();if(healthNow-healthAt>10000){healthAt=healthNow;double sum=0;for(float x:audio)sum+=x*x;
                    android.media.AudioRecordingConfiguration active=rec.getActiveRecordingConfiguration();
                    state.accept(new VoiceEvent("@MIC rms="+Math.sqrt(sum/Math.max(1,audio.length))+" silenced="+(active!=null&&active.isClientSilenced())+" phase="+turn.phase()+" blocked="+inputBlocked()));
                }
                if(!inputBlocked()&&!queue.offer(resampler.accept(audio)))overflow=true;
            }
        }
        catch(SecurityException e) {
            if(active(run)) {
                enabled=false;
                ready.accept(false,"麦克风权限不可用，请重新授权");
            }
        }
        catch(Exception e) {
            if(active(run)) {
                enabled=false;
                ready.accept(false,"录音失败："+e.getMessage());
            }
        }
        finally {
            if(rec!=null) {
                try {
                    rec.stop();
                }
                catch(Exception ignored) {
                }
                rec.release();
            }
            if(recorder==rec)recorder=null;
        }
    }
    public void listen() {
        if(!enabled)start();
        if(enabled) {
            turn.manual();generation.incrementAndGet();
            manual=true;
        }
    }
    /** 递增会话编号并丢弃排队音频，使本轮尚未发布的识别结果过期。 */
    public void invalidate() {
        turn.discard();
        generation.incrementAndGet();
        manual=false;
        frames.clear();
    }
    public boolean isCurrentEvent(VoiceEvent event) {
        return event.session==0||isCurrent(event.session);
    }
    public boolean isCurrent(long id) {
        return generation.get()==id&&enabled&&!closed&&!inputBlocked();
    }
    private boolean inputBlocked() {
        return playbackGate.blocked();
    }
    /** Separate playback exclusion; it never changes the user's listening permission. */
    /** 接收播报状态，屏蔽播放及尾音期间的识别输入，并要求处理线程重置旧音频状态。 */
    public void setPlaybackSuppressed(boolean value) {
        if(value) {
            playbackGate.block();
            turn.suppress();generation.incrementAndGet();manual=false;frames.clear();
        }
        else {
            playbackGate.release();
        }
        frames.clear();
        audioReset.incrementAndGet();
    }
    public synchronized void stop() {
        enabled=false;
        runGeneration.incrementAndGet();
        invalidate();
        AudioRecord rec=recorder;
        if(rec!=null)try {
            rec.stop();
        }
        catch(Exception ignored) {
        }
        state.accept(new VoiceEvent("助手已暂停"));
    }
    /** Called after service initialization has exited. Does not interrupt native inference. */
    /** 等待生命周期、采集和处理线程退出，再通过 NPU 调度器释放模型；中断只记录并恢复标记，不能提前释放原生对象。 */
    public void close() {
        synchronized(this) {
            closed=true;
            stop();
            lifecycle.shutdown();
        }
        boolean interrupted=false;
        while(true)try {
            if(lifecycle.awaitTermination(1,TimeUnit.DAYS))break;
        }
        catch(InterruptedException e) {
            interrupted=true;
        }
        join(capture);
        join(worker);
        NpuScheduler.run(runtime::release);
        if(interrupted)Thread.currentThread().interrupt();
    }
    private static void join(Thread thread) {
        if(thread==null)return;
        boolean interrupted=false;
        while(thread.isAlive())try {
            thread.join();
        }
        catch(InterruptedException e) {
            interrupted=true;
        }
        if(interrupted)Thread.currentThread().interrupt();
    }
    /** 从有界队列消费音频。处理播报屏蔽、过期会话、拥堵、唤醒和静音结束；真实推理通过 NPU 串行入口执行。 */
    private void processFrames(ArrayBlockingQueue<float[]> queue,long run)throws InterruptedException {
        boolean listening=false,hasSpeech=false;long started=0,session=0,lastUi=0,resetVersion=audioReset.get();
        runtime.reset();state.accept(new VoiceEvent(runtime.getWakeStatus()));
        while(active(run)) {
            long reset=audioReset.get();if(reset!=resetVersion){resetVersion=reset;listening=false;runtime.reset();queue.clear();}
            float[] frame=queue.poll(100,TimeUnit.MILLISECONDS);if(frame==null)continue;if(!active(run))break;
            if(inputBlocked()){listening=false;runtime.reset();continue;}
            if(overflow){overflow=false;listening=false;invalidate();runtime.reset();queue.clear();state.accept(new VoiceEvent("音频拥堵，请重说"));continue;}
            if(busy.getAsBoolean()){if(listening)turn.discard();listening=false;runtime.reset();continue;}
            VoiceTurnFlow.Phase phase=turn.phase();
            if(phase==VoiceTurnFlow.Phase.ACKNOWLEDGING||phase==VoiceTurnFlow.Phase.RESULT_PENDING)continue;
            if(listening&&(session!=generation.get()||phase!=VoiceTurnFlow.Phase.LISTENING)){listening=false;runtime.reset();}
            if(!manual&&(phase==VoiceTurnFlow.Phase.IDLE||phase==VoiceTurnFlow.Phase.LISTENING)) {
                String keyword=runtime.keyword(frame);
                if(keyword!=null){long ticket=turn.wake();if(ticket!=0){listening=false;session=generation.incrementAndGet();runtime.reset();queue.clear();state.accept(new VoiceEvent("已唤醒，正在回应",false,0,session,ticket));}continue;}
            }
            long now=SystemClock.elapsedRealtime();
            if(manual){manual=false;if(!turn.begin())continue;session=generation.get();listening=true;hasSpeech=false;started=now;runtime.reset();state.accept(new VoiceEvent("正在听，请说一条指令",true,0,session));}
            if(!listening)continue;
            Vad detector=runtime.getVad();detector.acceptWaveform(frame);hasSpeech|=detector.isSpeechDetected();
            if(now-lastUi>100){double sum=0;for(float v:frame)sum+=v*v;state.accept(new VoiceEvent("正在听，请说一条指令",true,(float)Math.min(1,Math.sqrt(sum/frame.length)*15),session));lastUi=now;}
            if(now-started>runtime.maxUtteranceMillis()||(!hasSpeech&&now-started>8000)){
                listening=false;runtime.reset();invalidate();state.accept(new VoiceEvent(hasSpeech?"指令太长，请分句重说":"没有听到指令，点击重试"));continue;
            }
            if(!detector.empty()){
                float[] samples=detector.front().getSamples().clone();detector.pop();listening=false;runtime.reset();
                if(samples.length<4800){turn.discard();state.accept(new VoiceEvent("声音太短，请重说",false,0,session));continue;}
                turn.recognizing();recognizing=true;state.accept(new VoiceEvent("正在识别 · NPU",false,0,session));long mark=SystemClock.elapsedRealtime();
                try{
                    SpeechTextResult recognized=NpuScheduler.call(()->runtime.transcribeDetailed(samples));
                    if(isCurrent(session)&&active(run)){turn.posted();result.accept(session,recognized,SystemClock.elapsedRealtime()-mark);}else turn.discard();
                }catch(Exception|LinkageError e){turn.discard();state.accept(new VoiceEvent("识别失败："+e.getMessage(),false,0,session));}
                finally{recognizing=false;queue.clear();overflow=false;state.accept(new VoiceEvent(""));}
            }
        }
    }
}
