package com.jmu.text2scpi.model;
import java.util.*;
import kotlinx.serialization.json.JsonElement;
/**
 * 供界面读取的状态快照。集中保存服务、模型、连接、确认、识别和播报状态；通过 Builder 创建下一份快照。
 */
public final class ControlUiState {
    public final String asrLanguage,resourceStatus;
    public final boolean resourcesBusy,lmEnabled,zhLmEnabled,zhLmReady,continuousListening,followScopeLanguage;
    public final String scopeLanguage;
    public final boolean feedbackEnabled,ttsReady,speaking;
    public final String feedbackLanguage,ttsStatus;
    public final String voiceState;
    public final boolean voiceReady;
    public final boolean listening;
    public final long voiceSession;
    public final boolean voiceEnabled;
    public final float level;
    public final String rawTranscript;
    public final String correctedTranscript;
    public final boolean autoSend;
    public final String latency;
    public final ServiceState serviceState;
    public final ModelState modelState;
    public final TcpState tcpState;
    public final TransportStatus transportStatus;
    public final DeliveryState deliveryState;
    public final String requestId;
    public final String inputText;
    public final IntentStatus intentStatus;
    public final RuleDisposition ruleDisposition;
    public final String intent;
    public final JsonElement parameters;
    public final String scpi;
    public final String response;
    public final String error;
    public final boolean processing;
    public final boolean awaitingConfirmation;
    public final int queuedCount;
    public final List<LogEntry> logs;
    public ControlUiState() {
        this(new Builder());
    }
    private ControlUiState(Builder b) {
        asrLanguage=b.asrLanguage;resourceStatus=b.resourceStatus;resourcesBusy=b.resourcesBusy;lmEnabled=b.lmEnabled;zhLmEnabled=b.zhLmEnabled;zhLmReady=b.zhLmReady;continuousListening=b.continuousListening;followScopeLanguage=b.followScopeLanguage;scopeLanguage=b.scopeLanguage;
        feedbackEnabled=b.feedbackEnabled;
        ttsReady=b.ttsReady;
        speaking=b.speaking;
        feedbackLanguage=b.feedbackLanguage;
        ttsStatus=b.ttsStatus;
        voiceState=b.voiceState;
        voiceReady=b.voiceReady;
        listening=b.listening;
        voiceSession=b.voiceSession;
        voiceEnabled=b.voiceEnabled;
        level=b.level;
        rawTranscript=b.rawTranscript;
        correctedTranscript=b.correctedTranscript;
        autoSend=b.autoSend;
        latency=b.latency;
        serviceState=b.serviceState;
        modelState=b.modelState;
        tcpState=b.tcpState;
        transportStatus=b.transportStatus;
        deliveryState=b.deliveryState;
        requestId=b.requestId;
        inputText=b.inputText;
        intentStatus=b.intentStatus;
        ruleDisposition=b.ruleDisposition;
        intent=b.intent;
        parameters=b.parameters;
        scpi=b.scpi;
        response=b.response;
        error=b.error;
        processing=b.processing;
        awaitingConfirmation=b.awaitingConfirmation;
        queuedCount=b.queuedCount;
        logs=Collections.unmodifiableList(new ArrayList<>(b.logs));
    }
    public String getVoiceState() {
        return voiceState;
    }
    public boolean getVoiceReady() {
        return voiceReady;
    }
    public boolean getListening() {
        return listening;
    }
    public long getVoiceSession() {
        return voiceSession;
    }
    public boolean getVoiceEnabled() {
        return voiceEnabled;
    }
    public float getLevel() {
        return level;
    }
    public String getRawTranscript() {
        return rawTranscript;
    }
    public String getCorrectedTranscript() {
        return correctedTranscript;
    }
    public boolean getAutoSend() {
        return autoSend;
    }
    public String getLatency() {
        return latency;
    }
    public ServiceState getServiceState() {
        return serviceState;
    }
    public ModelState getModelState() {
        return modelState;
    }
    public TcpState getTcpState() {
        return tcpState;
    }
    public TransportStatus getTransportStatus() {
        return transportStatus;
    }
    public DeliveryState getDeliveryState() {
        return deliveryState;
    }
    public String getRequestId() {
        return requestId;
    }
    public String getInputText() {
        return inputText;
    }
    public IntentStatus getIntentStatus() {
        return intentStatus;
    }
    public RuleDisposition getRuleDisposition() {
        return ruleDisposition;
    }
    public String getIntent() {
        return intent;
    }
    public JsonElement getParameters() {
        return parameters;
    }
    public String getScpi() {
        return scpi;
    }
    public String getResponse() {
        return response;
    }
    public String getError() {
        return error;
    }
    public boolean getProcessing() {
        return processing;
    }
    public boolean getAwaitingConfirmation() {
        return awaitingConfirmation;
    }
    public int getQueuedCount() {
        return queuedCount;
    }
    public List<LogEntry> getLogs() {
        return logs;
    }
    public Builder toBuilder() {
        return new Builder(this);
    }
    public static final class Builder {
        public String asrLanguage="zh",resourceStatus="请导入公共、中文、英文和双语指令资源包";
        public boolean resourcesBusy=false,lmEnabled=true,zhLmEnabled=true,zhLmReady=false,continuousListening=false,followScopeLanguage=true;
        public String scopeLanguage="";
        public boolean feedbackEnabled=true,ttsReady=false,speaking=false;
        public String feedbackLanguage="AUTO",ttsStatus="正在加载语音反馈";
        public String voiceState="正在准备离线助手";
        public boolean voiceReady=false;
        public boolean listening=false;
        public long voiceSession=0;
        public boolean voiceEnabled=false;
        public float level=0f;
        public String rawTranscript="";
        public String correctedTranscript="";
        public boolean autoSend=false;
        public String latency="";
        public ServiceState serviceState=ServiceState.STARTING;
        public ModelState modelState=ModelState.UNINITIALIZED;
        public TcpState tcpState=TcpState.DISCONNECTED;
        public TransportStatus transportStatus=TransportStatus.IDLE;
        public DeliveryState deliveryState=DeliveryState.NOT_SENT;
        public String requestId=null;
        public String inputText="";
        public IntentStatus intentStatus=null;
        public RuleDisposition ruleDisposition=null;
        public String intent=null;
        public JsonElement parameters=null;
        public String scpi=null;
        public String response=null;
        public String error=null;
        public boolean processing=false;
        public boolean awaitingConfirmation=false;
        public int queuedCount=0;
        public List<LogEntry> logs=Collections.emptyList();
        public Builder() {
        }
        public Builder(ControlUiState s) {
            asrLanguage=s.asrLanguage;resourceStatus=s.resourceStatus;resourcesBusy=s.resourcesBusy;lmEnabled=s.lmEnabled;zhLmEnabled=s.zhLmEnabled;zhLmReady=s.zhLmReady;continuousListening=s.continuousListening;followScopeLanguage=s.followScopeLanguage;scopeLanguage=s.scopeLanguage;
            feedbackEnabled=s.feedbackEnabled;
            ttsReady=s.ttsReady;
            speaking=s.speaking;
            feedbackLanguage=s.feedbackLanguage;
            ttsStatus=s.ttsStatus;
            voiceState=s.voiceState;
            voiceReady=s.voiceReady;
            listening=s.listening;
            voiceSession=s.voiceSession;
            voiceEnabled=s.voiceEnabled;
            level=s.level;
            rawTranscript=s.rawTranscript;
            correctedTranscript=s.correctedTranscript;
            autoSend=s.autoSend;
            latency=s.latency;
            serviceState=s.serviceState;
            modelState=s.modelState;
            tcpState=s.tcpState;
            transportStatus=s.transportStatus;
            deliveryState=s.deliveryState;
            requestId=s.requestId;
            inputText=s.inputText;
            intentStatus=s.intentStatus;
            ruleDisposition=s.ruleDisposition;
            intent=s.intent;
            parameters=s.parameters;
            scpi=s.scpi;
            response=s.response;
            error=s.error;
            processing=s.processing;
            awaitingConfirmation=s.awaitingConfirmation;
            queuedCount=s.queuedCount;
            logs=s.logs;
        }
        public ControlUiState build() {
            return new ControlUiState(this);
        }
    }
}
