package com.jmu.text2scpi.service;
import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.net.Uri;
import com.jmu.text2scpi.resources.ResourceStore;
import android.content.pm.*;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.jmu.text2scpi.intent.*;
import com.jmu.text2scpi.model.*;
import com.jmu.text2scpi.scpi.*;
import com.jmu.text2scpi.ui.MainActivity;
import com.jmu.text2scpi.ui.UiText;
import com.jmu.text2scpi.voice.*;
import com.jmu.text2scpi.tts.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
/**
 * 应用业务中枢。主线程管理状态与确认流程，串行控制线程处理模型及 TCP；请求编号隔离过期结果，关闭时等原生任务退出后释放资源。
 */
public final class OscilloscopeControlService extends Service {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final StateStore state=new StateStore();
    private final ExecutorService control=Executors.newSingleThreadExecutor(r->new Thread(r,"scpi-control"));
    // 保护请求有效性与“事务已开始”的原子边界，避免收起和发送同时发生。
    private final Object requestLock=new Object();
    private final AtomicBoolean modelBusy=new AtomicBoolean();
    private final ScpiTcpClient tcp=new ScpiTcpClient();
    private RkllmIntentEngine engine;
    private ResourceStore resources;
    private volatile boolean resourcesBusy,commonVerified,ttsPrepared;
    private IntentPipeline pipeline;
    private VoiceController voice;
    private OverlayController overlay;
    private StateStore.Subscription overlaySubscription;
    private PipelineResult pending;
    private Runnable expiry;
    private volatile boolean stopped,delivering;
    private boolean voiceRequested=true;
    private volatile boolean debugSuppressVoiceDispatch;
    // 当前指令身份；异步推理完成时只有仍匹配的结果才能更新界面或发送。
    private String activeId;
    private long asrMs,modelMs;
    private FeedbackController feedback;
    private DiagnosticJournal journal;
    private long requestFeedbackEpoch;
    private String requestOriginal="";
    private long lastFeedbackVoiceSession,lastWakeTicket,lastScopeCheck;
    private boolean wakeReplyPending,scopeCheckBusy,rearmScheduled;
    private int captureRecoveryAttempts;
    private final Runnable rearm=()->{rearmScheduled=false;rearmWhenIdle();};
    private final Runnable scopePoll=new Runnable(){public void run(){if(stopped)return;maybeCheckScopeLanguage();main.postDelayed(this,6000);}};

