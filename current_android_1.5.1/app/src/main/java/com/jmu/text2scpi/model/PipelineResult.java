package com.jmu.text2scpi.model;
import kotlinx.serialization.json.*;
/**
 * 意图流水线结果。保留模型原始输出、规则结论、序列化预览与拒绝原因；发送资格由规则与有效预览共同决定。
 */
public final class PipelineResult {
    public final String requestId;
    public final String inputText;
    public final String rawModelOutput;
    public final IntentStatus intentStatus;
    public final RuleDisposition ruleDisposition;
    public final String intent;
    public final JsonElement parameters;
    public final String serializerPreview;
    public final String verificationQuery;
    public final String blockReason;
    public PipelineResult(String requestId,String inputText,String rawModelOutput,IntentStatus intentStatus,RuleDisposition ruleDisposition,String intent,JsonElement parameters,String serializerPreview,String verificationQuery,String blockReason) {
        this.requestId=requestId;
        this.inputText=inputText;
        this.rawModelOutput=rawModelOutput;
        this.intentStatus=intentStatus;
        this.ruleDisposition=ruleDisposition;
        this.intent=intent;
        this.parameters=parameters;
        this.serializerPreview=serializerPreview;
        this.verificationQuery=verificationQuery;
        this.blockReason=blockReason;
    }
    public String getRequestId() {
        return requestId;
    }
    public String getInputText() {
        return inputText;
    }
    public String getRawModelOutput() {
        return rawModelOutput;
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
    public String getSerializerPreview() {
        return serializerPreview;
    }
    public String getVerificationQuery() {
        return verificationQuery;
    }
    public String getBlockReason() {
        return blockReason;
    }
    public boolean getSendEligible() {
        return ruleDisposition.getSendable()&&serializerPreview!=null&&!serializerPreview.trim().isEmpty();
    }
    public PipelineResult rejected(String reason) {
        return new PipelineResult(requestId,inputText,rawModelOutput,intentStatus,RuleDisposition.REJECTED_UNSAFE,intent,parameters,serializerPreview,verificationQuery,reason);
    }
}