    public StateStore getUiState() {
        return state;
    }
    private void ui(Runnable task) {
        if(Looper.myLooper()==Looper.getMainLooper())task.run();
        else main.post(task);
    }
    private void update(Consumer<ControlUiState.Builder> edit) {
        ui(()-> {
            if(!stopped){state.update(edit);scheduleRearm();}
        }
        );
    }
    /** 建立前台服务、UI 状态、语音控制和反馈组件；把模型初始化交给工作线程，避免阻塞界面。 */
    @Override public void onCreate() {
        super.onCreate();
        journal=new DiagnosticJournal(this);
        getSharedPreferences("bilingual",0).edit().remove("asr_test").apply();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("text2scpi_control",UiText.choose(getSharedPreferences("bilingual",0).getString("asr_language","zh"),"小欧语音助手","XiaoOu voice assistant"),NotificationManager.IMPORTANCE_LOW));
        foreground();
        update(b-> {
            b.autoSend=getSharedPreferences("voice",MODE_PRIVATE).getBoolean("auto",false);b.serviceState=ServiceState.RUNNING;b.modelState=ModelState.LOADING;
        }
        );
        // 建立 UI、意图流水线和语音回调；识别结果先回到主线程检查会话。
        resources=new ResourceStore(this);
        update(b->{b.asrLanguage=getSharedPreferences("bilingual",0).getString("asr_language","zh");b.lmEnabled=getSharedPreferences("bilingual",0).getBoolean("lm_enabled",true);b.zhLmEnabled=getSharedPreferences("bilingual",0).getBoolean("zh_lm_enabled",true);b.continuousListening=getSharedPreferences("bilingual",0).getBoolean("continuous_listening",false);b.followScopeLanguage=getSharedPreferences("bilingual",0).getBoolean("follow_scope_language",true);b.resourceStatus=resources.status();});
        overlay=new OverlayController(this,this);
        engine=new RkllmIntentEngine(this);
        engine.setLanguage(state.getValue().asrLanguage);
        pipeline=new IntentPipeline(engine);
        voice=new VoiceController(this,()->resourcesBusy||state.getValue().processing||state.getValue().awaitingConfirmation,
        event->ui(()-> {
            if(event.state.isEmpty()){scheduleRearm();return;}
            if(event.state.startsWith("@MIC")){if(!stopped)log("MIC",event.state.substring(1));return;}
            if(!stopped&&voice.isCurrentEvent(event)) {
                if(event.wakeTicket!=0){acknowledgeWake(event);return;}
                if(event.listening&&event.session>lastFeedbackVoiceSession) {
                    lastFeedbackVoiceSession=event.session;if(feedback!=null)feedback.stop();
                }
                update(b-> {
                    b.voiceState=event.state;b.listening=event.listening;b.level=event.level;if(event.session!=0)b.voiceSession=event.session;
                }
                );
            }
        }
        ),
        (ok,message)->ui(()-> {
            if(stopped)return;update(b-> {
                b.voiceReady=ok;b.voiceState=message;
            }
            );if(ok){captureRecoveryAttempts=0;if(voiceRequested&&!resourcesBusy)enableVoice();}
            else if(voiceRequested&&voice.getInitialized()&&captureRecoveryAttempts++<3){
                voice.stop();main.postDelayed(()->{if(!stopped&&voiceRequested&&!resourcesBusy){log("MIC_RECOVERY","重新启动录音 "+captureRecoveryAttempts);voice.start();}},1000);
            }
        }
        ),
        (id,speech,elapsed)->ui(()-> {
            try{
            if(stopped||!voice.isCurrent(id))return;
            update(b-> {
                b.rawTranscript=speech.getOriginal();b.correctedTranscript=speech.getCorrected();b.listening=false;
            }
            );
            log("ASR","raw="+speech.getOriginal()+" corrected="+speech.getCorrected()+" domain="+speech.getScore()+" elapsedMs="+elapsed+" "+speech.getDiagnostic());
            if(com.jmu.text2scpi.BuildConfig.DEBUG&&debugSuppressVoiceDispatch)return;
            if(!speech.hasSpokenContent()){update(b->{b.response=null;b.voiceState="没有听到指令，点击重试";});return;}
            if(!speech.getPassed()&&"en".equals(state.getValue().asrLanguage)&&!speech.getCorrected().isEmpty()){rejectSpeech(speech.getOriginal(),"英文单位识别不确定，请重说；毫伏请说 millivolts");return;}
            if(speech.getPassed())submit(speech.getCorrected(),true,elapsed);else rejectSpeech(speech.getOriginal(),speech.getOriginal().trim().isEmpty()?"没有识别到文字，请重说":"未识别为示波器指令，未发送");
            }finally{voice.resultHandled();scheduleRearm();}
        }
        ));
        overlaySubscription=state.observe(overlay::render);
        // 反馈使用独立 CPU 工作线程；播报状态反向控制麦克风输入门控。
        feedback=new FeedbackController(this,()->resourcesBusy||state.getValue().modelState!=ModelState.READY||!state.getValue().voiceReady||state.getValue().processing||state.getValue().listening||state.getValue().awaitingConfirmation||modelBusy.get()||wakeReplyPending||!voice.isTurnIdle(),new FeedbackController.Listener() {
            @Override public void status(boolean ready,boolean speaking,String detail) {
                update(b-> {
                    b.ttsReady=ready;b.speaking=speaking;b.ttsStatus=detail;
                    if(!ready&&detail.contains("不可用"))ttsPrepared=false;
                }
                );
            }
            @Override public void completed(){scheduleRearm();}
            @Override public void speechInputSuppressed(boolean suppressed) {
                voice.setPlaybackSuppressed(suppressed);
            }
        }
        );
        update(b-> {
            b.feedbackEnabled=feedback.isEnabled();b.feedbackLanguage=feedback.getLanguage().name();
        }
        );
        if(!getSharedPreferences("voice",0).contains("feedback_language"))setFeedbackLanguage(state.getValue().asrLanguage.equals("en")?"ENGLISH":"CHINESE");
        prepareResources();main.postDelayed(scopePoll,6000);
    }
    /** Retriable preparation after package import; all large work stays off UI. */
    public void prepareResources() {
        if(stopped||resourcesBusy)return;
        if(state.getValue().processing||state.getValue().listening||state.getValue().awaitingConfirmation||state.getValue().speaking||voice.isRecognizing()||isFeedbackBusy()){update(b->b.error="请等当前任务结束后再检查资源");return;}
        resourcesBusy=true;update(b->{b.resourcesBusy=true;b.error=null;b.resourceStatus="正在校验并准备资源…\n"+resources.status();});
        control.execute(()->{
            try {
                if(stopped)return;
                if(!voice.getInitialized()&&state.getValue().followScopeLanguage){
                    String selected=readScopeLanguage();
                    if(selected!=null){update(b->b.scopeLanguage=selected);if(resources.installed(selected)){
                        boolean changed=!selected.equals(voice.getLanguage());voice.selectInitialLanguage(selected);engine.setLanguage(selected);
                        if(changed)ui(()->setFeedbackLanguage(selected.equals("en")?"ENGLISH":"CHINESE"));
                        getSharedPreferences("bilingual",0).edit().putString("asr_language",selected).commit();update(b->b.asrLanguage=selected);
                    }}
                }
                if(resources.installed("common")&&!commonVerified){resources.verify("common");commonVerified=true;}
                if(commonVerified&&!ttsPrepared){ttsPrepared=true;feedback.initialize();}
                if(resources.installed("semantic")&&!engine.isInitialized()){
                    update(b->b.modelState=ModelState.LOADING);NpuScheduler.run(engine::initialize);update(b->b.modelState=ModelState.READY);
                }
                if(!voice.getInitialized()&&commonVerified&&resources.installed(voice.getLanguage()))voice.initialize();
                try{voice.prepareChineseLm();}catch(java.io.IOException lmError){log("ZH_LM",lmError.getMessage());update(b->b.error=lmError.getMessage());}
                update(b->b.zhLmReady=voice.hasChineseLm());
                if(state.getValue().tcpState!=TcpState.READY)refreshTcp();
            } catch(Exception|LinkageError error){update(b->{b.error="资源准备失败："+error.getMessage();if(!engine.isInitialized())b.modelState=ModelState.ERROR;});}
            finally {
                resourcesBusy=false;
                update(b->{b.resourcesBusy=false;b.resourceStatus=resources.status();if(!voice.getInitialized())b.voiceState="请在更多中导入当前语言和公共资源";});
                ui(()->{if(!stopped&&voiceRequested&&voice.getInitialized())enableVoice();});
            }
        });
    }
    private boolean idleForResources(){ControlUiState s=state.getValue();return !stopped&&!wakeReplyPending&&!resourcesBusy&&!s.processing&&!s.listening&&!s.awaitingConfirmation&&!s.speaking&&!modelBusy.get()&&!voice.isRecognizing()&&!isFeedbackBusy();}
    public void requestResourceImport(){
        if(!idleForResources()){update(b->b.error="请等当前语音、播报或设备操作结束后再导入");return;}
        startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("import_resources",true));
    }
    public void importResource(Uri uri){
        if(!idleForResources()){update(b->b.error="助手正忙，请稍后重新导入");return;}
        resourcesBusy=true;voice.stop();update(b->{b.resourcesBusy=true;b.voiceEnabled=false;b.error=null;});
        control.execute(()->{
            try{String message=resources.importUri(uri,text->update(b->b.resourceStatus=text));update(b->{b.resourceStatus=message;b.error=null;});}
            catch(Exception e){update(b->b.error="资源导入失败，现用资源保持不变："+e.getMessage());}
            finally{resourcesBusy=false;update(b->{b.resourcesBusy=false;b.zhLmEnabled=getSharedPreferences("bilingual",0).getBoolean("zh_lm_enabled",true);b.resourceStatus=resources.status();});ui(this::prepareResources);}
        });
    }
    public void switchAsrLanguage(String selected){switchAsrLanguage(selected,false);}
    private void switchAsrLanguage(String selected,boolean fromScope){
        if(!selected.equals("zh")&&!selected.equals("en"))return;
        if(selected.equals(state.getValue().asrLanguage))return;
        if(!idleForResources()){update(b->b.error="请等当前语音、播报或设备操作结束后再切换");return;}
        if(!resources.installed(selected)){update(b->b.error="请先导入"+(selected.equals("en")?"英文":"中文")+"识别资源包");return;}
        if(!voice.getInitialized()){update(b->b.error="请先准备当前语言和公共资源");return;}
        if(!fromScope){getSharedPreferences("bilingual",0).edit().putBoolean("follow_scope_language",false).apply();update(b->b.followScopeLanguage=false);}
        resourcesBusy=true;voice.stop();invalidateUnsent();update(b->{b.resourcesBusy=true;b.voiceReady=false;b.voiceEnabled=false;b.error=null;b.voiceState="正在切换识别模型…";});
        control.execute(()->{
            boolean success=false;
            try{String previous=voice.getLanguage();voice.switchLanguage(selected);if(!getSharedPreferences("bilingual",0).edit().putString("asr_language",selected).commit()){voice.switchLanguage(previous);throw new java.io.IOException("无法保存语言选择，已恢复原模型");}engine.setLanguage(selected);success=true;}
            catch(Exception|LinkageError e){update(b->b.error="切换失败："+e.getMessage());}
            final boolean switched=success;
            resourcesBusy=false;
            ui(()->{
                if(stopped)return;
                String actual=voice.getLanguage();
                update(b->{b.asrLanguage=actual;b.resourcesBusy=false;b.voiceReady=voice.getInitialized();b.rawTranscript="";b.correctedTranscript="";b.scpi=null;b.response=null;b.voiceState=voice.getInitialized()?(voiceRequested?"模型已就绪 · 点击说话或语音唤醒":"助手已暂停 · 模型已就绪"):"请重启助手恢复模型";});
                if(switched){setFeedbackLanguage(selected.equals("en")?"ENGLISH":"CHINESE");foreground();}
                if(voiceRequested&&voice.getInitialized())enableVoice();
            });
        });
    }
    private void acknowledgeWake(VoiceEvent event){
        if(event.wakeTicket<=lastWakeTicket)return;lastWakeTicket=event.wakeTicket;wakeReplyPending=true;
        log("WAKE_HIT","ticket="+event.wakeTicket);update(b->{b.listening=false;b.voiceState="已唤醒，正在回应";});
        feedback.stop();long epoch=feedback.epoch();
        log("WAKE_REPLY","我在 ticket="+event.wakeTicket);feedback.speak(epoch,"wake-"+event.wakeTicket,FeedbackText.wake(),()->{
            if(event.wakeTicket!=lastWakeTicket)return;wakeReplyPending=false;if(!stopped&&voiceRequested&&!resourcesBusy){voice.finishWakeReply(event.wakeTicket);log("WAKE_READY","ticket="+event.wakeTicket);}scheduleRearm();
        });
    }
    public void setContinuousListening(boolean value){
        getSharedPreferences("bilingual",0).edit().putBoolean("continuous_listening",value).apply();
        if(!value&&voice.isCapturing()){voice.invalidate();update(b->{b.listening=false;b.voiceState=voice.getLanguage().isEmpty()?"点击说话":"说「小欧小欧」或「Hey OWON」";});}
        update(b->b.continuousListening=value);log("LISTEN_MODE",value?"CONTINUOUS":"WAKE_PER_COMMAND");if(value)enableVoice();scheduleRearm();
    }
    public void setFollowScopeLanguage(boolean value){
        getSharedPreferences("bilingual",0).edit().putBoolean("follow_scope_language",value).apply();update(b->{b.followScopeLanguage=value;if(value)b.scopeLanguage="";});lastScopeCheck=0;if(value)maybeCheckScopeLanguage();
    }
    private void scheduleRearm(){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::scheduleRearm);return;}
        if(stopped||voice==null||feedback==null)return;
        if(!state.getValue().continuousListening||!voiceRequested){main.removeCallbacks(rearm);rearmScheduled=false;return;}
        if(!rearmScheduled){rearmScheduled=true;main.postDelayed(rearm,650);}
    }
    private void rearmWhenIdle(){
        ControlUiState v=state.getValue();
        if(!ListeningPolicy.mayRearm(v.continuousListening,voiceRequested,v.voiceReady&&v.modelState==ModelState.READY,
            voice.isTurnIdle()&&!v.listening,resourcesBusy,v.processing||modelBusy.get(),v.awaitingConfirmation,
            v.speaking||isFeedbackBusy(),delivering,wakeReplyPending))return;
        if(maybeCheckScopeLanguage())return;
        voice.listen();log("CONTINUOUS_LISTEN","下一条指令");
    }
    private String readScopeLanguage(){
        ScpiTcpClient reader=new ScpiTcpClient(new ScpiConnectionConfig("127.0.0.1",3000,600,900,4096));
        try{return ScopeLanguage.parse(reader.query("LANGuage?").response);}catch(Exception e){log("SCOPE_LANGUAGE","暂未读到示波器语言："+e.getMessage());return null;}finally{reader.disconnect();}
    }
    /** Only polls while idle; never switches a recording or an active transaction. */
    private boolean maybeCheckScopeLanguage(){
        if(stopped||voice==null||!state.getValue().followScopeLanguage||!voice.getInitialized()||!idleForResources()||!voice.isTurnIdle()||state.getValue().listening)return false;
        if(scopeCheckBusy)return true;
        String known=state.getValue().scopeLanguage;
        if(!known.isEmpty()&&!known.equals(voice.getLanguage())&&resources.installed(known)){switchAsrLanguage(known,true);return true;}
        long now=SystemClock.elapsedRealtime();if(now-lastScopeCheck<6000)return false;
        lastScopeCheck=now;scopeCheckBusy=true;
        control.execute(()->{String detected=readScopeLanguage();ui(()->{scopeCheckBusy=false;if(stopped)return;
            if(detected!=null)update(b->b.scopeLanguage=detected);
            if(detected!=null&&!detected.equals(voice.getLanguage())&&state.getValue().followScopeLanguage&&idleForResources()&&voice.isTurnIdle()&&resources.installed(detected))switchAsrLanguage(detected,true);
            else scheduleRearm();
        });});return true;
    }
    public void setZhLmEnabled(boolean enabled){
        if(!idleForResources())return;
        getSharedPreferences("bilingual",0).edit().putBoolean("zh_lm_enabled",enabled).apply();update(b->b.zhLmEnabled=enabled);
    }
    public void setLmEnabled(boolean enabled){
        if(!idleForResources())return;
        getSharedPreferences("bilingual",0).edit().putBoolean("lm_enabled",enabled).apply();update(b->b.lmEnabled=enabled);
    }
    /** 确认语音模型及录音权限就绪，再启用后台录音和唤醒。 */
    public void enableVoice() {
        voiceRequested=true;
        if(stopped||resourcesBusy||!voice.getInitialized()||!engine.isInitialized())return;
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            update(b->b.voiceState="请在主页允许麦克风权限");
            return;
        }
        foreground();
        voice.start();
        update(b->b.voiceEnabled=true);
    }
    /** 处理用户点击说话：停止当前播报、取消旧确认，并开始新的监听会话。 */
    public void listen() {
        if(stopped||resourcesBusy||state.getValue().processing)return;
        if(state.getValue().modelState!=ModelState.READY){update(b->b.error="请先导入并准备双语千问指令资源");return;}
        wakeReplyPending=false;feedback.stop();
        cancelPending(false);
        update(b-> {
            b.error=null;b.response=null;b.rawTranscript="";b.correctedTranscript="";b.latency="";
        }
        );
        enableVoice();
        voice.listen();
    }
    private void invalidateUnsent() {
        synchronized(requestLock) {
            if(!delivering) {
                activeId=null;
                update(b-> {
                    b.requestId=null;b.processing=false;
                }
                );
            }
        }
    }
    /** 停止监听与播报，使未发送请求失效；已开始的设备事务仍由控制线程完成。 */
    public void pauseVoice() {
        wakeReplyPending=false;voiceRequested=false;
        if(feedback!=null)feedback.stop();
        voiceRequested=false;
        voice.stop();
        cancelPending(false);
        invalidateUnsent();
        update(b-> {
            b.voiceEnabled=false;b.listening=false;b.voiceState="助手已暂停";
        }
        );
    }
    public void setAutoSend(boolean value) {
        feedback.stop();
        cancelPending(false);
        voice.invalidate();
        update(b->b.autoSend=value);
        getSharedPreferences("voice",MODE_PRIVATE).edit().putBoolean("auto",value).apply();
        log("MODE",value?"AUTO":"QUERY_AUTO_SET_CONFIRM");
    }
    public String submitText(String text) {
        return submit(text,false,0);
    }
    public void setFeedbackEnabled(boolean value) {
        feedback.setEnabled(value);
        update(b->b.feedbackEnabled=value);
    }
    public boolean isFeedbackBusy() {
        return feedback!=null&&feedback.isBusy();
    }
    public void setFeedbackLanguage(String value) {
        FeedbackLanguage language=FeedbackLanguage.preference(value);
        feedback.setLanguage(language);
        update(b->b.feedbackLanguage=language.name());
    }
    /** 提交新请求：冻结原始输入与播报代次，在串行工作线程推理；处理结果返回主线程前再次检查请求是否有效。 */
    private String submit(String text,boolean fromVoice,long elapsed) {
        String id=UUID.randomUUID().toString();
        ControlUiState snapshot=state.getValue();
        if(text.trim().isEmpty()||stopped||resourcesBusy||snapshot.processing||snapshot.awaitingConfirmation||modelBusy.get())return id;
        feedback.stop();
        requestFeedbackEpoch=feedback.epoch();
        requestOriginal=fromVoice?snapshot.rawTranscript:text;
        voice.invalidate();
        asrMs=elapsed;
        modelMs=0;
        synchronized(requestLock) {
            activeId=id;
        }
        update(b-> {
            b.requestId=id;b.inputText=text;if(!fromVoice)b.rawTranscript=text;b.correctedTranscript=text;b.processing=true;b.awaitingConfirmation=false;b.error=null;b.response=null;b.scpi=null;b.listening=false;b.voiceState="正在理解 · NPU";b.deliveryState=DeliveryState.NOT_SENT;
        }
        );
        log("INPUT",text);
        modelBusy.set(true);
        control.execute(()-> {
            try {
                if(!current(id))return;
                if(state.getValue().modelState!=ModelState.READY) {
                    failFor(id,"Text2SCPI模型未就绪");return;
                }
                if(!pipeline.getAds10000a())refreshTcp();
                if(!current(id))return;
                long mark=SystemClock.elapsedRealtime();
                PipelineResult result=NpuScheduler.call(()->pipeline.process(id,text));
                long elapsedModel=SystemClock.elapsedRealtime()-mark;
                if(!current(id))return;
                logFor(id,"MODEL_RAW",result.rawModelOutput);
                ui(()-> {
                    if(current(id)) {
                        modelMs=elapsedModel;applyResult(result);
                    }
                }
                );
            }
            catch(Exception e) {
                failFor(id,"理解失败："+e.getMessage());
            }
            finally {
                modelBusy.set(false);ui(this::scheduleRearm);
            }
        }
        );
        return id;
    }
    private boolean current(String id) {
        synchronized(requestLock) {
            return !stopped&&Objects.equals(activeId,id);
        }
    }
    /** 落实规则结论和发送策略。设置确认窗口为 30 秒，过期取消；查询或已开启自动模式的合法命令进入执行队列。 */
    private void applyResult(PipelineResult result) {
        log("INTENT",result.ruleDisposition.getWireName()+" "+(result.serializerPreview!=null?result.serializerPreview:result.blockReason));
        update(b-> {
            b.intent=result.intent;b.parameters=result.parameters;b.scpi=result.serializerPreview;b.ruleDisposition=result.ruleDisposition;b.intentStatus=result.intentStatus;b.processing=false;b.error=result.blockReason;
        }
        );
        // 规则资格通过后才应用发送策略；自动模式也不能跳过前面的校验。
        SendDecision decision=SendPolicy.decide(result.getSendEligible(),result.serializerPreview!=null&&ScpiTcpClient.isQuery(result.serializerPreview),state.getValue().autoSend);
        if(decision==SendDecision.BLOCK) {
            fail(result.blockReason==null?"指令未通过校验，未发送":result.blockReason);
            return;
        }
        if(decision==SendDecision.SEND)queueExecution(result);
        else {
            // 设置保存为待确认请求，并登记 30 秒超时取消。
            pending=result;
            update(b-> {
                b.awaitingConfirmation=true;b.voiceState="请确认这条设置";
            }
            );
            expiry=()-> {
                if(pending!=null&&pending.requestId.equals(result.requestId))cancel(result.requestId);
            }
            ;
            main.postDelayed(expiry,30000);
        }
    }
    /** 只接受与当前待确认请求编号相符的一次确认，随后清除待确认状态并排队执行。 */
    public void confirm(String requestId) {
        PipelineResult approved=pending;
        if(approved==null||!approved.requestId.equals(requestId))return;
        pending=null;
        if(expiry!=null)main.removeCallbacks(expiry);
        update(b-> {
            b.awaitingConfirmation=false;b.processing=true;
        }
        );
        queueExecution(approved);
    }
    public void cancel(String requestId) {
        if(pending!=null&&pending.requestId.equals(requestId))cancelPending(true);
    }
    private void cancelPending(boolean show) {
        pending=null;
        if(expiry!=null)main.removeCallbacks(expiry);
        expiry=null;
        if(show&&feedback!=null)feedback.stop();
        update(b-> {
            b.awaitingConfirmation=false;if(show)b.error="已取消，未发送";
        }
        );
    }
    private void queueExecution(PipelineResult result) {
        update(b-> {
            b.processing=true;b.awaitingConfirmation=false;
        }
        );
        control.execute(()->execute(result));
    }
    /** 事务开始后完成设置回读。界面收起只取消尚未开始的请求和迟到播报；已经写出的设置不自动重发。 */
    private void execute(PipelineResult result) {
        String scpi=result.serializerPreview;
        if(scpi==null)return;
        // Atomic boundary with collapse: a queued transaction can be cancelled; an acquired transaction finishes readback.
        synchronized(requestLock) {
            if(!current(result.requestId))return;
            delivering=true;
        }
        update(b-> {
            b.processing=true;b.voiceState="正在执行";b.awaitingConfirmation=false;
        }
        );
        AtomicBoolean settingWritten=new AtomicBoolean();
        try {
            // 按查询、可回读设置、无回读设置三条路线执行一次事务。
            TransactionObserver observer=e->{if(e.getStage()==TcpTransactionStage.TCP_SEND&&!ScpiTcpClient.isQuery(scpi))settingWritten.set(true);logFor(result.requestId,e.getStage().name(),e.getDetail());};
            TransactionResult tx=ScpiTcpClient.isQuery(scpi)?tcp.query(scpi,observer):result.verificationQuery!=null?tcp.verifiedSet(scpi,result.verificationQuery,observer):tcp.command(scpi,observer);
            String message=tx.deliveryState==DeliveryState.MISMATCH?"未达到目标值，设备回读："+tx.verifiedResponse:tx.deliveryState==DeliveryState.WRITTEN_UNVERIFIED?"已发送"+(tx.response==null?"":"："+tx.response):tx.isQuery?(tx.response==null?"":tx.response):"已确认设置 · "+tx.verifiedResponse;
            String timing="ASR "+asrMs+"ms · 理解 "+modelMs+"ms · TCP "+tx.elapsedMs+"ms";
            pipeline.setAds10000a(true);
            ui(()-> {
                if(current(result.requestId)) {
                    update(b-> {
                        b.processing=false;b.tcpState=TcpState.READY;b.deliveryState=tx.deliveryState;b.response=message;b.error=tx.deliveryState==DeliveryState.MISMATCH?message:null;b.voiceState=b.voiceEnabled?"完成 · 继续说「小欧小欧」或「Hey OWON」":"助手已暂停";b.latency=timing;
                    }
                    );log("TIMING",timing);
                    // 根据本次设备回读生成反馈，不能仅凭模型理解成功就播报执行成功。
                    FeedbackText.Message reply=FeedbackText.result(feedback.getLanguage(),requestOriginal,tx);
                    log("FEEDBACK_TEXT",reply.text);feedback.speak(requestFeedbackEpoch,result.requestId,reply);
                }
            }
            );
        }
        catch(Exception e) {
            DeliveryState delivery=e instanceof ScpiClientException?((ScpiClientException)e).getDeliveryState():DeliveryState.NOT_SENT;
            pipeline.setAds10000a(false);
            ui(()-> {
                if(current(result.requestId)) {
                    update(b-> {
                        b.deliveryState=delivery;b.tcpState=TcpState.DISCONNECTED;
                    }
                    );
                    if(delivery==DeliveryState.NOT_SENT)fail("未发送：请确认示波器测量界面已打开。"+e.getMessage());
                    else {
                        log("READBACK_DETAIL",e.getMessage());update(b->{b.processing=false;b.listening=false;b.response=settingWritten.get()?"已发送":null;b.error=null;b.voiceState="完成 · 继续说「小欧小欧」或「Hey OWON」";});
                        FeedbackText.Message reply=FeedbackText.failure(feedback.getLanguage(),requestOriginal,settingWritten.get()?DeliveryState.WRITTEN_UNVERIFIED:delivery);log("FEEDBACK_TEXT",reply.text);feedback.speak(requestFeedbackEpoch,result.requestId,reply);
                    }
                }
            }
            );
        }
        finally {
            synchronized(requestLock) {
                delivering=false;
            }
            ui(this::scheduleRearm);
        }
    }
    private void failFor(String id,String message) {
        ui(()-> {
            if(current(id))fail(message);
        }
        );
    }
    private void rejectSpeech(String original,String message) {
        feedback.stop();
        requestFeedbackEpoch=feedback.epoch();
        requestOriginal=original;
        String id=UUID.randomUUID().toString();
        synchronized(requestLock) {
            activeId=id;
        }
        update(b-> {
            b.requestId=id;b.inputText=original;b.scpi=null;b.response=null;b.deliveryState=DeliveryState.NOT_SENT;
        }
        );
        fail(message);
    }
    private void fail(String message) {
        update(b-> {
            b.processing=false;b.listening=false;b.voiceState="请重试";b.error=message;
        }
        );
        log("ERROR",message);
        if(feedback!=null&&state.getValue().requestId!=null) {
            FeedbackText.Message reply=FeedbackText.failure(feedback.getLanguage(),requestOriginal,state.getValue().deliveryState);
            log("FEEDBACK_TEXT",reply.text);
            feedback.speak(requestFeedbackEpoch,state.getValue().requestId,reply);
        }
    }
    /** 通过身份查询确认设备可用，同时更新流水线的 ADS10000A 能力标记。 */
    private void refreshTcp() {
        try {
            ConnectionInfo info=tcp.connect();
            pipeline.setAds10000a(true);
            update(b->b.tcpState=TcpState.READY);
            log("DEVICE",info.idn);
        }
        catch(Exception e) {
            pipeline.setAds10000a(false);
            update(b->b.tcpState=TcpState.DISCONNECTED);
        }
    }
    /** 收起当前会话：取消未发送请求、确认和播报，保留用户允许的后台唤醒，返回悬浮球是否显示成功。 */
    public boolean collapseCurrentSession() {
        feedback.stop();
        voice.invalidate();
        cancelPending(false);
        synchronized(requestLock) {
            if(!delivering) {
                activeId=null;
                update(b-> {
                    b.requestId=null;b.processing=false;b.response=null;b.error=null;
                }
                );
            }
        }
        update(b-> {
            b.listening=false;b.level=0f;b.voiceState=b.voiceEnabled?"说「小欧小欧」或「Hey OWON」":"助手已暂停";
        }
        );
        return overlay.showButton();
    }
    public boolean showOverlay() {
        return collapseCurrentSession();
    }
    public void hideOverlay() {
        pauseVoice();
        overlay.closeCompletely();
    }
    public boolean openOscilloscopeApp() {
        Intent intent=getPackageManager().getLaunchIntentForPackage("com.owon.eduinstr");
        if(intent==null)return false;
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        main.postDelayed(()-> {
            if(!stopped)control.execute(this::refreshTcp);
        }
        ,1500);
        return true;
    }
    private void log(String stage,String content) {
        ui(()->logFor(state.getValue().requestId,stage,content));
    }
    private void logFor(String id,String stage,String content) {
        Log.i("Voice-"+stage,content);
        if(journal!=null)journal.append(stage,content);
        update(b-> {
            List<LogEntry> logs=new ArrayList<>(b.logs);logs.add(new LogEntry(id==null?"":id,stage,content));if(logs.size()>200)logs=new ArrayList<>(logs.subList(logs.size()-200,logs.size()));b.logs=logs;
        }
        );
    }
    @Override public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }
    public final class LocalBinder extends Binder {
        public OscilloscopeControlService service() {
            return OscilloscopeControlService.this;
        }
    }
    private void foreground() {
        Notification notification=new NotificationCompat.Builder(this,"text2scpi_control").setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle(UiText.choose(getSharedPreferences("bilingual",0).getString("asr_language","zh"),"小欧 · 离线示波器助手","XiaoOu · Offline oscilloscope assistant")).setContentText(UiText.choose(getSharedPreferences("bilingual",0).getString("asr_language","zh"),"点击打开助手 · 麦克风启用时可语音唤醒","Tap to open · Wake phrases available when the microphone is enabled")).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build();
        boolean mic=ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
        startForeground(3000,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC|(mic?ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE:0));
    }
    /** 按队列顺序释放资源。先使回调失效，再等待语音、模型和已开始的事务退出，避免释放仍被原生代码使用的句柄。 */
    @Override public void onDestroy() {
        stopped=true;
        cancelPending(false);
        main.removeCallbacksAndMessages(null);
        overlay.release();
        if(overlaySubscription!=null)overlaySubscription.close();
        state.clearObservers();
        feedback.close();
        voice.stop();
        // Cleanup queued behind initialization, native inference and any started transaction. No unsafe Future.cancel(true).
        control.execute(()-> {
            voice.close();NpuScheduler.run(engine::release);tcp.disconnect();
        }
        );
        control.shutdown();
        if(journal!=null)journal.close();
        super.onDestroy();
    }
}
